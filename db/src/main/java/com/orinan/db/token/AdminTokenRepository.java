package com.orinan.db.token;

import com.orinan.db.token.enums.TokenStatus;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/** Token persistence used by administrator session management. */
@Repository
@RequiredArgsConstructor
public class AdminTokenRepository {
    private final EntityManager entityManager;

    public int revokeRefreshTokens(long userId, LocalDateTime now) {
        return entityManager.createQuery("""
                update TokenEntity t set t.status = :expired, t.revokedAt = :now
                where t.userId = :userId and t.status = :active
                """)
                .setParameter("expired", TokenStatus.EXPIRED).setParameter("now", now)
                .setParameter("userId", userId).setParameter("active", TokenStatus.ACTIVE)
                .executeUpdate();
    }
}
