package com.orinan.db.adminauth;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

@Repository
public class AdminAuthRepository {
    private final EntityManager entityManager;

    public AdminAuthRepository(EntityManager entityManager) { this.entityManager = entityManager; }

    public String displayName(long userId) {
        return entityManager.createQuery("select p.name from UserProfileEntity p where p.user.id = :userId", String.class)
                .setParameter("userId", userId).getResultStream().findFirst().orElse(null);
    }
}
