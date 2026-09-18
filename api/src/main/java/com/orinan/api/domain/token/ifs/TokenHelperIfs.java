package com.orinan.api.domain.token.ifs;


import com.orinan.api.domain.token.model.TokenDto;

import java.util.Map;

public interface TokenHelperIfs {

    TokenDto issueAccessToken(Map<String, Object> data);

    TokenDto issueRefreshToken();

    Map<String, Object> validationTokenWithThrow(String token);
}
