package com.orinan.api.domain.imweb;

import com.orinan.db.crypto.AesGcmStringEncryptor;
import com.orinan.db.crypto.CryptoProperties;
import com.orinan.db.imwebasset.ImwebAssetEntity;
import com.orinan.db.imwebasset.ImwebAssetRepository;
import com.orinan.db.imwebconnection.ImwebConnectionEntity;
import com.orinan.db.imwebconnection.ImwebConnectionRepository;
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
@ContextConfiguration(classes = ImwebPersistenceTest.Config.class)
class ImwebPersistenceTest {
    @Autowired UserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired PlatformConnectionRepository connections;
    @Autowired ImwebConnectionRepository credentials;
    @Autowired PlatformAssetRepository assets;
    @Autowired ImwebAssetRepository stores;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Test void oauthCredentialsAreEncryptedAndMappedBySharedConnectionId() {
        var connection = connection();
        credentials.saveAndFlush(ImwebConnectionEntity.builder().connection(connection).clientId("test-id")
                .accessToken("secret-access").refreshToken("secret-refresh").expiresAt(LocalDateTime.of(2026,9,29,12,0))
                .credentialVersion(1).grantedScopes("product:read").build());
        var row=jdbc.queryForMap("select access_token, refresh_token from imweb_connections where connection_id=?",connection.getId());
        assertThat(row.get("access_token").toString()).startsWith("v1:").doesNotContain("secret-access");
        assertThat(row.get("refresh_token").toString()).startsWith("v1:").doesNotContain("secret-refresh");
        em.clear();var detail=credentials.findById(connection.getId()).orElseThrow();
        assertThat(detail.getAccessToken()).isEqualTo("secret-access");assertThat(detail.getRefreshToken()).isEqualTo("secret-refresh");
        assertThat(detail.toString()).doesNotContain("secret-access","secret-refresh");
        assertThat(detail.getCredentialVersion()).isEqualTo(1);
    }
    @Test void savedUnitUsesCommonAssetAndRetainsSiteCurrency() {
        var connection=connection();
        var asset=assets.saveAndFlush(PlatformAssetEntity.builder().workspaceId(connection.getWorkspace().getId())
                .connectionId(connection.getId()).platformType(PlatformType.IMWEB).assetType(AssetType.STORE)
                .externalId("utest123").name("My shop").build());
        stores.saveAndFlush(ImwebAssetEntity.builder().asset(asset).siteCode("Stest123").unitCode("utest123")
                .currency("KRW").storeUrl("https://shop.imweb.me").build());em.clear();
        var store=stores.findById(asset.getId()).orElseThrow();
        assertThat(store.getAsset().getPlatformType()).isEqualTo(PlatformType.IMWEB);
        assertThat(store.getSiteCode()).isEqualTo("Stest123");assertThat(store.getCurrency()).isEqualTo("KRW");
    }
    private PlatformConnectionEntity connection() {
        var user=users.saveAndFlush(UserEntity.builder().email("imweb-owner@example.com").password("test-password")
                .status(UserStatus.REGISTERED).role(UserRole.CUSTOMER).build());
        var workspace=workspaces.saveAndFlush(WorkspaceEntity.builder().name("Imweb workspace").user(user).build());
        return connections.saveAndFlush(PlatformConnectionEntity.builder().workspace(workspace).providerType(ProviderType.IMWEB)
                .externalAccountId("Stest123").accountName("My shop").requiresReauth(false).build());
    }
    @Configuration
    @EntityScan(basePackageClasses = {UserEntity.class, UserProfileEntity.class, WorkspaceEntity.class, WorkspaceMemberEntity.class,
            PlatformConnectionEntity.class, ImwebConnectionEntity.class, PlatformAssetEntity.class, ImwebAssetEntity.class})
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, WorkspaceRepository.class,
            PlatformConnectionRepository.class, ImwebConnectionRepository.class, PlatformAssetRepository.class,
            ImwebAssetRepository.class})
    static class Config {
        @Bean
        AesGcmStringEncryptor encryptor() {
            var testKey = Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
            return new AesGcmStringEncryptor(new CryptoProperties(testKey));
        }
    }
}
