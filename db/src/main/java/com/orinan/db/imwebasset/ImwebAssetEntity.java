package com.orinan.db.imwebasset;
import com.orinan.db.platformasset.PlatformAssetEntity;
import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @Entity @Table(name = "imweb_assets")
@NoArgsConstructor @AllArgsConstructor @Builder
public class ImwebAssetEntity {
    @Id @Column(name = "asset_id") private Long assetId;
    @MapsId @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id") private PlatformAssetEntity asset;
    @Column(length = 100, nullable = false) private String siteCode;
    @Column(length = 100, nullable = false) private String unitCode;
    @Column(length = 3, nullable = false) private String currency;
    @Column(length = 2048) private String storeUrl;
}
