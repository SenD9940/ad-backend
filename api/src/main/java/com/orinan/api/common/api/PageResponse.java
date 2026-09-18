package com.orinan.api.common.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageResponse<T> {

    private int currentPage;
    private int totalPage;
    private Long totalItems;
    @JsonProperty("is_first")
    private boolean isFirst;
    @JsonProperty("is_last")
    private boolean isLast;

    private List<T> content;
}
