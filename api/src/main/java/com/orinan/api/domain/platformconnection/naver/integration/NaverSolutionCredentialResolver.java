package com.orinan.api.domain.platformconnection.naver.integration;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.platformconnection.naver.solution.NaverSolutionProperties;
import com.orinan.db.naverconnection.NaverConnectionEntity;
import com.orinan.db.naverconnection.enums.NaverCredentialSource;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import com.orinan.db.naversolution.NaverSolutionSubscriptionEntity;
import com.orinan.db.naversolution.NaverSolutionSubscriptionRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class NaverSolutionCredentialResolver {
    private final NaverSolutionProperties properties;
    private final NaverSolutionSubscriptionRepository subscriptions;
    private final EntityManager entityManager;

    public Resolved resolve(NaverConnectionEntity detail) {
        if (detail.getCredentialSource() != NaverCredentialSource.SOLUTION
                || detail.getSolutionSubscriptionId() == null || !properties.ready()
                || !Objects.equals(detail.getApplicationRef(), properties.getApplicationRef())) {
            throw unavailable();
        }
        var subscription = subscriptions.findById(detail.getSolutionSubscriptionId()).orElseThrow(this::unavailable);
        entityManager.refresh(subscription);
        requireActive(subscription, detail.getApplicationRef(), detail.getConnection().getExternalAccountId(),
                detail.getBoundSubscriptionGeneration());
        if (detail.getTokenType() != NaverTokenType.SELLER
                || !Objects.equals(detail.getAccountId(), subscription.getAccountUid())) throw unavailable();
        return new Resolved(properties.getClientId(), properties.getClientSecret(), subscription.getVersion(),
                properties.getCredentialVersion());
    }

    public boolean available(NaverConnectionEntity detail) {
        try { resolve(detail); return detail.getAccessToken() != null && detail.getExpiresAt() != null; }
        catch (ApiException exception) { return false; }
    }

    public void requireActive(NaverSolutionSubscriptionEntity subscription, String applicationRef,
                              String accountUid, Long generation) {
        if (!properties.ready() || !Objects.equals(applicationRef, properties.getApplicationRef())
                || !Objects.equals(subscription.getApplicationRef(), applicationRef)
                || !Objects.equals(subscription.getSolutionId(), properties.getSolutionId())
                || !Objects.equals(subscription.getAccountUid(), accountUid)
                || generation == null || generation != subscription.getGeneration()
                || !"ACTIVE".equals(subscription.getStatus())
                || subscription.getProviderSubscriptionId() == null || subscription.getProviderSubscriptionId().isBlank()) {
            throw unavailable();
        }
    }

    private ApiException unavailable() {
        return new ApiException(ApiCode.BAD_REQUEST, "네이버 솔루션 구독과 연결 권한을 확인하고 다시 연결해 주세요.");
    }

    public record Resolved(String clientId, String clientSecret, long subscriptionVersion, long applicationCredentialVersion) {
        @Override public String toString() { return "Resolved[REDACTED]"; }
    }
}
