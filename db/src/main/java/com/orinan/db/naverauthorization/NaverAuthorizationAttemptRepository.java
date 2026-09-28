package com.orinan.db.naverauthorization;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
public interface NaverAuthorizationAttemptRepository extends JpaRepository<NaverAuthorizationAttemptEntity,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from NaverAuthorizationAttemptEntity a where a.id=:id")
    Optional<NaverAuthorizationAttemptEntity> findByIdForUpdate(@Param("id") String id);
    Optional<NaverAuthorizationAttemptEntity> findByLaunchHash(String launchHash);
    @Query("select w.user.id from WorkspaceEntity w where w.id=:workspaceId")
    Optional<Long> findCurrentOwnerId(@Param("workspaceId") Long workspaceId);
    @Modifying @Query("update NaverAuthorizationAttemptEntity a set a.status='EXPIRED',a.encryptedProof=null,a.providerState=null,a.launchHash=null,a.updatedAt=:now where a.expiresAt<:now and a.status in ('WAITING_AUTH','VALIDATING','REVIEW_REQUIRED')")
    int expireUnconfirmed(@Param("now") java.time.Instant now);
    @Modifying @Query("update NaverAuthorizationAttemptEntity a set a.encryptedProof=null,a.providerState=null where a.expiresAt<:now and (a.encryptedProof is not null or a.providerState is not null)")
    int eraseExpiredProofs(@Param("now") java.time.Instant now);
    @Query("select a.id from NaverAuthorizationAttemptEntity a where a.status in ('APPROVING','RECONCILING','VERIFYING_CONNECTION') and a.confirmedAt is not null and a.updatedAt<:before and a.recoveryAttempts<20 and (a.nextReconcileAt is null or a.nextReconcileAt<=:now) order by a.updatedAt asc")
    java.util.List<String> findRecoveryCandidates(@Param("before") java.time.Instant before,@Param("now") java.time.Instant now,org.springframework.data.domain.Pageable pageable);
    boolean existsByProofHash(String proofHash);
}
