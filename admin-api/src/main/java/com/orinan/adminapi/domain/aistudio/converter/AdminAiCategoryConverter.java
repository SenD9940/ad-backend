package com.orinan.adminapi.domain.aistudio.converter;

import com.orinan.adminapi.annotation.Converter;
import com.orinan.adminapi.domain.aistudio.controller.model.AdminAiCategoryResponse;
import com.orinan.db.aistudio.category.AiStudioCategoryEntity;

@Converter
public class AdminAiCategoryConverter {
    public AdminAiCategoryResponse toResponse(AiStudioCategoryEntity category) {
        return new AdminAiCategoryResponse(category.getId(), category.getName());
    }
}
