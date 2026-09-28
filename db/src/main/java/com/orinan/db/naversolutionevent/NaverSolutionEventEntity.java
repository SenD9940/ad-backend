package com.orinan.db.naversolutionevent;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name="naver_solution_event_inbox", uniqueConstraints=@UniqueConstraint(name="uk_naver_solution_event_kind", columnNames={"solution_id","event_id","change_type"}))
@Getter @Setter @NoArgsConstructor
public class NaverSolutionEventEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=128) private String solutionId;
    @Column(nullable=false,length=255) private String eventId;
    @Column(nullable=false,length=64) private String changeType;
    @Column(nullable=false,length=255) private String accountUid;
    @Column(length=128) private String accountMappingId;
    @Column(nullable=false,length=30) private String state="PENDING";
    @Column(nullable=false) private Instant receivedAt=Instant.now();
    private Instant checkedAt;
    private Instant nextAttemptAt;
    @Column(nullable=false) private int attempts;
    @Version private long version;
}
