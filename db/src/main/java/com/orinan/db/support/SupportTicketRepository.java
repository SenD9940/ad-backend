package com.orinan.db.support;

import com.orinan.db.support.enums.SupportTicketStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface SupportTicketRepository extends JpaRepository<SupportTicketEntity, Long> {
    interface Scope {
        Long getWorkspaceId();
        Long getCustomerUserId();
    }

    @Query("select t.workspaceId as workspaceId, t.customerUserId as customerUserId from SupportTicketEntity t where t.id = :id")
    Optional<Scope> findScopeById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SupportTicketEntity t where t.id = :id")
    Optional<SupportTicketEntity> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select t from SupportTicketEntity t
            where (:status is null or t.status = :status)
              and (:customerUserId is null or t.customerUserId = :customerUserId)
              and (:workspaceId is null or t.workspaceId = :workspaceId)
            """)
    Page<SupportTicketEntity> findAll(@Param("status") SupportTicketStatus status,
            @Param("customerUserId") Long customerUserId, @Param("workspaceId") Long workspaceId, Pageable pageable);
}
