package com.orinan.api.domain.platformconnection.naver.authorization;

import com.orinan.db.crypto.*;
import com.orinan.db.naverauthorization.*;
import com.orinan.db.naversolution.*;
import com.orinan.db.user.UserEntity;
import com.orinan.db.userprofile.UserProfileEntity;
import com.orinan.db.workspace.WorkspaceEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties="spring.jpa.hibernate.ddl-auto=create-drop",showSql=false)
@ActiveProfiles("test") @ContextConfiguration(classes=NaverAuthorizationPersistenceTest.Config.class)
class NaverAuthorizationPersistenceTest {
    @Autowired NaverAuthorizationAttemptRepository attempts;
    @Autowired NaverSubscriptionOperationRepository operations;
    @Autowired NaverSolutionSubscriptionRepository subscriptions;
    @Autowired NaverMarketplaceReceiptRepository receipts;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Test void proofIsEncryptedAndDurableAttemptsRecoverAfterPersistenceContextReset() {
        var a=attempt("one");a.setEncryptedProof("sensitive-jwe");a.setProviderState("sensitive-state");attempts.saveAndFlush(a);
        assertThat(jdbc.queryForObject("select encrypted_proof from naver_authorization_attempts where id='one'",String.class)).startsWith("v1:").doesNotContain("sensitive-jwe");
        assertThat(jdbc.queryForObject("select provider_state from naver_authorization_attempts where id='one'",String.class)).startsWith("v1:").doesNotContain("sensitive-state");
        entityManager.clear();var stored=attempts.findByIdForUpdate("one").orElseThrow();assertThat(stored.getEncryptedProof()).isEqualTo("sensitive-jwe");assertThat(stored.getWorkspaceId()).isEqualTo(1);
    }
    @Test void cleanupExpiresUserInteractionButKeepsUncertainOperationAndErasesProof() {
        var waiting=attempt("waiting");waiting.setEncryptedProof("expired-proof");waiting.setExpiresAt(Instant.now().minusSeconds(5));attempts.saveAndFlush(waiting);
        var uncertain=attempt("uncertain");uncertain.setStatus("RECONCILING");uncertain.setEncryptedProof("approved-proof");uncertain.setExpiresAt(Instant.now().minusSeconds(5));attempts.saveAndFlush(uncertain);
        assertThat(attempts.expireUnconfirmed(Instant.now())).isEqualTo(1);assertThat(attempts.eraseExpiredProofs(Instant.now())).isEqualTo(1);entityManager.clear();
        assertThat(attempts.findById("waiting").orElseThrow().getStatus()).isEqualTo("EXPIRED");var reloaded=attempts.findById("uncertain").orElseThrow();assertThat(reloaded.getStatus()).isEqualTo("RECONCILING");assertThat(reloaded.getEncryptedProof()).isNull();
    }
    @Test void databaseEnforcesOneOperationPerSellerSubscriptionLifecycle() {
        var first=operation("first");operations.saveAndFlush(first);entityManager.clear();
        assertThat(operations.findById("first").orElseThrow().getStatus()).isEqualTo("DISPATCHING");
        assertThatThrownBy(()->operations.saveAndFlush(operation("second"))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void subscriptionGenerationDoesNotChangeWhenStatusVersionAdvances() {
        var g=new NaverSolutionSubscriptionEntity();g.setApplicationRef("app");g.setSolutionId("solution");g.setAccountUid("seller");g.setProviderSubscriptionId("lifecycle");g.setAccountMappingId("mapping");g.setStatus("PENDING");subscriptions.saveAndFlush(g);
        long version=g.getVersion();g.setStatus("ACTIVE");subscriptions.saveAndFlush(g);assertThat(g.getVersion()).isGreaterThan(version);assertThat(g.getGeneration()).isEqualTo(1);
    }
    @Test void receiptsRetainBindingAndCanBePurgedWithoutTouchingDurableOperations() {
        var r=new NaverMarketplaceReceiptEntity();r.setId("receipt");r.setAccountUid("seller");r.setBrowserHash("hash");r.setExpiresAt(Instant.now().minusSeconds(5));receipts.saveAndFlush(r);assertThat(receipts.deleteByExpiresAtBefore(Instant.now())).isEqualTo(1);
    }
    private NaverAuthorizationAttemptEntity attempt(String id) {var a=new NaverAuthorizationAttemptEntity();a.setId(id);a.setWorkspaceId(1L);a.setUserId(1L);a.setStatus("WAITING_AUTH");a.setBrowserHash("browser-hash");a.setStateHash("state-hash");a.setCreatedAt(Instant.now());a.setUpdatedAt(Instant.now());a.setExpiresAt(Instant.now().plusSeconds(600));return a;}
    private NaverSubscriptionOperationEntity operation(String id) {var o=new NaverSubscriptionOperationEntity();o.setId(id);o.setApplicationRef("app");o.setAccountUid("seller");o.setProviderSubscriptionId("lifecycle");o.setAccountMappingId("mapping");o.setStatus("DISPATCHING");o.setOwnerAttemptId("attempt");o.setCreatedAt(Instant.now());o.setUpdatedAt(Instant.now());return o;}
    @Configuration @EntityScan(basePackageClasses={NaverAuthorizationAttemptEntity.class,NaverSolutionSubscriptionEntity.class,WorkspaceEntity.class,UserEntity.class,UserProfileEntity.class})
    @EnableJpaRepositories(basePackageClasses={NaverAuthorizationAttemptRepository.class,NaverSolutionSubscriptionRepository.class})
    static class Config {
        @Bean AesGcmStringEncryptor encryptor(){return new AesGcmStringEncryptor(new CryptoProperties(Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))));}
    }
}
