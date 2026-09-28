package com.orinan.adminapi.domain.token.ifs;

import com.orinan.adminapi.domain.token.model.AdminTokenClaims;
import com.orinan.adminapi.domain.token.model.AdminTokenDto;

public interface AdminTokenHelperIfs {
    AdminTokenDto issueAccessToken(long userId, long authVersion);

    AdminTokenClaims validationTokenWithThrow(String token);
}
