package com.orinan.adminapi.domain.support;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.domain.support.business.AdminSupportBusiness;
import com.orinan.adminapi.domain.support.controller.model.*;
import com.orinan.adminapi.domain.support.converter.AdminSupportConverter;
import com.orinan.adminapi.domain.support.service.AdminSupportService;
import com.orinan.adminapi.domain.user.service.*;
import com.orinan.db.adminaudit.*;
import com.orinan.db.crypto.*;
import com.orinan.db.support.*;
import com.orinan.db.support.enums.*;
import com.orinan.db.user.*;
import com.orinan.db.user.enums.*;
import com.orinan.db.userprofile.UserProfileEntity;
import com.orinan.db.workspace.*;
import com.orinan.db.workspacemember.WorkspaceMemberEntity;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpStatus.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@ActiveProfiles("test")
@ContextConfiguration(classes = AdminSupportIntegrationTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminSupportIntegrationTest {
    @Autowired AdminSupportBusiness support;
    @Autowired SupportSessionRepository sessions;
    @Autowired SupportTicketRepository tickets;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    long admin, secondAdmin, customer, stranger, workspace;

    @BeforeEach
    void seed() {
        tx(() -> {
            for (String type : List.of("SupportActionEntity", "SupportSessionEntity", "SupportTicketEntity", "AdminAuditEntity",
                    "WorkspaceMemberEntity", "WorkspaceEntity", "UserProfileEntity", "UserEntity"))
                em.createQuery("delete from " + type).executeUpdate();
            admin = user("admin@example.test", UserRole.ADMIN);
            secondAdmin = user("another-admin@example.test", UserRole.ADMIN);
            customer = user("customer@example.test", UserRole.CUSTOMER);
            stranger = user("stranger@example.test", UserRole.CUSTOMER);
            var item = WorkspaceEntity.builder().name("고객 워크스페이스").user(em.getReference(UserEntity.class, customer)).build();
            em.persist(item); workspace = item.getId();
        });
    }

    @Test
    void creationBindsCurrentOwnerAndActorAndLeavesApprovalAndPaymentPending() {
        var ticket = create(30000);
        assertThat(ticket.assignedAdminId()).isEqualTo(admin);
        assertThat(ticket.customerUserId()).isEqualTo(customer);
        assertThat(ticket.workspaceId()).isEqualTo(workspace);
        assertThat(ticket.amountKrw()).isEqualTo(30000);
        assertThat(ticket.status()).isEqualTo(SupportTicketStatus.REQUESTED);
        assertThat(ticket.paymentStatus()).isEqualTo(SupportPaymentStatus.UNPAID);
        assertThat(ticket.approvedAt()).isNull();
        assertThat(auditCount("SUPPORT_TICKET_CREATE")).isEqualTo(1);
        assertCode(() -> support.create(admin, request(stranger, 100)), CONFLICT);
        assertCode(() -> support.create(stranger, request(customer, 100)), FORBIDDEN);
        jdbc.update("update users set status='SUSPENDED' where id=?", customer);
        assertCode(() -> support.create(admin, request(customer, 100)), CONFLICT);
    }

    @Test
    void searchFiltersAndPaginationExcludeOtherCustomerTickets() {
        var first = create(0);
        var second = create(15000);
        assertThat(support.tickets(SupportTicketStatus.REQUESTED, customer, workspace, 0, 1).items())
                .extracting(AdminSupportTicketResponse::id).containsExactly(second.id());
        assertThat(support.tickets(null, customer, workspace, 1, 1).items())
                .extracting(AdminSupportTicketResponse::id).containsExactly(first.id());
        assertThat(support.tickets(null, stranger, workspace, 0, 20).totalElements()).isZero();
        assertCode(() -> support.tickets(null, null, null, 0, 101), BAD_REQUEST);
        assertCode(() -> support.ticket(0), BAD_REQUEST);
        assertCode(() -> support.ticket(Long.MAX_VALUE), NOT_FOUND);
    }

    @Test
    void manualReceiptRequiresReferenceAndPositiveFeeAndWaiverRequiresZeroFee() {
        var paid = create(30000);
        assertCode(() -> support.payment(admin, paid.id(), payment(SupportPaymentStatus.PAID, null)), BAD_REQUEST);
        assertCode(() -> support.payment(admin, paid.id(), payment(SupportPaymentStatus.WAIVED, null)), BAD_REQUEST);
        assertThat(support.payment(admin, paid.id(), payment(SupportPaymentStatus.PAID, "  bank-2026-42  ")).paymentReference())
                .isEqualTo("bank-2026-42");
        assertThat(support.ticket(paid.id()).paymentRecordedBy()).isEqualTo(admin);
        support.payment(admin, paid.id(), payment(SupportPaymentStatus.PAID, "bank-2026-42"));
        assertThat(auditCount("SUPPORT_PAYMENT_RECORD")).isEqualTo(1);
        var free = create(0);
        assertCode(() -> support.payment(admin, free.id(), payment(SupportPaymentStatus.PAID, "bank-42")), BAD_REQUEST);
        assertThat(support.payment(admin, free.id(), payment(SupportPaymentStatus.WAIVED, null)).paymentStatus())
                .isEqualTo(SupportPaymentStatus.WAIVED);
    }

    @Test
    void customerRequestsExposeConsentAndCannotBeManuallyMarkedPaidOrWaived() {
        long id = customerRequest(false);
        var ticket = support.ticket(id);
        assertThat(ticket.assignedAdminId()).isNull();
        assertThat(ticket.requestSource()).isEqualTo(SupportRequestSource.CUSTOMER);
        assertThat(ticket.termsVersion()).isEqualTo("support-terms-v1");
        assertThat(ticket.termsSnapshot()).isEqualTo("지원료 9,900원 및 지원 범위에 동의합니다.");
        assertThat(ticket.amountKrw()).isEqualTo(9900);
        assertThat(ticket.status()).isEqualTo(SupportTicketStatus.APPROVED);
        assertThat(ticket.paymentStatus()).isEqualTo(SupportPaymentStatus.UNPAID);
        assertThat(support.tickets(null, customer, workspace, 0, 20).items()).singleElement()
                .satisfies(item -> assertThat(item.assignedAdminId()).isNull());

        for (SupportPaymentStatus status : SupportPaymentStatus.values())
            assertCode(() -> support.payment(admin, id, payment(status, "manual-override")), FORBIDDEN);
        assertCode(() -> start(id), CONFLICT);
        assertThat(support.ticket(id).assignedAdminId()).isNull();
        assertThat(support.ticket(id).paymentStatus()).isEqualTo(SupportPaymentStatus.UNPAID);
        assertThat(auditCount("SUPPORT_PAYMENT_RECORD")).isZero();
        assertThat(sessions.count()).isZero();
    }

    @Test
    void paidCustomerRequestIsClaimedByStartingAdminWithExistingSessionLimits() {
        long id = customerRequest(true);
        var token = support.start(secondAdmin, id, new AdminSupportReasonRequest(null));

        assertThat(support.ticket(id).assignedAdminId()).isEqualTo(secondAdmin);
        assertThat(support.ticket(id).status()).isEqualTo(SupportTicketStatus.IN_PROGRESS);
        assertThat(support.ticket(id).paymentStatus()).isEqualTo(SupportPaymentStatus.PAID);
        assertThat(support.ticket(id).paymentReference()).isEqualTo("verified-toss-payment");
        assertThat(support.ticket(id).paymentRecordedBy()).isNull();
        var session = sessions.findById(token.sessionId()).orElseThrow();
        assertThat(session.getAdminUserId()).isEqualTo(secondAdmin);
        assertThat(Duration.between(session.getStartedAt(), session.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jdbc.queryForObject("select reason from admin_audit_logs where action='SUPPORT_SESSION_START'", String.class))
                .isEqualTo("기술 지원 고객 화면 접속 시작");
        assertCode(() -> start(id), FORBIDDEN);
        assertCode(() -> support.payment(secondAdmin, id, payment(SupportPaymentStatus.UNPAID, null)), FORBIDDEN);
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    void waivedCustomerRequestCannotBypassVerifiedPaymentRequirement() {
        long id = customerRequest(false);
        jdbc.update("update support_tickets set payment_status='WAIVED' where id=?", id);
        assertCode(() -> start(id), CONFLICT);
        assertThat(support.ticket(id).assignedAdminId()).isNull();
        assertThat(sessions.count()).isZero();
    }

    @Test
    void customerAssignmentWaitsUntilConsentOwnershipAndAccountChecksPass() {
        long id = customerRequest(true);
        approve(id, now().minusSeconds(1));
        assertCode(() -> start(id), CONFLICT);
        assertThat(support.ticket(id).assignedAdminId()).isNull();
        approve(id, now().plusDays(7));
        jdbc.update("update support_tickets set status='CANCELLED' where id=?", id);
        assertCode(() -> start(id), CONFLICT);
        assertThat(support.ticket(id).assignedAdminId()).isNull();
        jdbc.update("update support_tickets set status='APPROVED' where id=?", id);
        jdbc.update("update users set status='SUSPENDED' where id=?", customer);
        assertCode(() -> start(id), CONFLICT);
        assertThat(support.ticket(id).assignedAdminId()).isNull();
        jdbc.update("update users set status='REGISTERED' where id=?", customer);
        jdbc.update("update workspaces set user_id=? where id=?", stranger, workspace);
        assertCode(() -> start(id), CONFLICT);
        assertThat(support.ticket(id).assignedAdminId()).isNull();
        assertThat(support.ticket(id).paymentStatus()).isEqualTo(SupportPaymentStatus.PAID);
        assertThat(sessions.count()).isZero();
        assertThat(auditCount("SUPPORT_SESSION_START")).isZero();
    }

    @Test
    void failedCustomerSessionAuditRollsBackAssignmentAndSession() {
        long id = customerRequest(true);
        jdbc.execute("alter table admin_audit_logs add constraint reject_customer_session_audit check (action <> 'SUPPORT_SESSION_START')");
        try {
            assertThatThrownBy(() -> start(id)).isInstanceOf(RuntimeException.class);
            assertThat(support.ticket(id).assignedAdminId()).isNull();
            assertThat(support.ticket(id).status()).isEqualTo(SupportTicketStatus.APPROVED);
            assertThat(support.ticket(id).paymentStatus()).isEqualTo(SupportPaymentStatus.PAID);
            assertThat(sessions.count()).isZero();
        } finally {
            jdbc.execute("alter table admin_audit_logs drop constraint reject_customer_session_audit");
        }
    }

    @Test
    void competingAdministratorsCannotClaimTheSameCustomerRequest() throws Exception {
        long id = customerRequest(true);
        var pool = Executors.newFixedThreadPool(2);
        var begin = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> startAfter(begin, admin, id));
            var second = pool.submit(() -> startAfter(begin, secondAdmin, id));
            begin.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(OK, FORBIDDEN);
            assertThat(sessions.count()).isEqualTo(1);
            assertThat(sessions.findAll().get(0).getAdminUserId()).isEqualTo(support.ticket(id).assignedAdminId());
            assertThat(auditCount("SUPPORT_SESSION_START")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unassignedLegacyAdminRequestCannotBeClaimedAsACustomerRequest() {
        long id = ready();
        jdbc.update("update support_tickets set assigned_admin_id=null where id=?", id);
        assertThat(support.ticket(id).requestSource()).isEqualTo(SupportRequestSource.ADMIN);
        assertCode(() -> start(id), FORBIDDEN);
        assertThat(support.ticket(id).assignedAdminId()).isNull();
        assertThat(sessions.count()).isZero();
    }

    @Test
    void sessionRequiresBothFreshCustomerApprovalAndReceiptCheck() {
        var ticket = create(0);
        assertCode(() -> start(ticket.id()), CONFLICT);
        approve(ticket.id(), now().plusDays(7));
        assertCode(() -> start(ticket.id()), CONFLICT);
        support.payment(admin, ticket.id(), payment(SupportPaymentStatus.WAIVED, null));
        approve(ticket.id(), now().minusSeconds(1));
        assertCode(() -> start(ticket.id()), CONFLICT);
        approve(ticket.id(), now().plusDays(7));
        assertThat(start(ticket.id()).accessToken()).hasSize(43);
    }

    @Test
    void issuedTokenIsHashedSnapshotsVersionsExpiresAndNeverAppearsInListsOrAudits() {
        jdbc.update("update users set auth_version=4 where id=?", admin);
        jdbc.update("update users set auth_version=7 where id=?", customer);
        long id = ready();
        var token = start(id);
        assertThat(token.toString()).doesNotContain(token.accessToken()).contains("redacted");
        var stored = sessions.findById(token.sessionId()).orElseThrow();
        assertThat(stored.getTokenHash()).isEqualTo(SupportTokenHash.hash(token.accessToken())).hasSize(64);
        assertThat(stored.getTokenHash()).isNotEqualTo(token.accessToken());
        assertThat(stored.getAdminAuthVersion()).isEqualTo(4);
        assertThat(stored.getCustomerAuthVersion()).isEqualTo(7);
        assertThat(Duration.between(stored.getStartedAt(), stored.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(support.ticket(id).status()).isEqualTo(SupportTicketStatus.IN_PROGRESS);
        String listing = JsonMapper.builder().build().writeValueAsString(support.sessions(id, 0, 20));
        assertThat(listing).doesNotContain(token.accessToken(), stored.getTokenHash(), "authVersion", "tokenHash", "accessToken");
        String audits = jdbc.queryForList("select before_value,after_value from admin_audit_logs").toString();
        assertThat(audits).doesNotContain(token.accessToken(), stored.getTokenHash());
    }

    @Test
    void newSessionEndsPreviousAndMayNotOutliveCustomerConsent() {
        long id = ready();
        var first = start(id);
        approve(id, now().plusMinutes(2));
        var second = start(id);
        assertThat(first.accessToken()).isNotEqualTo(second.accessToken());
        assertThat(sessions.findById(first.sessionId()).orElseThrow().getEndedAt()).isNotNull();
        assertThat(sessions.findById(second.sessionId()).orElseThrow().getEndedAt()).isNull();
        assertThat(second.expiresAt()).isEqualTo(support.ticket(id).approvalExpiresAt());
    }

    @Test
    void sessionRechecksAssignedAdministratorCurrentOwnerAndAccountStatus() {
        long id = ready();
        assertCode(() -> support.start(secondAdmin, id, reason()), FORBIDDEN);
        jdbc.update("update workspaces set user_id=? where id=?", stranger, workspace);
        assertCode(() -> start(id), CONFLICT);
        jdbc.update("update workspaces set user_id=? where id=?", customer, workspace);
        jdbc.update("update users set status='SUSPENDED' where id=?", customer);
        assertCode(() -> start(id), CONFLICT);
        jdbc.update("update users set status='REGISTERED' where id=?", customer);
        jdbc.update("update users set role='CUSTOMER' where id=?", admin);
        assertCode(() -> start(id), FORBIDDEN);
        assertThat(sessions.count()).isZero();
    }

    @Test
    void completionAndCancellationTerminateAllSessionsAndPreventRestart() {
        long completed = ready();
        var first = start(completed);
        assertThat(support.complete(admin, completed, reason()).status()).isEqualTo(SupportTicketStatus.COMPLETED);
        assertThat(sessions.findById(first.sessionId()).orElseThrow().getEndedAt()).isNotNull();
        assertCode(() -> start(completed), CONFLICT);
        support.complete(admin, completed, reason());
        assertThat(auditCount("SUPPORT_TICKET_COMPLETE")).isEqualTo(1);
        assertCode(() -> support.cancel(admin, completed, reason()), CONFLICT);
        long cancelled = ready();
        var second = start(cancelled);
        support.cancel(admin, cancelled, reason());
        assertThat(sessions.findById(second.sessionId()).orElseThrow().getEndedAt()).isNotNull();
        assertCode(() -> start(cancelled), CONFLICT);
    }

    @Test
    void endingOneSessionIsIdempotentAndReceiptCannotChangeAfterWorkStarts() {
        long id = ready();
        var token = start(id);
        assertCode(() -> support.payment(admin, id, payment(SupportPaymentStatus.UNPAID, null)), CONFLICT);
        assertThat(support.end(secondAdmin, token.sessionId(), reason()).active()).isFalse();
        support.end(secondAdmin, token.sessionId(), reason());
        assertThat(auditCount("SUPPORT_SESSION_END")).isEqualTo(1);
        assertThat(support.ticket(id).status()).isEqualTo(SupportTicketStatus.IN_PROGRESS);
    }

    @Test
    void actionHistorySupportsPendingAndCompletedRequestsWithoutCustomerPayloads() {
        long id = ready();
        var token = start(id);
        tx(() -> {
            var pending = new SupportActionEntity(token.sessionId(), id, admin, customer, workspace, "POST",
                    "/api/workspaces/" + workspace + "/meta/ad-accounts/10/ads", now());
            em.persist(pending);
            var completed = new SupportActionEntity(token.sessionId(), id, admin, customer, workspace, "GET",
                    "/api/workspaces/" + workspace + "/connections", now());
            completed.setStatusCode(200); completed.setCompletedAt(now()); em.persist(completed);
        });
        var page = support.actions(id, 0, 20);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.items().get(0).statusCode()).isEqualTo(200);
        assertThat(page.items().get(1).completedAt()).isNull();
        assertThat(JsonMapper.builder().build().writeValueAsString(page))
                .doesNotContain("accessToken", "tokenHash", "password", "requestBody");
    }

    @Test
    void auditFailureRollsBackNewSessionAndPreservesPreviousSessionAndTicket() {
        long id = ready();
        var first = start(id);
        jdbc.update("delete from admin_audit_logs where action='SUPPORT_SESSION_START'");
        jdbc.execute("alter table admin_audit_logs add constraint reject_support_audit check (action <> 'SUPPORT_SESSION_START')");
        try {
            assertThatThrownBy(() -> start(id)).isInstanceOf(RuntimeException.class);
            assertThat(sessions.count()).isEqualTo(1);
            assertThat(sessions.findById(first.sessionId()).orElseThrow().getEndedAt()).isNull();
        } finally {
            jdbc.execute("alter table admin_audit_logs drop constraint reject_support_audit");
        }
    }

    @Test
    void overlongNoteFailsEvenOnIdempotentOperationsAndPendingRequestCannotComplete() {
        long id = create(0).id();
        assertCode(() -> support.complete(admin, id, reason()), CONFLICT);
        support.cancel(admin, id, reason());
        assertCode(() -> support.cancel(admin, id, new AdminSupportReasonRequest("x".repeat(501))), BAD_REQUEST);
        assertCode(() -> support.create(admin, request(customer, -1)), BAD_REQUEST);
        assertCode(() -> support.create(admin, request(customer, 1_000_000_001L)), BAD_REQUEST);
    }

    @Test
    void omittedAndBlankNotesProduceSpecificAuditEntriesForEverySupportAction() {
        var base = request(customer, 0);
        var created = support.create(admin, new AdminSupportTicketRequest(base.workspaceId(), base.customerUserId(),
                base.title(), base.description(), base.accessMode(), base.amountKrw(), null));
        support.payment(admin, created.id(), new AdminSupportPaymentRequest(SupportPaymentStatus.WAIVED, null, " \t "));
        approve(created.id(), now().plusDays(7));
        var session = support.start(admin, created.id(), new AdminSupportReasonRequest(null));
        support.end(admin, session.sessionId(), new AdminSupportReasonRequest("  "));
        support.complete(admin, created.id(), new AdminSupportReasonRequest(null));
        long cancelled = create(0).id();
        support.cancel(admin, cancelled, new AdminSupportReasonRequest("\n "));

        Map<String, String> expected = Map.of(
                "SUPPORT_TICKET_CREATE", "기술 지원 요청 등록",
                "SUPPORT_PAYMENT_RECORD", "기술 지원 수납 상태 기록",
                "SUPPORT_SESSION_START", "기술 지원 고객 화면 접속 시작",
                "SUPPORT_SESSION_END", "기술 지원 고객 화면 접속 종료",
                "SUPPORT_TICKET_COMPLETE", "기술 지원 요청 완료",
                "SUPPORT_TICKET_CANCEL", "기술 지원 요청 취소");
        expected.forEach((action, note) -> {
            long target = action.equals("SUPPORT_TICKET_CANCEL") ? cancelled : created.id();
            var audit = jdbc.queryForMap("select actor_user_id,target_type,target_id,reason from admin_audit_logs where action=? and target_id=?",
                    action, target);
            assertThat(((Number) audit.get("actor_user_id")).longValue()).isEqualTo(admin);
            assertThat(audit.get("target_type")).isEqualTo("SUPPORT_TICKET");
            assertThat(((Number) audit.get("target_id")).longValue()).isEqualTo(target);
            assertThat(audit.get("reason")).isEqualTo(note);
        });
        assertThat(jdbc.queryForObject("select before_value from admin_audit_logs where action='SUPPORT_PAYMENT_RECORD'", String.class))
                .isEqualTo("UNPAID");
        assertThat(jdbc.queryForObject("select after_value from admin_audit_logs where action='SUPPORT_PAYMENT_RECORD'", String.class))
                .isEqualTo("WAIVED");
        assertThat(jdbc.queryForObject("select before_value from admin_audit_logs where action='SUPPORT_TICKET_COMPLETE'", String.class))
                .isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("select after_value from admin_audit_logs where action='SUPPORT_TICKET_COMPLETE'", String.class))
                .isEqualTo("COMPLETED");
        assertThat(sessions.findById(session.sessionId()).orElseThrow().getEndedAt()).isNotNull();
    }

    @Test
    void explicitNotesAreTrimmedPreservedAndMayContainUpTo500Characters() {
        var base = request(customer, 0);
        var ticket = support.create(admin, new AdminSupportTicketRequest(base.workspaceId(), base.customerUserId(),
                base.title(), base.description(), base.accessMode(), base.amountKrw(), "  고객 요청을 확인함 \n"));
        assertThat(jdbc.queryForObject("select reason from admin_audit_logs where action='SUPPORT_TICKET_CREATE'", String.class))
                .isEqualTo("고객 요청을 확인함");
        String longest = "메".repeat(500);
        support.payment(admin, ticket.id(), new AdminSupportPaymentRequest(SupportPaymentStatus.WAIVED, null, longest));
        assertThat(jdbc.queryForObject("select reason from admin_audit_logs where action='SUPPORT_PAYMENT_RECORD'", String.class))
                .isEqualTo(longest);
    }

    @Test
    void sessionStartWaitingForCustomerRevocationCannotUseStaleApprovedTicket() throws Exception {
        long id = ready();
        var executor = Executors.newSingleThreadExecutor();
        var started = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Future<HttpStatus>> pending = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            tx(() -> {
                em.find(UserEntity.class, customer, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                pending.set(executor.submit(() -> {
                    started.countDown();
                    try { start(id); return OK; }
                    catch (AdminException exception) { return exception.status(); }
                }));
                try {
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> pending.get().get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt(); throw new RuntimeException(exception);
                }
                var ticket = tickets.findByIdForUpdate(id).orElseThrow();
                ticket.setStatus(SupportTicketStatus.CANCELLED);
                ticket.setUpdatedAt(now());
            });
            assertThat(pending.get().get(5, TimeUnit.SECONDS)).isEqualTo(CONFLICT);
            assertThat(sessions.count()).isZero();
        } finally { executor.shutdownNow(); }
    }

    private AdminSupportTicketResponse create(long amount) { return support.create(admin, request(customer, amount)); }
    private long customerRequest(boolean paid) {
        var id = new java.util.concurrent.atomic.AtomicLong();
        tx(() -> {
            var ticket = SupportTicketEntity.customerRequest(workspace, customer, "고객 기술 지원 신청",
                    "고객이 동의한 상품 등록 지원", SupportAccessMode.OPERATE, 9900,
                    "support-terms-v1", "지원료 9,900원 및 지원 범위에 동의합니다.", now());
            if (paid) {
                ticket.setPaymentStatus(SupportPaymentStatus.PAID);
                ticket.setPaymentReference("verified-toss-payment");
                ticket.setPaymentRecordedAt(now());
            }
            em.persist(ticket);
            id.set(ticket.getId());
        });
        return id.get();
    }
    private HttpStatus startAfter(CountDownLatch begin, long actor, long id) throws InterruptedException {
        begin.await();
        try {
            support.start(actor, id, new AdminSupportReasonRequest(null));
            return OK;
        } catch (AdminException exception) {
            return exception.status();
        }
    }
    private AdminSupportTicketRequest request(long customerId, long amount) {
        return new AdminSupportTicketRequest(workspace, customerId, "상품 등록 지원", "고객과 합의한 상품 등록 작업",
                SupportAccessMode.OPERATE, amount, "고객 기술지원 요청");
    }
    private AdminSupportPaymentRequest payment(SupportPaymentStatus status, String reference) {
        return new AdminSupportPaymentRequest(status, reference, "수납 내역 확인");
    }
    private AdminSupportReasonRequest reason() { return new AdminSupportReasonRequest("지원 요청 처리"); }
    private AdminSupportSessionTokenResponse start(long id) { return support.start(admin, id, reason()); }
    private long ready() {
        long id = create(0).id(); approve(id, now().plusDays(7));
        support.payment(admin, id, payment(SupportPaymentStatus.WAIVED, null)); return id;
    }
    private void approve(long id, LocalDateTime expiry) {
        tx(() -> {
            var ticket = tickets.findByIdForUpdate(id).orElseThrow();
            ticket.setStatus(SupportTicketStatus.APPROVED); ticket.setApprovedAt(now()); ticket.setApprovalExpiresAt(expiry);
        });
    }
    private long user(String email, UserRole role) {
        var user = UserEntity.builder().email(email).password("test-password").status(UserStatus.REGISTERED).role(role).build();
        em.persist(user); return user.getId();
    }
    private long auditCount(String action) {
        return jdbc.queryForObject("select count(*) from admin_audit_logs where action=?", Long.class, action);
    }
    private void tx(Runnable task) { new TransactionTemplate(transactions).executeWithoutResult(status -> task.run()); }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Seoul")); }
    private void assertCode(ThrowingCallable task, HttpStatus status) {
        assertThatThrownBy(task).isInstanceOfSatisfying(AdminException.class, error -> assertThat(error.status()).isEqualTo(status));
    }

    @Configuration
    @EntityScan(basePackageClasses = {SupportTicketEntity.class, UserEntity.class, UserProfileEntity.class,
            WorkspaceEntity.class, WorkspaceMemberEntity.class, AdminAuditEntity.class})
    @EnableJpaRepositories(basePackageClasses = {SupportTicketRepository.class, WorkspaceRepository.class, UserRepository.class})
    @Import({AdminSupportBusiness.class, AdminSupportService.class, AdminSupportConverter.class,
            AdminUserMutationGuard.class, AdminUserService.class, AdminUserRepository.class,
            AdminAuditService.class, AdminAuditRepository.class})
    static class Config {
        @Bean AesGcmStringEncryptor encryptor() {
            return new AesGcmStringEncryptor(new CryptoProperties(Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))));
        }
    }
}
