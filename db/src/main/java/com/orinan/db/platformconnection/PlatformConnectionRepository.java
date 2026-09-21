package com.orinan.db.platformconnection;

import com.orinan.db.platformconnection.enums.ProviderType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlatformConnectionRepository extends JpaRepository<PlatformConnectionEntity, Long> {

    List<PlatformConnectionEntity> findAllByWorkspaceIdOrderByIdDesc(Long workspaceId);

    Optional<PlatformConnectionEntity> findByWorkspaceIdAndProviderTypeAndExternalAccountId(
            Long workspaceId, ProviderType providerType, String externalAccountId);

    Optional<PlatformConnectionEntity> findByIdAndWorkspaceId(Long id, Long workspaceId);
}
