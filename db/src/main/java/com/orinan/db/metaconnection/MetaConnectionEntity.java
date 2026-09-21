package com.orinan.db.metaconnection;

import com.orinan.db.crypto.DataCryptConverter;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "meta_connections")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MetaConnectionEntity {

    @Id
    @Column(name = "connection_id")
    @EqualsAndHashCode.Include
    private Long connectionId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_id")
    @ToString.Exclude
    private PlatformConnectionEntity connection;

    @Convert(converter = DataCryptConverter.class)
    @Column(columnDefinition = "TEXT", nullable = false)
    @ToString.Exclude
    private String accessToken;

    private LocalDateTime expiresAt;

    @Column(columnDefinition = "TEXT")
    private String grantedScopes;
}
