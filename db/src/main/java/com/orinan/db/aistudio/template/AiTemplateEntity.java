package com.orinan.db.aistudio.template;

import com.orinan.db.aistudio.enums.AiStudioKind;
import com.orinan.db.aistudio.category.AiStudioCategoryEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_templates", indexes = @Index(name = "idx_ai_template_published_kind", columnList = "published,kind,id"))
@Getter
@NoArgsConstructor
public class AiTemplateEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Setter @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private AiStudioKind kind;
    @Setter @Column(nullable = false, length = 150) private String title;
    @Setter @Column(nullable = false, length = 2000) private String description;
    @Setter @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ai_template_category"))
    private AiStudioCategoryEntity category;
    @Setter @Column(nullable = false, length = 6000) private String prompt;
    @Setter @Column(length = 512) private String previewImageKey;
    @Setter @Column(nullable = false) private boolean published;
    @Column(nullable = false, updatable = false) private Long createdBy;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    @Setter @Column(nullable = false) private LocalDateTime updatedAt;
    @Version private long version;

    public AiTemplateEntity(AiStudioKind kind, String title, String description, AiStudioCategoryEntity category, String prompt,
                            String previewImageKey, boolean published, long createdBy, LocalDateTime now) {
        this.kind = kind; this.title = title; this.description = description; this.category = category;
        this.prompt = prompt; this.previewImageKey = previewImageKey; this.published = published;
        this.createdBy = createdBy; this.createdAt = now; this.updatedAt = now;
    }
}
