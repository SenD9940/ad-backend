package com.orinan.api.domain.support.payment;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.support.payment.SupportPaymentModels.*;
import com.orinan.api.domain.support.service.SupportTerms;
import com.orinan.db.adminaudit.AdminAuditEntity;
import com.orinan.db.adminaudit.AdminAuditRepository;
import com.orinan.db.support.SupportSessionRepository;
import com.orinan.db.support.SupportTicketEntity;
import com.orinan.db.support.SupportTicketRepository;
import com.orinan.db.support.SupportTokenHash;
import com.orinan.db.support.enums.SupportPaymentStatus;
import com.orinan.db.support.enums.SupportRequestSource;
import com.orinan.db.support.enums.SupportTicketStatus;
import com.orinan.db.supportpayment.SupportPaymentEntity;
import com.orinan.db.supportpayment.SupportPaymentRepository;
import com.orinan.db.supportpayment.enums.SupportPaymentOrderStatus;
import com.orinan.db.user.UserRepository;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.orinan.db.supportpayment.enums.SupportPaymentOrderStatus.*;

/** Short transactions only. The payment provider is never called while a DB lock is held. */
@Service
@RequiredArgsConstructor
@Transactional
public class SupportPaymentTransactionService {
    private static final Set<SupportPaymentOrderStatus> REORDERABLE = Set.of(FAILED, ABORTED, EXPIRED);
    private static final Set<SupportPaymentOrderStatus> REFUNDS = Set.of(CANCELED, PARTIAL_CANCELED);
    private final SupportPaymentRepository payments;
    private final SupportTicketRepository tickets;
    private final SupportSessionRepository sessions;
    private final UserRepository users;
    private final WorkspaceRepository workspaces;
    private final AdminAuditRepository audit;
    private final TossPaymentProperties properties;
    private final EntityManager entityManager;

    public Order createOrder(long workspaceId, long ticketId, long customerId) {
        configured();
        var ticket = lockOwned(workspaceId, ticketId, customerId);
        eligible(ticket);
        if (payments.existsByTicketIdAndStatusIn(ticketId, Set.of(CONFIRMING, UNKNOWN, WAITING_FOR_DEPOSIT, PAID))) {
            throw error(SupportPaymentErrorCode.PAYMENT_PENDING);
        }
        var previous = payments.findFirstByTicketIdOrderByIdDesc(ticketId).orElse(null);
        if (previous != null) {
            fresh(previous);
            if (previous.getStatus() == READY) return order(previous);
            if (!REORDERABLE.contains(previous.getStatus())) throw error(SupportPaymentErrorCode.PAYMENT_PENDING);
        }
        var now = SeoulDateTimes.now();
        var payment = payments.saveAndFlush(new SupportPaymentEntity("support_" + UUID.randomUUID().toString().replace("-", ""),
                ticketId, workspaceId, customerId, "support_" + UUID.randomUUID().toString().replace("-", ""),
                SupportTerms.AMOUNT_KRW, now));
        record(payment, "SUPPORT_PAYMENT_ORDER", null, READY.name(), "고객 기술 지원 결제 주문 생성");
        return order(payment);
    }

    public Preparation prepareConfirm(long workspaceId, long ticketId, long customerId, Confirm request) {
        configured();
        if (request == null || request.amount() != SupportTerms.AMOUNT_KRW || !validOrder(request.orderId())
                || request.paymentKey() == null || request.paymentKey().isBlank() || request.paymentKey().length() > 200
                || request.paymentKey().chars().anyMatch(Character::isISOControl)) {
            throw error(SupportPaymentErrorCode.INVALID_REQUEST);
        }
        var ticket = lockOwned(workspaceId, ticketId, customerId);
        var payment = ownedOrder(request.orderId(), ticket);
        if (payment.getAmountKrw() != request.amount()) throw error(SupportPaymentErrorCode.INVALID_REQUEST);
        if (payment.getPaymentKey() != null && !payment.getPaymentKey().equals(request.paymentKey())) {
            throw error(SupportPaymentErrorCode.CONFLICT);
        }
        boolean confirm = payment.getStatus() == READY;
        if (confirm) {
            eligible(ticket);
            if (payments.existsByTicketIdAndStatusIn(ticketId, Set.of(CONFIRMING, UNKNOWN, WAITING_FOR_DEPOSIT, PAID))) {
                throw error(SupportPaymentErrorCode.PAYMENT_PENDING);
            }
            payment.setPaymentKey(request.paymentKey());
            payment.setPaymentKeyHash(SupportTokenHash.hash(request.paymentKey()));
            payment.setStatus(CONFIRMING);
            payment.setUpdatedAt(SeoulDateTimes.now());
            try { payments.flush(); }
            catch (DataIntegrityViolationException ignored) { throw error(SupportPaymentErrorCode.CONFLICT); }
            record(payment, "SUPPORT_PAYMENT_CONFIRM_START", READY.name(), CONFIRMING.name(), "기술 지원 결제 승인 요청 시작");
        }
        return prepare(payment, confirm);
    }

    public Preparation prepareQuery(long workspaceId, long ticketId, long customerId, String orderId) {
        configured();
        if (orderId != null && !validOrder(orderId)) throw error(SupportPaymentErrorCode.INVALID_REQUEST);
        var ticket = lockOwned(workspaceId, ticketId, customerId);
        var payment = orderId == null ? payments.findFirstByTicketIdOrderByIdDesc(ticketId)
                .orElseThrow(() -> error(SupportPaymentErrorCode.NOT_FOUND)) : ownedOrder(orderId, ticket);
        fresh(payment);
        // A READY order has never sent an approval request; the first callback must still perform that step.
        if (payment.getPaymentKey() == null) return new Preparation(null, result(payment));
        return prepare(payment, false);
    }

    public Preparation prepareWebhook(String orderId) {
        if (!validOrder(orderId)) return null;
        var scope = payments.findScopeByOrderId(orderId).orElse(null);
        if (scope == null) return null;
        configured();
        lockFacts(scope.getWorkspaceId(), scope.getTicketId(), scope.getCustomerUserId());
        var payment = payments.findByOrderIdForUpdate(orderId).orElseThrow(() -> error(SupportPaymentErrorCode.NOT_FOUND));
        fresh(payment);
        // There is no trusted payment key before the first customer confirmation callback.
        // Ignore early notifications instead of relying on an unsupported widget-key order lookup.
        if (payment.getPaymentKey() == null) return new Preparation(null, result(payment));
        return prepare(payment, false);
    }

    public Result apply(Attempt attempt, TossPaymentsClient.PaymentSnapshot snapshot) {
        var scope = scope(attempt.orderId());
        var ticket = lockFacts(scope.getWorkspaceId(), scope.getTicketId(), scope.getCustomerUserId());
        var payment = payments.findByOrderIdForUpdate(attempt.orderId()).orElseThrow(() -> error(SupportPaymentErrorCode.NOT_FOUND));
        fresh(payment);
        // Early provider notifications can arrive before our approval callback binds the key.
        // They must not consume the READY order or grant support access.
        if (payment.getPaymentKey() == null) return result(payment);
        var desired = validatedStatus(payment, snapshot);
        boolean refund = REFUNDS.contains(desired);
        if (payment.getVerificationRevision() != attempt.revision() && !refund) return result(payment);
        // A late DONE response must never undo a verified full/partial cancellation.
        if (payment.getStatus() == CANCELED || (payment.getStatus() == PARTIAL_CANCELED && desired != CANCELED)) {
            return result(payment);
        }
        if (payment.getStatus() == PAID && desired != PAID && !refund && desired != WAITING_FOR_DEPOSIT) {
            payment.setLastCheckedAt(SeoulDateTimes.now());
            return result(payment);
        }
        var before = payment.getStatus();
        payment.setStatus(desired);
        var now = SeoulDateTimes.now();
        payment.setLastCheckedAt(now);
        payment.setUpdatedAt(now);
        if (desired == PAID) {
            if (payment.getConfirmedAt() == null) payment.setConfirmedAt(now);
            ticket.setPaymentStatus(SupportPaymentStatus.PAID);
            ticket.setPaymentReference(reference(payment));
            ticket.setPaymentRecordedAt(now);
            ticket.setPaymentRecordedBy(null);
            ticket.setUpdatedAt(now);
            // Never change the approval, cancellation, owner, assignee, or session state here.
        } else if (refund || desired == WAITING_FOR_DEPOSIT) {
            if (Objects.equals(ticket.getPaymentReference(), reference(payment))) {
                ticket.setPaymentStatus(SupportPaymentStatus.UNPAID);
                ticket.setPaymentRecordedAt(now);
                ticket.setPaymentRecordedBy(null);
                ticket.setUpdatedAt(now);
                sessions.endActiveByTicketId(ticket.getId(), now);
            }
        }
        if (before != desired) record(payment, refund ? "SUPPORT_PAYMENT_REFUNDED" : "SUPPORT_PAYMENT_STATE",
                before.name(), desired.name(), refund ? "토스 결제 조회로 취소 또는 부분 취소 확인" : "토스 결제 조회 결과 반영");
        return result(payment);
    }

    public Result failed(Attempt attempt, boolean ambiguous) {
        var scope = scope(attempt.orderId());
        lockFacts(scope.getWorkspaceId(), scope.getTicketId(), scope.getCustomerUserId());
        var payment = payments.findByOrderIdForUpdate(attempt.orderId()).orElseThrow(() -> error(SupportPaymentErrorCode.NOT_FOUND));
        fresh(payment);
        if (payment.getVerificationRevision() != attempt.revision() || payment.getPaymentKey() == null
                || payment.getStatus() == PAID || REFUNDS.contains(payment.getStatus())) return result(payment);
        if (!attempt.confirm() && REORDERABLE.contains(payment.getStatus())) return result(payment);
        var before = payment.getStatus();
        var desired = attempt.confirm() && !ambiguous ? FAILED : UNKNOWN;
        payment.setStatus(desired);
        payment.setUpdatedAt(SeoulDateTimes.now());
        payment.setLastCheckedAt(SeoulDateTimes.now());
        if (before != desired) record(payment, "SUPPORT_PAYMENT_STATE", before.name(), desired.name(),
                "토스 결제 요청의 처리 결과 분류");
        return result(payment);
    }

    private Preparation prepare(SupportPaymentEntity payment, boolean confirm) {
        payment.setVerificationRevision(Math.addExact(payment.getVerificationRevision(), 1));
        return new Preparation(new Attempt(payment.getOrderId(), payment.getPaymentKey(), payment.getAmountKrw(),
                payment.getVerificationRevision(), confirm), result(payment));
    }

    private SupportPaymentOrderStatus validatedStatus(SupportPaymentEntity payment, TossPaymentsClient.PaymentSnapshot remote) {
        if (remote == null || !Objects.equals(payment.getOrderId(), remote.orderId())
                || !Objects.equals(payment.getPaymentKey(), remote.paymentKey()) || !"KRW".equals(remote.currency())
                || remote.totalAmount() != SupportTerms.AMOUNT_KRW || remote.totalAmount() != payment.getAmountKrw()) return UNKNOWN;
        return switch (remote.status()) {
            case "DONE" -> remote.balanceAmount() == SupportTerms.AMOUNT_KRW ? PAID : UNKNOWN;
            case "CANCELED" -> remote.balanceAmount() == 0 ? CANCELED : UNKNOWN;
            case "PARTIAL_CANCELED" -> remote.balanceAmount() >= 0 && remote.balanceAmount() < SupportTerms.AMOUNT_KRW
                    ? PARTIAL_CANCELED : UNKNOWN;
            case "ABORTED" -> ABORTED;
            case "EXPIRED" -> EXPIRED;
            case "WAITING_FOR_DEPOSIT" -> WAITING_FOR_DEPOSIT;
            default -> UNKNOWN;
        };
    }

    private SupportTicketEntity lockOwned(long workspaceId, long ticketId, long customerId) {
        if (workspaceId <= 0 || ticketId <= 0 || customerId <= 0) throw error(SupportPaymentErrorCode.INVALID_REQUEST);
        var customer = users.findByIdForUpdate(customerId).orElseThrow(() -> error(SupportPaymentErrorCode.ACCESS_DENIED));
        fresh(customer);
        if (customer.getStatus() != UserStatus.REGISTERED) throw error(SupportPaymentErrorCode.ACCESS_DENIED);
        var workspace = workspaces.findByIdForUpdate(workspaceId).orElseThrow(() -> error(SupportPaymentErrorCode.NOT_FOUND));
        fresh(workspace);
        if (workspace.getUser() == null || !Objects.equals(workspace.getUser().getId(), customerId)) {
            throw error(SupportPaymentErrorCode.ACCESS_DENIED);
        }
        var ticket = tickets.findByIdForUpdate(ticketId).orElseThrow(() -> error(SupportPaymentErrorCode.NOT_FOUND));
        fresh(ticket);
        if (!Objects.equals(ticket.getWorkspaceId(), workspaceId) || !Objects.equals(ticket.getCustomerUserId(), customerId)
                || ticket.getRequestSource() != SupportRequestSource.CUSTOMER) throw error(SupportPaymentErrorCode.NOT_FOUND);
        return ticket;
    }

    private SupportTicketEntity lockFacts(long workspaceId, long ticketId, long customerId) {
        // Re-check the objects under the same lock order as support consent, but retain actual payment facts
        // even if the customer was suspended, ownership changed, or consent was withdrawn during HTTP.
        users.findByIdForUpdate(customerId).ifPresent(this::fresh);
        workspaces.findByIdForUpdate(workspaceId).ifPresent(this::fresh);
        var ticket = tickets.findByIdForUpdate(ticketId).orElseThrow(() -> error(SupportPaymentErrorCode.NOT_FOUND));
        fresh(ticket);
        if (!Objects.equals(ticket.getWorkspaceId(), workspaceId) || !Objects.equals(ticket.getCustomerUserId(), customerId)) {
            throw error(SupportPaymentErrorCode.CONFLICT);
        }
        return ticket;
    }

    private SupportPaymentRepository.Scope scope(String orderId) {
        return payments.findScopeByOrderId(orderId).orElseThrow(() -> error(SupportPaymentErrorCode.NOT_FOUND));
    }

    private SupportPaymentEntity ownedOrder(String orderId, SupportTicketEntity ticket) {
        var payment = payments.findByOrderIdForUpdate(orderId).orElseThrow(() -> error(SupportPaymentErrorCode.NOT_FOUND));
        fresh(payment);
        if (!Objects.equals(payment.getTicketId(), ticket.getId()) || !Objects.equals(payment.getWorkspaceId(), ticket.getWorkspaceId())
                || !Objects.equals(payment.getCustomerUserId(), ticket.getCustomerUserId())) throw error(SupportPaymentErrorCode.NOT_FOUND);
        return payment;
    }

    private void eligible(SupportTicketEntity ticket) {
        if (ticket.getRequestSource() != SupportRequestSource.CUSTOMER || ticket.getAmountKrw() != SupportTerms.AMOUNT_KRW
                || ticket.getPaymentStatus() != SupportPaymentStatus.UNPAID || ticket.getStatus() != SupportTicketStatus.APPROVED
                || ticket.getApprovedAt() == null || ticket.getApprovalExpiresAt() == null
                || !ticket.getApprovalExpiresAt().isAfter(SeoulDateTimes.now())
                || !Objects.equals(ticket.getTermsVersion(), SupportTerms.VERSION)
                || !Objects.equals(ticket.getTermsSnapshot(), SupportTerms.snapshot(ticket.getAccessMode()))) throw error(SupportPaymentErrorCode.CONFLICT);
    }

    private void record(SupportPaymentEntity payment, String action, String before, String after, String reason) {
        audit.save(new AdminAuditEntity(payment.getCustomerUserId(), action, "SUPPORT_PAYMENT", payment.getId(),
                reason, before, after, SeoulDateTimes.now()));
    }

    private Order order(SupportPaymentEntity payment) {
        return new Order(payment.getOrderId(), payment.getTicketId(), "기술 지원 1회", payment.getAmountKrw(), properties.getClientKey(),
                payment.getCustomerKey(), payment.getStatus().name());
    }
    private Result result(SupportPaymentEntity payment) {
        return new Result(payment.getOrderId(), payment.getStatus().name(), payment.getAmountKrw(), payment.getTicketId());
    }
    private String reference(SupportPaymentEntity payment) { return "toss:" + payment.getOrderId(); }
    private boolean validOrder(String id) { return id != null && id.matches("[A-Za-z0-9_-]{6,64}"); }
    private void configured() { if (!properties.isConfigured()) throw error(SupportPaymentErrorCode.UNAVAILABLE); }
    private ApiException error(SupportPaymentErrorCode code) { return new ApiException(code); }

    private void fresh(Object entity) {
        // OSIV can retain this entity between our separate transactions. FOR UPDATE locks the row,
        // but a query/find may still return its old first-level-cache state without refreshing it.
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
    }

    public record Preparation(Attempt attempt, Result result) { }
}
