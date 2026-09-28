package com.orinan.db.supportpayment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Collection;
import com.orinan.db.supportpayment.enums.SupportPaymentOrderStatus;

public interface SupportPaymentRepository extends JpaRepository<SupportPaymentEntity, Long> {
    Optional<SupportPaymentEntity> findFirstByTicketIdOrderByIdDesc(Long ticketId);
    Optional<SupportPaymentEntity> findByOrderId(String orderId);
    boolean existsByTicketIdAndStatusIn(Long ticketId, Collection<SupportPaymentOrderStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SupportPaymentEntity p where p.orderId=:orderId")
    Optional<SupportPaymentEntity> findByOrderIdForUpdate(@Param("orderId") String orderId);

    interface Scope {
        Long getTicketId();
        Long getWorkspaceId();
        Long getCustomerUserId();
    }
    @Query("select p.ticketId as ticketId, p.workspaceId as workspaceId, p.customerUserId as customerUserId from SupportPaymentEntity p where p.orderId=:orderId")
    Optional<Scope> findScopeByOrderId(@Param("orderId") String orderId);
}
