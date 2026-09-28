package com.orinan.db.aistudio.category;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_studio_categories", uniqueConstraints = @UniqueConstraint(name = "uk_ai_studio_category_name", columnNames = "name"))
@Getter
@NoArgsConstructor
public class AiStudioCategoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 80) private String name;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(nullable = false) private LocalDateTime updatedAt;

    public AiStudioCategoryEntity(String name, LocalDateTime now) {
        this.name = name; this.createdAt = now; this.updatedAt = now;
    }
    public void rename(String name, LocalDateTime now) { this.name = name; this.updatedAt = now; }
}
