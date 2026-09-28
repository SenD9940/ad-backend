package com.orinan.api.domain.metaad.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record MetaAdPageSaveRequest(
        @NotBlank @Pattern(regexp = "[0-9]{1,32}") String externalId
) {
}
