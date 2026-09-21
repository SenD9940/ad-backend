package com.orinan.db.metaasset;

import com.orinan.db.platformasset.PlatformAssetEntity;
import jakarta.persistence.*;
import lombok.*;

@Data
@Entity
@Table(name = "meta_assets")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MetaAssetEntity {

    @Id
    @Column(name = "asset_id")
    @EqualsAndHashCode.Include
    private Long assetId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id")
    @ToString.Exclude
    private PlatformAssetEntity asset;

    private String facebookPageId;
}
