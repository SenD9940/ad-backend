package com.orinan.adminapi.domain.aistudio.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminAiCategoryRequest(@NotBlank @Size(max = 80) String name) { }
