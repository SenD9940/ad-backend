package com.orinan.db.token;

import com.orinan.db.token.enums.TokenStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface TokenRepository extends JpaRepository<TokenEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TokenEntity> findByRefreshTokenHashAndStatus(String refreshTokenHash, TokenStatus status);

    Optional<TokenEntity> findByRefreshTokenHash(String refreshTokenHash);

    Optional<TokenEntity> findByUserIdAndStatus(Long userId, TokenStatus status);

}
