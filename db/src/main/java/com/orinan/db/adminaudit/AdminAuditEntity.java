package com.orinan.db.adminaudit;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "admin_audit_logs", indexes = {
        @Index(name = "idx_admin_audit_actor_id", columnList = "actor_user_id,id"),
        @Index(name = "idx_admin_audit_target", columnList = "target_type,target_id,id")})
@NoArgsConstructor
public class AdminAuditEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long actorUserId;
    @Column(nullable = false, length = 60) private String action;
    @Column(nullable = false, length = 40) private String targetType;
    private Long targetId;
    @Column(nullable = false, length = 500) private String reason;
    @Column(length = 1000) private String beforeValue;
    @Column(length = 1000) private String afterValue;
    @Column(nullable = false) private LocalDateTime createdAt;

    public AdminAuditEntity(long actor, String action, String type, Long target, String reason,
                            String before, String after, LocalDateTime at) {
        this.actorUserId = actor; this.action = action; this.targetType = type; this.targetId = target;
        this.reason = reason; this.beforeValue = before; this.afterValue = after; this.createdAt = at;
    }
}
