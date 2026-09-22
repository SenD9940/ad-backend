package com.orinan.api.domain.platformconnection;

import com.orinan.db.crypto.AesGcmStringEncryptor;
import com.orinan.db.crypto.CryptoProperties;
import com.orinan.db.naverasset.NaverAssetEntity;
import com.orinan.db.naverasset.NaverAssetRepository;
import com.orinan.db.naverconnection.NaverConnectionEntity;
import com.orinan.db.naverconnection.NaverConnectionRepository;
import com.orinan.db.naverconnection.enums.NaverTokenType;
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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@ActiveProfiles("test")
@ContextConfiguration(classes = NaverConnectionPersistenceTest.Config.class)
class NaverConnectionPersistenceTest {

    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private PlatformConnectionRepository connections;
    @Autowired private NaverConnectionRepository naverConnections;
    @Autowired private PlatformAssetRepository assets;
    @Autowired private NaverAssetRepository naverAssets;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void storesCredentialsEncryptedInTheWorkspaceConnectionExtension() {
        var workspace = workspace();
        var connection = connection(workspace);
        var expiresAt = LocalDateTime.of(2026, 9, 22, 12, 0);
        String secret = "test-naver-client-secret";
        String token = "test-naver-access-token";
        var extension = naverConnections.saveAndFlush(NaverConnectionEntity.builder()
                .connection(connection).clientId("test-client-id").clientSecret(secret)
                .tokenType(NaverTokenType.SELLER).accountId("seller-uid-123")
                .accessToken(token).expiresAt(expiresAt).build());

        assertThat(extension.getConnectionId()).isEqualTo(connection.getId());
        assertThat(extension.toString()).doesNotContain(secret, token);
        var storedSecret = jdbc.queryForObject(
                "select client_secret from naver_connections where connection_id = ?",
                String.class, connection.getId());
        var storedToken = jdbc.queryForObject(
                "select access_token from naver_connections where connection_id = ?",
                String.class, connection.getId());
        assertThat(storedSecret).startsWith("v1:").doesNotContain(secret);
        assertThat(storedToken).startsWith("v1:").doesNotContain(token);
        entityManager.clear();

        var reloaded = naverConnections.findById(connection.getId()).orElseThrow();
        assertThat(reloaded.getClientSecret()).isEqualTo(secret);
        assertThat(reloaded.getAccessToken()).isEqualTo(token);
        assertThat(reloaded.getClientId()).isEqualTo("test-client-id");
        assertThat(reloaded.getTokenType()).isEqualTo(NaverTokenType.SELLER);
        assertThat(reloaded.getAccountId()).isEqualTo("seller-uid-123");
        assertThat(reloaded.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(reloaded.getConnection().getWorkspace().getId()).isEqualTo(workspace.getId());
        assertThat(reloaded.getConnection().getProviderType()).isEqualTo(ProviderType.NAVER);
        assertThat(connections.findByWorkspaceIdAndProviderTypeAndExternalAccountId(
                workspace.getId(), ProviderType.NAVER, "verified-seller-id"))
                .get().extracting(PlatformConnectionEntity::getId).isEqualTo(connection.getId());
    }

    @Test
    void permitsSelfCredentialsWithoutASellerAccountIdAndPersistsTokenReplacementEncrypted() {
        var connection = connection(workspace());
        naverConnections.saveAndFlush(NaverConnectionEntity.builder()
                .connection(connection).clientId("self-client-id").clientSecret("self-secret")
                .tokenType(NaverTokenType.SELF).accessToken("initial-token")
                .expiresAt(LocalDateTime.of(2026, 9, 21, 12, 0)).build());
        entityManager.clear();

        var stored = naverConnections.findById(connection.getId()).orElseThrow();
        stored.setAccessToken("replacement-token");
        stored.setExpiresAt(LocalDateTime.of(2026, 9, 22, 12, 0));
        naverConnections.flush();
        entityManager.clear();

        var reloaded = naverConnections.findById(connection.getId()).orElseThrow();
        assertThat(reloaded.getTokenType()).isEqualTo(NaverTokenType.SELF);
        assertThat(reloaded.getAccountId()).isNull();
        assertThat(reloaded.getAccessToken()).isEqualTo("replacement-token");
        assertThat(reloaded.getClientSecret()).isEqualTo("self-secret");
        assertThat(jdbc.queryForObject(
                "select access_token from naver_connections where connection_id = ?",
                String.class, connection.getId())).startsWith("v1:").doesNotContain("replacement-token");
    }

    @Test
    void storesSmartStoreChannelDetailsUsingTheCommonAssetId() {
        var workspace = workspace();
        var connection = connection(workspace);
        var asset = assets.saveAndFlush(PlatformAssetEntity.builder()
                .workspaceId(workspace.getId()).connectionId(connection.getId())
                .platformType(PlatformType.NAVER_SMART_STORE).assetType(AssetType.STORE)
                .externalId("123456789").name("우리 스마트스토어").build());
        var extension = naverAssets.saveAndFlush(NaverAssetEntity.builder()
                .asset(asset).channelType("STOREFARM")
                .channelUrl("https://smartstore.naver.com/example").build());

        assertThat(extension.getAssetId()).isEqualTo(asset.getId());
        entityManager.clear();

        var reloaded = naverAssets.findById(asset.getId()).orElseThrow();
        assertThat(reloaded.getChannelType()).isEqualTo("STOREFARM");
        assertThat(reloaded.getChannelUrl()).isEqualTo("https://smartstore.naver.com/example");
        assertThat(reloaded.getAsset().getWorkspaceId()).isEqualTo(workspace.getId());
        assertThat(reloaded.getAsset().getConnectionId()).isEqualTo(connection.getId());
        assertThat(reloaded.getAsset().getPlatformType()).isEqualTo(PlatformType.NAVER_SMART_STORE);
        assertThat(reloaded.getAsset().getAssetType()).isEqualTo(AssetType.STORE);
        assertThat(assets.findAllByConnectionIdOrderByIdAsc(connection.getId()))
                .extracting(PlatformAssetEntity::getId).containsExactly(asset.getId());
    }

    private WorkspaceEntity workspace() {
        var user = users.saveAndFlush(UserEntity.builder()
                .email("naver-owner@example.com").password("test-password")
                .status(UserStatus.REGISTERED).role(UserRole.CUSTOMER).build());
        return workspaces.saveAndFlush(WorkspaceEntity.builder().name("네이버 워크스페이스").user(user).build());
    }

    private PlatformConnectionEntity connection(WorkspaceEntity workspace) {
        return connections.saveAndFlush(PlatformConnectionEntity.builder()
                .workspace(workspace).providerType(ProviderType.NAVER)
                .externalAccountId("verified-seller-id").accountName("스마트스토어 판매자")
                .requiresReauth(false).build());
    }

    @Configuration
    @EntityScan(basePackageClasses = {UserEntity.class, UserProfileEntity.class, WorkspaceEntity.class, WorkspaceMemberEntity.class,
            PlatformConnectionEntity.class, NaverConnectionEntity.class, PlatformAssetEntity.class, NaverAssetEntity.class})
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, WorkspaceRepository.class,
            PlatformConnectionRepository.class, NaverConnectionRepository.class, PlatformAssetRepository.class,
            NaverAssetRepository.class})
    static class Config {
        @Bean
        AesGcmStringEncryptor encryptor() {
            var testKey = Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
            return new AesGcmStringEncryptor(new CryptoProperties(testKey));
        }
    }
}
