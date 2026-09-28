package com.orinan.api.domain.navercommerce.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Physical-product notice fields from Naver Commerce API 2.89.0.
 * Provider lookups select the types permitted for a category; this schema supplies their write shape.
 */
@Component
public class NaverProductNoticeSchema {
    private final Map<String, Definition> definitions;

    public NaverProductNoticeSchema(JsonMapper mapper) {
        try (var input = new ClassPathResource("naver-product-notices.json").getInputStream()) {
            var document = mapper.readTree(input);
            var common = fields(document.path("commonFields"));
            var loaded = new LinkedHashMap<String, Definition>();
            for (var node : document.path("noticeTypes")) {
                var fields = new ArrayList<>(common);
                fields.addAll(fields(node.path("fields")));
                var type = new NoticeType(node.path("type").asText(), node.path("name").asText(),
                        fields.stream().map(FieldDefinition::field).toList());
                var byKey = new LinkedHashMap<String, FieldDefinition>();
                for (var field : fields) {
                    if (byKey.put(field.field().key(), field) != null) {
                        throw new IllegalStateException("Duplicate Naver product notice field");
                    }
                }
                if (loaded.put(type.type(), new Definition(type, node.path("upstreamProperty").asText(),
                        Collections.unmodifiableMap(byKey))) != null) {
                    throw new IllegalStateException("Duplicate Naver product notice type");
                }
            }
            if (loaded.isEmpty()) throw new IllegalStateException("Empty Naver product notice schema");
            definitions = Collections.unmodifiableMap(loaded);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not load Naver product notice schema", exception);
        }
    }

    /** Only the provider's category-specific types are available. Unknown future types are not guessed. */
    public List<NoticeType> available(Set<String> providerTypes) {
        if (providerTypes == null || providerTypes.isEmpty()) return List.of();
        return definitions.values().stream().map(Definition::type)
                .filter(type -> providerTypes.contains(type.type())).toList();
    }

    /** Validates user-entered fields and builds the exact camel-case object expected by Naver. */
    public Map<String, Object> payload(String type, Map<String, Object> fields) {
        var definition = type == null ? null : definitions.get(type);
        if (definition == null) throw invalid("지원하지 않는 상품정보제공고시 유형입니다.");
        if (fields == null) throw invalid("상품정보제공고시를 입력해 주세요.");
        if (fields.keySet().stream().anyMatch(key -> !definition.fields().containsKey(key))) {
            throw invalid("선택한 상품정보제공고시에 없는 항목이 포함되어 있습니다.");
        }

        var values = new LinkedHashMap<String, Object>();
        for (var fieldDefinition : definition.fields().values()) {
            var field = fieldDefinition.field();
            var value = fields.get(field.key());
            if (value == null || value instanceof String text && text.isBlank()) {
                if (field.required()) throw invalid(field.label() + " 항목을 입력해 주세요.");
                continue;
            }
            Object normalized = switch (field.type()) {
                case "TEXT" -> text(field, value);
                case "BOOLEAN" -> bool(field, value);
                case "NUMBER" -> number(field, value);
                default -> throw new IllegalStateException("Unsupported Naver product notice field type");
            };
            values.put(fieldDefinition.upstreamKey(), normalized);
        }
        return Map.of("productInfoProvidedNoticeType", type, definition.upstreamProperty(),
                Collections.unmodifiableMap(values));
    }

    private String text(NoticeField field, Object value) {
        if (!(value instanceof String supplied)) throw invalid(field.label() + " 항목은 문자열로 입력해 주세요.");
        var text = supplied.strip();
        if (field.maxLength() > 0 && text.codePointCount(0, text.length()) > field.maxLength()) {
            throw invalid(field.label() + " 항목은 " + field.maxLength() + "자 이하로 입력해 주세요.");
        }
        if (!field.options().isEmpty() && field.options().stream().noneMatch(option -> option.value().equals(text))) {
            throw invalid(field.label() + " 항목의 선택값이 올바르지 않습니다.");
        }
        return text;
    }

    private Boolean bool(NoticeField field, Object value) {
        if (!(value instanceof Boolean supplied)) throw invalid(field.label() + " 항목을 선택해 주세요.");
        return supplied;
    }

    private Long number(NoticeField field, Object value) {
        if (!(value instanceof Number supplied)) throw invalid(field.label() + " 항목은 정수로 입력해 주세요.");
        try {
            return new BigDecimal(supplied.toString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw invalid(field.label() + " 항목은 정수로 입력해 주세요.");
        }
    }

    private List<FieldDefinition> fields(JsonNode nodes) {
        if (!nodes.isArray()) throw new IllegalStateException("Invalid Naver product notice fields");
        var result = new ArrayList<FieldDefinition>();
        for (var node : nodes) {
            var options = new ArrayList<Choice>();
            for (var choice : node.path("options")) {
                options.add(new Choice(choice.path("value").asText(), choice.path("label").asText()));
            }
            var field = new NoticeField(node.path("key").asText(), node.path("label").asText(),
                    node.path("description").asText(), node.path("maxLength").asInt(),
                    node.path("required").asBoolean(), node.path("type").asText(), List.copyOf(options));
            result.add(new FieldDefinition(field, node.path("upstreamKey").asText()));
        }
        return List.copyOf(result);
    }

    private ApiException invalid(String message) {
        return new ApiException(ApiCode.BAD_REQUEST, message);
    }

    public record NoticeType(String type, String name, List<NoticeField> fields) {}
    public record NoticeField(String key, String label, String description, int maxLength,
                              boolean required, String type, List<Choice> options) {}
    public record Choice(String value, String label) {}

    private record Definition(NoticeType type, String upstreamProperty, Map<String, FieldDefinition> fields) {}
    private record FieldDefinition(NoticeField field, String upstreamKey) {}
}
