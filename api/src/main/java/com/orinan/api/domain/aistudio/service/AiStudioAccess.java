package com.orinan.api.domain.aistudio.service;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.aistudio.exception.AiStudioErrorCode;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiStudioAccess {
    private final EntityManager entityManager;
    /** Scalar query deliberately bypasses OSIV's managed entity cache on every authorization check. */
    public void requireMember(long workspaceId, long userId) {
        long count = entityManager.createQuery("""
                select count(w) from WorkspaceEntity w where w.id = :workspaceId
                and exists (select u.id from UserEntity u where u.id = :userId
                    and u.status = com.orinan.db.user.enums.UserStatus.REGISTERED)
                and (w.user.id = :userId or exists (select m.id.userId from WorkspaceMemberEntity m
                    where m.id.workspaceId = :workspaceId and m.id.userId = :userId))
                """, Long.class).setParameter("workspaceId", workspaceId).setParameter("userId", userId).getSingleResult();
        if (count != 1) throw new ApiException(AiStudioErrorCode.ACCESS_DENIED);
    }
}
