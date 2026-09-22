package com.orinan.db.naverasset;

import com.orinan.db.platformasset.PlatformAssetEntity;
import jakarta.persistence.*;
import lombok.*;

@Data
@Entity
@Table(name = "naver_assets")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class NaverAssetEntity {

    @Id
    @Column(name = "asset_id")
    @EqualsAndHashCode.Include
    private Long assetId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id")
    @ToString.Exclude
    private PlatformAssetEntity asset;

    @Column(length = 30, nullable = false)
    private String channelType;

    @Column(length = 2048)
    private String channelUrl;
}
