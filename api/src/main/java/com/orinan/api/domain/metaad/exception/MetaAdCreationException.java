package com.orinan.api.domain.metaad.exception;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.metaad.controller.model.MetaAdCreateResponse;
import lombok.Getter;

@Getter
public class MetaAdCreationException extends ApiException {

    private final MetaAdCreateResponse result;

    public MetaAdCreationException(ApiException failure, MetaAdCreateResponse result) {
        super(failure.getCodeIfs(), result.message());
        this.result = result;
    }
}
