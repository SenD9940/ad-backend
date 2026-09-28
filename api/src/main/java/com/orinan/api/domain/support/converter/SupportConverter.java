package com.orinan.api.domain.support.converter;

import com.orinan.api.domain.support.controller.model.SupportResponse.Ticket;
import com.orinan.db.support.SupportTicketEntity;
import org.springframework.stereotype.Component;

@Component
public class SupportConverter {
    public Ticket ticket(SupportTicketEntity value) {
        return new Ticket(value.getId(), value.getWorkspaceId(), value.getCustomerUserId(), value.getAssignedAdminId(),
                value.getTitle(), value.getDescription(), value.getAccessMode().name(), value.getAmountKrw(),
                value.getPaymentStatus().name(), value.getStatus().name(), value.getApprovedAt(),
                value.getApprovalExpiresAt(), value.getCreatedAt(), value.getUpdatedAt(), value.getRequestSource().name(),
                value.getTermsVersion(), value.getTermsSnapshot());
    }
}
