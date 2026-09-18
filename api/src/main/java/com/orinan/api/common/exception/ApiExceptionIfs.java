package com.orinan.api.common.exception;

import com.orinan.api.common.code.CodeIfs;

public interface ApiExceptionIfs {

    CodeIfs getCodeIfs();
    String getDescription();
}
