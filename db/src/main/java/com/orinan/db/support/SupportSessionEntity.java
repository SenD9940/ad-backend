package com.orinan.db.support;

import com.orinan.db.support.enums.SupportAccessMode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "support_sessions", indexes = {
        @Index(name = "idx_support_session_ticket", columnList = "ticket_id,id"),
        @Index(name = "idx_support_session_customer", columnList = "customer_user_id,ended_at")},
        uniqueConstraints = @UniqueConstraint(name = "uk_support_session_token", columnNames = "token_hash"))
public class SupportSessionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, updatable = false) private Long ticketId;
    @Column(nullable = false, updatable = false) private Long adminUserId;
    @Column(nullable = false, updatable = false) private Long customerUserId;
    @Column(nullable = false, updatable = false) private Long workspaceId;
    @Column(nullable = false, length = 64, updatable = false) private String tokenHash;
    @Column(nullable = false, updatable = false) private long adminAuthVersion;
    @Column(nullable = false, updatable = false) private long customerAuthVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false) private SupportAccessMode accessMode;
    @Column(nullable = false, updatable = false) private LocalDateTime startedAt;
    @Column(nullable = false, updatable = false) private LocalDateTime expiresAt;
    @Setter private LocalDateTime endedAt;

    public SupportSessionEntity(long ticketId, long adminUserId, long customerUserId, long workspaceId,
            String tokenHash, long adminAuthVersion, long customerAuthVersion, SupportAccessMode accessMode,
            LocalDateTime startedAt, LocalDateTime expiresAt) {
        this.ticketId = ticketId; this.adminUserId = adminUserId; this.customerUserId = customerUserId;
        this.workspaceId = workspaceId; this.tokenHash = tokenHash; this.adminAuthVersion = adminAuthVersion;
        this.customerAuthVersion = customerAuthVersion; this.accessMode = accessMode;
        this.startedAt = startedAt; this.expiresAt = expiresAt;
    }
}
