package com.orinan.api.domain.aistudio.controller.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public record AiStudioGenerationRequest(
        @NotNull @Positive Long templateId,
        @NotBlank @Size(max = 150) String productName,
        @NotBlank @Size(max = 3000) String productDescription,
        @Size(max = 500) String audience,
        @Size(max = 1000) String instructions,
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String idempotencyKey,
        @Size(max = 512) String productImageKey) {
    @JsonAnySetter public void rejectUnknownField(String field, Object value) { throw new IllegalArgumentException("허용되지 않는 생성 항목입니다."); }
}
