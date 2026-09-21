package com.orinan.db.platformasset;

import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlatformAssetRepository extends JpaRepository<PlatformAssetEntity, Long> {

    List<PlatformAssetEntity> findAllByConnectionIdOrderByIdAsc(Long connectionId);

    Optional<PlatformAssetEntity> findByConnectionIdAndPlatformTypeAndAssetTypeAndExternalId(
            Long connectionId, PlatformType platformType, AssetType assetType, String externalId);
}
