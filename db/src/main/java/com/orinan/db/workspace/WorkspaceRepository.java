package com.orinan.db.workspace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, Long> {

    List<WorkspaceEntity> findAllByUserIdOrderByIdDesc(Long userId);
}
