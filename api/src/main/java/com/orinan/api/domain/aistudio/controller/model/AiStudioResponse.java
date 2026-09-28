package com.orinan.api.domain.aistudio.controller.model;

import com.orinan.db.aistudio.enums.AiStudioKind;
import com.orinan.db.aistudio.output.AiStudioOutputStatus;
import java.time.LocalDateTime;
import java.util.List;

public final class AiStudioResponse {
    private AiStudioResponse() {}
    public record Capabilities(boolean enabled, String disabledReason) {}
    public record Category(long id, String name) {}
    public record Template(long id, AiStudioKind kind, String title, String description, long categoryId, String categoryName,
                           String imageUrl, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record Output(long id, long workspaceId, long templateId, AiStudioKind kind, String title,
                         String imageUrl, String detailHtml, AiStudioOutputStatus status, LocalDateTime createdAt) {}
    public record Page<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
        public static <T> Page<T> from(org.springframework.data.domain.Page<?> source, List<T> items) {
            return new Page<>(List.copyOf(items), source.getNumber(), source.getSize(), source.getTotalElements(), source.getTotalPages());
        }
    }
}
