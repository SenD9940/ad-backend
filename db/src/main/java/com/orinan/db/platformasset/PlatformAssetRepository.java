package com.orinan.db.platformasset;

import com.orinan.db.platformconnection.PlatformConnectionEntity;
import com.orinan.db.platformconnection.PlatformConnectionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PlatformAssetRepository extends JpaRepository<PlatformConnectionEntity, Long>{
}
