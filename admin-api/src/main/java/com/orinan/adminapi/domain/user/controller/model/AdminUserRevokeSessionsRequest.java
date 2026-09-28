package com.orinan.adminapi.domain.user.controller.model;

import jakarta.validation.constraints.Size;

public record AdminUserRevokeSessionsRequest(@Size(max = 500) String reason) {}
