package com.orinan.adminapi.domain.support.controller.model;

import com.orinan.db.support.enums.*;
import java.time.LocalDateTime;

public record AdminSupportTicketResponse(long id, long workspaceId, long customerUserId, Long assignedAdminId,
        String title, String description, SupportAccessMode accessMode, long amountKrw,
        SupportPaymentStatus paymentStatus, String paymentReference, LocalDateTime paymentRecordedAt,
        Long paymentRecordedBy, SupportTicketStatus status, LocalDateTime approvedAt, LocalDateTime approvalExpiresAt,
        LocalDateTime createdAt, LocalDateTime updatedAt, SupportRequestSource requestSource,
        String termsVersion, String termsSnapshot) {}
