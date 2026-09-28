package com.orinan.api.domain.support;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.support.converter.SupportConverter;
import com.orinan.api.domain.support.exception.SupportErrorCode;
import com.orinan.api.domain.support.model.SupportContext;
import com.orinan.api.domain.support.service.*;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@ActiveProfiles("test")
@ContextConfiguration(classes = SupportPersistenceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SupportPersistenceTest {
    private static final String TOKEN = "a".repeat(43);
    @Autowired private SupportConsentService consent;
    @Autowired private SupportSessionService sessions;
    @Autowired private SupportActionService actions;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private long adminId, ownerId, strangerId, workspaceId, ticketId, sessionId;

    @BeforeEach void seed() {
        tx(() -> {
            for (String entity : List.of("SupportActionEntity", "SupportSessionEntity", "SupportTicketEntity", "AdminAuditEntity",
                    "WorkspaceMemberEntity", "WorkspaceEntity", "UserProfileEntity", "UserEntity")) {
                em.createQuery("delete from " + entity).executeUpdate();
            }
            var admin = user("admin@test.example", UserRole.ADMIN);
            var owner = user("owner@test.example", UserRole.CUSTOMER);
            var stranger = user("stranger@test.example", UserRole.CUSTOMER);
            adminId = admin.getId(); ownerId = owner.getId(); strangerId = stranger.getId();
            var workspace = WorkspaceEntity.builder().name("고객 스토어").user(owner).build();
            em.persist(workspace); workspaceId = workspace.getId();
            var ticket = new SupportTicketEntity(workspaceId, ownerId, adminId, "상품 등록 지원", "승인한 스토어의 상품만 등록",
                    SupportAccessMode.OPERATE, 50000, now());
            em.persist(ticket); ticketId = ticket.getId();
        });
    }

    @Test void onlyOwnerCanSeeAndApproveTheirExactQuote() {
        var list = consent.tickets(workspaceId, ownerId, 0, 20);
        assertThat(list.totalElements()).isEqualTo(1);
        assertThat(list.items().get(0).amountKrw()).isEqualTo(50000);
        assertThatThrownBy(() -> consent.tickets(workspaceId, strangerId, 0, 20)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> consent.approve(workspaceId, ticketId, strangerId, null)).isInstanceOf(ApiException.class);
        var ticket = consent.approve(workspaceId, ticketId, ownerId, "  안내한 범위와 비용에 동의합니다  ");
        assertThat(ticket.status()).isEqualTo("APPROVED");
        assertThat(ticket.approvalExpiresAt()).isEqualTo(ticket.approvedAt().plusDays(7));
        assertThat(jdbc.queryForObject("select actor_user_id from admin_audit_logs where action='SUPPORT_CONSENT_APPROVE'", Long.class))
                .isEqualTo(ownerId);
        assertThat(jdbc.queryForObject("select reason from admin_audit_logs where action='SUPPORT_CONSENT_APPROVE'", String.class))
                .isEqualTo("안내한 범위와 비용에 동의합니다");
        assertThatThrownBy(() -> consent.approve(workspaceId, ticketId, ownerId, "다시 승인"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCodeIfs()).isEqualTo(SupportErrorCode.CONFLICT));
    }

    @Test void ticketCannotBeApprovedAfterWorkspaceOwnerChanges() {
        tx(() -> em.find(WorkspaceEntity.class, workspaceId).setUser(em.getReference(UserEntity.class, strangerId)));
        assertThatThrownBy(() -> consent.approve(workspaceId, ticketId, ownerId, "동의")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> consent.approve(workspaceId, ticketId, strangerId, "동의")).isInstanceOf(ApiException.class);
    }

    @Test void rejectsUnsupportedPagingAndOversizedNotesWithoutChangingTheTicket() {
        assertThatThrownBy(() -> consent.tickets(workspaceId, ownerId, -1, 20)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> consent.tickets(workspaceId, ownerId, 0, 101)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> consent.approve(workspaceId, ticketId, ownerId, "x".repeat(501))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> consent.revoke(workspaceId, ticketId, ownerId, " ".repeat(501))).isInstanceOf(ApiException.class);
        assertThat(consent.tickets(workspaceId, ownerId, 0, 20).items().get(0).status()).isEqualTo("REQUESTED");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\u2003"})
    void consentActionsRecordTheirPurposeWhenNoNoteIsProvided(String note) {
        assertThat(consent.approve(workspaceId, ticketId, ownerId, note).status()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForMap("select actor_user_id, target_id, reason, before_value, after_value from admin_audit_logs where action='SUPPORT_CONSENT_APPROVE'"))
                .containsEntry("ACTOR_USER_ID", ownerId).containsEntry("TARGET_ID", ticketId)
                .containsEntry("REASON", "고객 기술 지원 승인")
                .containsEntry("BEFORE_VALUE", "REQUESTED").containsEntry("AFTER_VALUE", "APPROVED");
        assertThat(consent.revoke(workspaceId, ticketId, ownerId, note).status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select reason from admin_audit_logs where action='SUPPORT_CONSENT_REVOKE'", String.class))
                .isEqualTo("고객 기술 지원 승인 철회");
    }

    @Test void rejectingAnUnapprovedRequestRecordsTheRejectionAction() {
        assertThat(consent.revoke(workspaceId, ticketId, ownerId, null).status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select reason from admin_audit_logs where action='SUPPORT_CONSENT_REVOKE'", String.class))
                .isEqualTo("고객 기술 지원 요청 거절");
    }

    @Test void customerRevocationImmediatelyEndsAllTicketSessions() {
        activate();
        assertThat(sessions.authenticate(TOKEN).workspaceId()).isEqualTo(workspaceId);
        var result = consent.revoke(workspaceId, ticketId, ownerId, null);
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select ended_at is not null from support_sessions where id=?", Boolean.class, sessionId)).isTrue();
        assertThatThrownBy(() -> sessions.authenticate(TOKEN)).isInstanceOf(ApiException.class);
        assertThat(consent.revoke(workspaceId, ticketId, ownerId, "중복 요청").status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_logs where action='SUPPORT_CONSENT_REVOKE'", Long.class)).isEqualTo(1);
    }

    @Test void auditFailureRollsBackCustomerApproval() {
        rejectAudit();
        try {
            assertThatThrownBy(() -> consent.approve(workspaceId, ticketId, ownerId, "기록 실패")).isInstanceOf(RuntimeException.class);
            var ticket = consent.tickets(workspaceId, ownerId, 0, 20).items().get(0);
            assertThat(ticket.status()).isEqualTo("REQUESTED"); assertThat(ticket.approvedAt()).isNull();
        } finally { allowAudit(); }
    }

    @Test void auditFailureRollsBackCustomerCancellationAndSessionEndTogether() {
        activate(); rejectAudit();
        try {
            assertThatThrownBy(() -> consent.revoke(workspaceId, ticketId, ownerId, "기록 실패")).isInstanceOf(RuntimeException.class);
            assertThat(consent.tickets(workspaceId, ownerId, 0, 20).items().get(0).status()).isEqualTo("IN_PROGRESS");
            assertThat(sessions.authenticate(TOKEN)).isNotNull();
        } finally { allowAudit(); }
    }

    @Test void requestAuditSurvivesTheBusinessTransactionRollback() {
        activate();
        var context = sessions.authenticate(TOKEN);
        assertThatThrownBy(() -> tx(() -> {
            actions.begin(context, "PATCH", "/api/workspaces/{workspaceId}/meta/ad-accounts/{assetId}/ads/{adId}");
            throw new IllegalStateException("business write failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from support_actions", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status_code from support_actions", Integer.class)).isNull();
        assertThat(jdbc.queryForObject("select completed_at from support_actions", LocalDateTime.class)).isNull();
        long id = jdbc.queryForObject("select id from support_actions", Long.class);
        actions.complete(id, 502);
        assertThat(jdbc.queryForObject("select status_code from support_actions", Integer.class)).isEqualTo(502);
    }

    @Test void userSessionRevocationAndOwnerTransferInvalidateRealPersistedGrant() {
        activate();
        tx(() -> em.find(UserEntity.class, ownerId).setAuthVersion(1));
        assertThatThrownBy(() -> sessions.authenticate(TOKEN)).isInstanceOf(ApiException.class);
        tx(() -> em.find(UserEntity.class, ownerId).setAuthVersion(0));
        assertThat(sessions.authenticate(TOKEN)).isNotNull();
        tx(() -> em.find(WorkspaceEntity.class, workspaceId).setUser(em.getReference(UserEntity.class, strangerId)));
        assertThatThrownBy(() -> sessions.authenticate(TOKEN)).isInstanceOf(ApiException.class);
    }

    private void activate() {
        consent.approve(workspaceId, ticketId, ownerId, "금액과 작업 범위에 동의합니다");
        tx(() -> {
            var ticket = em.find(SupportTicketEntity.class, ticketId);
            ticket.setPaymentStatus(SupportPaymentStatus.PAID); ticket.setStatus(SupportTicketStatus.IN_PROGRESS);
            var session = new SupportSessionEntity(ticketId, adminId, ownerId, workspaceId, SupportTokenHash.hash(TOKEN),
                    0, 0, SupportAccessMode.OPERATE, now(), now().plusMinutes(30));
            em.persist(session); sessionId = session.getId();
        });
    }
    private void rejectAudit() { jdbc.execute("alter table admin_audit_logs add constraint reject_support_audit check (reason <> '기록 실패')"); }
    private void allowAudit() { jdbc.execute("alter table admin_audit_logs drop constraint reject_support_audit"); }
    private UserEntity user(String email, UserRole role) {
        var user = UserEntity.builder().email(email).password("test-private-password").status(UserStatus.REGISTERED).role(role).build();
        em.persist(user); return user;
    }
    private void tx(Runnable runnable) { new TransactionTemplate(transactions).executeWithoutResult(status -> runnable.run()); }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Seoul")); }

    @Configuration
    @EntityScan(basePackageClasses = {UserEntity.class, UserProfileEntity.class, WorkspaceEntity.class,
            WorkspaceMemberEntity.class, SupportTicketEntity.class, AdminAuditEntity.class})
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, WorkspaceRepository.class, SupportTicketRepository.class})
    @Import({SupportConsentService.class, SupportSessionService.class, SupportActionService.class,
            SupportConverter.class, AdminAuditRepository.class})
    static class Config {
        @Bean AesGcmStringEncryptor encryptor() {
            return new AesGcmStringEncryptor(new CryptoProperties(Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))));
        }
    }
}
