package com.orinan.db.support;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.Optional;

public interface SupportSessionRepository extends JpaRepository<SupportSessionEntity, Long> {
    @Query("select s.ticketId from SupportSessionEntity s where s.id = :id")
    Optional<Long> findTicketIdById(@Param("id") Long id);

    Optional<SupportSessionEntity> findByTokenHash(String tokenHash);
    Page<SupportSessionEntity> findAllByTicketId(Long ticketId, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SupportSessionEntity s where s.id = :id")
    Optional<SupportSessionEntity> findByIdForUpdate(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query("update SupportSessionEntity s set s.endedAt = :now where s.ticketId = :ticketId and s.endedAt is null")
    int endActiveByTicketId(@Param("ticketId") Long ticketId, @Param("now") LocalDateTime now);
}
