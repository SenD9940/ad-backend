package com.orinan.db.support;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportActionRepository extends JpaRepository<SupportActionEntity, Long> {
    Page<SupportActionEntity> findAllByTicketId(Long ticketId, Pageable pageable);
    Page<SupportActionEntity> findAllBySessionId(Long sessionId, Pageable pageable);
}
