package com.orinan.db.naversolution;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface NaverSolutionSubscriptionRepository extends JpaRepository<NaverSolutionSubscriptionEntity,Long> {
    Optional<NaverSolutionSubscriptionEntity> findByApplicationRefAndAccountUid(String applicationRef,String accountUid);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from NaverSolutionSubscriptionEntity s where s.applicationRef=:applicationRef and s.accountUid=:accountUid")
    Optional<NaverSolutionSubscriptionEntity> findByApplicationRefAndAccountUidForUpdate(@Param("applicationRef") String applicationRef,@Param("accountUid") String accountUid);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from NaverSolutionSubscriptionEntity s where s.id=:id")
    Optional<NaverSolutionSubscriptionEntity> findByIdForUpdate(@Param("id") Long id);
}
