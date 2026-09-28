package com.orinan.db.supportpayment;

import com.orinan.db.crypto.DataCryptConverter;
import com.orinan.db.supportpayment.enums.SupportPaymentOrderStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Payment identifiers stay server-side; API projections never contain the payment key. */
@Entity
@Table(name = "support_payments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_support_payment_order", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_support_payment_key_hash", columnNames = "payment_key_hash")}, indexes = {
        @Index(name = "idx_support_payment_ticket", columnList = "ticket_id,id"),
        @Index(name = "idx_support_payment_status", columnList = "status,updated_at")})
@Getter
@NoArgsConstructor
public class SupportPaymentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 64, updatable = false) private String orderId;
    @Column(nullable = false, updatable = false) private Long ticketId;
    @Column(nullable = false, updatable = false) private Long workspaceId;
    @Column(nullable = false, updatable = false) private Long customerUserId;
    @Column(nullable = false, length = 50, updatable = false) private String customerKey;
    @Column(nullable = false, updatable = false) private long amountKrw;
    @Setter @Convert(converter = DataCryptConverter.class) @Column(length = 1024) private String paymentKey;
    @Setter @Column(length = 64) private String paymentKeyHash;
    @Setter @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private SupportPaymentOrderStatus status;
    @Setter @Column(nullable = false) private long verificationRevision;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    @Setter @Column(nullable = false) private LocalDateTime updatedAt;
    @Setter private LocalDateTime confirmedAt;
    @Setter private LocalDateTime lastCheckedAt;
    @Version private long version;

    public SupportPaymentEntity(String orderId, long ticketId, long workspaceId, long customerUserId,
                                String customerKey, long amountKrw, LocalDateTime now) {
        this.orderId = orderId; this.ticketId = ticketId; this.workspaceId = workspaceId;
        this.customerUserId = customerUserId; this.customerKey = customerKey; this.amountKrw = amountKrw;
        this.status = SupportPaymentOrderStatus.READY; this.createdAt = now; this.updatedAt = now;
    }
}
