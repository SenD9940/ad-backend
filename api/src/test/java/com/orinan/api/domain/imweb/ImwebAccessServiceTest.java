package com.orinan.api.domain.imweb;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.imweb.client.ImwebApiClient.Token;
import com.orinan.api.domain.imweb.service.ImwebAccessService;
import com.orinan.api.domain.imweb.service.ImwebAccessService.Context;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.db.imwebasset.*;
import com.orinan.db.imwebconnection.*;
import com.orinan.db.platformasset.*;
import com.orinan.db.platformasset.enums.*;
import com.orinan.db.platformconnection.*;
import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.workspace.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImwebAccessServiceTest {
    PlatformConnectionService permissions=mock(PlatformConnectionService.class);
    PlatformConnectionRepository connections=mock(PlatformConnectionRepository.class);
    ImwebConnectionRepository details=mock(ImwebConnectionRepository.class);
    PlatformAssetRepository assets=mock(PlatformAssetRepository.class);
    ImwebAssetRepository assetDetails=mock(ImwebAssetRepository.class);
    WorkspaceRepository workspaces=mock(WorkspaceRepository.class);
    UserService users=mock(UserService.class); EntityManager em=mock(EntityManager.class);
    ImwebAccessService service=new ImwebAccessService(permissions,connections,details,assets,assetDetails,workspaces,users,em,ImwebPropertiesTest.configured());
    UserEntity user=UserEntity.builder().id(2L).status(UserStatus.REGISTERED).build();
    WorkspaceEntity workspace=WorkspaceEntity.builder().id(1L).user(user).build();
    PlatformConnectionEntity connection=PlatformConnectionEntity.builder().id(3L).workspace(workspace).providerType(ProviderType.IMWEB)
            .externalAccountId("Stest123").requiresReauth(false).build();
    ImwebConnectionEntity detail=ImwebConnectionEntity.builder().connectionId(3L).connection(connection).clientId("test-id")
            .accessToken("access").refreshToken("refresh").expiresAt(LocalDateTime.now().plusHours(2)).credentialVersion(1).build();
    @BeforeEach void setup() {
        when(workspaces.findById(1L)).thenReturn(Optional.of(workspace));when(workspaces.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace));
        when(users.findByIdAndStatusWithThrow(2L,UserStatus.REGISTERED)).thenReturn(user);
        when(connections.findByIdAndWorkspaceId(3L,1L)).thenReturn(Optional.of(connection));when(details.findById(3L)).thenReturn(Optional.of(detail));
    }
    @Test void credentialsRefreshCurrentMembershipAndEntitiesBeforeUse() {
        var result=service.credentials(1L,3L,2L);assertThat(result.accessToken()).isEqualTo("access");
        verify(em).refresh(workspace);verify(em).refresh(user);verify(em).refresh(connection);verify(em).refresh(detail);
        verify(permissions).requireMember(1L,2L);assertThat(result.toString()).doesNotContain("access","refresh");
    }
    @Test void anotherProviderCannotExposeTokens() {
        connection.setProviderType(ProviderType.NAVER);assertThatThrownBy(()->service.credentials(1L,3L,2L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(details);
    }
    @Test void adminRevocationRejectsUnexpiredTokens() {
        connection.setRequiresReauth(true);assertThatThrownBy(()->service.credentials(1L,3L,2L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(details);
    }
    @Test void disabledUserIsRejectedEvenIfOldRequestHadPassedAuthentication() {
        user.setStatus(UserStatus.UNREGISTERED);assertThatThrownBy(()->service.credentials(1L,3L,2L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(details);
    }
    @Test void concurrentReconnectCannotBeOverwrittenOrInvalidatedByOldRefresh() {
        var expected=service.credentials(1L,3L,2L);detail.setCredentialVersion(2);detail.setAccessToken("replacement");
        assertThatThrownBy(()->service.replaceToken(1L,2L,expected,new Token("stale","stale-refresh",detail.getExpiresAt(),null))).isInstanceOf(ApiException.class);
        service.requireReauth(1L,2L,expected);assertThat(connection.getRequiresReauth()).isFalse();verify(details,never()).saveAndFlush(any());
    }
    @Test void contextChecksAssetWorkspaceProviderCurrencyAndCurrentVersion() {
        var asset=PlatformAssetEntity.builder().id(4L).workspaceId(1L).connectionId(3L).platformType(PlatformType.IMWEB).assetType(AssetType.STORE).externalId("utest123").build();
        when(assets.findByIdAndWorkspaceId(4L,1L)).thenReturn(Optional.of(asset));
        var ad=ImwebAssetEntity.builder().assetId(4L).asset(asset).siteCode("Stest123").unitCode("utest123").currency("KRW").build();
        when(assetDetails.findById(4L)).thenReturn(Optional.of(ad));
        Context ctx=new Context(1,2,3,4,"Stest123","utest123","KRW","access",1);
        service.requireUnchanged(ctx);ad.setCurrency("USD");
        assertThatThrownBy(()->service.requireUnchanged(ctx)).isInstanceOf(ApiException.class);
        ad.setCurrency("KRW");detail.setCredentialVersion(2);
        assertThatThrownBy(()->service.requireUnchanged(ctx)).isInstanceOf(ApiException.class);
    }
}
