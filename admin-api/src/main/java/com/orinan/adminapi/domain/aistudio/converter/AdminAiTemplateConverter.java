package com.orinan.adminapi.domain.aistudio.converter;

import com.orinan.adminapi.annotation.Converter;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.adminapi.domain.aistudio.controller.model.AdminAiTemplateResponse;
import com.orinan.adminapi.domain.aistudio.service.AdminAiImageStorage;
import com.orinan.db.aistudio.template.AiTemplateEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;

@Converter
@RequiredArgsConstructor
public class AdminAiTemplateConverter {
    private final AdminAiImageStorage storage;

    public AdminAiTemplateResponse toResponse(AiTemplateEntity template) {
        var preview = storage.preview(template.getPreviewImageKey());
        return new AdminAiTemplateResponse(template.getId(), template.getKind(), template.getTitle(), template.getDescription(),
                template.getCategory().getId(), template.getCategory().getName(), template.getPrompt(), template.getPreviewImageKey(), preview == null ? null : preview.url(),
                preview == null ? null : preview.expiresAt(), template.isPublished(), template.getCreatedBy(),
                template.getCreatedAt(), template.getUpdatedAt());
    }
    public PageResponse<AdminAiTemplateResponse> toPage(Page<AiTemplateEntity> page) {
        return PageResponse.of(page.getContent().stream().map(this::toResponse).toList(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
