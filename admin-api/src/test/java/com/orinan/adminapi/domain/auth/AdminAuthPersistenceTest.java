package com.orinan.adminapi.domain.auth;

import com.orinan.adminapi.domain.auth.business.AdminAuthBusiness;
import com.orinan.adminapi.domain.auth.controller.model.AdminLoginRequest;
import com.orinan.adminapi.domain.auth.converter.AdminAuthConverter;
import com.orinan.adminapi.domain.auth.service.AdminAuthService;
import com.orinan.adminapi.domain.token.business.AdminTokenBusiness;
import com.orinan.adminapi.domain.token.converter.AdminTokenConverter;
import com.orinan.adminapi.domain.token.helper.AdminTokenHelper;
import com.orinan.adminapi.domain.token.service.AdminTokenService;
import com.orinan.db.adminauth.AdminAuthRepository;
import com.orinan.db.adminaudit.AdminAuditRepository;
import com.orinan.db.token.AdminTokenRepository;

import com.orinan.db.adminaudit.AdminAuditEntity;
import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.db.crypto.AesGcmStringEncryptor;
import com.orinan.db.crypto.CryptoProperties;
import com.orinan.db.token.TokenEntity;
import com.orinan.db.token.enums.TokenStatus;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.UserRepository;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.userprofile.UserProfileEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@ActiveProfiles("test")
@ContextConfiguration(classes = AdminAuthPersistenceTest.Config.class)
class AdminAuthPersistenceTest {
    @Autowired UserRepository users;
    @Autowired EntityManager em;
    @Autowired AdminAuthBusiness auth;
    @Autowired PasswordEncoder passwords;

    @Test
    void realLoginAuditAndLogoutRevokeAllAdminRefreshTokensWithoutTouchingOtherMembers() {
        var admin = user("admin@example.com", UserRole.ADMIN);
        var customer = user("customer@example.com", UserRole.CUSTOMER);
        token(admin.getId(), "admin-refresh-1");
        token(admin.getId(), "admin-refresh-2");
        token(customer.getId(), "customer-refresh");
        em.flush();
        em.clear();

        var login = auth.login(new AdminLoginRequest("ADMIN@example.com", "password"));
        assertThat(auth.authenticate(login.accessToken()).id()).isEqualTo(admin.getId());
        auth.logout(admin.getId());
        em.flush();
        em.clear();

        var reloaded = users.findById(admin.getId()).orElseThrow();
        assertThat(reloaded.getLastLoginAt()).isNotNull();
        assertThat(reloaded.getAuthVersion()).isEqualTo(1);
        assertThatThrownBy(() -> auth.authenticate(login.accessToken())).isInstanceOf(AdminException.class);
        var revoked = em.createQuery("select t from TokenEntity t where t.userId = :userId", TokenEntity.class)
                .setParameter("userId", admin.getId()).getResultList();
        assertThat(revoked).hasSize(2).allSatisfy(token -> {
            assertThat(token.getStatus()).isEqualTo(TokenStatus.EXPIRED);
            assertThat(token.getRevokedAt()).isNotNull();
        });
        assertThat(em.createQuery("select t.status from TokenEntity t where t.userId = :userId", TokenStatus.class)
                .setParameter("userId", customer.getId()).getSingleResult()).isEqualTo(TokenStatus.ACTIVE);
        assertThat(em.createQuery("select a from AdminAuditEntity a order by a.id", AdminAuditEntity.class).getResultList())
                .extracting(AdminAuditEntity::getAction).containsExactly("ADMIN_LOGIN", "ADMIN_LOGOUT");
    }

    @Test
    void suspendedAdminCannotLoginAndCannotCreateSuccessfulLoginAudit() {
        var admin = user("suspended@example.com", UserRole.ADMIN);
        admin.setStatus(UserStatus.SUSPENDED);
        em.flush();
        em.clear();
        assertThatThrownBy(() -> auth.login(new AdminLoginRequest("suspended@example.com", "password"))).isInstanceOf(AdminException.class);
        assertThat(em.createQuery("select count(a) from AdminAuditEntity a", Long.class).getSingleResult()).isZero();
    }

    private UserEntity user(String email, UserRole role) {
        return users.saveAndFlush(UserEntity.builder().email(email).password(passwords.encode("password"))
                .role(role).status(UserStatus.REGISTERED).build());
    }

    private void token(long userId, String hash) {
        em.persist(TokenEntity.builder().userId(userId).refreshTokenHash(hash).status(TokenStatus.ACTIVE).build());
    }

    @Configuration
    @EntityScan(basePackageClasses = {UserEntity.class, UserProfileEntity.class, TokenEntity.class, AdminAuditEntity.class})
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    @Import({AdminAuthBusiness.class, AdminAuthService.class, AdminAuthConverter.class,
            AdminAuthRepository.class, AdminAuditService.class, AdminAuditRepository.class,
            AdminTokenBusiness.class, AdminTokenService.class, AdminTokenConverter.class, AdminTokenRepository.class})
    static class Config {
        @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(); }
        @Bean AdminTokenHelper tokenHelper() {
            return new AdminTokenHelper("admin-test-key-with-at-least-32-bytes-secret", 30);
        }
        @Bean AesGcmStringEncryptor encryptor() {
            return new AesGcmStringEncryptor(new CryptoProperties(Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))));
        }
    }
}
