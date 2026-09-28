package com.orinan.db.platformconnection;

import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.platformconnection.projection.AdminPlatformConnectionProjection;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class AdminPlatformConnectionRepository {
    private final EntityManager em;

    public Page<AdminPlatformConnectionProjection> search(Long workspaceId, ProviderType provider, Boolean requiresReauth, Pageable pageable) {
        String where = " where 1=1";
        Map<String, Object> args = new LinkedHashMap<>();
        if (workspaceId != null) { where += " and w.id=:workspace"; args.put("workspace", workspaceId); }
        if (provider != null) { where += " and c.providerType=:provider"; args.put("provider", provider); }
        if (requiresReauth != null) { where += " and c.requiresReauth=:reauth"; args.put("reauth", requiresReauth); }
        var rows = em.createQuery(select() + where + " order by c.id desc", Object[].class);
        var total = em.createQuery("select count(c) from PlatformConnectionEntity c join c.workspace w" + where, Long.class);
        args.forEach((key, value) -> { rows.setParameter(key, value); total.setParameter(key, value); });
        return new PageImpl<>(rows.setFirstResult((int) pageable.getOffset()).setMaxResults(pageable.getPageSize())
                .getResultList().stream().map(this::projection).toList(), pageable, total.getSingleResult());
    }

    public Optional<AdminPlatformConnectionProjection> findSummary(long id) {
        return em.createQuery(select() + " where c.id=:id", Object[].class).setParameter("id", id)
                .getResultStream().findFirst().map(this::projection);
    }

    public Optional<Long> findWorkspaceId(long id) {
        return em.createQuery("select c.workspace.id from PlatformConnectionEntity c where c.id=:id", Long.class)
                .setParameter("id", id).getResultStream().findFirst();
    }

    public Optional<PlatformConnectionEntity> findByIdForUpdate(long id) {
        return Optional.ofNullable(em.find(PlatformConnectionEntity.class, id, LockModeType.PESSIMISTIC_WRITE));
    }

    private String select() {
        return "select c.id,w.id,w.name,c.providerType,c.externalAccountId,c.accountName,c.requiresReauth,"
                + "(select count(a) from PlatformAssetEntity a where a.connectionId=c.id and a.workspaceId=w.id),"
                + "coalesce(m.expiresAt,n.expiresAt),c.registeredAt,c.updatedAt"
                + " from PlatformConnectionEntity c join c.workspace w"
                + " left join MetaConnectionEntity m on m.connectionId=c.id left join NaverConnectionEntity n on n.connectionId=c.id";
    }

    private AdminPlatformConnectionProjection projection(Object[] row) {
        return new AdminPlatformConnectionProjection((Long) row[0], (Long) row[1], (String) row[2], (ProviderType) row[3],
                (String) row[4], (String) row[5], (Boolean) row[6], (Long) row[7], (LocalDateTime) row[8],
                (LocalDateTime) row[9], (LocalDateTime) row[10]);
    }
}
