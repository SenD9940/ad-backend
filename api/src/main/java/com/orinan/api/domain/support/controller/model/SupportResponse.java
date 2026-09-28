package com.orinan.api.domain.support.controller.model;

import java.time.LocalDateTime;
import java.util.List;

public final class SupportResponse {
    private SupportResponse() {}
    public record Ticket(long id, long workspaceId, long customerUserId, Long assignedAdminId, String title,
                         String description, String accessMode, long amountKrw, String paymentStatus, String status,
                         LocalDateTime approvedAt, LocalDateTime approvalExpiresAt,
                         LocalDateTime createdAt, LocalDateTime updatedAt,
                         String requestSource, String termsVersion, String termsSnapshot) {}
    public record Offer(boolean enabled, long amountKrw, String termsVersion, String termsText) {}
    public record Page<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
        public static <T> Page<T> of(List<T> items, int page, int size, long total) {
            return new Page<>(List.copyOf(items), page, size, total, (int) ((total + size - 1) / size));
        }
    }
    public record Session(long sessionId, long ticketId, long workspaceId, long customerUserId,
                          String accessMode, LocalDateTime expiresAt) {}
    public record Ended(long sessionId, boolean ended) {}
}
