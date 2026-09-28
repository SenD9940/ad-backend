package com.orinan.api.domain.aistudio.service;

import com.orinan.db.aistudio.enums.AiStudioKind;

/** Authenticated, durable export; the HTML still contains the single STUDIO_IMAGE placeholder. */
public record AiStudioExport(AiStudioKind kind, String title, String detailHtml,
                             byte[] imageBytes, String imageContentType) {}
