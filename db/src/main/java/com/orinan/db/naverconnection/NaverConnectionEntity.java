package com.orinan.db.naverconnection;

import com.orinan.db.crypto.DataCryptConverter;
import com.orinan.db.naverconnection.enums.NaverTokenType;
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

    @Column(length = 255, nullable = false)
    private String clientId;

    @Convert(converter = DataCryptConverter.class)
    @Column(columnDefinition = "TEXT", nullable = false)
    @ToString.Exclude
    private String clientSecret;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private NaverTokenType tokenType;

    @Column(length = 255)
    private String accountId;

    @Convert(converter = DataCryptConverter.class)
    @Column(columnDefinition = "TEXT", nullable = false)
    @ToString.Exclude
    private String accessToken;

    @Column(nullable = false)
    private LocalDateTime expiresAt;
}
