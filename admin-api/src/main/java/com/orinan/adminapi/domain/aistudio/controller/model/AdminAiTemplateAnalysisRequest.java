package com.orinan.adminapi.domain.aistudio.controller.model;

import com.orinan.db.aistudio.enums.AiStudioKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminAiTemplateAnalysisRequest(@NotBlank @Size(max = 512) String imageKey, @NotNull AiStudioKind kind) { }
