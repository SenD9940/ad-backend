package com.orinan.api.domain.token.converter;

import com.orinan.api.annotation.Converter;
import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.token.controller.model.TokenResponse;
import com.orinan.api.domain.token.model.TokenDto;
import com.orinan.db.token.TokenEntity;

import java.util.Objects;

@Converter
public class TokenConverter {

    public TokenResponse toResponse(TokenDto accessToken, TokenDto refreshToken) {

        Objects.requireNonNull(accessToken, () -> { throw new ApiException(ApiCode.NULL_POINT);});
        Objects.requireNonNull(refreshToken, () -> { throw new ApiException(ApiCode.NULL_POINT);});

        return TokenResponse.builder()
                .accessToken(accessToken.getToken())
                .accessTokenExpiredAt(accessToken.getExpiredAt())
                .refreshToken(refreshToken.getToken())
                .refreshTokenExpiredAt(refreshToken.getExpiredAt())
                .build();
    }

    public TokenEntity toEntity(TokenDto tokenDto) {
        Objects.requireNonNull(tokenDto, () -> { throw new ApiException(ApiCode.NULL_POINT);});

        return TokenEntity.builder()
                .expiresAt(tokenDto.getExpiredAt())
                .build();
    }

    public TokenDto toDto(TokenEntity tokenEntity) {
        Objects.requireNonNull(tokenEntity, () -> { throw new ApiException(ApiCode.NULL_POINT);});

        return TokenDto.builder()
                .token(tokenEntity.getRefreshTokenHash())
                .expiredAt(tokenEntity.getExpiresAt())
                .build();
    }

    public TokenDto toDto(TokenEntity tokenEntity, String refreshToken) {
        return TokenDto.builder()
                .token(refreshToken)
                .expiredAt(tokenEntity.getExpiresAt())
                .build();
    }
}
