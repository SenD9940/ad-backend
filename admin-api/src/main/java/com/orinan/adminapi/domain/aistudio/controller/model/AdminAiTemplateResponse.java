package com.orinan.adminapi.domain.aistudio.controller.model;

import com.orinan.db.aistudio.enums.AiStudioKind;
import java.time.Instant;
import java.time.LocalDateTime;

public record AdminAiTemplateResponse(long id, AiStudioKind kind, String title, String description, long categoryId, String categoryName,
        String prompt, String previewImageKey, String previewImageUrl, Instant previewImageExpiresAt,
        boolean published, long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) { }
