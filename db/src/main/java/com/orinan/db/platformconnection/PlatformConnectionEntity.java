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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "platform_connections", uniqueConstraints = {
        @UniqueConstraint(name = "uk_platform_connections_workspace_provider_account",
                columnNames = {"workspace_id", "provider_type", "external_account_id"})
})
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

    @Column(nullable = false)
    private Boolean requiresReauth;

    @CreationTimestamp
    private LocalDateTime registeredAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

}
