package com.orinan.adminapi.domain.aistudio.controller.model;

import com.orinan.db.aistudio.enums.AiStudioKind;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;

/** Null fields preserve existing values; an empty preview image key removes a draft's image. */
public record AdminAiTemplateUpdateRequest(
        AiStudioKind kind,
        @Size(max = 150) String title,
        @Size(max = 2000) String description,
        @Positive Long categoryId,
        @Size(max = 6000) String prompt,
        @Size(max = 512) String previewImageKey,
        Boolean published) {
    public boolean empty() {
        return kind == null && title == null && description == null && categoryId == null && prompt == null
                && previewImageKey == null && published == null;
    }
}
