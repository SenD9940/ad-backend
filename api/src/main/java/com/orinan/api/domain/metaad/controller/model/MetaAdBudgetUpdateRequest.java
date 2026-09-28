package com.orinan.api.domain.metaad.controller.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record MetaAdBudgetUpdateRequest(
        @Size(max = 255) String name,
        MetaAdUpdateRequest.Status status,
        @Positive @JsonDeserialize(using = DailyBudgetDeserializer.class) Long dailyBudget
) {
    @JsonIgnore
    @AssertTrue(message = "수정할 이름, 상태 또는 일 예산을 입력해 주세요. 이름은 공백일 수 없습니다.")
    public boolean isValidUpdate() {
        return (name != null || status != null || dailyBudget != null) && (name == null || !name.isBlank());
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("지원하지 않는 광고 수정 항목입니다.");
    }

    public static class DailyBudgetDeserializer extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            // The default Long deserializer truncates floating-point amounts.
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Long.class, "일 예산은 JSON 정수로 입력해 주세요.");
            }
            return parser.getLongValue();
        }
    }
}
