package com.orinan.db.workspace;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkspaceEntity w where w.id = :workspaceId")
    Optional<WorkspaceEntity> findByIdForUpdate(@Param("workspaceId") Long workspaceId);

    List<WorkspaceEntity> findAllByUserIdOrderByIdDesc(Long userId);

    @Query("""
            select w from WorkspaceEntity w
            where w.user.id <> :userId
              and exists (
                  select 1 from WorkspaceMemberEntity m
                  where m.id.workspaceId = w.id and m.id.userId = :userId
              )
            order by w.id desc
            """)
    List<WorkspaceEntity> findAllJoinedByUserId(@Param("userId") Long userId);
}
