package com.orinan.api.domain.platformconnection.naver.integration;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.platformconnection.naver.solution.NaverSolutionProperties;
import com.orinan.db.naverconnection.NaverConnectionEntity;
import com.orinan.db.naverconnection.enums.NaverCredentialSource;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import com.orinan.db.naversolution.NaverSolutionSubscriptionEntity;
import com.orinan.db.naversolution.NaverSolutionSubscriptionRepository;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaverSolutionCredentialResolverTest {
    private final NaverSolutionProperties properties = mock(NaverSolutionProperties.class);
    private final NaverSolutionSubscriptionRepository subscriptions = mock(NaverSolutionSubscriptionRepository.class);
    private final EntityManager manager = mock(EntityManager.class);
    private final NaverSolutionCredentialResolver resolver = new NaverSolutionCredentialResolver(properties, subscriptions, manager);
    private NaverSolutionSubscriptionEntity subscription;
    private NaverConnectionEntity detail;

    @BeforeEach void setUp() {
        when(properties.ready()).thenReturn(true);
        when(properties.getApplicationRef()).thenReturn("application");
        when(properties.getSolutionId()).thenReturn("solution");
        when(properties.getClientId()).thenReturn("server-client");
        when(properties.getClientSecret()).thenReturn("server-secret");
        subscription = new NaverSolutionSubscriptionEntity();
        subscription.setId(7L); subscription.setApplicationRef("application"); subscription.setSolutionId("solution");
        subscription.setAccountUid("seller"); subscription.setProviderSubscriptionId("lifecycle");
        subscription.setGeneration(2); subscription.setVersion(12); subscription.setStatus("ACTIVE");
        when(subscriptions.findById(7L)).thenReturn(Optional.of(subscription));
        detail = NaverConnectionEntity.builder().connection(PlatformConnectionEntity.builder().id(20L)
                .externalAccountId("seller").requiresReauth(false).build())
                .credentialSource(NaverCredentialSource.SOLUTION).applicationRef("application").solutionSubscriptionId(7L)
                .boundSubscriptionGeneration(2L).accountId("seller").tokenType(NaverTokenType.SELLER)
                .accessToken("stored-seller-token").expiresAt(SeoulDateTimes.now().plusHours(1)).build();
    }

    @Test void resolvesOnlyServerCredentialsAfterRefreshingSameActiveGeneration() {
        var resolved = resolver.resolve(detail);
        assertThat(resolved.clientId()).isEqualTo("server-client");
        assertThat(resolved.clientSecret()).isEqualTo("server-secret");
        assertThat(resolved.subscriptionVersion()).isEqualTo(12);
        assertThat(resolved.toString()).doesNotContain("server-secret", "server-client");
        verify(manager).refresh(subscription);
        assertThat(detail.getClientId()).isNull(); assertThat(detail.getClientSecret()).isNull();
    }

    @Test void revokedUnknownAndPendingGrantsBlockExistingUnexpiredToken() {
        for (String state : List.of("ENDED", "UNKNOWN", "PENDING")) {
            subscription.setStatus(state);
            assertThatThrownBy(() -> resolver.resolve(detail)).isInstanceOf(ApiException.class);
            assertThat(resolver.available(detail)).isFalse();
        }
        assertThat(detail.getAccessToken()).isEqualTo("stored-seller-token");
    }

    @Test void newSubscriptionGenerationNeverReactivatesPreviouslyBoundConnection() {
        subscription.setGeneration(3);
        assertThatThrownBy(() -> resolver.resolve(detail)).isInstanceOf(ApiException.class);
        assertThat(resolver.available(detail)).isFalse();
        detail.setBoundSubscriptionGeneration(null);
        assertThatThrownBy(() -> resolver.resolve(detail)).isInstanceOf(ApiException.class);
    }

    @Test void checksFreshDatabaseStateInsteadOfCachedActiveEntity() {
        doAnswer(invocation -> { subscription.setStatus("ENDED"); return null; }).when(manager).refresh(subscription);
        assertThatThrownBy(() -> resolver.resolve(detail)).isInstanceOf(ApiException.class);
        verify(manager).refresh(subscription);
    }

    @Test void rejectsDisabledAppWrongSourceAndCrossApplicationIdentity() {
        when(properties.ready()).thenReturn(false);
        assertThatThrownBy(() -> resolver.resolve(detail)).isInstanceOf(ApiException.class);
        verifyNoInteractions(subscriptions);
        when(properties.ready()).thenReturn(true);
        detail.setCredentialSource(NaverCredentialSource.MANUAL);
        assertThatThrownBy(() -> resolver.resolve(detail)).isInstanceOf(ApiException.class);
        detail.setCredentialSource(NaverCredentialSource.SOLUTION); detail.setApplicationRef("another-app");
        assertThatThrownBy(() -> resolver.resolve(detail)).isInstanceOf(ApiException.class);
        verifyNoInteractions(subscriptions);
    }

    @Test void rejectsCrossSellerSolutionTokenTypeOrMissingLifecycle() {
        subscription.setAccountUid("other-seller");
        assertThatThrownBy(() -> resolver.resolve(detail)).isInstanceOf(ApiException.class);
        subscription.setAccountUid("seller"); subscription.setSolutionId("other-solution");
        assertThatThrownBy(() -> resolver.resolve(detail)).isInstanceOf(ApiException.class);
        subscription.setSolutionId("solution"); detail.setTokenType(NaverTokenType.SELF);
        assertThatThrownBy(() -> resolver.resolve(detail)).isInstanceOf(ApiException.class);
        detail.setTokenType(NaverTokenType.SELLER); detail.setAccountId("other-seller");
        assertThatThrownBy(() -> resolver.resolve(detail)).isInstanceOf(ApiException.class);
        detail.setAccountId("seller"); subscription.setProviderSubscriptionId(" ");
        assertThatThrownBy(() -> resolver.resolve(detail)).isInstanceOf(ApiException.class);
    }

    @Test void missingSubscriptionAndMissingUsableTokenReportUnavailable() {
        when(subscriptions.findById(7L)).thenReturn(Optional.empty());
        assertThat(resolver.available(detail)).isFalse();
        when(subscriptions.findById(7L)).thenReturn(Optional.of(subscription));
        detail.setAccessToken(null); assertThat(resolver.available(detail)).isFalse();
        detail.setAccessToken("token"); detail.setExpiresAt(null); assertThat(resolver.available(detail)).isFalse();
    }
}
