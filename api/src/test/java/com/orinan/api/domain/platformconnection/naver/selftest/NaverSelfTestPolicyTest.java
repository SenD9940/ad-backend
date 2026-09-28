package com.orinan.api.domain.platformconnection.naver.selftest;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.db.user.UserEntity;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaverSelfTestPolicyTest {
    private final NaverSelfTestProperties properties = new NaverSelfTestProperties();
    private final MockEnvironment environment = new MockEnvironment();
    private final PlatformConnectionService permissions = mock(PlatformConnectionService.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final EntityManager manager = mock(EntityManager.class);
    private final NaverSelfTestPolicy policy = new NaverSelfTestPolicy(properties, environment, permissions, workspaces, manager);
    private final WorkspaceEntity workspace = WorkspaceEntity.builder().id(1L)
            .user(UserEntity.builder().id(2L).build()).build();

    @BeforeEach void setUp() {
        environment.setActiveProfiles("local");
        properties.setAppId("configured-app"); properties.setAppSecret("configured-secret");
        properties.getSelfTest().setEnabled(true); properties.getSelfTest().setWorkspaceId(1L);
        when(workspaces.findById(1L)).thenReturn(Optional.of(workspace));
    }

    @Test void ownerOfConfiguredLocalWorkspaceCanUseServerCredentialsWithoutExposingThemInCapabilities() {
        assertThat(policy.availability(1L,2L)).isEqualTo(new NaverSelfTestPolicy.Availability(true,null));
        var credentials = policy.requireConfiguredOwner(1L,2L);
        assertThat(credentials.appId()).isEqualTo("configured-app");
        assertThat(credentials.appSecret()).isEqualTo("configured-secret");
        assertThat(credentials.toString()).doesNotContain("configured-app","configured-secret");
        verify(manager,times(2)).refresh(workspace);
    }

    @Test void configuredDefaultLocalProfileWorksWithoutExplicitActiveProfile() {
        environment.setActiveProfiles(); environment.setDefaultProfiles("local");
        assertThat(policy.availability(1L,2L).available()).isTrue();
        environment.setActiveProfiles("prod");
        assertThat(policy.availability(1L,2L).available()).isFalse();
    }

    @ParameterizedTest @CsvSource({"prod,", "production,", "test,", "local,prod", "local,production"})
    void productionAndNonLocalProfilesCannotUseServerCredentials(String first, String second) {
        environment.setActiveProfiles(second == null ? new String[]{first} : new String[]{first, second});
        assertThat(policy.availability(1L,2L).available()).isFalse();
        assertThatThrownBy(() -> policy.requireConfiguredOwner(1L,2L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(workspaces,manager);
    }

    @Test void disabledMissingCredentialAndWrongWorkspaceAreUnavailable() {
        assertUnavailable(9L);
        properties.getSelfTest().setEnabled(false); assertUnavailable(1L);
        properties.getSelfTest().setEnabled(true);
        properties.setAppSecret(" "); assertUnavailable(1L);
        properties.setAppSecret("configured-secret"); properties.setAppId(null); assertUnavailable(1L);
        properties.setAppId("configured-app"); properties.getSelfTest().setWorkspaceId(0L); assertUnavailable(1L);
        verifyNoInteractions(workspaces,manager);
    }

    @Test void workspaceMembersCanReadUnavailableStatusButCannotUseSharedApp() {
        assertThat(policy.availability(1L,3L).available()).isFalse();
        assertThatThrownBy(() -> policy.requireConfiguredOwner(1L,3L)).isInstanceOf(ApiException.class);
        verify(permissions,times(2)).requireMember(1L,3L);
    }

    @Test void nonMembersCannotProbeConfiguration() {
        doThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY)).when(permissions).requireMember(1L,3L);
        assertThatThrownBy(() -> policy.availability(1L,3L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.requireConfiguredOwner(1L,3L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(workspaces,manager);
    }

    @Test void ownerLossKeyRotationAndDisablementInvalidateAnInflightConnection() {
        var snapshot = policy.requireConfiguredOwner(1L,2L);
        workspace.setUser(UserEntity.builder().id(3L).build());
        assertThatThrownBy(() -> policy.requireUnchanged(workspace,2L,snapshot)).isInstanceOf(ApiException.class);
        workspace.setUser(UserEntity.builder().id(2L).build()); properties.setAppSecret("rotated-secret");
        assertThatThrownBy(() -> policy.requireUnchanged(workspace,2L,snapshot)).isInstanceOf(ApiException.class);
        properties.setAppSecret("configured-secret"); properties.getSelfTest().setEnabled(false);
        assertThatThrownBy(() -> policy.requireUnchanged(workspace,2L,snapshot)).isInstanceOf(ApiException.class);
    }

    @Test void propertiesBindExistingAppKeysAndRequireExplicitOptIn() {
        var configured = Binder.get(new MockEnvironment().withProperty("app.naver-commerce.app-id","app")
                .withProperty("app.naver-commerce.app-secret","secret")
                .withProperty("app.naver-commerce.self-test.workspace-id","1"))
                .bind("app.naver-commerce",NaverSelfTestProperties.class).get();
        assertThat(configured.getAppId()).isEqualTo("app"); assertThat(configured.getAppSecret()).isEqualTo("secret");
        assertThat(configured.getSelfTest().getWorkspaceId()).isEqualTo(1L);
        assertThat(configured.getSelfTest().isEnabled()).isFalse();
    }

    private void assertUnavailable(long workspaceId) {
        assertThat(policy.availability(workspaceId,2L).available()).isFalse();
        assertThatThrownBy(() -> policy.requireConfiguredOwner(workspaceId,2L)).isInstanceOf(ApiException.class);
    }
}
