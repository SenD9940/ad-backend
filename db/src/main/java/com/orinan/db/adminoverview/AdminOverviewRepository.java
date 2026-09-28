package com.orinan.db.adminoverview;

import com.orinan.db.adminoverview.projection.AdminOverviewProjection;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;

@Repository
@RequiredArgsConstructor
public class AdminOverviewRepository {
    private final EntityManager em;

    public AdminOverviewProjection summarize(LocalDateTime now) {
        return new AdminOverviewProjection(count("select count(u) from UserEntity u"),
                count("select count(u) from UserEntity u where u.status='REGISTERED'"),
                count("select count(u) from UserEntity u where u.status='SUSPENDED'"),
                count("select count(u) from UserEntity u where u.status='UNREGISTERED'"),
                count("select count(u) from UserEntity u where u.status='REGISTERED' and u.role='ADMIN'"),
                em.createQuery("select count(u) from UserEntity u where u.registeredAt >= :since", Long.class)
                        .setParameter("since", now.minusDays(7)).getSingleResult(),
                count("select count(w) from WorkspaceEntity w"), count("select count(c) from PlatformConnectionEntity c"),
                count("select count(c) from PlatformConnectionEntity c where c.requiresReauth=true"),
                count("select count(a) from PlatformAssetEntity a"),
                em.createQuery("select count(i) from WorkspaceInvitationEntity i where i.expiresAt > :now", Long.class)
                        .setParameter("now", now).getSingleResult());
    }

    private long count(String query) { return em.createQuery(query, Long.class).getSingleResult(); }
}
