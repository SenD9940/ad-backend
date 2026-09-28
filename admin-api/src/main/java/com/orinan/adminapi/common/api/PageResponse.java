package com.orinan.adminapi.common.api;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    public static <T> PageResponse<T> of(List<T> items, int page, int size, long count) {
        return new PageResponse<>(List.copyOf(items), page, size, count, (int) Math.min(Integer.MAX_VALUE, (count + size - 1) / size));
    }
}
