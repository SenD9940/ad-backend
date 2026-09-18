package com.orinan.db.platformconnection;


import com.orinan.db.BaseEntity;
import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.workspace.WorkspaceEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.sql.Blob;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "platform_connections")
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PlatformConnectionEntity extends BaseEntity {

    @JoinColumn(name = "workspace_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY)
    private WorkspaceEntity workspace;

    @Column(length = 30, nullable = false)
    @Enumerated(EnumType.STRING)
    private ProviderType providerType;

    @Column(length = 255)
    private String externalAccountId;

    @Column(length = 255)
    private String accountName;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String accessToken;

    @Column(columnDefinition = "TEXT")
    private String refreshToken;

    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private Boolean requiresReauth;

    private LocalDateTime registeredAt;

    private LocalDateTime updatedAt;

}
