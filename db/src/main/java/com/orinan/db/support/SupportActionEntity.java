package com.orinan.db.support;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

/** Metadata only: request bodies, query strings, credentials and customer payloads must never be stored. */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "support_actions", indexes = {
        @Index(name = "idx_support_action_ticket", columnList = "ticket_id,id"),
        @Index(name = "idx_support_action_session", columnList = "session_id,id")})
public class SupportActionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, updatable = false) private Long sessionId;
    @Column(nullable = false, updatable = false) private Long ticketId;
    @Column(nullable = false, updatable = false) private Long actorUserId;
    @Column(nullable = false, updatable = false) private Long customerUserId;
    @Column(nullable = false, updatable = false) private Long workspaceId;
    @Column(nullable = false, length = 10, updatable = false) private String httpMethod;
    @Column(nullable = false, length = 500, updatable = false) private String path;
    @Setter private Integer statusCode;
    @Column(nullable = false, updatable = false) private LocalDateTime startedAt;
    @Setter private LocalDateTime completedAt;

    public SupportActionEntity(long sessionId, long ticketId, long actorUserId, long customerUserId,
            long workspaceId, String httpMethod, String path, LocalDateTime startedAt) {
        this.sessionId = sessionId; this.ticketId = ticketId; this.actorUserId = actorUserId;
        this.customerUserId = customerUserId; this.workspaceId = workspaceId;
        this.httpMethod = httpMethod; this.path = path; this.startedAt = startedAt;
    }
}
