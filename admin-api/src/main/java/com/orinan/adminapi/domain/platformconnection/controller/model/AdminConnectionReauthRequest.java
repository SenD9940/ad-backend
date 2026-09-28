package com.orinan.adminapi.domain.platformconnection.controller.model;

import jakarta.validation.constraints.Size;

public record AdminConnectionReauthRequest(@Size(max = 500) String reason) {}
