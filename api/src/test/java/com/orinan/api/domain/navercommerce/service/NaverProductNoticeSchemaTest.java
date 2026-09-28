package com.orinan.api.domain.navercommerce.service;

import com.orinan.api.common.exception.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NaverProductNoticeSchemaTest {
    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private final NaverProductNoticeSchema schema = new NaverProductNoticeSchema(mapper);

    @Test void exposesOnlyProviderPermittedPhysicalTypesWithoutInventingUnknownSchemas() {
        assertThat(schema.available(Set.of("WEAR", "GENERAL_FOOD", "ETC", "GIFT_CARD", "RENTAL_HA", "FUTURE")))
                .extracting(NaverProductNoticeSchema.NoticeType::type)
                .containsExactly("WEAR", "GENERAL_FOOD", "ETC");
        assertThat(schema.available(Set.of())).isEmpty();
        assertThat(schema.available(null)).isEmpty();
        assertThatThrownBy(() -> schema.payload("GIFT_CARD", Map.of())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> schema.payload("FUTURE", Map.of())).isInstanceOf(ApiException.class);
    }

    @Test void convertsOnlyAllowlistedFieldsToTheExactProviderShapeUnderSnakeCaseMapper() {
        var values = required("WEAR");
        values.put("material", " 면 100% ");
        var result = mapper.valueToTree(schema.payload("WEAR", values));
        assertThat(result.path("productInfoProvidedNoticeType").asText()).isEqualTo("WEAR");
        assertThat(result.path("wear").path("material").asText()).isEqualTo("면 100%");
        assertThat(result.path("wear").path("packDateText").asText()).isEqualTo("실제 상품 정보");
        assertThat(result.path("wear").has("pack_date_text")).isFalse();
        assertThat(result.path("wear").has("packDate")).isFalse();
        assertThat(values.get("material")).isEqualTo(" 면 100% ");
    }

    @Test void preservesNonstandardProviderGroupCapitalization() {
        for (var entry : Map.of("MICROELECTRONICS", "microElectronics", "CELLPHONE", "cellPhone").entrySet()) {
            assertThat(schema.payload(entry.getKey(), required(entry.getKey())))
                    .containsKey(entry.getValue());
        }
    }

    @Test void datesUseRequiredTextAlternativesAndDoNotExposeDeprecatedFoodExpiryFields() {
        var food = schema.available(Set.of("FOOD")).get(0);
        assertThat(food.fields()).extracting(NaverProductNoticeSchema.NoticeField::key)
                .contains("pack_date_text", "consumption_date_text")
                .doesNotContain("pack_date", "consumption_date", "expiration_date", "expiration_date_text");
        var fields = required("FOOD");
        fields.remove("consumption_date_text");
        assertThatThrownBy(() -> schema.payload("FOOD", fields)).isInstanceOf(ApiException.class)
                .hasMessageContaining("소비기한");
    }

    @Test void validatesRequiredActualFactsAndAllowsOptionalFieldsToBeOmitted() {
        var fields = required("ETC");
        fields.put("certificate_details", " ");
        fields.put("customer_service_phone_number", null);
        var payload = mapper.valueToTree(schema.payload("ETC", fields)).path("etc");
        assertThat(payload.has("certificateDetails")).isFalse();
        assertThat(payload.has("customerServicePhoneNumber")).isFalse();
        assertThat(payload.path("afterServiceDirector").asText()).isEqualTo("실제 상품 정보");
        fields.remove("after_service_director");
        assertThatThrownBy(() -> schema.payload("ETC", fields)).isInstanceOf(ApiException.class)
                .hasMessageContaining("A/S");
        assertThatThrownBy(() -> schema.payload("ETC", null)).isInstanceOf(ApiException.class);
    }

    @Test void rejectsUnknownFieldsIncludingRawProviderCamelCaseAndExemptionClaims() {
        for (var unknown : new String[]{"afterServiceDirector", "certification_target_exclude_content", "__proto__"}) {
            var fields = required("ETC");
            fields.put(unknown, "임의 값");
            assertThatThrownBy(() -> schema.payload("ETC", fields)).isInstanceOf(ApiException.class);
        }
        var nullKey = new HashMap<>(required("ETC"));
        nullKey.put(null, "임의 값");
        assertThatThrownBy(() -> schema.payload("ETC", nullKey)).isInstanceOf(ApiException.class);
    }

    @Test void validatesExactTextLimitsTypesAndPolicyOptions() {
        var fields = required("ETC");
        fields.put("item_name", "가".repeat(50));
        schema.payload("ETC", fields);
        fields.put("item_name", "가".repeat(51));
        assertThatThrownBy(() -> schema.payload("ETC", fields)).isInstanceOf(ApiException.class)
                .hasMessageContaining("50자");
        fields.put("item_name", 123);
        assertThatThrownBy(() -> schema.payload("ETC", fields)).isInstanceOf(ApiException.class)
                .hasMessageContaining("문자열");
        fields.put("item_name", "상품");
        fields.put("return_cost_reason", "임의 약관");
        assertThatThrownBy(() -> schema.payload("ETC", fields)).isInstanceOf(ApiException.class)
                .hasMessageContaining("선택값");
        fields.put("return_cost_reason", "1");
        schema.payload("ETC", fields);
    }

    @Test void requiresExplicitBooleansAndPreservesFalseWithoutStringCoercion() {
        var fields = required("GENERAL_FOOD");
        var result = mapper.valueToTree(schema.payload("GENERAL_FOOD", fields)).path("generalFood");
        assertThat(result.path("geneticallyModified").isBoolean()).isTrue();
        assertThat(result.path("geneticallyModified").asBoolean()).isFalse();
        assertThat(result.path("importDeclarationCheck").asBoolean()).isFalse();
        fields.put("genetically_modified", "false");
        assertThatThrownBy(() -> schema.payload("GENERAL_FOOD", fields)).isInstanceOf(ApiException.class);
        fields.remove("genetically_modified");
        assertThatThrownBy(() -> schema.payload("GENERAL_FOOD", fields)).isInstanceOf(ApiException.class);
    }

    private Map<String, Object> required(String type) {
        var fields = new LinkedHashMap<String, Object>();
        for (var field : schema.available(Set.of(type)).get(0).fields()) {
            if (!field.required()) continue;
            fields.put(field.key(), switch (field.type()) {
                case "BOOLEAN" -> false;
                case "NUMBER" -> 1;
                default -> field.options().isEmpty() ? "실제 상품 정보" : field.options().get(0).value();
            });
        }
        return fields;
    }
}
