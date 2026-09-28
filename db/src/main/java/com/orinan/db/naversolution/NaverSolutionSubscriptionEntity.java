package com.orinan.db.naversolution;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name="naver_solution_subscriptions", uniqueConstraints=@UniqueConstraint(name="uk_naver_solution_seller", columnNames={"application_ref","account_uid"}))
@Getter @Setter @NoArgsConstructor
public class NaverSolutionSubscriptionEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=128) private String applicationRef;
    @Column(nullable=false,length=128) private String solutionId;
    @Column(nullable=false,length=255) private String accountUid;
    @Column(nullable=false,length=255) private String providerSubscriptionId;
    @Column(nullable=false,length=128) private String accountMappingId;
    @Column(nullable=false,length=30) private String status;
    @Column(nullable=false) private long generation=1;
    @Version private long version;
    private Instant verifiedAt;
}
