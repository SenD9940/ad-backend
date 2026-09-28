package com.orinan.adminapi.domain.support.controller.model;

import jakarta.validation.constraints.*;

public record AdminSupportReasonRequest(@Size(max = 500) String reason) {}
