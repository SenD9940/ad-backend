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
@Table(name = "platform_assets", uniqueConstraints = {
        @UniqueConstraint(name = "uk_platform_assets_connection_platform_type_external",
                columnNames = {"connection_id", "platform_type", "asset_type", "external_id"})
})
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PlatformAssetEntity extends BaseEntity {

    @Column(nullable = false)
    private Long workspaceId;

    // 연결 삭제 시 외래 키의 SET NULL 동작으로 자산 기록을 보존합니다.
    @Column(nullable = true)
    private Long connectionId;

    @Column(name = "platform_type", length = 30, nullable = false)
    @Enumerated(EnumType.STRING)
    private PlatformType platformType;

    @Column(name = "asset_type", length = 30, nullable = false)
    @Enumerated(EnumType.STRING)
    private AssetType assetType;

    @Column(nullable = false)
    private String externalId;

    private String name;

    @CreationTimestamp
    private LocalDateTime registeredAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
