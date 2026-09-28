package com.orinan.db.adminaudit;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class AdminAuditRepository {
    private final EntityManager entityManager;

    public void save(AdminAuditEntity entity) {
        entityManager.persist(entity);
    }

    public Page<AdminAuditEntity> findAll(Long actorId, String action, String targetType, Long targetId, Pageable paging) {
        StringBuilder where = new StringBuilder(" where 1=1");
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (actorId != null) { where.append(" and a.actorUserId=:actor"); parameters.put("actor", actorId); }
        if (targetId != null) { where.append(" and a.targetId=:target"); parameters.put("target", targetId); }
        if (action != null) { where.append(" and a.action=:action"); parameters.put("action", action); }
        if (targetType != null) { where.append(" and a.targetType=:type"); parameters.put("type", targetType); }
        var query = entityManager.createQuery("select a from AdminAuditEntity a" + where + " order by a.id desc", AdminAuditEntity.class);
        var count = entityManager.createQuery("select count(a) from AdminAuditEntity a" + where, Long.class);
        parameters.forEach((name, value) -> { query.setParameter(name, value); count.setParameter(name, value); });
        var items = query.setFirstResult((int) paging.getOffset()).setMaxResults(paging.getPageSize()).getResultList();
        return new PageImpl<>(items, paging, count.getSingleResult());
    }
}
