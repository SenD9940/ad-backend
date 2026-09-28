package com.orinan.api.domain.support;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.support.controller.model.SupportCreateRequest;
import com.orinan.api.domain.support.converter.SupportConverter;
import com.orinan.api.domain.support.exception.SupportErrorCode;
import com.orinan.api.domain.support.payment.TossPaymentProperties;
import com.orinan.api.domain.support.service.SupportRequestService;
import com.orinan.api.domain.support.service.SupportTerms;
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
import org.junit.jupiter.api.*;
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
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@ActiveProfiles("test")
@ContextConfiguration(classes = SupportRequestPersistenceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SupportRequestPersistenceTest {
    @Autowired SupportRequestService requests;
    @Autowired TossPaymentProperties payments;
    @Autowired SupportTicketRepository tickets;
    @Autowired SupportConverter converter;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    long ownerId, strangerId, adminId, workspaceId;

    @BeforeEach void seed() {
        payments.setClientKey("test-request-client-key");
        payments.setSecretKey("test-request-secret-key");
        tx(() -> {
            for (String entity : List.of("SupportActionEntity", "SupportSessionEntity", "SupportTicketEntity", "AdminAuditEntity",
                    "WorkspaceMemberEntity", "WorkspaceEntity", "UserProfileEntity", "UserEntity"))
                em.createQuery("delete from " + entity).executeUpdate();
            ownerId = user("owner@example.test", UserRole.CUSTOMER);
            strangerId = user("other@example.test", UserRole.CUSTOMER);
            adminId = user("admin@example.test", UserRole.ADMIN);
            var workspace = WorkspaceEntity.builder().name("고객 스토어").user(em.getReference(UserEntity.class, ownerId)).build();
            em.persist(workspace); workspaceId = workspace.getId();
        });
    }

    @Test void ownerOfferUsesFixedFeeAndPublishedTermsWithoutDisclosingPaymentKeys() {
        var offer = requests.offer(workspaceId, ownerId);
        assertThat(offer.enabled()).isTrue();
        assertThat(offer.amountKrw()).isEqualTo(9900);
        assertThat(offer.termsVersion()).isEqualTo(SupportTerms.VERSION);
        assertThat(offer.termsText()).isEqualTo(SupportTerms.TEXT).contains("9,900원", "7일", "15분");
        assertThat(JsonMapper.builder().build().writeValueAsString(offer))
                .doesNotContain(payments.getClientKey(), payments.getSecretKey());
        assertCode(() -> requests.offer(workspaceId, strangerId), SupportErrorCode.ACCESS_DENIED);
        assertCode(() -> requests.offer(0, ownerId), SupportErrorCode.INVALID_REQUEST);
    }

    @Test void customerApplicationIsImmediatelyConsentedButUnpaidAndUnassigned() {
        var response = requests.create(workspaceId, ownerId, request(SupportAccessMode.OPERATE, true, SupportTerms.VERSION));
        assertThat(response.customerUserId()).isEqualTo(ownerId);
        assertThat(response.workspaceId()).isEqualTo(workspaceId);
        assertThat(response.assignedAdminId()).isNull();
        assertThat(response.requestSource()).isEqualTo("CUSTOMER");
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.paymentStatus()).isEqualTo("UNPAID");
        assertThat(response.amountKrw()).isEqualTo(9900);
        assertThat(response.approvalExpiresAt()).isEqualTo(response.approvedAt().plusDays(7));
        assertThat(response.title()).isEqualTo("상품 등록 지원");
        assertThat(response.description()).isEqualTo("선택한 스토어의 상품 등록 지원");
        var stored = tickets.findById(response.id()).orElseThrow();
        assertThat(stored.getTermsVersion()).isEqualTo(SupportTerms.VERSION);
        assertThat(stored.getTermsSnapshot()).startsWith(SupportTerms.TEXT)
                .contains("선택한 접근 범위: 조회 및 조작", "동의한 지원료: 9900원");
        assertThat(stored.getPaymentRecordedAt()).isNull();
        assertThat(stored.getPaymentRecordedBy()).isNull();
    }

    @Test void bothAccessModesHaveTheSameServerPriceAndTheirOwnScopeSnapshot() {
        var read = requests.create(workspaceId, ownerId, request(SupportAccessMode.READ_ONLY, true, SupportTerms.VERSION));
        var operate = requests.create(workspaceId, ownerId, request(SupportAccessMode.OPERATE, true, SupportTerms.VERSION));
        assertThat(read.amountKrw()).isEqualTo(9900).isEqualTo(operate.amountKrw());
        assertThat(read.termsSnapshot()).contains("선택한 접근 범위: 조회만");
        assertThat(operate.termsSnapshot()).contains("선택한 접근 범위: 조회 및 조작");
        assertThat(read.termsSnapshot()).isNotEqualTo(operate.termsSnapshot());
    }

    @Test void applicationAuditRetainsAuthenticatedActorTargetScopePriceAndTermsVersion() {
        var ticket = requests.create(workspaceId, ownerId, request(SupportAccessMode.READ_ONLY, true, SupportTerms.VERSION));
        var audit = jdbc.queryForMap("select actor_user_id,action,target_type,target_id,reason,before_value,after_value from admin_audit_logs");
        assertThat(audit.get("actor_user_id")).isEqualTo(ownerId);
        assertThat(audit.get("action")).isEqualTo("SUPPORT_CUSTOMER_REQUEST");
        assertThat(audit.get("target_type")).isEqualTo("SUPPORT_TICKET");
        assertThat(audit.get("target_id")).isEqualTo(ticket.id());
        assertThat(audit.get("reason")).isEqualTo("고객 기술 지원 신청 및 약관 동의");
        assertThat(audit.get("before_value")).isNull();
        assertThat((String) audit.get("after_value")).contains("source:CUSTOMER", "mode:READ_ONLY", "amount_krw:9900", SupportTerms.VERSION);
    }

    @Test void disabledPaymentConfigurationAllowsOfferButPreventsApplications() {
        for (boolean missingClient : List.of(true, false)) {
            payments.setClientKey(missingClient ? " " : "test-client");
            payments.setSecretKey(missingClient ? "test-secret" : "");
            assertThat(requests.offer(workspaceId, ownerId).enabled()).isFalse();
            assertCode(() -> requests.create(workspaceId, ownerId, request(SupportAccessMode.READ_ONLY, true, SupportTerms.VERSION)),
                    SupportErrorCode.PAYMENT_UNAVAILABLE);
        }
        assertThat(tickets.count()).isZero();
        assertThat(auditCount()).isZero();
    }

    @Test void consentMustBeExplicitAndCurrentBeforeAnyTicketIsWritten() {
        assertCode(() -> requests.create(workspaceId, ownerId, request(SupportAccessMode.OPERATE, false, SupportTerms.VERSION)), SupportErrorCode.INVALID_REQUEST);
        assertCode(() -> requests.create(workspaceId, ownerId, request(SupportAccessMode.OPERATE, null, SupportTerms.VERSION)), SupportErrorCode.INVALID_REQUEST);
        assertCode(() -> requests.create(workspaceId, ownerId, request(SupportAccessMode.OPERATE, true, "old-version")), SupportErrorCode.TERMS_CHANGED);
        assertCode(() -> requests.create(workspaceId, ownerId, request(null, true, SupportTerms.VERSION)), SupportErrorCode.INVALID_REQUEST);
        assertThat(tickets.count()).isZero();
        assertThat(auditCount()).isZero();
    }

    @Test void onlyTheCurrentActiveOwnerMayApplyForTheWorkspace() {
        assertCode(() -> requests.create(workspaceId, strangerId, request(SupportAccessMode.OPERATE, true, SupportTerms.VERSION)), SupportErrorCode.ACCESS_DENIED);
        tx(() -> em.find(UserEntity.class, ownerId).setStatus(UserStatus.SUSPENDED));
        assertCode(() -> requests.offer(workspaceId, ownerId), SupportErrorCode.ACCESS_DENIED);
        assertCode(() -> requests.create(workspaceId, ownerId, request(SupportAccessMode.OPERATE, true, SupportTerms.VERSION)), SupportErrorCode.ACCESS_DENIED);
        tx(() -> {
            em.find(UserEntity.class, ownerId).setStatus(UserStatus.REGISTERED);
            em.find(WorkspaceEntity.class, workspaceId).setUser(em.getReference(UserEntity.class, strangerId));
        });
        assertCode(() -> requests.create(workspaceId, ownerId, request(SupportAccessMode.OPERATE, true, SupportTerms.VERSION)), SupportErrorCode.ACCESS_DENIED);
        assertThat(requests.create(workspaceId, strangerId, request(SupportAccessMode.READ_ONLY, true, SupportTerms.VERSION)).customerUserId())
                .isEqualTo(strangerId);
    }

    @Test void auditFailureRollsBackTheApplicationAndItsConsentSnapshot() {
        jdbc.execute("alter table admin_audit_logs add constraint reject_customer_request check (action <> 'SUPPORT_CUSTOMER_REQUEST')");
        try {
            assertThatThrownBy(() -> requests.create(workspaceId, ownerId, request(SupportAccessMode.OPERATE, true, SupportTerms.VERSION)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(tickets.count()).isZero();
            assertThat(auditCount()).isZero();
        } finally { jdbc.execute("alter table admin_audit_logs drop constraint reject_customer_request"); }
    }

    @Test void existingAdministratorCreatedTicketsKeepTheirAssignmentAndPendingApproval() {
        tx(() -> em.persist(new SupportTicketEntity(workspaceId, ownerId, adminId, "기존 관리자 요청", "기존 설명",
                SupportAccessMode.READ_ONLY, 30000, LocalDateTime.now(ZoneId.of("Asia/Seoul")))));
        var legacy = converter.ticket(tickets.findAll().get(0));
        assertThat(legacy.requestSource()).isEqualTo("ADMIN");
        assertThat(legacy.assignedAdminId()).isEqualTo(adminId);
        assertThat(legacy.status()).isEqualTo("REQUESTED");
        assertThat(legacy.amountKrw()).isEqualTo(30000);
        assertThat(legacy.termsVersion()).isNull();
        assertThat(legacy.termsSnapshot()).isNull();
    }

    private SupportCreateRequest request(SupportAccessMode mode, Boolean accepted, String terms) {
        return new SupportCreateRequest("  상품 등록 지원  ", "  선택한 스토어의 상품 등록 지원  ", mode, terms, accepted);
    }
    private long user(String email, UserRole role) {
        var user = UserEntity.builder().email(email).password("test-only-password").status(UserStatus.REGISTERED).role(role).build();
        em.persist(user); return user.getId();
    }
    private long auditCount() { return jdbc.queryForObject("select count(*) from admin_audit_logs", Long.class); }
    private void tx(Runnable task) { new TransactionTemplate(transactions).executeWithoutResult(status -> task.run()); }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable task, SupportErrorCode code) {
        assertThatThrownBy(task).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCodeIfs()).isEqualTo(code));
    }

    @Configuration
    @EntityScan(basePackageClasses = {UserEntity.class, UserProfileEntity.class, WorkspaceEntity.class,
            WorkspaceMemberEntity.class, SupportTicketEntity.class, AdminAuditEntity.class})
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, WorkspaceRepository.class, SupportTicketRepository.class})
    @Import({SupportRequestService.class, SupportConverter.class, AdminAuditRepository.class})
    static class Config {
        @Bean TossPaymentProperties payments() { return new TossPaymentProperties(); }
        @Bean AesGcmStringEncryptor encryptor() {
            return new AesGcmStringEncryptor(new CryptoProperties(Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))));
        }
    }
}
