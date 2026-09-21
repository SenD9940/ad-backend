package com.orinan.api.domain.platformconnection;

import com.orinan.api.common.api.Api;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.platformconnection.business.PlatformConnectionBusiness;
import com.orinan.api.domain.platformconnection.controller.model.MetaAssetSelectRequest;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import com.orinan.api.domain.platformconnection.meta.MetaProperties;
import com.orinan.api.domain.platformconnection.service.MetaOAuthStateService;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.domain.workspace.service.WorkspaceService;
import com.orinan.api.domain.workspacemember.service.WorkspaceMemberService;
import com.orinan.db.crypto.AesGcmStringEncryptor;
import com.orinan.db.crypto.CryptoProperties;
import com.orinan.db.metaasset.MetaAssetEntity;
import com.orinan.db.metaasset.MetaAssetRepository;
import com.orinan.db.metaconnection.MetaConnectionEntity;
import com.orinan.db.metaconnection.MetaConnectionRepository;
import com.orinan.db.platformasset.PlatformAssetEntity;
import com.orinan.db.platformasset.PlatformAssetRepository;
import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import com.orinan.db.platformconnection.PlatformConnectionRepository;
import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.UserRepository;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.userprofile.UserProfileEntity;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspace.WorkspaceRepository;
import com.orinan.db.workspacemember.WorkspaceMemberEntity;
import com.orinan.db.workspacemember.WorkspaceMemberId;
import com.orinan.db.workspacemember.WorkspaceMemberRepository;
import com.orinan.db.workspacemember.enums.WorkspaceMemberRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@ActiveProfiles("test")
@ContextConfiguration(classes = MetaConnectionPersistenceTest.Config.class)
class MetaConnectionPersistenceTest {

    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private PlatformConnectionRepository connections;
    @Autowired private MetaConnectionRepository metaConnections;
    @Autowired private PlatformAssetRepository assets;
    @Autowired private MetaAssetRepository metaAssets;
    @Autowired private WorkspaceMemberRepository members;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void membersUseWorkspaceTokenAndLoseAccessWhenMembershipIsRemoved() {
        var workspace = workspace("공유 계정 워크스페이스");
        var member = users.saveAndFlush(UserEntity.builder().email("shared-member@example.com")
                .password("test-password").status(UserStatus.REGISTERED).role(UserRole.CUSTOMER).build());
        var memberId = new WorkspaceMemberId(workspace.getId(), member.getId());
        members.saveAndFlush(WorkspaceMemberEntity.builder().id(memberId).role(WorkspaceMemberRole.MEMBER).build());
        var connection = connection(workspace, "owner-meta-account");
        String workspaceToken = "workspace-shared-secret-token";
        metaConnections.saveAndFlush(MetaConnectionEntity.builder().connection(connection)
                .accessToken(workspaceToken).expiresAt(SeoulDateTimes.now().plusDays(30))
                .grantedScopes("ads_read").build());
        var service = new PlatformConnectionService(connections, metaConnections, assets, metaAssets,
                new WorkspaceService(workspaces), workspaces, new WorkspaceMemberService(members),
                new UserService(users, mock(PasswordEncoder.class)), entityManager);
        var client = mock(MetaGraphClient.class);
        var business = new PlatformConnectionBusiness(service, client,
                mock(MetaOAuthStateService.class), mock(MetaProperties.class));
        var available = new MetaGraphClient.DiscoveredAsset("act_shared", "공유 광고 계정",
                PlatformType.FACEBOOK, AssetType.AD_ACCOUNT, null);
        when(client.discoverAssets(workspaceToken)).thenReturn(List.of(available));

        assertThat(business.discoverMetaAssets(workspace.getId(), connection.getId(), member.getId()))
                .containsExactly(available);
        var request = new MetaAssetSelectRequest(List.of(new MetaAssetSelectRequest.Selection(
                available.externalId(), available.platformType(), available.assetType())));
        var saved = business.selectMetaAssets(workspace.getId(), connection.getId(), member.getId(), request);

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).externalId()).isEqualTo("act_shared");
        assertThat(connections.findAllByWorkspaceIdOrderByIdDesc(workspace.getId())).hasSize(1);
        assertThat(metaConnections.count()).isEqualTo(1);
        verify(client, times(2)).discoverAssets(workspaceToken);
        var mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        String json = mapper.writeValueAsString(Api.OK(service.findAll(workspace.getId(), member.getId())));
        assertThat(json).contains("act_shared", "workspace_id")
                .doesNotContain(workspaceToken, "access_token", "refresh_token", "accessToken");

        members.deleteById(memberId);
        members.flush();
        entityManager.clear();
        clearInvocations(client);
        assertThatThrownBy(() -> business.discoverMetaAssets(workspace.getId(), connection.getId(), member.getId()))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));
        assertThatThrownBy(() -> business.selectMetaAssets(workspace.getId(), connection.getId(), member.getId(), request))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));
        verifyNoInteractions(client);
        assertThat(metaConnections.findById(connection.getId()).orElseThrow().getAccessToken()).isEqualTo(workspaceToken);
        assertThat(service.findAll(workspace.getId(), workspace.getUser().getId())).hasSize(1);
    }

    @Test
    void storesSharedConnectionAndEncryptedMetaCredentialsWithTheSameId() {
        var workspace = workspace("광고 워크스페이스");
        var connection = connection(workspace, "facebook-user-123");
        var expiresAt = LocalDateTime.of(2026, 10, 1, 12, 0);
        var token = "test-meta-access-token-never-store-plaintext";
        var credentials = metaConnections.saveAndFlush(MetaConnectionEntity.builder()
                .connection(connection)
                .accessToken(token)
                .expiresAt(expiresAt)
                .grantedScopes("ads_read,ads_management,pages_show_list")
                .build());

        assertThat(credentials.getConnectionId()).isEqualTo(connection.getId());
        var storedToken = jdbc.queryForObject(
                "select access_token from meta_connections where connection_id = ?",
                String.class, connection.getId());
        assertThat(storedToken).startsWith("v1:").doesNotContain(token);
        entityManager.clear();

        var reloaded = metaConnections.findById(connection.getId()).orElseThrow();
        assertThat(reloaded.getAccessToken()).isEqualTo(token);
        assertThat(reloaded.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(reloaded.getGrantedScopes()).isEqualTo("ads_read,ads_management,pages_show_list");
        assertThat(reloaded.getConnection().getWorkspace().getId()).isEqualTo(workspace.getId());
        assertThat(reloaded.getConnection().getProviderType()).isEqualTo(ProviderType.META);
        assertThat(reloaded.getConnection().getAccountName()).isEqualTo("Meta 광고 운영자");
        assertThat(connections.findByWorkspaceIdAndProviderTypeAndExternalAccountId(
                workspace.getId(), ProviderType.META, "facebook-user-123"))
                .get().extracting(PlatformConnectionEntity::getId).isEqualTo(connection.getId());
    }

    @Test
    void storesInstagramAssetAndItsFacebookPageExtensionWithTheSameId() {
        var workspace = workspace("인스타그램 워크스페이스");
        var connection = connection(workspace, "facebook-user-123");
        var asset = asset(connection, PlatformType.INSTAGRAM, AssetType.PROFILE, "instagram-456");
        var extension = metaAssets.saveAndFlush(MetaAssetEntity.builder()
                .asset(asset).facebookPageId("facebook-page-789").build());

        assertThat(extension.getAssetId()).isEqualTo(asset.getId());
        entityManager.clear();

        var reloaded = metaAssets.findById(asset.getId()).orElseThrow();
        assertThat(reloaded.getFacebookPageId()).isEqualTo("facebook-page-789");
        assertThat(reloaded.getAsset().getExternalId()).isEqualTo("instagram-456");
        assertThat(reloaded.getAsset().getWorkspaceId()).isEqualTo(workspace.getId());
        assertThat(reloaded.getAsset().getConnectionId()).isEqualTo(connection.getId());
        assertThat(assets.findAllByConnectionIdOrderByIdAsc(connection.getId()))
                .extracting(PlatformAssetEntity::getId).containsExactly(asset.getId());
    }

    @Test
    void keepsDisconnectedAssetsWithoutReturningThemForAnActiveConnection() {
        var workspace = workspace("연결 해제 이력 워크스페이스");
        var connection = connection(workspace, "facebook-user-123");
        var linked = asset(connection, PlatformType.INSTAGRAM, AssetType.PROFILE, "instagram-456");
        var firstHistorical = assets.saveAndFlush(PlatformAssetEntity.builder()
                .workspaceId(workspace.getId()).connectionId(null)
                .platformType(PlatformType.INSTAGRAM).assetType(AssetType.PROFILE)
                .externalId("instagram-456").name("과거 연결 1").build());
        var secondHistorical = assets.saveAndFlush(PlatformAssetEntity.builder()
                .workspaceId(workspace.getId()).connectionId(null)
                .platformType(PlatformType.INSTAGRAM).assetType(AssetType.PROFILE)
                .externalId("instagram-456").name("과거 연결 2").build());
        entityManager.clear();

        assertThat(assets.findById(firstHistorical.getId()).orElseThrow().getConnectionId()).isNull();
        assertThat(assets.findById(secondHistorical.getId()).orElseThrow().getConnectionId()).isNull();
        assertThat(assets.findAllByConnectionIdOrderByIdAsc(connection.getId()))
                .extracting(PlatformAssetEntity::getId).containsExactly(linked.getId());
    }

    @Test
    void permitsTheSameExternalAccountInDifferentWorkspacesButRejectsDuplicateConnection() {
        var firstWorkspace = workspace("첫 번째 워크스페이스");
        var secondWorkspace = workspace("두 번째 워크스페이스");
        var first = connection(firstWorkspace, "shared-facebook-user");
        var second = connection(secondWorkspace, "shared-facebook-user");

        assertThat(connections.findAllByWorkspaceIdOrderByIdDesc(firstWorkspace.getId()))
                .extracting(PlatformConnectionEntity::getId).containsExactly(first.getId());
        assertThat(connections.findAllByWorkspaceIdOrderByIdDesc(secondWorkspace.getId()))
                .extracting(PlatformConnectionEntity::getId).containsExactly(second.getId());
        assertThat(connections.findByIdAndWorkspaceId(first.getId(), secondWorkspace.getId())).isEmpty();
        assertThatThrownBy(() -> connection(firstWorkspace, "shared-facebook-user"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void scopesAssetUniquenessToConnectionPlatformAndType() {
        var workspace = workspace("복수 연결 워크스페이스");
        var firstConnection = connection(workspace, "facebook-user-1");
        var secondConnection = connection(workspace, "facebook-user-2");
        var first = asset(firstConnection, PlatformType.INSTAGRAM, AssetType.PROFILE, "same-id");
        var second = asset(secondConnection, PlatformType.INSTAGRAM, AssetType.PROFILE, "same-id");
        var otherPlatform = asset(firstConnection, PlatformType.FACEBOOK, AssetType.PROFILE, "same-id");
        var otherType = asset(firstConnection, PlatformType.FACEBOOK, AssetType.PAGE, "same-id");

        assertThat(assets.findAllByConnectionIdOrderByIdAsc(firstConnection.getId()))
                .extracting(PlatformAssetEntity::getId)
                .containsExactly(first.getId(), otherPlatform.getId(), otherType.getId());
        assertThat(assets.findAllByConnectionIdOrderByIdAsc(secondConnection.getId()))
                .extracting(PlatformAssetEntity::getId).containsExactly(second.getId());
        assertThatThrownBy(() -> asset(firstConnection, PlatformType.INSTAGRAM, AssetType.PROFILE, "same-id"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private WorkspaceEntity workspace(String name) {
        var user = users.saveAndFlush(UserEntity.builder()
                .email(name + "@example.com").password("test-password")
                .status(UserStatus.REGISTERED).role(UserRole.CUSTOMER).build());
        return workspaces.saveAndFlush(WorkspaceEntity.builder().name(name).user(user).build());
    }

    private PlatformConnectionEntity connection(WorkspaceEntity workspace, String externalAccountId) {
        return connections.saveAndFlush(PlatformConnectionEntity.builder()
                .workspace(workspace).providerType(ProviderType.META)
                .externalAccountId(externalAccountId).accountName("Meta 광고 운영자")
                .requiresReauth(false).build());
    }

    private PlatformAssetEntity asset(PlatformConnectionEntity connection, PlatformType platform,
                                      AssetType type, String externalId) {
        return assets.saveAndFlush(PlatformAssetEntity.builder()
                .workspaceId(connection.getWorkspace().getId()).connectionId(connection.getId())
                .platformType(platform).assetType(type).externalId(externalId).name("광고 자산").build());
    }

    @Configuration
    @EntityScan(basePackageClasses = {UserEntity.class, UserProfileEntity.class, WorkspaceEntity.class, WorkspaceMemberEntity.class,
            PlatformConnectionEntity.class, MetaConnectionEntity.class, PlatformAssetEntity.class, MetaAssetEntity.class})
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, WorkspaceRepository.class,
            PlatformConnectionRepository.class, MetaConnectionRepository.class, PlatformAssetRepository.class,
            MetaAssetRepository.class, WorkspaceMemberRepository.class})
    static class Config {
        @Bean
        AesGcmStringEncryptor encryptor() {
            var testKey = Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
            return new AesGcmStringEncryptor(new CryptoProperties(testKey));
        }
    }
}
