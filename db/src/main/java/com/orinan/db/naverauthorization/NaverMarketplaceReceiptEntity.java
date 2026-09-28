package com.orinan.db.naverauthorization;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
@Entity @Table(name="naver_marketplace_receipts") @Getter @Setter @NoArgsConstructor
public class NaverMarketplaceReceiptEntity {
    @Id @Column(length=64) private String id;
    @Column(nullable=false,length=64) private String browserHash;
    @Column(nullable=false,length=255) private String accountUid;
    @Column(nullable=false) private Instant expiresAt;
    private boolean consumed;
    @Version private long version;
}
