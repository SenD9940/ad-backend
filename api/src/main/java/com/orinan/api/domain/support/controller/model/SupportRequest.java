package com.orinan.api.domain.support.controller.model;

import jakarta.validation.constraints.Size;

public record SupportRequest(@Size(max = 500) String reason) {}
