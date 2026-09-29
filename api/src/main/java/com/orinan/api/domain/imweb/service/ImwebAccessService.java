package com.orinan.api.domain.imweb.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.imweb.client.ImwebApiClient.Token;
import com.orinan.api.domain.imweb.client.ImwebProperties;
import com.orinan.api.domain.platformconnection.controller.model.PlatformConnectionResponse;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.db.imwebasset.*;
import com.orinan.db.imwebconnection.*;
import com.orinan.db.platformasset.*;
import com.orinan.db.platformasset.enums.*;
import com.orinan.db.platformconnection.*;
import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class ImwebAccessService {
    private final PlatformConnectionService permissions;
    private final PlatformConnectionRepository connections;
    private final ImwebConnectionRepository details;
    private final PlatformAssetRepository assets;
    private final ImwebAssetRepository assetDetails;
    private final WorkspaceRepository workspaces;
    private final UserService users;
    private final EntityManager em;
    private final ImwebProperties properties;

    public void requireOwner(Long workspaceId, Long userId) { member(workspaceId, userId); permissions.requireOwner(workspaceId, userId); }
    public void requireMember(Long workspaceId, Long userId) { member(workspaceId, userId); }
    private void member(Long workspaceId, Long userId) {
        var workspace = workspaces.findById(workspaceId).orElseThrow(this::invalid);
        em.refresh(workspace);
        var user = users.findByIdAndStatusWithThrow(userId, UserStatus.REGISTERED);
        em.refresh(user);
        if (user.getStatus() != UserStatus.REGISTERED) throw invalid();
        permissions.requireMember(workspaceId, userId);
    }
    public List<Store> stores(Long workspaceId, Long userId) {
        member(workspaceId, userId);
        var result = new ArrayList<Store>();
        for (var connection : connections.findAllByWorkspaceIdOrderByIdDesc(workspaceId)) {
            if (connection.getProviderType() != ProviderType.IMWEB) continue;
            for (var asset : assets.findAllByConnectionIdOrderByIdAsc(connection.getId())) {
                if (asset.getPlatformType() == PlatformType.IMWEB && asset.getAssetType() == AssetType.STORE)
                    result.add(store(workspaceId, asset.getId(), userId));
            }
        }
        return List.copyOf(result);
    }
    public Store store(Long workspaceId, Long assetId, Long userId) {
        member(workspaceId, userId);
        var asset = assets.findByIdAndWorkspaceId(assetId, workspaceId).orElseThrow(this::invalid);
        em.refresh(asset);
        if (!workspaceId.equals(asset.getWorkspaceId()) || asset.getConnectionId() == null
                || asset.getPlatformType() != PlatformType.IMWEB || asset.getAssetType() != AssetType.STORE) throw invalid();
        var connection = loadConnection(workspaceId, asset.getConnectionId());
        var detail = assetDetails.findById(assetId).orElseThrow(this::invalid); em.refresh(detail);
        if (!Objects.equals(detail.getSiteCode(), connection.getExternalAccountId())
                || !Objects.equals(detail.getUnitCode(), asset.getExternalId()) || !ImwebOAuthStateService.validUnit(detail.getUnitCode())) throw invalid();
        var credential = details.findById(connection.getId()).orElse(null);
        if (credential != null) em.refresh(credential);
        boolean reauth = Boolean.TRUE.equals(connection.getRequiresReauth()) || credential == null
                || credential.getAccessToken() == null || credential.getRefreshToken() == null
                || !Objects.equals(credential.getClientId(), properties.getClientId());
        return new Store(assetId, connection.getId(), detail.getSiteCode(), detail.getUnitCode(), asset.getName(),
                detail.getStoreUrl(), detail.getCurrency(), connection.getAccountName(), reauth);
    }
    public Credentials credentials(Long workspaceId, Long connectionId, Long userId) {
        member(workspaceId, userId);
        var connection = loadConnection(workspaceId, connectionId);
        if (Boolean.TRUE.equals(connection.getRequiresReauth())) throw reauth();
        var detail = details.findById(connectionId).orElseThrow(this::reauth); em.refresh(detail);
        if (!Objects.equals(properties.getClientId(), detail.getClientId()) || detail.getAccessToken() == null
                || detail.getRefreshToken() == null) throw reauth();
        return new Credentials(connectionId, connection.getExternalAccountId(), detail.getAccessToken(), detail.getRefreshToken(),
                detail.getExpiresAt(), detail.getGrantedScopes(), detail.getCredentialVersion());
    }
    public void requireUnchanged(Long workspaceId, Long userId, Credentials expected) {
        if (!same(credentials(workspaceId, expected.connectionId(), userId), expected)) throw changed();
    }
    public void requireUnchanged(Context expected) {
        var store = store(expected.workspaceId(), expected.assetId(), expected.userId());
        var credential = credentials(expected.workspaceId(), expected.connectionId(), expected.userId());
        if (store.requiresReauth() || !Objects.equals(store.connectionId(), expected.connectionId())
                || !Objects.equals(store.siteCode(), expected.siteCode()) || !Objects.equals(store.unitCode(), expected.unitCode())
                || !Objects.equals(store.currency(), expected.currency()) || credential.credentialVersion() != expected.credentialVersion()
                || !Objects.equals(credential.accessToken(), expected.accessToken())) throw changed();
    }
    @Transactional
    public PlatformConnectionResponse save(Long workspaceId, Long userId, String siteCode, String name, Token token) {
        lock(workspaceId); requireOwner(workspaceId, userId);
        var connection = connections.findByWorkspaceIdAndProviderTypeAndExternalAccountId(workspaceId, ProviderType.IMWEB, siteCode)
                .orElseGet(() -> PlatformConnectionEntity.builder().workspace(workspaces.getReferenceById(workspaceId))
                        .providerType(ProviderType.IMWEB).externalAccountId(siteCode).build());
        connection.setAccountName(name); connection.setRequiresReauth(false); connection = connections.saveAndFlush(connection);
        var detail = details.findById(connection.getId()).orElse(null);
        if (detail == null) detail = ImwebConnectionEntity.builder().connection(connection).build();
        detail.setClientId(properties.getClientId()); apply(detail, token); details.saveAndFlush(detail);
        return permissions.findById(workspaceId, connection.getId(), userId);
    }
    @Transactional
    public Credentials replaceToken(Long workspaceId, Long userId, Credentials expected, Token token) {
        lock(workspaceId); requireUnchanged(workspaceId, userId, expected);
        var detail = details.findById(expected.connectionId()).orElseThrow(this::reauth);
        apply(detail, token); details.saveAndFlush(detail);
        return credentials(workspaceId, expected.connectionId(), userId);
    }
    @Transactional
    public void requireReauth(Long workspaceId, Long userId, Credentials expected) {
        lock(workspaceId); member(workspaceId, userId);
        var connection = loadConnection(workspaceId, expected.connectionId());
        if (Boolean.TRUE.equals(connection.getRequiresReauth())) return;
        var current = credentials(workspaceId, expected.connectionId(), userId);
        if (same(current, expected)) { connection.setRequiresReauth(true); connections.saveAndFlush(connection); }
    }
    @Transactional
    public List<Store> saveUnits(Long workspaceId, Long userId, Credentials expected, List<Unit> verified) {
        lock(workspaceId); requireUnchanged(workspaceId, userId, expected);
        for (var unit : verified) {
            var asset = assets.findByConnectionIdAndPlatformTypeAndAssetTypeAndExternalId(expected.connectionId(), PlatformType.IMWEB, AssetType.STORE, unit.unitCode())
                    .orElseGet(() -> PlatformAssetEntity.builder().workspaceId(workspaceId).connectionId(expected.connectionId())
                            .platformType(PlatformType.IMWEB).assetType(AssetType.STORE).externalId(unit.unitCode()).build());
            asset.setName(unit.name()); asset = assets.saveAndFlush(asset);
            var detail = assetDetails.findById(asset.getId()).orElse(null);
            if (detail == null) detail = ImwebAssetEntity.builder().asset(asset).build();
            detail.setSiteCode(expected.siteCode()); detail.setUnitCode(unit.unitCode()); detail.setCurrency(unit.currency());
            detail.setStoreUrl(unit.storeUrl()); assetDetails.saveAndFlush(detail);
        }
        return stores(workspaceId, userId).stream().filter(s -> s.connectionId().equals(expected.connectionId())).toList();
    }
    private PlatformConnectionEntity loadConnection(Long workspaceId, Long id) {
        var connection = connections.findByIdAndWorkspaceId(id, workspaceId).orElseThrow(this::invalid); em.refresh(connection);
        if (connection.getProviderType() != ProviderType.IMWEB || !workspaceId.equals(connection.getWorkspace().getId())
                || !ImwebOAuthStateService.validSite(connection.getExternalAccountId())) throw invalid();
        return connection;
    }
    private void lock(Long workspaceId) { em.refresh(workspaces.findByIdForUpdate(workspaceId).orElseThrow(this::invalid)); }
    private boolean same(Credentials a, Credentials b) { return a.credentialVersion() == b.credentialVersion()
            && Objects.equals(a.siteCode(), b.siteCode()) && Objects.equals(a.accessToken(), b.accessToken())
            && Objects.equals(a.refreshToken(), b.refreshToken()); }
    private void apply(ImwebConnectionEntity detail, Token token) {
        detail.setAccessToken(token.accessToken()); detail.setRefreshToken(token.refreshToken()); detail.setExpiresAt(token.expiresAt());
        detail.setGrantedScopes(token.scopes() == null ? ImwebProperties.SCOPES : token.scopes());
        detail.setCredentialVersion(detail.getCredentialVersion() + 1);
    }
    private ApiException invalid() { return new ApiException(ApiCode.BAD_REQUEST, "워크스페이스에 저장된 아임웹 스토어를 선택해 주세요."); }
    private ApiException reauth() { return new ApiException(ApiCode.BAD_REQUEST, "아임웹 사이트를 다시 연결해 주세요."); }
    private ApiException changed() { return new ApiException(ApiCode.BAD_REQUEST, "아임웹 연결 정보가 변경되었습니다. 스토어를 다시 선택해 주세요."); }
    public record Store(Long assetId, Long connectionId, String siteCode, String unitCode, String name,
                        String storeUrl, String currency, String connectionName, boolean requiresReauth) {}
    public record Unit(String unitCode, String name, String currency, String storeUrl, boolean selected) {}
    public record Credentials(long connectionId, String siteCode, String accessToken, String refreshToken,
                              LocalDateTime expiresAt, String scopes, long credentialVersion) {
        @Override public String toString() { return "ImwebCredentials[REDACTED]"; }
    }
    public record Context(long workspaceId, long userId, long connectionId, long assetId, String siteCode,
                          String unitCode, String currency, String accessToken, long credentialVersion) {
        @Override public String toString() { return "ImwebContext[REDACTED]"; }
    }
}
