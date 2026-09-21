package com.orinan.api.domain.token.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.code.DatabaseErrorCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.token.converter.TokenConverter;
import com.orinan.api.domain.token.exception.TokenErrorCode;
import com.orinan.api.domain.token.ifs.TokenHelperIfs;
import com.orinan.api.domain.token.model.TokenDto;
import com.orinan.db.crypto.SearchHashEncoder;
import com.orinan.db.token.TokenEntity;
import com.orinan.db.token.TokenRepository;
import com.orinan.db.token.enums.TokenStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenService {

    private final TokenHelperIfs tokenHelperifs;
    private final TokenRepository tokenRepository;
    private final TokenConverter tokenConverter;
    private final SearchHashEncoder searchHashEncoder;

    public TokenDto issueAccessToken(Long userId){
        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        return tokenHelperifs.issueAccessToken(data);
    }

    public TokenDto issueRefreshToken(Long userId){
        TokenDto tokenDto = tokenHelperifs.issueRefreshToken();
        tokenRepository.findByUserIdAndStatus(userId, TokenStatus.ACTIVE).ifPresent(tokenRepository::delete);
        var tokenEntity = save(tokenDto, userId);
        return tokenConverter.toDto(tokenEntity, tokenDto.getToken());
    }

    public Long validateAccessToken(String token){
        Map<String, Object> data = tokenHelperifs.validationTokenWithThrow(token);
        try {
            long userId = Long.parseLong(String.valueOf(data.get("userId")));
            if (userId <= 0) {
                throw new NumberFormatException();
            }
            return userId;
        } catch (NumberFormatException exception) {
            throw new ApiException(TokenErrorCode.INVALID_TOKEN);
        }
    }

    public TokenEntity validateRefreshToken(String refreshToken) {
        Map<String, Object> data = tokenHelperifs.validationTokenWithThrow(refreshToken);
        if(data == null){
            throw new ApiException(ApiCode.NULL_POINT);
        }
        return tokenRepository.findByRefreshTokenHashAndStatus(searchHashEncoder.encode(refreshToken), TokenStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(TokenErrorCode.INVALID_TOKEN));
    }

    public boolean expireRefreshToken(String refreshToken) {
        var entity = tokenRepository.findByRefreshTokenHashAndStatus(searchHashEncoder.encode(refreshToken), TokenStatus.ACTIVE).orElseThrow(
                () -> new ApiException(TokenErrorCode.INVALID_TOKEN)
        );
        tokenRepository.delete(entity);
        if(tokenRepository.findByRefreshTokenHash(searchHashEncoder.encode(refreshToken)).isPresent()) {
            throw new ApiException(DatabaseErrorCode.DELETE_ERROR);
        }
        return true;
    }

    public TokenEntity save(TokenDto tokenDto, Long userId) {
        TokenEntity tokenEntity = tokenConverter.toEntity(tokenDto);
        tokenEntity.setUserId(userId);
        tokenEntity.setStatus(TokenStatus.ACTIVE);
        tokenEntity.setRefreshTokenHash(searchHashEncoder.encode(tokenDto.getToken()));
        tokenEntity.setIssuedAt(LocalDateTime.now());
        tokenEntity.setExpiresAt(tokenDto.getExpiredAt());
        return tokenRepository.save(tokenEntity);
    }

    public TokenEntity findByIdWithThrow(Long id){
        return tokenRepository.findById(id).orElseThrow(() -> new ApiException(TokenErrorCode.INVALID_TOKEN));
    }
}
