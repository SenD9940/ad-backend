package com.orinan.api.domain.support.controller.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.orinan.db.support.enums.SupportAccessMode;
import jakarta.validation.constraints.*;

/** Customer identity, administrator, fee and payment state are determined by the server. */
public record SupportCreateRequest(
        @NotBlank @Size(max = 150) String title,
        @NotBlank @Size(max = 1000) String description,
        @NotNull SupportAccessMode accessMode,
        @NotBlank @Size(max = 80) String termsVersion,
        @NotNull @AssertTrue Boolean acceptedTerms) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("지원 신청에 허용되지 않는 항목입니다.");
    }
}
