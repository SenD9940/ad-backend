package com.orinan.adminapi.domain.support.business;

import com.orinan.adminapi.annotation.Business;
import com.orinan.adminapi.common.api.AdminPageRequest;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.domain.support.controller.model.*;
import com.orinan.adminapi.domain.support.converter.AdminSupportConverter;
import com.orinan.adminapi.domain.support.service.AdminSupportService;
import com.orinan.adminapi.domain.user.service.AdminUserMutationGuard;
import com.orinan.db.support.*;
import com.orinan.db.support.enums.*;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.workspace.WorkspaceEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Objects;

@Business
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminSupportBusiness {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AdminSupportService service;
    private final AdminSupportConverter converter;
    private final AdminUserMutationGuard guard;
    private final AdminAuditService audit;

    public PageResponse<AdminSupportTicketResponse> tickets(SupportTicketStatus status, Long customer, Long workspace, int page, int size) {
        if (customer != null) positive(customer);
        if (workspace != null) positive(workspace);
        return converter.tickets(service.tickets(status, customer, workspace, AdminPageRequest.page(page, size)));
    }
    public AdminSupportTicketResponse ticket(long id) {
        positive(id);
        return converter.ticket(service.ticket(id));
    }
    public PageResponse<AdminSupportSessionResponse> sessions(long id, int page, int size) {
        positive(id);
        service.ticket(id);
        return converter.sessions(service.sessions(id, AdminPageRequest.page(page, size)), now());
    }
    public PageResponse<AdminSupportActionResponse> actions(long id, int page, int size) {
        positive(id);
        service.ticket(id);
        return converter.actions(service.actions(id, AdminPageRequest.page(page, size)));
    }

    @Transactional
    public AdminSupportTicketResponse create(long actor, AdminSupportTicketRequest request) {
        String reason = reason(request.reason(), "기술 지원 요청 등록");
        if (request.workspaceId() == null || request.customerUserId() == null || request.accessMode() == null
                || request.amountKrw() == null || request.amountKrw() < 0 || request.amountKrw() > 1_000_000_000L
                || request.title() == null || request.title().isBlank() || request.title().length() > 150
                || request.description() == null || request.description().isBlank() || request.description().length() > 1000)
            throw badRequest("지원 요청의 고객, 작업 범위, 금액과 설명을 확인해 주세요.");
        positive(request.workspaceId());
        var users = guard.lock(actor, request.customerUserId());
        var workspace = service.lockWorkspace(request.workspaceId());
        requireOwner(workspace, users.target());
        var ticket = service.save(new SupportTicketEntity(request.workspaceId(), request.customerUserId(), actor,
                request.title().strip(), request.description().strip(), request.accessMode(), request.amountKrw(), now()));
        audit.record(actor, "SUPPORT_TICKET_CREATE", "SUPPORT_TICKET", ticket.getId(), reason, null,
                "workspace:" + ticket.getWorkspaceId() + ";customer:" + ticket.getCustomerUserId()
                        + ";mode:" + ticket.getAccessMode() + ";amount_krw:" + ticket.getAmountKrw());
        return converter.ticket(ticket);
    }

    /** Records an operator's receipt check; it does not charge or confirm a payment-provider transaction. */
    @Transactional
    public AdminSupportTicketResponse payment(long actor, long id, AdminSupportPaymentRequest request) {
        String reason = reason(request.reason(), "기술 지원 수납 상태 기록");
        var locked = lock(actor, id);
        var ticket = locked.ticket();
        if (ticket.getRequestSource() == SupportRequestSource.CUSTOMER)
            throw forbidden("고객이 신청한 지원의 결제 상태는 토스페이먼츠 결제 확인으로만 변경할 수 있습니다.");
        if (ticket.getStatus() != SupportTicketStatus.REQUESTED && ticket.getStatus() != SupportTicketStatus.APPROVED)
            throw conflict("지원 작업 시작 전의 요청만 수납 상태를 변경할 수 있습니다.");
        String reference = request.paymentReference() == null ? null : request.paymentReference().strip();
        if (reference != null && reference.isEmpty()) reference = null;
        if (request.paymentStatus() == null || reference != null && reference.length() > 200)
            throw badRequest("수납 상태와 확인 번호를 확인해 주세요.");
        if (request.paymentStatus() == SupportPaymentStatus.PAID && (ticket.getAmountKrw() <= 0 || reference == null))
            throw badRequest("유료 지원은 양수 금액과 수납 확인 번호가 필요합니다.");
        if (request.paymentStatus() == SupportPaymentStatus.WAIVED && ticket.getAmountKrw() != 0)
            throw badRequest("지원료가 0원인 요청만 무료 처리할 수 있습니다.");
        if (request.paymentStatus() != SupportPaymentStatus.PAID) reference = null;
        if (ticket.getPaymentStatus() == request.paymentStatus() && Objects.equals(ticket.getPaymentReference(), reference))
            return converter.ticket(ticket);
        var previous = ticket.getPaymentStatus();
        ticket.setPaymentStatus(request.paymentStatus());
        ticket.setPaymentReference(reference);
        ticket.setPaymentRecordedAt(now());
        ticket.setPaymentRecordedBy(actor);
        ticket.setUpdatedAt(now());
        audit.record(actor, "SUPPORT_PAYMENT_RECORD", "SUPPORT_TICKET", id, reason, previous.name(), request.paymentStatus().name());
        return converter.ticket(ticket);
    }

    @Transactional
    public AdminSupportSessionTokenResponse start(long actor, long id, AdminSupportReasonRequest request) {
        String reason = reason(request.reason(), "기술 지원 고객 화면 접속 시작");
        var locked = lock(actor, id);
        var ticket = locked.ticket();
        boolean customerRequest = ticket.getRequestSource() == SupportRequestSource.CUSTOMER;
        Long assignedAdmin = ticket.getAssignedAdminId();
        if (assignedAdmin == null ? !customerRequest : assignedAdmin != actor)
            throw forbidden("지정된 담당 관리자만 지원 화면에 접근할 수 있습니다.");
        requireOwner(locked.workspace(), locked.users().target());
        LocalDateTime now = now();
        if ((ticket.getStatus() != SupportTicketStatus.APPROVED && ticket.getStatus() != SupportTicketStatus.IN_PROGRESS)
                || ticket.getApprovedAt() == null || ticket.getApprovalExpiresAt() == null || !ticket.getApprovalExpiresAt().isAfter(now))
            throw conflict("고객이 승인한 유효한 지원 요청이 필요합니다.");
        if (customerRequest && ticket.getPaymentStatus() != SupportPaymentStatus.PAID)
            throw conflict("토스페이먼츠 결제가 완료된 지원 요청만 시작할 수 있습니다.");
        if (!customerRequest && ticket.getPaymentStatus() != SupportPaymentStatus.PAID && ticket.getPaymentStatus() != SupportPaymentStatus.WAIVED)
            throw conflict("수납 확인 또는 무료 지원 처리를 먼저 완료해 주세요.");
        // The locked ticket serializes competing administrators. Failed validation or
        // session/audit persistence rolls back the assignment in this same transaction.
        if (assignedAdmin == null) ticket.setAssignedAdminId(actor);
        service.endSessions(id, now);
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        LocalDateTime expiresAt = now.plusMinutes(15);
        if (ticket.getApprovalExpiresAt().isBefore(expiresAt)) expiresAt = ticket.getApprovalExpiresAt();
        var session = service.save(new SupportSessionEntity(id, actor, ticket.getCustomerUserId(), ticket.getWorkspaceId(),
                SupportTokenHash.hash(token), locked.users().actor().authVersion(), locked.users().target().authVersion(),
                ticket.getAccessMode(), now, expiresAt));
        ticket.setStatus(SupportTicketStatus.IN_PROGRESS);
        ticket.setUpdatedAt(now);
        audit.record(actor, "SUPPORT_SESSION_START", "SUPPORT_TICKET", id, reason, null,
                "session:" + session.getId() + ";mode:" + ticket.getAccessMode());
        return converter.token(session, token);
    }

    @Transactional
    public AdminSupportTicketResponse complete(long actor, long id, AdminSupportReasonRequest request) {
        return finish(actor, id, request, SupportTicketStatus.COMPLETED);
    }
    @Transactional
    public AdminSupportTicketResponse cancel(long actor, long id, AdminSupportReasonRequest request) {
        return finish(actor, id, request, SupportTicketStatus.CANCELLED);
    }
    @Transactional
    public AdminSupportSessionResponse end(long actor, long id, AdminSupportReasonRequest request) {
        positive(id);
        String reason = reason(request.reason(), "기술 지원 고객 화면 접속 종료");
        long ticketId = service.sessionTicketId(id);
        lock(actor, ticketId);
        var session = service.lockSession(id);
        LocalDateTime now = now();
        if (session.getEndedAt() == null) {
            session.setEndedAt(now);
            audit.record(actor, "SUPPORT_SESSION_END", "SUPPORT_TICKET", ticketId, reason,
                    "session:" + id, "ended");
        }
        return converter.session(session, now);
    }

    private AdminSupportTicketResponse finish(long actor, long id, AdminSupportReasonRequest request, SupportTicketStatus status) {
        String reason = reason(request.reason(), status == SupportTicketStatus.COMPLETED
                ? "기술 지원 요청 완료" : "기술 지원 요청 취소");
        var ticket = lock(actor, id).ticket();
        if (ticket.getStatus() == status) return converter.ticket(ticket);
        if (ticket.getStatus() == SupportTicketStatus.CANCELLED || ticket.getStatus() == SupportTicketStatus.COMPLETED)
            throw conflict("이미 종료된 지원 요청입니다.");
        if (status == SupportTicketStatus.COMPLETED && ticket.getStatus() == SupportTicketStatus.REQUESTED)
            throw conflict("고객 승인 전의 요청은 취소로 종료해 주세요.");
        var previous = ticket.getStatus();
        LocalDateTime now = now();
        service.endSessions(id, now);
        ticket.setStatus(status);
        ticket.setUpdatedAt(now);
        audit.record(actor, status == SupportTicketStatus.COMPLETED ? "SUPPORT_TICKET_COMPLETE" : "SUPPORT_TICKET_CANCEL",
                "SUPPORT_TICKET", id, reason, previous.name(), status.name());
        return converter.ticket(ticket);
    }

    private LockedTicket lock(long actor, long id) {
        positive(id);
        // Fetch only immutable coordinates before acquiring locks, avoiding stale managed ticket state.
        var scope = service.scope(id);
        var users = guard.lock(actor, scope.getCustomerUserId());
        var workspace = service.lockWorkspace(scope.getWorkspaceId());
        var ticket = service.lockTicket(id);
        return new LockedTicket(ticket, workspace, users);
    }
    private void requireOwner(WorkspaceEntity workspace, AdminUserMutationGuard.LockedUser customer) {
        if (customer.status() != UserStatus.REGISTERED || workspace.getUser() == null
                || workspace.getUser().getId() != customer.id())
            throw conflict("현재 활성 상태인 워크스페이스 소유자의 지원 요청만 시작할 수 있습니다.");
    }
    private void positive(long id) { if (id < 1) throw badRequest("올바른 ID를 입력해 주세요."); }
    private String reason(String reason, String defaultReason) {
        if (reason != null && reason.length() > 500) throw badRequest("작업 메모는 500자 이하로 입력해 주세요.");
        return reason == null || reason.isBlank() ? defaultReason : reason.strip();
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Seoul")); }
    private AdminException badRequest(String message) { return new AdminException(HttpStatus.BAD_REQUEST, message); }
    private AdminException conflict(String message) { return new AdminException(HttpStatus.CONFLICT, message); }
    private AdminException forbidden(String message) { return new AdminException(HttpStatus.FORBIDDEN, message); }
    private record LockedTicket(SupportTicketEntity ticket, WorkspaceEntity workspace, AdminUserMutationGuard.LockedUsers users) {}
}
