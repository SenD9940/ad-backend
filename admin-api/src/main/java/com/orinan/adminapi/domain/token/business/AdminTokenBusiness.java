package com.orinan.adminapi.domain.token.business;

import com.orinan.adminapi.annotation.Business;
import com.orinan.adminapi.domain.token.controller.model.AdminTokenResponse;
import com.orinan.adminapi.domain.token.converter.AdminTokenConverter;
import com.orinan.adminapi.domain.token.model.AdminTokenRevocation;
import com.orinan.adminapi.domain.token.service.AdminTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@Business
@RequiredArgsConstructor
public class AdminTokenBusiness {
    private final AdminTokenService tokenService;
    private final AdminTokenConverter tokenConverter;

    @Transactional
    public AdminTokenResponse issueToken(long userId) {
        // Join the login transaction and issue from the current locked account.
        tokenService.lockActiveAdmin(userId);
        return tokenConverter.toResponse(tokenService.issueAccessToken(userId));
    }

    @Transactional(readOnly = true)
    public long validateAccessToken(String token) {
        return tokenService.validateAccessToken(token);
    }

    @Transactional
    public AdminTokenRevocation expireToken(long userId) {
        var user = tokenService.lockActiveAdmin(userId);
        return tokenService.revokeSessions(user);
    }
}
