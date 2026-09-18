package com.orinan.db.platformasset;

import com.orinan.db.BaseEntity;
import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
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
@Table(name = "platform_assets")
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PlatformAssetEntity extends BaseEntity {

    @Column(nullable = false)
    private Long workspaceId;

    private Long connectionId;

    @JoinColumn(name = "platform_type")
    @Enumerated(EnumType.STRING)
    private PlatformType platformType;

    @JoinColumn(name = "asset_type")
    @Enumerated(EnumType.STRING)
    private AssetType assetType;

    private String externalId;

    private String name;

    @CreationTimestamp
    private LocalDateTime registeredAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
