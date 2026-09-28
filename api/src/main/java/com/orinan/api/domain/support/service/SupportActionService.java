package com.orinan.api.domain.support.service;

import com.orinan.api.domain.support.model.SupportContext;
import com.orinan.db.support.SupportActionEntity;
import com.orinan.db.support.SupportActionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class SupportActionService {
    private final SupportActionRepository actions;

    /** Commit the intent before controllers can perform a database or external write. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long begin(SupportContext context, String method, String route) {
        return actions.saveAndFlush(new SupportActionEntity(context.sessionId(), context.ticketId(), context.adminUserId(),
                context.customerUserId(), context.workspaceId(), method, route, now())).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(long actionId, int status) {
        var action = actions.findById(actionId).orElseThrow();
        action.setStatusCode(status);
        action.setCompletedAt(now());
        actions.flush();
    }

    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Seoul")); }
}
