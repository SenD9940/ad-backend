package com.orinan.api.domain.platformconnection.naver.authorization;
import com.orinan.api.domain.platformconnection.naver.solution.NaverAuthorizationProvider;
import com.orinan.db.naverauthorization.*;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
@Component @RequiredArgsConstructor
public class NaverAuthorizationCleanup {
    private final NaverAuthorizationProvider provider;
    private final NaverAuthorizationAttemptRepository attempts;
    private final NaverMarketplaceReceiptRepository receipts;
    @Scheduled(initialDelay=60000,fixedDelay=60000) @Transactional
    public void discardExpiredProofs() {
        if(!provider.ready()) return;
        Instant now=Instant.now();
        attempts.expireUnconfirmed(now);attempts.eraseExpiredProofs(now);receipts.deleteByExpiresAtBefore(now);
    }
}
