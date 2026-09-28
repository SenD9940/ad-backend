package com.orinan.db.aistudio.template;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_template_images", uniqueConstraints = @UniqueConstraint(name = "uk_ai_template_image_key", columnNames = "image_key"))
@Getter
@NoArgsConstructor
public class AiTemplateImageEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 512, updatable = false) private String imageKey;
    @Column(nullable = false, length = 20, updatable = false) private String contentType;
    @Column(nullable = false, updatable = false) private long byteSize;
    @Column(nullable = false, updatable = false) private int width;
    @Column(nullable = false, updatable = false) private int height;
    @Column(nullable = false, updatable = false) private long uploadedBy;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;

    public AiTemplateImageEntity(String imageKey, String contentType, long byteSize, int width, int height,
                                 long uploadedBy, LocalDateTime createdAt) {
        this.imageKey = imageKey; this.contentType = contentType; this.byteSize = byteSize;
        this.width = width; this.height = height; this.uploadedBy = uploadedBy; this.createdAt = createdAt;
    }
}
