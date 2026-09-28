package com.orinan.db.naversolution;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name="naver_subscription_operations",uniqueConstraints=@UniqueConstraint(name="uk_naver_approval_lifecycle",columnNames={"application_ref","account_uid","provider_subscription_id","operation_type"}))
@Getter @Setter @NoArgsConstructor
public class NaverSubscriptionOperationEntity {
    @Id @Column(length=64) private String id;
    @Column(nullable=false,length=128) private String applicationRef;
    @Column(nullable=false,length=255) private String accountUid;
    @Column(nullable=false,length=255) private String providerSubscriptionId;
    @Column(nullable=false,length=30) private String operationType="APPROVE";
    @Column(nullable=false,length=128) private String accountMappingId;
    @Column(nullable=false,length=40) private String status;
    @Column(nullable=false,length=64) private String ownerAttemptId;
    @Column(nullable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    @Version private long version;
}
