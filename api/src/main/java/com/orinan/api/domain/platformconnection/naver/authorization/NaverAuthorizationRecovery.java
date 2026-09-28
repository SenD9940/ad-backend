package com.orinan.api.domain.platformconnection.naver.authorization;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class NaverAuthorizationRecovery {
    private final NaverAuthorizationService authorizations;
    @Scheduled(initialDelay=60000,fixedDelay=30000)
    public void reconcileConfirmedAttempts() {authorizations.recoverPending();}
}
