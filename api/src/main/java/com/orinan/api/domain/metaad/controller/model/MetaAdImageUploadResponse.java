package com.orinan.api.domain.metaad.controller.model;

import java.time.Instant;

public record MetaAdImageUploadResponse(
        String imageKey,
        String imageUrl,
        Instant expiresAt,
        String contentType,
        long size
) {
}
