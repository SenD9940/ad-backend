package com.orinan.api.domain.token.service;

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
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.UserRepository;
import com.orinan.db.user.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.math.BigInteger;
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
    private final UserRepository userRepository;

    public TokenDto issueAccessToken(Long userId){
        var user = registeredUser(userId);
        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("authVersion", user.getAuthVersion());
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
        if (data == null) {
            throw new ApiException(TokenErrorCode.INVALID_TOKEN);
        }
        try {
            long userId = Long.parseLong(String.valueOf(data.get("userId")));
            if (userId <= 0) {
                throw new NumberFormatException();
            }
            long version = data.containsKey("authVersion") ? authVersion(data.get("authVersion")) : 0;
            if (registeredUser(userId).getAuthVersion() != version) {
                throw new ApiException(TokenErrorCode.INVALID_TOKEN);
            }
            return userId;
        } catch (NumberFormatException exception) {
            throw new ApiException(TokenErrorCode.INVALID_TOKEN);
        }
    }

    public TokenEntity validateRefreshToken(String refreshToken) {
        Map<String, Object> data = tokenHelperifs.validationTokenWithThrow(refreshToken);
        if(data == null){
            throw new ApiException(TokenErrorCode.INVALID_TOKEN);
        }
        var hash = searchHashEncoder.encode(refreshToken);
        // Match administrator writes: lock the user before the refresh token. The
        // first lookup only identifies the owner; the token must still be active
        // after acquiring the user lock because an administrator may revoke it.
        var candidate = tokenRepository.findByRefreshTokenHash(hash)
                .orElseThrow(() -> new ApiException(TokenErrorCode.INVALID_TOKEN));
        var user = lockRegisteredUser(candidate.getUserId());
        var token = tokenRepository.findByRefreshTokenHashAndStatus(hash, TokenStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(TokenErrorCode.INVALID_TOKEN));
        if (!user.getId().equals(token.getUserId())) {
            throw new ApiException(TokenErrorCode.INVALID_TOKEN);
        }
        return token;
    }

    public UserEntity lockRegisteredUser(Long userId) {
        return userRepository.findByIdForUpdate(userId)
                .filter(user -> user.getStatus() == UserStatus.REGISTERED)
                .orElseThrow(() -> new ApiException(TokenErrorCode.INVALID_TOKEN));
    }

    private UserEntity registeredUser(Long userId) {
        return userRepository.findByIdAndStatus(userId, UserStatus.REGISTERED)
                .orElseThrow(() -> new ApiException(TokenErrorCode.INVALID_TOKEN));
    }

    private long authVersion(Object claim) {
        long value;
        if (claim instanceof Byte || claim instanceof Short || claim instanceof Integer || claim instanceof Long) {
            value = ((Number) claim).longValue();
        } else if (claim instanceof BigInteger integer && integer.bitLength() <= 63) {
            value = integer.longValue();
        } else {
            throw new ApiException(TokenErrorCode.INVALID_TOKEN);
        }
        if (value < 0) {
            throw new ApiException(TokenErrorCode.INVALID_TOKEN);
        }
        return value;
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
