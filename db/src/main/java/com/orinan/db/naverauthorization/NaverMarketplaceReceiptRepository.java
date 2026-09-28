package com.orinan.db.naverauthorization;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
public interface NaverMarketplaceReceiptRepository extends JpaRepository<NaverMarketplaceReceiptEntity,String> {
    long deleteByExpiresAtBefore(java.time.Instant instant);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from NaverMarketplaceReceiptEntity r where r.id=:id")
    Optional<NaverMarketplaceReceiptEntity> findByIdForUpdate(@Param("id") String id);
}
