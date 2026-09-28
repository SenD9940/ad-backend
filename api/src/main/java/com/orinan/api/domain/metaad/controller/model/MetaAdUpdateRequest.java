package com.orinan.api.domain.metaad.controller.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

public record MetaAdUpdateRequest(
        @Size(max = 255) String name,
        Status status
) {
    @JsonIgnore
    @AssertTrue(message = "수정할 이름 또는 상태를 입력해 주세요. 이름은 공백일 수 없습니다.")
    public boolean isValidUpdate() {
        return (name != null || status != null) && (name == null || !name.isBlank());
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("지원하지 않는 광고 수정 항목입니다.");
    }

    public enum Status {
        ACTIVE, PAUSED;

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Status from(String value) {
            // Enum ordinals such as 0 must never implicitly activate an ad.
            return Status.valueOf(value);
        }
    }
}
