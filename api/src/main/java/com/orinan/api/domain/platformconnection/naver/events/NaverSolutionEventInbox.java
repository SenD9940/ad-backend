package com.orinan.api.domain.platformconnection.naver.events;

import com.orinan.api.domain.platformconnection.naver.solution.NaverAuthorizationProvider;
import com.orinan.api.domain.platformconnection.naver.solution.NaverSolutionProperties;
import com.orinan.db.naverconnection.NaverConnectionRepository;
import com.orinan.db.naverconnection.enums.NaverCredentialSource;
import com.orinan.db.naversolution.NaverSolutionSubscriptionRepository;
import com.orinan.db.naversolutionevent.NaverSolutionEventEntity;
import com.orinan.db.naversolutionevent.NaverSolutionEventRepository;
import com.orinan.db.platformconnection.PlatformConnectionRepository;
import com.orinan.db.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Comparator;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class NaverSolutionEventInbox {
    private final NaverSolutionEventRepository events;
    private final NaverSolutionSubscriptionRepository subscriptions;
    private final NaverConnectionRepository details;
    private final PlatformConnectionRepository connections;
    private final NaverSolutionProperties properties;
    private final EntityManager entityManager;
    private final WorkspaceRepository workspaces;

    @Transactional
    public void accept(List<NaverSolutionEventData> batch) {
        for (var data : batch) {
            if (events.existsBySolutionIdAndEventIdAndChangeType(data.solutionId(),data.eventId(),data.changeType())) continue;
            var event = new NaverSolutionEventEntity();
            event.setSolutionId(data.solutionId()); event.setEventId(data.eventId()); event.setChangeType(data.changeType());
            event.setAccountUid(data.accountUid()); event.setAccountMappingId(data.accountMappingId());
            events.saveAndFlush(event);
            subscriptions.findByApplicationRefAndAccountUidForUpdate(properties.getApplicationRef(), data.accountUid()).ifPresent(subscription -> {
                entityManager.refresh(subscription);
                // A callback cannot grant access. Fence the known generation until read-back,
                // but an old lifecycle's event must not revoke a newly confirmed lifecycle.
                if (data.accountMappingId() == null || Objects.equals(data.accountMappingId(),subscription.getAccountMappingId())) {
                    subscription.setStatus("UNKNOWN");
                    subscriptions.saveAndFlush(subscription);
                }
            });
        }
    }

    @Transactional(readOnly=true)
    public Check context(Long eventId) {
        var event=events.findById(eventId).orElse(null);
        if (event==null || !"PENDING".equals(event.getState())) return null;
        var subscription=subscriptions.findByApplicationRefAndAccountUid(properties.getApplicationRef(),event.getAccountUid()).orElse(null);
        return new Check(eventId,event.getAccountUid(),subscription==null?null:subscription.getId(),
                subscription==null?0:subscription.getGeneration(),subscription==null?0:subscription.getVersion());
    }

    @Transactional
    public void apply(Check check,NaverAuthorizationProvider.Subscription remote) {
        var event=events.findByIdForUpdate(check.eventId()).orElse(null);
        if (event==null || !"PENDING".equals(event.getState())) return;
        if (check.subscriptionId()==null) { event.setState("IGNORED"); event.setCheckedAt(Instant.now()); return; }
        var subscription=subscriptions.findByIdForUpdate(check.subscriptionId()).orElse(null);
        if (subscription==null) { event.setState("IGNORED"); return; }
        entityManager.refresh(subscription);
        if (event.getAccountMappingId() != null
                && !Objects.equals(event.getAccountMappingId(), subscription.getAccountMappingId())) {
            event.setState("IGNORED"); event.setCheckedAt(Instant.now()); return;
        }
        if (subscription.getGeneration()!=check.generation() || subscription.getVersion()!=check.version()) {
            event.setNextAttemptAt(Instant.now().plusSeconds(5)); return;
        }
        boolean sameLifetime=remote!=null && Objects.equals(remote.accountUid(),subscription.getAccountUid())
                && Objects.equals(remote.subscriptionId(),subscription.getProviderSubscriptionId())
                && Objects.equals(remote.accountMappingId(),subscription.getAccountMappingId());
        String status=sameLifetime?remote.status():"UNKNOWN";
        if (!List.of("ACTIVE","PENDING","ENDED","UNKNOWN").contains(status)) status="UNKNOWN";
        subscription.setStatus(status); subscription.setVerifiedAt(Instant.now());
        subscriptions.saveAndFlush(subscription);
        if (!"ACTIVE".equals(status)) {
            var boundConnections = details.findAllBySolutionSubscriptionId(subscription.getId()).stream()
                    .sorted(Comparator.comparing(detail -> detail.getConnection().getWorkspace().getId())).toList();
            for (var detail:boundConnections) {
                // Serialize with manual conversion and token renewal before inspecting the row.
                // A stale event must never restore a connection's previous credential source.
                var workspace = workspaces.findByIdForUpdate(detail.getConnection().getWorkspace().getId());
                if (workspace.isEmpty()) continue;
                entityManager.refresh(detail);
                if (detail.getCredentialSource()!=NaverCredentialSource.SOLUTION
                        || !Objects.equals(detail.getSolutionSubscriptionId(),subscription.getId())
                        || !Objects.equals(detail.getBoundSubscriptionGeneration(),subscription.getGeneration())) continue;
                entityManager.refresh(detail.getConnection());
                detail.setAccessToken(null); detail.setExpiresAt(null);
                detail.setCredentialVersion(detail.getCredentialVersion()+1);
                detail.getConnection().setRequiresReauth(true);
                connections.save(detail.getConnection()); details.save(detail);
            }
        }
        if ("UNKNOWN".equals(status)) {
            retryLater(event);
        } else {
            event.setState("PROCESSED"); event.setCheckedAt(Instant.now());
        }
    }

    @Transactional
    public void failed(Long eventId) {
        events.findByIdForUpdate(eventId).filter(event -> "PENDING".equals(event.getState())).ifPresent(this::retryLater);
    }

    private void retryLater(NaverSolutionEventEntity event) {
        int attempts=Math.min(event.getAttempts()+1,1000);
        event.setAttempts(attempts); event.setCheckedAt(Instant.now());
        event.setNextAttemptAt(Instant.now().plusSeconds(Math.min(900,5L*(1L<<Math.min(attempts,7)))));
    }

    public record Check(Long eventId,String accountUid,Long subscriptionId,long generation,long version) {
        @Override public String toString() { return "Check[REDACTED]"; }
    }
}
