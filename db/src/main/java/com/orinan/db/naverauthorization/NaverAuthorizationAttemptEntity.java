package com.orinan.db.naverauthorization;

import com.orinan.db.crypto.DataCryptConverter;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name="naver_authorization_attempts",indexes={@Index(name="ix_naver_auth_expiry",columnList="expires_at"),@Index(name="ix_naver_auth_operation",columnList="operation_id")},uniqueConstraints={@UniqueConstraint(name="uk_naver_auth_launch",columnNames="launch_hash"),@UniqueConstraint(name="uk_naver_auth_proof",columnNames="proof_hash")})
@Getter @Setter @NoArgsConstructor
public class NaverAuthorizationAttemptEntity {
    @Id @Column(length=64) private String id;
    @Column(nullable=false) private Long workspaceId;
    @Column(nullable=false) private Long userId;
    private Long reconnectConnectionId;
    @Column(length=255) private String expectedAccountUid;
    @Column(nullable=false,length=40) private String status;
    @Column(nullable=false,length=64) private String browserHash;
    @Column(nullable=false,length=64) private String stateHash;
    @Convert(converter=DataCryptConverter.class) @Column(columnDefinition="TEXT") private String providerState;
    @Column(length=64) private String launchHash;
    private Instant launchExpiresAt;
    @Column(nullable=false) private Instant expiresAt;
    @Column(nullable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    private Instant confirmedAt;
    private Instant nextReconcileAt;
    @Column(nullable=false) private int recoveryAttempts;
    @Column(nullable=false) private long reviewRevision;
    @Column(length=64) private String idempotencyHash;
    @Column(length=64) private String confirmationHash;
    @Column(length=64) private String proofHash;
    @Convert(converter=DataCryptConverter.class) @Column(columnDefinition="TEXT") private String encryptedProof;
    @Column(length=255) private String accountUid;
    @Column(length=255) private String accountId;
    @Column(length=255) private String sellerName;
    @Column(length=2048) private String storeUrl;
    @Column(length=255) private String providerSubscriptionId;
    @Column(length=255) private String planName;
    @Column(length=255) private String planId;
    private boolean requiresApproval;
    private Long subscriptionId;
    private long subscriptionGeneration;
    @Column(length=64) private String operationId;
    private Long connectionId;
    @Column(length=500) private String errorMessage;
    @Version private long version;
}
