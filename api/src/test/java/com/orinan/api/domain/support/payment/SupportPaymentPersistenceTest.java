package com.orinan.api.domain.support.payment;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.support.payment.SupportPaymentModels.*;
import com.orinan.api.domain.support.service.SupportTerms;
import com.orinan.db.adminaudit.*;
import com.orinan.db.crypto.*;
import com.orinan.db.support.*;
import com.orinan.db.support.enums.*;
import com.orinan.db.supportpayment.*;
import com.orinan.db.user.*;
import com.orinan.db.user.enums.*;
import com.orinan.db.userprofile.UserProfileEntity;
import com.orinan.db.workspace.*;
import com.orinan.db.workspacemember.WorkspaceMemberEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@ActiveProfiles("test")
@ContextConfiguration(classes = SupportPaymentPersistenceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SupportPaymentPersistenceTest {
    private static final String KEY = "test-private-payment-key";
    @Autowired private SupportPaymentService service;
    @Autowired private SupportPaymentTransactionService paymentTransactions;
    @Autowired private SupportPaymentRepository payments;
    @Autowired private TossPaymentsClient toss;
    @Autowired private TossPaymentProperties properties;
    @Autowired private EntityManager em;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private long adminId, ownerId, strangerId, workspaceId, ticketId;

    @BeforeEach void seed() {
        reset(toss);
        properties.setClientKey("test_gck_public"); properties.setSecretKey("test_gsk_private");
        tx(() -> {
            for (String entity : List.of("SupportPaymentEntity", "SupportActionEntity", "SupportSessionEntity", "SupportTicketEntity",
                    "AdminAuditEntity", "WorkspaceMemberEntity", "WorkspaceEntity", "UserProfileEntity", "UserEntity")) {
                em.createQuery("delete from " + entity).executeUpdate();
            }
            adminId = user("admin@payment.example", UserRole.ADMIN).getId();
            var owner = user("owner@payment.example", UserRole.CUSTOMER); ownerId = owner.getId();
            strangerId = user("stranger@payment.example", UserRole.CUSTOMER).getId();
            var workspace = WorkspaceEntity.builder().name("결제 테스트 워크스페이스").user(owner).build();
            em.persist(workspace); workspaceId = workspace.getId();
            var ticket = customerTicket(); em.persist(ticket); ticketId = ticket.getId();
        });
    }

    @Test void createsOneServerPricedOrderAndReusesReadyOrderWithoutCallingToss() {
        var first = order(); var second = order();
        assertThat(first.orderId()).isEqualTo(second.orderId()).matches("support_[a-f0-9]{32}");
        assertThat(first.customerKey()).matches("support_[a-f0-9]{32}").isNotEqualTo(first.orderId());
        assertThat(first.amount()).isEqualTo(9900); assertThat(first.ticketId()).isEqualTo(ticketId);
        assertThat(first.clientKey()).isEqualTo("test_gck_public"); assertThat(first.status()).isEqualTo("READY");
        assertThat(payments.count()).isEqualTo(1); assertThat(ticketPayment()).isEqualTo("UNPAID");
        assertThat(service.payment(workspaceId, ticketId, ownerId, null).status()).isEqualTo("READY");
        verifyNoInteractions(toss);
    }

    @ParameterizedTest @ValueSource(strings = {"amount", "source", "status", "payment", "terms", "snapshot", "expired", "suspended"})
    void rejectsIneligibleTicketsBeforeCreatingAnOrder(String kind) {
        switch (kind) {
            case "amount" -> jdbc.update("update support_tickets set amount_krw=1 where id=?", ticketId);
            case "source" -> jdbc.update("update support_tickets set request_source='ADMIN' where id=?", ticketId);
            case "status" -> jdbc.update("update support_tickets set status='CANCELLED' where id=?", ticketId);
            case "payment" -> jdbc.update("update support_tickets set payment_status='PAID' where id=?", ticketId);
            case "terms" -> jdbc.update("update support_tickets set terms_version='old-version' where id=?", ticketId);
            case "snapshot" -> jdbc.update("update support_tickets set terms_snapshot='unapproved terms' where id=?", ticketId);
            case "expired" -> jdbc.update("update support_tickets set approval_expires_at=? where id=?", SeoulDateTimes.now().minusSeconds(1), ticketId);
            case "suspended" -> jdbc.update("update users set status='SUSPENDED' where id=?", ownerId);
        }
        assertThatThrownBy(this::order).isInstanceOf(ApiException.class);
        assertThat(payments.count()).isZero(); verifyNoInteractions(toss);
    }

    @Test void ownershipConfigurationAndForeignOrdersAreValidatedBeforeAnyProviderCall() {
        assertThatThrownBy(() -> service.createOrder(workspaceId, ticketId, strangerId)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.payment(workspaceId, ticketId, ownerId, null)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCodeIfs()).isEqualTo(SupportPaymentErrorCode.NOT_FOUND));
        properties.setSecretKey("");
        assertThatThrownBy(this::order).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCodeIfs()).isEqualTo(SupportPaymentErrorCode.UNAVAILABLE));
        properties.setSecretKey("test_gsk_private");
        var order = order();
        assertThatThrownBy(() -> service.confirm(workspaceId, ticketId, ownerId, new Confirm(KEY, order.orderId(), 1)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.confirm(workspaceId, ticketId, strangerId, confirm(order)))
                .isInstanceOf(ApiException.class);
        long[] other = new long[1];
        tx(() -> { var ticket = customerTicket(); em.persist(ticket); other[0] = ticket.getId(); });
        assertThatThrownBy(() -> service.confirm(workspaceId, other[0], ownerId, confirm(order))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.payment(workspaceId, other[0], ownerId, order.orderId())).isInstanceOf(ApiException.class);
        assertThat(state(order)).isEqualTo("READY"); verifyNoInteractions(toss);
    }

    @Test void confirmationCommitsItsClaimBeforeHttpAndSavesVerifiedPaymentWithoutGrantingASession() {
        var order = order();
        when(toss.confirm(KEY, order.orderId(), 9900)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(state(order)).isEqualTo("CONFIRMING");
            return snapshot(order, "DONE", 9900);
        });
        var result = service.confirm(workspaceId, ticketId, ownerId, confirm(order));
        assertThat(result.status()).isEqualTo("PAID"); assertThat(ticketPayment()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select status from support_tickets where id=?", String.class, ticketId)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select count(*) from support_sessions", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select payment_key from support_payments where order_id=?", String.class, order.orderId()))
                .isNotEqualTo(KEY).doesNotContain(KEY);
        assertThat(payments.findByOrderId(order.orderId()).orElseThrow().getPaymentKey()).isEqualTo(KEY);
        var mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        assertThat(mapper.writeValueAsString(result)).contains("order_id", "ticket_id").doesNotContain(KEY, "payment_key", "secret");
        assertThat(jdbc.queryForList("select reason,before_value,after_value from admin_audit_logs").toString()).doesNotContain(KEY);
        assertThatThrownBy(this::order).isInstanceOf(ApiException.class);
    }

    @Test void duplicateCallbacksOnlyReadAndCannotSubstituteThePaymentKey() {
        var order = paidOrder();
        when(toss.getByPaymentKey(KEY)).thenReturn(snapshot(order, "DONE", 9900));
        assertThat(service.confirm(workspaceId, ticketId, ownerId, confirm(order)).status()).isEqualTo("PAID");
        assertThatThrownBy(() -> service.confirm(workspaceId, ticketId, ownerId, new Confirm("different-key", order.orderId(), 9900)))
                .isInstanceOf(ApiException.class);
        verify(toss, times(1)).confirm(KEY, order.orderId(), 9900);
        verify(toss, times(1)).getByPaymentKey(KEY);
    }

    @Test void anUnknownConfirmationIsRecoveredByReadAndBlocksAnotherCharge() {
        var order = order();
        when(toss.confirm(KEY, order.orderId(), 9900)).thenThrow(new TossPaymentException("safe timeout", true, null));
        assertThat(service.confirm(workspaceId, ticketId, ownerId, confirm(order)).status()).isEqualTo("UNKNOWN");
        assertThat(ticketPayment()).isEqualTo("UNPAID");
        assertThatThrownBy(this::order).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCodeIfs()).isEqualTo(SupportPaymentErrorCode.PAYMENT_PENDING));
        when(toss.getByPaymentKey(KEY)).thenReturn(snapshot(order, "DONE", 9900));
        assertThat(service.payment(workspaceId, ticketId, ownerId, null).status()).isEqualTo("PAID");
        verify(toss, times(1)).confirm(KEY, order.orderId(), 9900);
    }

    @Test void definitiveRejectionAllowsNewOrderWhileTheOldOrderCanNeverBeReconfirmed() {
        var first = order();
        when(toss.confirm(KEY, first.orderId(), 9900)).thenThrow(new TossPaymentException("safe rejected", false, 400));
        assertThat(service.confirm(workspaceId, ticketId, ownerId, confirm(first)).status()).isEqualTo("FAILED");
        var second = order(); assertThat(second.orderId()).isNotEqualTo(first.orderId());
        when(toss.getByPaymentKey(KEY)).thenReturn(snapshot(first, "ABORTED", 9900));
        assertThat(service.confirm(workspaceId, ticketId, ownerId, confirm(first)).status()).isEqualTo("ABORTED");
        verify(toss, times(1)).confirm(KEY, first.orderId(), 9900);
        verify(toss, never()).confirm(anyString(), eq(second.orderId()), anyLong());
    }

    @ParameterizedTest @ValueSource(strings = {"order", "key", "currency", "total", "balance", "status"})
    void providerSuccessStillRequiresExactOrderKeyCurrencyAndBothAmounts(String invalid) {
        var order = order();
        var response = new TossPaymentsClient.PaymentSnapshot(invalid.equals("order") ? "another_order" : order.orderId(),
                invalid.equals("key") ? "different_key" : KEY, invalid.equals("status") ? "IN_PROGRESS" : "DONE",
                invalid.equals("currency") ? "USD" : "KRW", invalid.equals("total") ? 1 : 9900, invalid.equals("balance") ? 1 : 9900);
        when(toss.confirm(KEY, order.orderId(), 9900)).thenReturn(response);
        assertThat(service.confirm(workspaceId, ticketId, ownerId, confirm(order)).status()).isEqualTo("UNKNOWN");
        assertThat(ticketPayment()).isEqualTo("UNPAID");
    }

    @Test void cancellationOrOwnershipChangeDuringApprovalDoesNotEraseTheRealPaymentOrRegrantConsent() {
        var order = order();
        when(toss.confirm(KEY, order.orderId(), 9900)).thenAnswer(invocation -> {
            tx(() -> {
                em.find(SupportTicketEntity.class, ticketId).setStatus(SupportTicketStatus.CANCELLED);
                em.find(WorkspaceEntity.class, workspaceId).setUser(em.getReference(UserEntity.class, strangerId));
                em.find(UserEntity.class, ownerId).setStatus(UserStatus.SUSPENDED);
            });
            return snapshot(order, "DONE", 9900);
        });
        assertThat(service.confirm(workspaceId, ticketId, ownerId, confirm(order)).status()).isEqualTo("PAID");
        assertThat(ticketPayment()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select status from support_tickets where id=?", String.class, ticketId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select count(*) from support_sessions", Long.class)).isZero();
        assertThatThrownBy(() -> service.payment(workspaceId, ticketId, ownerId, null)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"CANCELED", "PARTIAL_CANCELED", "WAITING_FOR_DEPOSIT"})
    void verifiedRefundOrDepositReversalRevokesPaidAccessAndEndsSessions(String remoteStatus) {
        var order = paidOrder(); long sessionId = session();
        long balance = remoteStatus.equals("CANCELED") ? 0 : remoteStatus.equals("PARTIAL_CANCELED") ? 5000 : 9900;
        when(toss.getByPaymentKey(KEY)).thenReturn(snapshot(order, remoteStatus, balance));
        assertThat(service.payment(workspaceId, ticketId, ownerId, null).status()).isEqualTo(remoteStatus);
        assertThat(ticketPayment()).isEqualTo("UNPAID");
        assertThat(jdbc.queryForObject("select ended_at is not null from support_sessions where id=?", Boolean.class, sessionId)).isTrue();
        assertThatThrownBy(this::order).isInstanceOf(ApiException.class);
    }

    @Test void staleDoneCannotUndoARefundAndStaleFailureCannotUndoVerifiedDone() {
        var order = paidOrder();
        var earlier = paymentTransactions.prepareQuery(workspaceId, ticketId, ownerId, order.orderId());
        var later = paymentTransactions.prepareQuery(workspaceId, ticketId, ownerId, order.orderId());
        assertThat(paymentTransactions.apply(later.attempt(), snapshot(order, "DONE", 9900)).status()).isEqualTo("PAID");
        assertThat(paymentTransactions.failed(earlier.attempt(), true).status()).isEqualTo("PAID");
        assertThat(paymentTransactions.apply(earlier.attempt(), snapshot(order, "CANCELED", 0)).status()).isEqualTo("CANCELED");
        assertThat(paymentTransactions.apply(later.attempt(), snapshot(order, "DONE", 9900)).status()).isEqualTo("CANCELED");
        assertThat(ticketPayment()).isEqualTo("UNPAID");
    }

    @Test void transportFailureDoesNotPretendAPreviouslyVerifiedChargeWasCanceled() {
        var order = paidOrder();
        when(toss.getByPaymentKey(KEY)).thenThrow(new TossPaymentException("safe timeout", true, null));
        assertThatThrownBy(() -> service.payment(workspaceId, ticketId, ownerId, null)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCodeIfs()).isEqualTo(SupportPaymentErrorCode.RECONCILE_REQUIRED));
        assertThat(state(order)).isEqualTo("PAID"); assertThat(ticketPayment()).isEqualTo("PAID");
    }

    @Test void webhookUsesOnlyKnownOrdersAndReconcilesEvenAfterTheOwnerChanged() {
        service.webhook("support_unknown"); verifyNoInteractions(toss);
        var order = paidOrder(); long sessionId = session();
        tx(() -> em.find(WorkspaceEntity.class, workspaceId).setUser(em.getReference(UserEntity.class, strangerId)));
        when(toss.getByPaymentKey(KEY)).thenReturn(snapshot(order, "CANCELED", 0));
        service.webhook(order.orderId());
        assertThat(ticketPayment()).isEqualTo("UNPAID");
        assertThat(jdbc.queryForObject("select ended_at is not null from support_sessions where id=?", Boolean.class, sessionId)).isTrue();
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong());
    }

    @Test void earlyWebhooksCannotBindAnUnapprovedKeyOrConsumeAReadyCheckout() {
        var order = order();
        service.webhook(order.orderId());
        assertThat(state(order)).isEqualTo("READY"); assertThat(ticketPayment()).isEqualTo("UNPAID");
        assertThat(payments.findByOrderId(order.orderId()).orElseThrow().getPaymentKey()).isNull();
        verifyNoInteractions(toss);
    }

    @Test void failedWebhookReadReturnsAnErrorSoTheProviderCanRedeliver() {
        var order = order();
        paymentTransactions.prepareConfirm(workspaceId, ticketId, ownerId, confirm(order));
        when(toss.getByPaymentKey(KEY)).thenThrow(new TossPaymentException("safe unavailable", true, 503));
        assertThatThrownBy(() -> service.webhook(order.orderId())).isInstanceOf(ApiException.class);
        assertThat(state(order)).isEqualTo("UNKNOWN"); assertThat(ticketPayment()).isEqualTo("UNPAID");
        verify(toss, never()).confirm(anyString(), anyString(), anyLong());
    }

    @Test void auditFailureAfterAChargeLeavesARecoverableClaimAndRollsBackTheLocalPaidFlag() {
        var order = order();
        when(toss.confirm(KEY, order.orderId(), 9900)).thenReturn(snapshot(order, "DONE", 9900));
        jdbc.execute("alter table admin_audit_logs add constraint reject_payment_result check (action <> 'SUPPORT_PAYMENT_STATE')");
        try {
            assertThatThrownBy(() -> service.confirm(workspaceId, ticketId, ownerId, confirm(order))).isInstanceOf(RuntimeException.class);
            assertThat(state(order)).isEqualTo("CONFIRMING"); assertThat(ticketPayment()).isEqualTo("UNPAID");
        } finally { jdbc.execute("alter table admin_audit_logs drop constraint reject_payment_result"); }
        when(toss.getByPaymentKey(KEY)).thenReturn(snapshot(order, "DONE", 9900));
        assertThat(service.payment(workspaceId, ticketId, ownerId, null).status()).isEqualTo("PAID");
        verify(toss, times(1)).confirm(KEY, order.orderId(), 9900);
    }

    @Test void theSameProviderPaymentKeyCannotBeChargedAgainstAnotherTicket() {
        paidOrder();
        long[] secondTicket = new long[1];
        tx(() -> { var ticket = customerTicket(); em.persist(ticket); secondTicket[0] = ticket.getId(); });
        var second = service.createOrder(workspaceId, secondTicket[0], ownerId);
        assertThatThrownBy(() -> service.confirm(workspaceId, secondTicket[0], ownerId, confirm(second))).isInstanceOf(ApiException.class);
        assertThat(state(second)).isEqualTo("READY");
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong());
    }

    @Test void simultaneousCallbacksNeverSendTwoApprovalRequests() throws Exception {
        var order = order();
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        when(toss.confirm(KEY, order.orderId(), 9900)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("concurrent callback did not complete");
            return snapshot(order, "DONE", 9900);
        });
        when(toss.getByPaymentKey(KEY)).thenReturn(snapshot(order, "IN_PROGRESS", 9900), snapshot(order, "DONE", 9900));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> service.confirm(workspaceId, ticketId, ownerId, confirm(order)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(this::order).isInstanceOf(ApiException.class);
            assertThat(service.confirm(workspaceId, ticketId, ownerId, confirm(order)).status()).isEqualTo("UNKNOWN");
            finish.countDown(); first.get(5, TimeUnit.SECONDS);
            assertThat(service.payment(workspaceId, ticketId, ownerId, null).status()).isEqualTo("PAID");
            verify(toss, times(1)).confirm(KEY, order.orderId(), 9900);
        } finally { finish.countDown(); executor.shutdownNow(); }
    }

    @Test void osivRetainedTicketCannotOverwriteConcurrentRevocationWhenApprovalReturns() {
        var order = order();
        withRequestEntityManager(requestEntityManager -> {
            when(toss.confirm(KEY, order.orderId(), 9900)).thenAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                var cached = requestEntityManager.find(SupportTicketEntity.class, ticketId);
                assertThat(cached.getStatus()).isEqualTo(SupportTicketStatus.APPROVED);
                inSeparateRequest(() -> {
                    tx(() -> {
                        em.find(SupportTicketEntity.class, ticketId).setStatus(SupportTicketStatus.CANCELLED);
                        em.find(WorkspaceEntity.class, workspaceId).setUser(em.getReference(UserEntity.class, strangerId));
                        em.find(UserEntity.class, ownerId).setStatus(UserStatus.SUSPENDED);
                    });
                    return null;
                });
                // Prove this test really retains an outdated first-level cache between transactions.
                assertThat(requestEntityManager.find(SupportTicketEntity.class, ticketId)).isSameAs(cached);
                assertThat(cached.getStatus()).isEqualTo(SupportTicketStatus.APPROVED);
                return snapshot(order, "DONE", 9900);
            });
            assertThat(service.confirm(workspaceId, ticketId, ownerId, confirm(order)).status()).isEqualTo("PAID");
            assertThat(requestEntityManager.find(SupportTicketEntity.class, ticketId).getStatus()).isEqualTo(SupportTicketStatus.CANCELLED);
        });
        assertThat(jdbc.queryForObject("select status from support_tickets where id=?", String.class, ticketId)).isEqualTo("CANCELLED");
        assertThat(ticketPayment()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select count(*) from support_sessions", Long.class)).isZero();
    }

    @Test void osivRetainedWorkspaceAndCustomerAreRefreshedBeforeAFirstApproval() {
        withRequestEntityManager(requestEntityManager -> {
            var order = order();
            var cachedWorkspace = requestEntityManager.find(WorkspaceEntity.class, workspaceId);
            assertThat(cachedWorkspace.getUser().getId()).isEqualTo(ownerId);
            inSeparateRequest(() -> {
                tx(() -> em.find(WorkspaceEntity.class, workspaceId).setUser(em.getReference(UserEntity.class, strangerId)));
                return null;
            });
            assertThat(cachedWorkspace.getUser().getId()).isEqualTo(ownerId);
            assertThatThrownBy(() -> service.confirm(workspaceId, ticketId, ownerId, confirm(order)))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCodeIfs()).isEqualTo(SupportPaymentErrorCode.ACCESS_DENIED));
        });
        verifyNoInteractions(toss);
    }

    @Test void osivRetainedPaymentRevisionCannotApplyAnOlderApprovalOverANewerVerification() {
        var order = order();
        withRequestEntityManager(requestEntityManager -> {
            var earlier = paymentTransactions.prepareConfirm(workspaceId, ticketId, ownerId, confirm(order));
            var cached = payments.findByOrderId(order.orderId()).orElseThrow();
            long oldRevision = cached.getVerificationRevision();
            var later = inSeparateRequest(() -> paymentTransactions.prepareQuery(workspaceId, ticketId, ownerId, order.orderId()));
            assertThat(cached.getVerificationRevision()).isEqualTo(oldRevision);
            assertThat(later.attempt().revision()).isGreaterThan(oldRevision);
            assertThat(paymentTransactions.apply(earlier.attempt(), snapshot(order, "DONE", 9900)).status()).isEqualTo("CONFIRMING");
            assertThat(ticketPayment()).isEqualTo("UNPAID");
            assertThat(paymentTransactions.apply(later.attempt(), snapshot(order, "DONE", 9900)).status()).isEqualTo("PAID");
        });
        assertThat(ticketPayment()).isEqualTo("PAID");
    }

    @Test void osivRetainedPaidStateCannotRestoreAPaymentCanceledByAnotherRequest() {
        var order = paidOrder();
        withRequestEntityManager(requestEntityManager -> {
            var earlier = paymentTransactions.prepareQuery(workspaceId, ticketId, ownerId, order.orderId());
            var cached = payments.findByOrderId(order.orderId()).orElseThrow();
            inSeparateRequest(() -> {
                var later = paymentTransactions.prepareQuery(workspaceId, ticketId, ownerId, order.orderId());
                return paymentTransactions.apply(later.attempt(), snapshot(order, "CANCELED", 0));
            });
            assertThat(cached.getStatus().name()).isEqualTo("PAID");
            assertThat(paymentTransactions.apply(earlier.attempt(), snapshot(order, "DONE", 9900)).status()).isEqualTo("CANCELED");
        });
        assertThat(state(order)).isEqualTo("CANCELED"); assertThat(ticketPayment()).isEqualTo("UNPAID");
    }

    private Order order() { return service.createOrder(workspaceId, ticketId, ownerId); }
    private Confirm confirm(Order order) { return new Confirm(KEY, order.orderId(), 9900); }
    private Order paidOrder() {
        var order = order(); when(toss.confirm(KEY, order.orderId(), 9900)).thenReturn(snapshot(order, "DONE", 9900));
        service.confirm(workspaceId, ticketId, ownerId, confirm(order)); return order;
    }
    private TossPaymentsClient.PaymentSnapshot snapshot(Order order, String status, long balance) {
        return new TossPaymentsClient.PaymentSnapshot(order.orderId(), KEY, status, "KRW", 9900, balance);
    }
    private String ticketPayment() { return jdbc.queryForObject("select payment_status from support_tickets where id=?", String.class, ticketId); }
    private String state(Order order) { return jdbc.queryForObject("select status from support_payments where order_id=?", String.class, order.orderId()); }
    private SupportTicketEntity customerTicket() {
        return SupportTicketEntity.customerRequest(workspaceId, ownerId, "상품 등록 지원", "합의한 상품 등록 범위 지원",
                SupportAccessMode.OPERATE, 9900, SupportTerms.VERSION, SupportTerms.snapshot(SupportAccessMode.OPERATE), SeoulDateTimes.now());
    }
    private long session() {
        long[] id = new long[1];
        tx(() -> {
            var ticket = em.find(SupportTicketEntity.class, ticketId);
            ticket.setStatus(SupportTicketStatus.IN_PROGRESS); ticket.setAssignedAdminId(adminId);
            var now = SeoulDateTimes.now();
            var session = new SupportSessionEntity(ticketId, adminId, ownerId, workspaceId, SupportTokenHash.hash("support-session"),
                    0, 0, SupportAccessMode.OPERATE, now, now.plusMinutes(15));
            em.persist(session); id[0] = session.getId();
        });
        return id[0];
    }
    private UserEntity user(String email, UserRole role) {
        var user = UserEntity.builder().email(email).password("test-password").status(UserStatus.REGISTERED).role(role).build();
        em.persist(user); return user;
    }
    private void tx(Runnable action) { new TransactionTemplate(manager).executeWithoutResult(status -> action.run()); }

    private void withRequestEntityManager(Consumer<EntityManager> request) {
        assertThat(TransactionSynchronizationManager.hasResource(entityManagerFactory)).isFalse();
        var requestEntityManager = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestEntityManager));
        try { request.accept(requestEntityManager); }
        finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            requestEntityManager.close();
        }
    }

    private <T> T inSeparateRequest(Callable<T> action) {
        var executor = Executors.newSingleThreadExecutor();
        try { return executor.submit(action).get(10, TimeUnit.SECONDS); }
        catch (Exception error) { throw new AssertionError("Concurrent request did not complete", error); }
        finally { executor.shutdownNow(); }
    }

    @Configuration
    @EntityScan(basePackageClasses = {UserEntity.class, UserProfileEntity.class, WorkspaceEntity.class,
            WorkspaceMemberEntity.class, SupportTicketEntity.class, SupportPaymentEntity.class, AdminAuditEntity.class})
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, WorkspaceRepository.class, SupportTicketRepository.class,
            SupportPaymentRepository.class})
    @Import({SupportPaymentTransactionService.class, SupportPaymentService.class, AdminAuditRepository.class})
    static class Config {
        @Bean TossPaymentsClient toss() { return mock(TossPaymentsClient.class); }
        @Bean TossPaymentProperties properties() { return new TossPaymentProperties(); }
        @Bean AesGcmStringEncryptor encryptor() {
            return new AesGcmStringEncryptor(new CryptoProperties(Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))));
        }
    }
}
