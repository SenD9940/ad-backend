package com.orinan.db.aistudio.output;

import com.orinan.db.aistudio.enums.AiStudioKind;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface AiStudioOutputRepository extends JpaRepository<AiStudioOutputEntity, Long> {
    Optional<AiStudioOutputEntity> findByWorkspaceIdAndCreatedByAndIdempotencyKey(Long workspaceId, Long createdBy, String idempotencyKey);
    Optional<AiStudioOutputEntity> findByIdAndWorkspaceIdAndStatus(Long id, Long workspaceId, AiStudioOutputStatus status);
    Optional<AiStudioOutputEntity> findByIdAndWorkspaceId(Long id, Long workspaceId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from AiStudioOutputEntity o where o.id = :id")
    Optional<AiStudioOutputEntity> findByIdForUpdate(@Param("id") Long id);
    @Query("select o from AiStudioOutputEntity o where o.workspaceId = :workspaceId and o.status = com.orinan.db.aistudio.output.AiStudioOutputStatus.SUCCEEDED and (:kind is null or o.kind = :kind)")
    Page<AiStudioOutputEntity> searchCompleted(@Param("workspaceId") Long workspaceId, @Param("kind") AiStudioKind kind, Pageable pageable);
    @Query("select o from AiStudioOutputEntity o where o.workspaceId = :workspaceId and (:kind is null or o.kind = :kind)")
    Page<AiStudioOutputEntity> search(@Param("workspaceId") Long workspaceId, @Param("kind") AiStudioKind kind, Pageable pageable);
}
