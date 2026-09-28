package com.orinan.adminapi.domain.workspace.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminWorkspaceRenameRequest(@NotBlank @Size(max = 100) String name, @Size(max = 500) String reason) {}
