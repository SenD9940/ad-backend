package com.orinan.db.workspace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, Long> {

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
