package com.orinan.adminapi.domain.support.converter;

import com.orinan.adminapi.annotation.Converter;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.adminapi.domain.support.controller.model.*;
import com.orinan.db.support.*;
import org.springframework.data.domain.Page;
import java.time.LocalDateTime;

@Converter
public class AdminSupportConverter {
    public AdminSupportTicketResponse ticket(SupportTicketEntity ticket) {
        return new AdminSupportTicketResponse(ticket.getId(), ticket.getWorkspaceId(), ticket.getCustomerUserId(),
                ticket.getAssignedAdminId(), ticket.getTitle(), ticket.getDescription(), ticket.getAccessMode(),
                ticket.getAmountKrw(), ticket.getPaymentStatus(), ticket.getPaymentReference(), ticket.getPaymentRecordedAt(),
                ticket.getPaymentRecordedBy(), ticket.getStatus(), ticket.getApprovedAt(), ticket.getApprovalExpiresAt(),
                ticket.getCreatedAt(), ticket.getUpdatedAt(), ticket.getRequestSource(),
                ticket.getTermsVersion(), ticket.getTermsSnapshot());
    }
    public PageResponse<AdminSupportTicketResponse> tickets(Page<SupportTicketEntity> page) {
        return PageResponse.of(page.getContent().stream().map(this::ticket).toList(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
    public AdminSupportSessionResponse session(SupportSessionEntity session, LocalDateTime now) {
        return new AdminSupportSessionResponse(session.getId(), session.getTicketId(), session.getAdminUserId(),
                session.getCustomerUserId(), session.getWorkspaceId(), session.getAccessMode(), session.getStartedAt(),
                session.getExpiresAt(), session.getEndedAt(), session.getEndedAt() == null && session.getExpiresAt().isAfter(now));
    }
    public AdminSupportSessionTokenResponse token(SupportSessionEntity session, String token) {
        return new AdminSupportSessionTokenResponse(session.getId(), session.getTicketId(), session.getWorkspaceId(),
                session.getCustomerUserId(), session.getAccessMode(), token, session.getExpiresAt());
    }
    public PageResponse<AdminSupportSessionResponse> sessions(Page<SupportSessionEntity> page, LocalDateTime now) {
        return PageResponse.of(page.getContent().stream().map(item -> session(item, now)).toList(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
    public PageResponse<AdminSupportActionResponse> actions(Page<SupportActionEntity> page) {
        return PageResponse.of(page.getContent().stream().map(item -> new AdminSupportActionResponse(item.getId(),
                item.getSessionId(), item.getTicketId(), item.getActorUserId(), item.getCustomerUserId(), item.getWorkspaceId(),
                item.getHttpMethod(), item.getPath(), item.getStatusCode(), item.getStartedAt(), item.getCompletedAt())).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
