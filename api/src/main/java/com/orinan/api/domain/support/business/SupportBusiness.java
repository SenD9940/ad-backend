package com.orinan.api.domain.support.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.domain.support.controller.model.SupportResponse.*;
import com.orinan.api.domain.support.controller.model.SupportCreateRequest;
import com.orinan.api.domain.support.model.SupportContext;
import com.orinan.api.domain.support.service.SupportConsentService;
import com.orinan.api.domain.support.service.SupportSessionService;
import com.orinan.api.domain.support.service.SupportRequestService;
import lombok.RequiredArgsConstructor;

@Business
@RequiredArgsConstructor
public class SupportBusiness {
    private final SupportConsentService consent;
    private final SupportSessionService sessions;
    private final SupportRequestService requests;

    public Offer offer(long workspaceId, long userId) { return requests.offer(workspaceId, userId); }
    public Ticket create(long workspaceId, long userId, SupportCreateRequest request) {
        return requests.create(workspaceId, userId, request);
    }

    public Page<Ticket> tickets(long workspaceId, long userId, int page, int size) {
        return consent.tickets(workspaceId, userId, page, size);
    }
    public Ticket approve(long workspaceId, long ticketId, long userId, String reason) {
        return consent.approve(workspaceId, ticketId, userId, reason);
    }
    public Ticket revoke(long workspaceId, long ticketId, long userId, String reason) {
        return consent.revoke(workspaceId, ticketId, userId, reason);
    }
    public Session session(SupportContext context) {
        return new Session(context.sessionId(), context.ticketId(), context.workspaceId(), context.customerUserId(),
                context.accessMode(), context.expiresAt());
    }
    public Ended end(SupportContext context) {
        sessions.end(context);
        return new Ended(context.sessionId(), true);
    }
}
