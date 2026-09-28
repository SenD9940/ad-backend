package com.orinan.db.aistudio.template;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AiTemplateImageRepository extends JpaRepository<AiTemplateImageEntity, Long> {
    Optional<AiTemplateImageEntity> findByImageKey(String imageKey);
}
