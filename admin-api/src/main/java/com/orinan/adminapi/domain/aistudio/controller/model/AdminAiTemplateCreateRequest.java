package com.orinan.adminapi.domain.aistudio.controller.model;

import com.orinan.db.aistudio.enums.AiStudioKind;
import jakarta.validation.constraints.*;

public record AdminAiTemplateCreateRequest(
        @NotNull AiStudioKind kind,
        @Size(max = 150) String title,
        @Size(max = 2000) String description,
        @NotNull @Positive Long categoryId,
        @Size(max = 6000) String prompt,
        @Size(max = 512) String previewImageKey,
        boolean published) { }
