package com.orinan.api.domain.token;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.token.converter.TokenConverter;
import com.orinan.api.domain.token.exception.TokenErrorCode;
import com.orinan.api.domain.token.ifs.TokenHelperIfs;
import com.orinan.api.domain.token.service.TokenService;
import com.orinan.db.crypto.AesGcmStringEncryptor;
import com.orinan.db.crypto.CryptoProperties;
import com.orinan.db.crypto.SearchHashEncoder;
import com.orinan.db.token.TokenEntity;
import com.orinan.db.token.TokenRepository;
import com.orinan.db.token.enums.TokenStatus;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.UserRepository;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.userprofile.UserProfileEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@ActiveProfiles("test")
@ContextConfiguration(classes = TokenRevocationPersistenceTest.Config.class)
class TokenRevocationPersistenceTest {
    @Autowired private UserRepository users;
    @Autowired private TokenRepository tokens;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void refreshWaitingForAnAdministrativeRevocationCannotReuseItsEarlierTokenSnapshot() throws Exception {
        var tx = new TransactionTemplate(transactionManager);
        long userId = tx.execute(status -> {
            var user = users.saveAndFlush(UserEntity.builder().email("revocation-race@example.com")
                    .password("test-password").role(UserRole.CUSTOMER).status(UserStatus.REGISTERED).build());
            tokens.saveAndFlush(TokenEntity.builder().userId(user.getId()).refreshTokenHash("race-refresh-hash")
                    .status(TokenStatus.ACTIVE).expiresAt(LocalDateTime.now().plusDays(1)).build());
            return user.getId();
        });
        var adminLocked = new CountDownLatch(1);
        var finishAdmin = new CountDownLatch(1);
        var refreshReadOldToken = new CountDownLatch(1);
        var tokenObserver = mock(TokenRepository.class, delegatesTo(tokens));
        doAnswer(invocation -> {
            var token = tokens.findByRefreshTokenHash("race-refresh-hash");
            refreshReadOldToken.countDown();
            return token;
        }).when(tokenObserver).findByRefreshTokenHash("race-refresh-hash");
        var helper = mock(TokenHelperIfs.class);
        var hashes = mock(SearchHashEncoder.class);
        when(helper.validationTokenWithThrow("refresh-token")).thenReturn(Map.of());
        when(hashes.encode("refresh-token")).thenReturn("race-refresh-hash");
        var service = new TokenService(helper, tokenObserver, new TokenConverter(), hashes, users);
        var pool = Executors.newFixedThreadPool(2);

        try {
            var admin = pool.submit(() -> tx.executeWithoutResult(status -> {
                var user = users.findByIdForUpdate(userId).orElseThrow();
                adminLocked.countDown();
                await(finishAdmin);
                user.setAuthVersion(user.getAuthVersion() + 1);
                var token = tokens.findByUserIdAndStatus(userId, TokenStatus.ACTIVE).orElseThrow();
                token.setStatus(TokenStatus.EXPIRED);
                token.setRevokedAt(LocalDateTime.now());
                users.saveAndFlush(user);
                tokens.saveAndFlush(token);
            }));
            assertThat(adminLocked.await(5, TimeUnit.SECONDS)).isTrue();
            var refresh = pool.submit(() -> tx.execute(status -> service.validateRefreshToken("refresh-token")));
            assertThat(refreshReadOldToken.await(5, TimeUnit.SECONDS)).isTrue();
            finishAdmin.countDown();

            admin.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> refresh.get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(ApiException.class)
                    .rootCause().isInstanceOfSatisfying(ApiException.class,
                            exception -> assertThat(exception.getCodeIfs()).isEqualTo(TokenErrorCode.INVALID_TOKEN));
            assertThat(users.findById(userId).orElseThrow().getAuthVersion()).isEqualTo(1);
            assertThat(tokens.findByUserIdAndStatus(userId, TokenStatus.ACTIVE)).isEmpty();
            verify(helper, never()).issueAccessToken(anyMap());
            verify(helper, never()).issueRefreshToken();
        } finally {
            finishAdmin.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            tx.executeWithoutResult(status -> {
                tokens.findByRefreshTokenHash("race-refresh-hash").ifPresent(tokens::delete);
                tokens.flush();
                users.deleteById(userId);
            });
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrent transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted concurrent transaction", exception);
        }
    }

    @Configuration
    @EntityScan(basePackageClasses = {UserEntity.class, UserProfileEntity.class, TokenEntity.class})
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, TokenRepository.class})
    static class Config {
        @Bean
        AesGcmStringEncryptor encryptor() {
            return new AesGcmStringEncryptor(new CryptoProperties(Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))));
        }
    }
}
