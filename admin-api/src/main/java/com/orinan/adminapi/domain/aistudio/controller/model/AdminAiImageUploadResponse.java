package com.orinan.adminapi.domain.aistudio.controller.model;

import java.time.Instant;

public record AdminAiImageUploadResponse(String imageKey, String imageUrl, Instant expiresAt) { }
