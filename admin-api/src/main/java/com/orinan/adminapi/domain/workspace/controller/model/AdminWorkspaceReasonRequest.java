package com.orinan.adminapi.domain.workspace.controller.model;

import jakarta.validation.constraints.Size;

public record AdminWorkspaceReasonRequest(@Size(max = 500) String reason) {}
