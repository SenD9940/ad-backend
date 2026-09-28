package com.orinan.api.domain.platformconnection.naver.events;

import com.orinan.api.domain.platformconnection.naver.solution.NaverSolutionClient;
import com.orinan.api.domain.platformconnection.naver.solution.NaverSolutionProperties;
import com.orinan.db.naversolutionevent.NaverSolutionEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component @RequiredArgsConstructor
public class NaverSolutionEventWorker {
    private final NaverSolutionProperties properties;
    private final NaverSolutionEventRepository events;
    private final NaverSolutionEventInbox inbox;
    private final NaverSolutionClient client;

    @Scheduled(fixedDelayString="${naver.solution.event-poll-ms:10000}")
    public void reconcile() {
        if (!properties.ready()) return;
        for(var id:events.pending(Instant.now(),PageRequest.of(0,20))) {
            try {
                var check=inbox.context(id);
                if(check==null) continue;
                if(check.subscriptionId()==null) inbox.apply(check,null);
                else inbox.apply(check,client.getSubscription(check.accountUid()));
            } catch(RuntimeException exception) {
                // Provider errors are intentionally not logged with credentials or raw bodies.
                inbox.failed(id);
            }
        }
    }
}
