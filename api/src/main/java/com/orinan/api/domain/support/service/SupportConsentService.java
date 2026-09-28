package com.orinan.api.domain.support.service;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.support.controller.model.SupportResponse.*;
import com.orinan.api.domain.support.converter.SupportConverter;
import com.orinan.api.domain.support.exception.SupportErrorCode;
import com.orinan.db.adminaudit.AdminAuditEntity;
import com.orinan.db.adminaudit.AdminAuditRepository;
import com.orinan.db.support.*;
import com.orinan.db.support.enums.SupportTicketStatus;
import com.orinan.db.user.UserRepository;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupportConsentService {
    private final SupportTicketRepository tickets;
    private final SupportSessionRepository sessions;
    private final UserRepository users;
    private final WorkspaceRepository workspaces;
    private final AdminAuditRepository audit;
    private final SupportConverter converter;

    public Page<Ticket> tickets(long workspaceId, long userId, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) throw new ApiException(SupportErrorCode.INVALID_REQUEST);
        var workspace = workspaces.findById(workspaceId).orElseThrow(() -> new ApiException(SupportErrorCode.NOT_FOUND));
        owner(workspace, userId);
        var result = tickets.findAll(null, userId, workspaceId, PageRequest.of(page, size, Sort.by("id").descending()));
        return Page.of(result.getContent().stream().map(converter::ticket).toList(), page, size, result.getTotalElements());
    }

    @Transactional
    public Ticket approve(long workspaceId, long ticketId, long userId, String reason) {
        String explanation = reason(reason, "고객 기술 지원 승인");
        var ticket = lock(workspaceId, ticketId, userId);
        if (ticket.getStatus() != SupportTicketStatus.REQUESTED) throw new ApiException(SupportErrorCode.CONFLICT);
        var now = now();
        ticket.setStatus(SupportTicketStatus.APPROVED);
        ticket.setApprovedAt(now);
        ticket.setApprovalExpiresAt(now.plusDays(7));
        ticket.setUpdatedAt(now);
        audit.save(new AdminAuditEntity(userId, "SUPPORT_CONSENT_APPROVE", "SUPPORT_TICKET", ticketId, explanation,
                SupportTicketStatus.REQUESTED.name(), SupportTicketStatus.APPROVED.name(), now));
        return converter.ticket(ticket);
    }

    @Transactional
    public Ticket revoke(long workspaceId, long ticketId, long userId, String reason) {
        var ticket = lock(workspaceId, ticketId, userId);
        String explanation = reason(reason, ticket.getStatus() == SupportTicketStatus.REQUESTED
                ? "고객 기술 지원 요청 거절" : "고객 기술 지원 승인 철회");
        if (ticket.getStatus() == SupportTicketStatus.COMPLETED) throw new ApiException(SupportErrorCode.CONFLICT);
        if (ticket.getStatus() == SupportTicketStatus.CANCELLED) return converter.ticket(ticket);
        String before = ticket.getStatus().name();
        var now = now();
        ticket.setStatus(SupportTicketStatus.CANCELLED);
        ticket.setUpdatedAt(now);
        sessions.endActiveByTicketId(ticketId, now);
        audit.save(new AdminAuditEntity(userId, "SUPPORT_CONSENT_REVOKE", "SUPPORT_TICKET", ticketId, explanation,
                before, SupportTicketStatus.CANCELLED.name(), now));
        return converter.ticket(ticket);
    }

    private SupportTicketEntity lock(long workspaceId, long ticketId, long userId) {
        if (workspaceId <= 0 || ticketId <= 0 || userId <= 0) throw new ApiException(SupportErrorCode.INVALID_REQUEST);
        var user = users.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(SupportErrorCode.ACCESS_DENIED));
        if (user.getStatus() != UserStatus.REGISTERED) throw new ApiException(SupportErrorCode.ACCESS_DENIED);
        var workspace = workspaces.findByIdForUpdate(workspaceId).orElseThrow(() -> new ApiException(SupportErrorCode.NOT_FOUND));
        owner(workspace, userId);
        var ticket = tickets.findByIdForUpdate(ticketId).orElseThrow(() -> new ApiException(SupportErrorCode.NOT_FOUND));
        if (!Objects.equals(ticket.getWorkspaceId(), workspaceId) || !Objects.equals(ticket.getCustomerUserId(), userId)) {
            throw new ApiException(SupportErrorCode.NOT_FOUND);
        }
        return ticket;
    }

    private void owner(WorkspaceEntity workspace, long userId) {
        if (workspace.getUser() == null || !Objects.equals(workspace.getUser().getId(), userId)) {
            throw new ApiException(SupportErrorCode.ACCESS_DENIED);
        }
    }
    private String reason(String value, String defaultReason) {
        if (value != null && value.length() > 500) throw new ApiException(SupportErrorCode.INVALID_REQUEST);
        return value == null || value.isBlank() ? defaultReason : value.strip();
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Seoul")); }
}
