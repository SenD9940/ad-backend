package com.orinan.adminapi.domain.user.business;

import com.orinan.adminapi.domain.user.controller.model.AdminUserResponse;
import com.orinan.adminapi.domain.user.controller.model.AdminUserWorkspaceResponse;
import com.orinan.adminapi.domain.user.converter.AdminUserConverter;
import com.orinan.adminapi.domain.user.service.AdminUserMutationGuard;
import com.orinan.adminapi.domain.user.service.AdminUserService;
import com.orinan.db.user.AdminUserRepository;
import com.orinan.db.adminaudit.AdminAuditRepository;

import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpStatus.*;

/** Real SQL and transactions, with an isolated in-memory database and no external service configuration. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminUserBusinessTest {
    private AnnotationConfigApplicationContext context;
    private AdminUserBusiness business;
    private AdminUserMutationGuard guard;
    private JdbcTemplate jdbc;

    @BeforeAll
    void start() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        business = context.getBean(AdminUserBusiness.class);
        guard = context.getBean(AdminUserMutationGuard.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.execute("""
                CREATE TABLE users (id BIGINT PRIMARY KEY, email VARCHAR(512), password VARCHAR(512),
                  status VARCHAR(30), role VARCHAR(10), auth_version BIGINT NOT NULL DEFAULT 0,
                  last_login_at TIMESTAMP, registered_at TIMESTAMP, updated_at TIMESTAMP, un_registered_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE user_profiles (id BIGINT PRIMARY KEY, user_id BIGINT UNIQUE, name VARCHAR(50),
                  phone VARCHAR(512), phone_hash VARCHAR(64), address VARCHAR(1024), address_detail VARCHAR(512))
                """);
        jdbc.execute("""
                CREATE TABLE workspaces (id BIGINT PRIMARY KEY, user_id BIGINT, name VARCHAR(100),
                  registered_at TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE workspace_members (workspace_id BIGINT, user_id BIGINT, role VARCHAR(30))");
        jdbc.execute("""
                CREATE TABLE tokens (id BIGINT PRIMARY KEY, user_id BIGINT, refresh_token_hash VARCHAR(64),
                  status VARCHAR(10), revoked_at TIMESTAMP, expires_at TIMESTAMP)
                """);
    }

    @AfterAll
    void stop() { context.close(); }

    @BeforeEach
    void reset() {
        for (String table : List.of("tokens", "workspace_members", "workspaces", "user_profiles", "users", "admin_audit_logs")) {
            jdbc.update("DELETE FROM " + table);
        }
        user(1, "admin@example.test", "First Admin", UserStatus.REGISTERED, UserRole.ADMIN);
        user(2, "admin2@example.test", "Second Admin", UserStatus.REGISTERED, UserRole.ADMIN);
        user(3, "customer@example.test", "고객", UserStatus.REGISTERED, UserRole.CUSTOMER);
        user(4, "percent%_!@example.test", "Literal %_! name", UserStatus.SUSPENDED, UserRole.CUSTOMER);
        user(5, "withdrawn@example.test", "탈퇴회원", UserStatus.UNREGISTERED, UserRole.CUSTOMER);
        for (int id : List.of(10, 11, 12)) {
            jdbc.update("INSERT INTO workspaces (id, user_id, name) VALUES (?, ?, ?)",
                    id, id == 10 ? 3 : id == 11 ? 1 : 2, "Workspace " + id);
            jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, 3, 'MEMBER')", id);
        }
        jdbc.update("INSERT INTO tokens (id, user_id, status) VALUES (1, 3, 'ACTIVE'), (2, 3, 'ACTIVE'), (3, 3, 'EXPIRED'), (4, 1, 'ACTIVE')");
    }

    @Test
    void searchesWithLiteralWildcardsFiltersAndStablePagination() {
        var all = business.search(null, null, null, 0, 2);
        assertThat(all.items()).extracting(AdminUserResponse::id).containsExactly(5L, 4L);
        assertThat(all.totalElements()).isEqualTo(5);
        assertThat(all.totalPages()).isEqualTo(3);
        assertThat(business.search(null, null, null, 1, 2).items())
                .extracting(AdminUserResponse::id).containsExactly(3L, 2L);
        assertThat(business.search(" %_! ", UserStatus.SUSPENDED, UserRole.CUSTOMER, 0, 20).items())
                .extracting(AdminUserResponse::id).containsExactly(4L);
        assertThat(business.search("FIRST ADMIN", UserStatus.REGISTERED, UserRole.ADMIN, 0, 20).items())
                .extracting(AdminUserResponse::id).containsExactly(1L);
        assertThat(business.search("' OR 1=1 --", null, null, 0, 20).items()).isEmpty();
    }

    @Test
    void listsOwnedAndMemberWorkspacesOnceAndPreservesSafeProjection() {
        var user = business.detail(3);
        assertThat(user.ownedWorkspaceCount()).isEqualTo(1);
        assertThat(user.workspaceCount()).isEqualTo(3);
        var workspaces = business.workspaces(3, 0, 20);
        assertThat(workspaces.totalElements()).isEqualTo(3);
        assertThat(workspaces.items()).extracting(AdminUserWorkspaceResponse::id).containsExactly(12L, 11L, 10L);
        assertThat(workspaces.items()).extracting(AdminUserWorkspaceResponse::role).containsExactly("MEMBER", "MEMBER", "OWNER");
        assertThat(workspaces.items().get(2).ownerId()).isEqualTo(3);
        assertThat(user.toString()).doesNotContain("password-marker", "not-valid-ciphertext", "phone-hash-marker");
        assertThat(business.detail(5).status()).isEqualTo(UserStatus.UNREGISTERED);
    }

    @Test
    void boundsQueriesAndReportsMissingUsers() {
        assertCode(() -> business.search(null, null, null, -1, 20), BAD_REQUEST);
        assertCode(() -> business.search(null, null, null, 0, 101), BAD_REQUEST);
        assertCode(() -> business.search("x".repeat(201), null, null, 0, 20), BAD_REQUEST);
        assertCode(() -> business.detail(0), BAD_REQUEST);
        assertCode(() -> business.detail(99), NOT_FOUND);
        assertCode(() -> business.workspaces(99, 0, 20), NOT_FOUND);
    }

    @Test
    void suspensionExpiresAllSessionsAndAuditsOnlyChangedFieldWhilePreservingWorkspaces() {
        var changed = business.changeStatus(1, 3, UserStatus.SUSPENDED, "  운영 정책 위반  ");
        assertThat(changed.changed()).isTrue();
        assertThat(changed.revokedSessions()).isEqualTo(2);
        assertThat(business.detail(3).status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(version(3)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tokens WHERE user_id = 3 AND status = 'ACTIVE'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tokens WHERE user_id = 3 AND revoked_at IS NOT NULL", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM tokens WHERE id = 4", String.class)).isEqualTo("ACTIVE");
        assertThat(business.workspaces(3, 0, 20).totalElements()).isEqualTo(3);
        assertThat(jdbc.queryForMap("SELECT action, reason, before_value, after_value FROM admin_audit_logs"))
                .containsEntry("ACTION", "USER_STATUS_CHANGED").containsEntry("REASON", "운영 정책 위반")
                .containsEntry("BEFORE_VALUE", "REGISTERED").containsEntry("AFTER_VALUE", "SUSPENDED");
    }

    @Test
    void roleChangesInvalidateSessionsAndNoOpChangesDoNotInvalidateOrAudit() {
        var noOp = business.changeRole(1, 3, UserRole.CUSTOMER, "확인");
        assertThat(noOp.changed()).isFalse();
        assertThat(version(3)).isZero();
        assertThat(auditCount()).isZero();

        var changed = business.changeRole(1, 3, UserRole.ADMIN, "관리 담당자 지정");
        assertThat(changed.role()).isEqualTo(UserRole.ADMIN);
        assertThat(changed.revokedSessions()).isEqualTo(2);
        assertThat(version(3)).isEqualTo(1);
        assertThat(auditCount()).isEqualTo(1);

        assertThat(business.changeStatus(1, 3, UserStatus.REGISTERED, "상태 확인").changed()).isFalse();
        assertThat(version(3)).isEqualTo(1);
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test
    void suspendedUserCanBeRestoredWithoutRestoringPreviouslyIssuedSessions() {
        business.changeStatus(1, 3, UserStatus.SUSPENDED, "점검");
        var restored = business.changeStatus(1, 3, UserStatus.REGISTERED, "점검 완료");
        assertThat(restored.status()).isEqualTo(UserStatus.REGISTERED);
        assertThat(restored.revokedSessions()).isZero();
        assertThat(version(3)).isEqualTo(2);
        assertThat(auditCount()).isEqualTo(2);
    }

    @Test
    void selfDemotionSuspensionAndUnregistrationAreRejected() {
        assertCode(() -> business.changeRole(1, 1, UserRole.CUSTOMER, "테스트"), CONFLICT);
        assertCode(() -> business.changeStatus(1, 1, UserStatus.SUSPENDED, "테스트"), CONFLICT);
        assertCode(() -> business.changeStatus(1, 3, UserStatus.UNREGISTERED, "테스트"), BAD_REQUEST);
        assertCode(() -> business.changeStatus(1, 5, UserStatus.REGISTERED, "테스트"), CONFLICT);
        assertCode(() -> business.changeRole(1, 5, UserRole.ADMIN, "테스트"), CONFLICT);
        assertThat(version(1)).isZero();
        assertThat(version(5)).isZero();
        assertThat(auditCount()).isZero();
    }

    @Test
    void requiresCurrentActorAuthorityEvenAfterRequestAuthentication() {
        assertCode(() -> business.changeStatus(3, 4, UserStatus.REGISTERED, "테스트"), FORBIDDEN);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = 1");
        assertCode(() -> business.changeRole(1, 3, UserRole.ADMIN, "테스트"), FORBIDDEN);
        assertCode(() -> business.revokeSessions(1, 3, "테스트"), FORBIDDEN);
        assertCode(() -> business.changeStatus(99, 3, UserStatus.SUSPENDED, "테스트"), FORBIDDEN);
        assertCode(() -> business.changeStatus(2, 99, UserStatus.SUSPENDED, "테스트"), NOT_FOUND);
        assertThat(auditCount()).isZero();
    }

    @Test
    void memoLengthIsBoundedAndForcedRevocationInvalidatesAccessOnlySessionsToo() {
        for (String reason : new String[]{"x".repeat(501), " ".repeat(501)}) {
            assertCode(() -> business.revokeSessions(1, 3, reason), BAD_REQUEST);
            assertCode(() -> business.changeRole(1, 3, UserRole.ADMIN, reason), BAD_REQUEST);
            assertCode(() -> business.changeStatus(1, 3, UserStatus.SUSPENDED, reason), BAD_REQUEST);
        }
        assertThat(business.revokeSessions(1, 3, "보안 조치").revokedSessions()).isEqualTo(2);
        assertThat(business.revokeSessions(1, 3, "다시 발급된 액세스 세션 차단").revokedSessions()).isZero();
        assertThat(version(3)).isEqualTo(2);
        assertThat(auditCount()).isEqualTo(2);
        assertThat(business.revokeSessions(1, 1, "내 모든 기기 로그아웃").revokedSessions()).isEqualTo(1);
        assertThat(version(1)).isEqualTo(1);
    }

    @Test
    void missingAndBlankMemosRecordThePerformedActionWithoutChangingAccessRules() {
        assertThat(business.changeStatus(1, 3, UserStatus.SUSPENDED, null).revokedSessions()).isEqualTo(2);
        business.changeStatus(1, 3, UserStatus.REGISTERED, "  ");
        business.changeRole(1, 3, UserRole.ADMIN, null);
        business.changeRole(1, 3, UserRole.CUSTOMER, "\t ");
        business.revokeSessions(1, 3, "");

        assertThat(jdbc.queryForList("SELECT reason FROM admin_audit_logs ORDER BY id", String.class))
                .containsExactly("회원 정지", "회원 정지 해제", "관리자 권한 부여", "관리자 권한 해제", "전체 세션 종료");
        assertThat(version(3)).isEqualTo(5);
        assertThat(business.detail(3).status()).isEqualTo(UserStatus.REGISTERED);
        assertThat(business.detail(3).role()).isEqualTo(UserRole.CUSTOMER);
        assertCode(() -> business.changeStatus(1, 1, UserStatus.SUSPENDED, null), CONFLICT);
        assertCode(() -> business.changeRole(1, 1, UserRole.CUSTOMER, ""), CONFLICT);
        assertThat(auditCount()).isEqualTo(5);
    }

    @Test
    void auditFailureRollsBackAccountAndTokenChanges() {
        jdbc.execute("ALTER TABLE admin_audit_logs ADD CONSTRAINT reject_test_action CHECK (action <> 'USER_STATUS_CHANGED')");
        try {
            assertThatThrownBy(() -> business.changeStatus(1, 3, UserStatus.SUSPENDED, "감사 저장 실패"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(business.detail(3).status()).isEqualTo(UserStatus.REGISTERED);
            assertThat(version(3)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tokens WHERE user_id = 3 AND status = 'ACTIVE'", Long.class)).isEqualTo(2);
            assertThat(auditCount()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE admin_audit_logs DROP CONSTRAINT reject_test_action");
        }
    }

    @Test
    void concurrentMutualDemotionLeavesOneActiveAdministratorWithoutDeadlock() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> demoteAfter(start, 1, 2));
            var second = pool.submit(() -> demoteAfter(start, 2, 1));
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("changed", "forbidden");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND status = 'REGISTERED'", Long.class)).isEqualTo(1);
            assertThat(auditCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void guardRequiresAnExistingTransactionAndReturnsSafeRows() {
        assertThatThrownBy(() -> guard.lock(1, 3)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        var locked = transaction.execute(status -> guard.lock(2, 1));
        assertThat(locked.actor().id()).isEqualTo(2);
        assertThat(locked.target().id()).isEqualTo(1);
        assertThat(locked.toString()).doesNotContain("password-marker", "phone-hash-marker");
    }

    private String demoteAfter(CountDownLatch start, long actor, long target) throws InterruptedException {
        start.await();
        try {
            business.changeRole(actor, target, UserRole.CUSTOMER, "동시 권한 변경");
            return "changed";
        } catch (AdminException exception) {
            if (exception.status() != FORBIDDEN) throw exception;
            return "forbidden";
        }
    }

    private long version(long id) { return jdbc.queryForObject("SELECT auth_version FROM users WHERE id = ?", Long.class, id); }
    private long auditCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long.class); }

    private void user(long id, String email, String name, UserStatus status, UserRole role) {
        jdbc.update("INSERT INTO users (id, email, password, status, role, registered_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, email, "password-marker", status.name(), role.name(), LocalDateTime.of(2026, 9, 28, 10, 0));
        jdbc.update("INSERT INTO user_profiles (id, user_id, name, phone, phone_hash, address) VALUES (?, ?, ?, ?, ?, ?)",
                id, id, name, "not-valid-ciphertext", "phone-hash-marker", "not-valid-ciphertext");
    }

    private static void assertCode(ThrowingCallable action, org.springframework.http.HttpStatus status) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AdminException.class, error -> assertThat(error.status()).isEqualTo(status));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfig {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:admin-users;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000", "sa", "");
        }

        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.orinan.db.adminaudit");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
            return factory;
        }

        @Bean @Primary EntityManager entityManager(EntityManagerFactory factory) { return SharedEntityManagerCreator.createSharedEntityManager(factory); }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean AdminUserRepository users(EntityManager em) { return new AdminUserRepository(em); }
        @Bean AdminUserMutationGuard guard(AdminUserService users) { return new AdminUserMutationGuard(users); }
        @Bean AdminAuditService audit(EntityManager em) { return new AdminAuditService(new AdminAuditRepository(em)); }
        @Bean AdminUserService usersService(AdminUserRepository users) { return new AdminUserService(users); }
        @Bean AdminUserConverter converter() { return new AdminUserConverter(); }
        @Bean AdminUserBusiness business(AdminUserService users, AdminUserConverter converter,
                                        AdminUserMutationGuard guard, AdminAuditService audit) {
            return new AdminUserBusiness(users, converter, guard, audit);
        }
    }
}
