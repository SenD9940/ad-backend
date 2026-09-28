package com.orinan.adminapi.common.api;

import com.orinan.adminapi.common.exception.AdminException;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

public final class AdminPageRequest {
    private AdminPageRequest() {}
    public static Pageable page(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100)
            throw new AdminException(HttpStatus.BAD_REQUEST, "page는 0~100000, size는 1~100으로 지정해 주세요.");
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    }
    public static String query(String value) {
        if (value == null || value.isBlank()) return null;
        String stripped = value.strip();
        if (stripped.length() > 200) throw new AdminException(HttpStatus.BAD_REQUEST, "검색어는 200자 이하로 입력해 주세요.");
        return stripped;
    }
}
