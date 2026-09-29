package com.orinan.db.imwebconnection;

import com.orinan.db.crypto.DataCryptConverter;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Getter @Setter @Entity @Table(name = "imweb_connections")
@NoArgsConstructor @AllArgsConstructor @Builder
public class ImwebConnectionEntity {
    @Id @Column(name = "connection_id") private Long connectionId;
    @MapsId @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_id") private PlatformConnectionEntity connection;
    @Column(length = 255, nullable = false) private String clientId;
    @Convert(converter = DataCryptConverter.class) @Column(columnDefinition = "TEXT", nullable = false)
    private String accessToken;
    @Convert(converter = DataCryptConverter.class) @Column(columnDefinition = "TEXT", nullable = false)
    private String refreshToken;
    @Column(nullable = false) private LocalDateTime expiresAt;
    @Column(length = 1000) private String grantedScopes;
    @Column(nullable = false) private long credentialVersion;
    @Override public String toString() { return "ImwebConnectionEntity[REDACTED]"; }
}
