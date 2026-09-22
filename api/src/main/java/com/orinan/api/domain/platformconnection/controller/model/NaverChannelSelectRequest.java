package com.orinan.api.domain.platformconnection.controller.model;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record NaverChannelSelectRequest(
        @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> channelNos
) {
}
