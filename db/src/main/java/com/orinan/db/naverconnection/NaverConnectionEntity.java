package com.orinan.db.naverconnection;

import com.orinan.db.crypto.DataCryptConverter;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import com.orinan.db.naverconnection.enums.NaverCredentialSource;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "naver_connections")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class NaverConnectionEntity {

    @Id
    @Column(name = "connection_id")
    @EqualsAndHashCode.Include
    private Long connectionId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_id")
    @ToString.Exclude
    private PlatformConnectionEntity connection;

    @Column(length = 255)
    private String clientId;

    @Convert(converter = DataCryptConverter.class)
    @Column(columnDefinition = "TEXT")
    @ToString.Exclude
    private String clientSecret;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private NaverTokenType tokenType;

    @Column(length = 255)
    private String accountId;

    @Convert(converter = DataCryptConverter.class)
    @Column(columnDefinition = "TEXT")
    @ToString.Exclude
    private String accessToken;

    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    @Builder.Default
    private NaverCredentialSource credentialSource = NaverCredentialSource.MANUAL;

    @Column(length = 128)
    private String applicationRef;

    private Long solutionSubscriptionId;
    private Long boundSubscriptionGeneration;

    @Column(nullable = false)
    @Builder.Default
    private long credentialVersion = 0;
}
