package com.orinan.db.support;

import com.orinan.db.support.enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "support_tickets", indexes = {
        @Index(name = "idx_support_ticket_customer", columnList = "customer_user_id,id"),
        @Index(name = "idx_support_ticket_workspace", columnList = "workspace_id,id"),
        @Index(name = "idx_support_ticket_status", columnList = "status,id")})
public class SupportTicketEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, updatable = false) private Long workspaceId;
    @Column(nullable = false, updatable = false) private Long customerUserId;
    @Setter private Long assignedAdminId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false)
    private SupportRequestSource requestSource = SupportRequestSource.ADMIN;
    @Column(length = 80, updatable = false) private String termsVersion;
    @Column(columnDefinition = "TEXT", updatable = false) private String termsSnapshot;
    @Column(nullable = false, length = 150, updatable = false) private String title;
    @Column(nullable = false, length = 1000, updatable = false) private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false) private SupportAccessMode accessMode;
    @Column(nullable = false, updatable = false) private long amountKrw;
    @Setter @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private SupportPaymentStatus paymentStatus;
    @Setter @Column(length = 200) private String paymentReference;
    @Setter private LocalDateTime paymentRecordedAt;
    @Setter private Long paymentRecordedBy;
    @Setter @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private SupportTicketStatus status;
    @Setter private LocalDateTime approvedAt;
    @Setter private LocalDateTime approvalExpiresAt;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    @Setter @Column(nullable = false) private LocalDateTime updatedAt;

    public SupportTicketEntity(long workspaceId, long customerUserId, long assignedAdminId, String title,
            String description, SupportAccessMode accessMode, long amountKrw, LocalDateTime now) {
        this.workspaceId = workspaceId; this.customerUserId = customerUserId; this.assignedAdminId = assignedAdminId;
        this.title = title; this.description = description; this.accessMode = accessMode; this.amountKrw = amountKrw;
        this.paymentStatus = SupportPaymentStatus.UNPAID; this.status = SupportTicketStatus.REQUESTED;
        this.createdAt = now; this.updatedAt = now;
    }

    public static SupportTicketEntity customerRequest(long workspaceId, long customerUserId, String title,
            String description, SupportAccessMode accessMode, long amountKrw, String termsVersion,
            String termsSnapshot, LocalDateTime now) {
        var ticket = new SupportTicketEntity(workspaceId, customerUserId, 0, title, description, accessMode, amountKrw, now);
        ticket.assignedAdminId = null;
        ticket.requestSource = SupportRequestSource.CUSTOMER;
        ticket.termsVersion = termsVersion;
        ticket.termsSnapshot = termsSnapshot;
        ticket.status = SupportTicketStatus.APPROVED;
        ticket.approvedAt = now;
        ticket.approvalExpiresAt = now.plusDays(7);
        return ticket;
    }
}
