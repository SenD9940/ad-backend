package com.orinan.db.naversolutionevent;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NaverSolutionEventRepository extends JpaRepository<NaverSolutionEventEntity,Long> {
    boolean existsBySolutionIdAndEventIdAndChangeType(String solutionId,String eventId,String changeType);
    @Query("select e.id from NaverSolutionEventEntity e where e.state='PENDING' and (e.nextAttemptAt is null or e.nextAttemptAt <= :now) order by e.id")
    List<Long> pending(@Param("now") Instant now, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from NaverSolutionEventEntity e where e.id=:id")
    Optional<NaverSolutionEventEntity> findByIdForUpdate(@Param("id") Long id);
}
