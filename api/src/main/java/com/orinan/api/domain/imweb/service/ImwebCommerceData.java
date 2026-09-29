package com.orinan.api.domain.imweb.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.imweb.controller.model.ImwebCommerceResponse.Category;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Extracts only display/aggregation fields. Upstream customer data is never retained in DTOs or errors. */
public final class ImwebCommerceData {
    private ImwebCommerceData() {}

    public record Page(List<JsonNode> items, int number, int size, int totalPages, long totalCount) {
        public boolean hasNext() { return number < totalPages; }
    }

    public static Page page(JsonNode data, int expectedPage, int expectedSize) {
        // The product schema documents an array containing the paginated response; orders use an object.
        if (data != null && data.isArray() && data.size() == 1) data = data.get(0);
        if (data == null || !data.isObject() || !data.path("list").isArray()) throw invalid();
        long number = integer(data, "currentPage", 1, Integer.MAX_VALUE);
        long size = integer(data, "pageSize", 1, 100);
        long totalPages = integer(data, "totalPage", 0, Integer.MAX_VALUE);
        long count = integer(data, "totalCount", 0, Integer.MAX_VALUE);
        if (number != expectedPage || size != expectedSize || data.path("list").size() > size
                || count == 0 && (totalPages > 1 || !data.path("list").isEmpty())
                || count > 0 && (totalPages != (count + size - 1) / size || number > totalPages)) throw invalid();
        List<JsonNode> items = new ArrayList<>();
        data.path("list").forEach(item -> { if (!item.isObject()) throw invalid(); items.add(item); });
        if (number < totalPages && items.size() != size || number == totalPages && count > 0
                && items.size() != count - (number - 1) * size) throw invalid();
        return new Page(List.copyOf(items), (int) number, (int) size, (int) totalPages, count);
    }

    public static String text(JsonNode node, String field, int maximum) {
        var value = node.path(field);
        if (!value.isString() || value.asString().isBlank() || value.asString().length() > maximum) throw invalid();
        return value.asString();
    }

    public static long integer(JsonNode node, String field, long minimum, long maximum) {
        var value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < minimum || value.asLong() > maximum) throw invalid();
        return value.asLong();
    }

    public static BigDecimal money(JsonNode node, String field, boolean nullable) {
        var value = node.path(field);
        if (nullable && (value.isMissingNode() || value.isNull())) return null;
        if (!value.isNumber()) throw invalid();
        try {
            BigDecimal amount = new BigDecimal(value.asString());
            if (amount.signum() < 0 || amount.precision() > 24 || amount.precision() - amount.scale() > 24 || amount.scale() > 8) throw invalid();
            return amount;
        } catch (NumberFormatException exception) { throw invalid(); }
    }

    public static Instant timestamp(JsonNode node, String field) {
        try { return Instant.parse(text(node, field, 100)); }
        catch (RuntimeException ignored) { throw invalid(); }
    }

    public static String imageUrl(JsonNode images) {
        if (images == null || images.isMissingNode() || images.isNull() || images.isArray() && images.isEmpty()) return null;
        if (!images.isArray() || !images.get(0).isString()) throw invalid();
        String value = images.get(0).asString();
        return safeUrl(value) ? value : null;
    }

    public static boolean safeUrl(String value) {
        try {
            URI uri = URI.create(value);
            return value.length() <= 4096 && "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (RuntimeException ignored) { return false; }
    }

    public static List<Category> categories(JsonNode data) {
        List<Category> result = new ArrayList<>();
        flatten(data, "", 0, result, new HashSet<>());
        return List.copyOf(result);
    }

    private static void flatten(JsonNode values, String parent, int depth, List<Category> result, Set<String> seen) {
        if (values == null || !values.isArray() || depth > 10) throw invalid();
        for (JsonNode item : values) {
            if (result.size() >= 2000) throw invalid();
            String code = text(item, "categoryCode", 100);
            String name = text(item, "name", 255);
            if (!code.matches("[A-Za-z0-9_-]+") || !seen.add(code)) throw invalid();
            String display = parent.isEmpty() ? name : parent + " > " + name;
            result.add(new Category(code, display));
            flatten(item.path("children"), display, depth + 1, result, seen);
        }
    }

    public static ApiException invalid() {
        return new ApiException(ApiCode.SERVER_ERROR, "아임웹 응답을 확인하지 못했습니다. 잠시 후 다시 조회해 주세요.");
    }
    public static ApiException bad(String message) { return new ApiException(ApiCode.BAD_REQUEST, message); }
}
