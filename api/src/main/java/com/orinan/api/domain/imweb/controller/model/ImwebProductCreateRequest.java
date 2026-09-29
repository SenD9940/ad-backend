package com.orinan.api.domain.imweb.controller.model;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ImwebProductCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 100) String categoryCode,
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal salePrice,
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal originalPrice,
        @NotNull @Min(1) @Max(999999999) Long stockQuantity,
        @Size(max = 50000) String detailContent,
        @Positive Long studioOutputId
) {}
