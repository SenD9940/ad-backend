package com.orinan.db.aistudio.output;

import com.orinan.db.aistudio.enums.AiStudioKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "ai_studio_outputs", uniqueConstraints = @UniqueConstraint(name = "uk_ai_output_request", columnNames = {"workspace_id", "created_by", "idempotency_key"}),
        indexes = @Index(name = "idx_ai_output_workspace", columnList = "workspace_id,status,kind,id"))
public class AiStudioOutputEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, updatable = false) private Long workspaceId;
    @Column(nullable = false, updatable = false) private Long createdBy;
    @Column(nullable = false, updatable = false) private Long templateId;
    @Column(nullable = false, length = 36, updatable = false) private String idempotencyKey;
    @Column(nullable = false, length = 64, updatable = false) private String requestHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false) private AiStudioKind kind;
    @Column(nullable = false, length = 150, updatable = false) private String title;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private AiStudioOutputStatus status;
    @Column(length = 512) private String imageKey;
    @Column(length = 30) private String imageContentType;
    @Column(columnDefinition = "TEXT") private String detailHtml;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(nullable = false) private LocalDateTime updatedAt;

    public AiStudioOutputEntity(long workspaceId, long createdBy, long templateId, String idempotencyKey,
            String requestHash, AiStudioKind kind, String title, LocalDateTime now) {
        this.workspaceId = workspaceId; this.createdBy = createdBy; this.templateId = templateId;
        this.idempotencyKey = idempotencyKey; this.requestHash = requestHash; this.kind = kind; this.title = title;
        this.status = AiStudioOutputStatus.PENDING; this.createdAt = now; this.updatedAt = now;
    }
    public void complete(String imageKey, String imageContentType, String detailHtml, LocalDateTime now) {
        if (status != AiStudioOutputStatus.PENDING) throw new IllegalStateException("생성 결과가 이미 확정되었습니다.");
        this.imageKey = imageKey; this.imageContentType = imageContentType; this.detailHtml = detailHtml;
        this.status = AiStudioOutputStatus.SUCCEEDED; this.updatedAt = now;
    }
    public void fail(LocalDateTime now) {
        if (status == AiStudioOutputStatus.PENDING) { this.status = AiStudioOutputStatus.FAILED; this.updatedAt = now; }
    }
}
