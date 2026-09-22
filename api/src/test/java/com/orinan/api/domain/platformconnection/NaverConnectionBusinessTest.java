package com.orinan.api.domain.platformconnection;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.platformconnection.business.NaverConnectionBusiness;
import com.orinan.api.domain.platformconnection.controller.model.NaverChannelSelectRequest;
import com.orinan.api.domain.platformconnection.controller.model.NaverConnectRequest;
import com.orinan.api.domain.platformconnection.controller.model.PlatformConnectionResponse;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.AuthenticationException;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.Channel;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.IssuedToken;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.SellerAccount;
import com.orinan.api.domain.platformconnection.service.NaverConnectionService;
import com.orinan.api.domain.platformconnection.service.NaverConnectionService.Credentials;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import com.orinan.db.platformconnection.enums.ProviderType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NaverConnectionBusinessTest {

    private final PlatformConnectionService platformConnections = mock(PlatformConnectionService.class);
    private final NaverConnectionService service = mock(NaverConnectionService.class);
    private final NaverCommerceClient client = mock(NaverCommerceClient.class);
    private final NaverConnectionBusiness business = new NaverConnectionBusiness(platformConnections, service, client);

    @Test
    void memberCannotExchangeCredentialsOrReplaceTheWorkspaceConnection() {
        doThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY)).when(platformConnections).requireOwner(10L, 2L);

        assertThatThrownBy(() -> business.connect(10L, 2L, request()))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));

        verifyNoInteractions(client, service);
    }

    @Test
    void connectResolvesCanonicalSellerIdentityBeforePersistingCredentials() {
        var request = request();
        var token = new IssuedToken("issued-token", SeoulDateTimes.now().plusHours(3));
        var account = new SellerAccount("seller-login", "canonical-seller-uid");
        var response = new PlatformConnectionResponse(20L, 10L, ProviderType.NAVER,
                account.accountUid(), account.accountId(), false, token.expiresAt(), List.of());
        when(client.issueToken("app-id", "app-secret", NaverTokenType.SELF, null)).thenReturn(token);
        when(client.getSellerAccount("issued-token")).thenReturn(account);
        when(service.saveConnection(10L, 1L, request, token, account)).thenReturn(response);

        assertThat(business.connect(10L, 1L, request)).isSameAs(response);

        var ordered = inOrder(platformConnections, client, service);
        ordered.verify(platformConnections).requireOwner(10L, 1L);
        ordered.verify(client).issueToken("app-id", "app-secret", NaverTokenType.SELF, null);
        ordered.verify(client).getSellerAccount("issued-token");
        ordered.verify(service).saveConnection(10L, 1L, request, token, account);
    }

    @Test
    void sellerTokenForAnotherSellerCannotBeSaved() {
        var request = request();
        request.setTokenType(NaverTokenType.SELLER);
        request.setAccountId("requested-seller");
        var token = new IssuedToken("issued-token", SeoulDateTimes.now().plusHours(3));
        when(client.issueToken("app-id", "app-secret", NaverTokenType.SELLER, "requested-seller")).thenReturn(token);
        when(client.getSellerAccount("issued-token")).thenReturn(new SellerAccount("other-login", "other-uid"));

        assertThatThrownBy(() -> business.connect(10L, 1L, request))
                .isInstanceOf(ApiException.class).hasMessageContaining("판매자가 일치하지 않습니다");

        verify(service, never()).saveConnection(any(), any(), any(), any(), any());
    }

    @Test
    void memberDiscoversOnlySmartStoreChannelsUsingTheWorkspaceToken() {
        var credentials = credentials("saved-token", SeoulDateTimes.now().plusHours(2));
        when(service.getCredentials(10L, 20L, 2L)).thenReturn(credentials);
        when(client.getChannels("saved-token")).thenReturn(List.of(store(), new Channel(99L, "WINDOW", "브랜드스토어", null)));

        assertThat(business.getChannels(10L, 20L, 2L)).containsExactly(store());

        var ordered = inOrder(service, client, platformConnections);
        ordered.verify(service).getCredentials(10L, 20L, 2L);
        ordered.verify(client).getChannels("saved-token");
        ordered.verify(platformConnections).requireMember(10L, 2L);
        verify(client, never()).issueToken(any(), any(), any(), any());
    }

    @Test
    void removedMemberCannotUseStoredCredentialsForDiscoveryOrSelection() {
        when(service.getCredentials(10L, 20L, 2L)).thenThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY));

        assertThatThrownBy(() -> business.getChannels(10L, 20L, 2L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> business.selectChannels(10L, 20L, 2L, new NaverChannelSelectRequest(List.of(123L))))
                .isInstanceOf(ApiException.class);

        verifyNoInteractions(client);
        verify(service, never()).saveChannels(any(), any(), any(), any(), any());
    }

    @Test
    void membershipRevokedDuringRemoteRequestPreventsReturningChannels() {
        when(service.getCredentials(10L, 20L, 2L)).thenReturn(credentials("saved-token", SeoulDateTimes.now().plusHours(2)));
        when(client.getChannels("saved-token")).thenReturn(List.of(store()));
        doThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY)).when(platformConnections).requireMember(10L, 2L);

        assertThatThrownBy(() -> business.getChannels(10L, 20L, 2L))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));

        verify(client).getChannels("saved-token");
    }

    @Test
    void expiredTokenIsRenewedAndSellerIsVerifiedBeforeChannelLookup() {
        var expired = credentials("expired-token", SeoulDateTimes.now().minusMinutes(1));
        var renewed = credentials("renewed-token", SeoulDateTimes.now().plusHours(3));
        var issued = stubRenewal(expired, renewed);

        assertThat(business.getChannels(10L, 20L, 2L)).containsExactly(store());

        var ordered = inOrder(client, service);
        ordered.verify(service).getCredentials(10L, 20L, 2L);
        ordered.verify(client).issueToken("app-id", "app-secret", NaverTokenType.SELF, null);
        ordered.verify(client).getSellerAccount("renewed-token");
        ordered.verify(service).updateToken(10L, 20L, 2L, expired, issued);
        ordered.verify(client).getChannels("renewed-token");
        verify(client, never()).getChannels("expired-token");
    }

    @Test
    void changedCanonicalSellerDuringRenewalInvalidatesConnectionWithoutSavingToken() {
        var expired = credentials("expired-token", SeoulDateTimes.now().minusMinutes(1));
        when(service.getCredentials(10L, 20L, 2L)).thenReturn(expired);
        when(client.issueToken("app-id", "app-secret", NaverTokenType.SELF, null))
                .thenReturn(new IssuedToken("other-seller-token", SeoulDateTimes.now().plusHours(3)));
        when(client.getSellerAccount("other-seller-token")).thenReturn(new SellerAccount("seller-login", "another-uid"));

        assertThatThrownBy(() -> business.getChannels(10L, 20L, 2L))
                .isInstanceOf(ApiException.class).hasMessageContaining("판매자가 변경되었습니다");

        verify(service).markRequiresReauth(10L, 20L, 2L, expired);
        verify(service, never()).updateToken(any(), any(), any(), any(), any());
        verify(client, never()).getChannels(any());
    }

    @Test
    void unauthorizedTokenRenewsOnceAndRetriesWithTheSavedToken() {
        var old = credentials("old-token", SeoulDateTimes.now().plusHours(1));
        var renewed = credentials("renewed-token", SeoulDateTimes.now().plusHours(3));
        stubRenewal(old, renewed);
        when(client.getChannels("old-token")).thenThrow(new AuthenticationException());

        assertThat(business.getChannels(10L, 20L, 2L)).containsExactly(store());

        verify(client, times(1)).issueToken("app-id", "app-secret", NaverTokenType.SELF, null);
        verify(client).getChannels("old-token");
        verify(client).getChannels("renewed-token");
        verify(service, never()).markRequiresReauth(any(), any(), any(), any());
    }

    @Test
    void secondAuthenticationFailureMarksReauthorizationAndDoesNotLoop() {
        var old = credentials("old-token", SeoulDateTimes.now().plusHours(1));
        var renewed = credentials("renewed-token", SeoulDateTimes.now().plusHours(3));
        stubRenewal(old, renewed);
        when(client.getChannels("old-token")).thenThrow(new AuthenticationException());
        when(client.getChannels("renewed-token")).thenThrow(new AuthenticationException());

        assertThatThrownBy(() -> business.getChannels(10L, 20L, 2L)).isInstanceOf(AuthenticationException.class);

        verify(client, times(1)).issueToken("app-id", "app-secret", NaverTokenType.SELF, null);
        verify(client, times(2)).getChannels(anyString());
        verify(service).markRequiresReauth(10L, 20L, 2L, renewed);
    }

    @Test
    void authenticationFailureWhileVerifyingRenewedTokenMarksTheOriginalConnection() {
        var original = credentials("old-token", SeoulDateTimes.now().plusHours(1));
        when(service.getCredentials(10L, 20L, 2L)).thenReturn(original);
        when(client.getChannels("old-token")).thenThrow(new AuthenticationException());
        when(client.issueToken("app-id", "app-secret", NaverTokenType.SELF, null))
                .thenReturn(new IssuedToken("new-token", SeoulDateTimes.now().plusHours(3)));
        when(client.getSellerAccount("new-token")).thenThrow(new AuthenticationException());

        assertThatThrownBy(() -> business.getChannels(10L, 20L, 2L)).isInstanceOf(AuthenticationException.class);

        verify(service).markRequiresReauth(10L, 20L, 2L, original);
        verify(service, never()).updateToken(any(), any(), any(), any(), any());
        verify(client, times(1)).getChannels(anyString());
        verify(client, never()).getChannels("new-token");
        verify(client, times(1)).issueToken("app-id", "app-secret", NaverTokenType.SELF, null);
    }

    @Test
    void permissionFailureDoesNotTriggerTokenRenewalOrPersistSelections() {
        when(service.getCredentials(10L, 20L, 2L)).thenReturn(credentials("saved-token", SeoulDateTimes.now().plusHours(2)));
        when(client.getChannels("saved-token")).thenThrow(new ApiException(ApiCode.BAD_REQUEST, "판매자 권한 없음"));

        assertThatThrownBy(() -> business.selectChannels(10L, 20L, 2L, new NaverChannelSelectRequest(List.of(123L))))
                .isInstanceOf(ApiException.class).hasMessage("판매자 권한 없음");

        verify(client, never()).issueToken(any(), any(), any(), any());
        verify(service, never()).markRequiresReauth(any(), any(), any(), any());
        verify(service, never()).saveChannels(any(), any(), any(), any(), any());
    }

    @Test
    void mixedValidAndUnownedSelectionRejectsTheWholeBatchBeforeSaving() {
        when(service.getCredentials(10L, 20L, 2L)).thenReturn(credentials("saved-token", SeoulDateTimes.now().plusHours(2)));
        when(client.getChannels("saved-token")).thenReturn(List.of(store()));

        assertThatThrownBy(() -> business.selectChannels(10L, 20L, 2L,
                new NaverChannelSelectRequest(List.of(123L, 999L))))
                .isInstanceOf(ApiException.class).hasMessageContaining("접근할 수 없습니다");

        verify(service, never()).saveChannels(any(), any(), any(), any(), any());
    }

    @Test
    void duplicateSelectionSavesOnlyTheServerVerifiedChannelMetadataOnce() {
        var credentials = credentials("saved-token", SeoulDateTimes.now().plusHours(2));
        when(service.getCredentials(10L, 20L, 2L)).thenReturn(credentials);
        when(client.getChannels("saved-token")).thenReturn(List.of(store()));

        business.selectChannels(10L, 20L, 2L, new NaverChannelSelectRequest(List.of(123L, 123L)));

        verify(service).saveChannels(10L, 20L, 2L, credentials, List.of(store()));
    }

    private IssuedToken stubRenewal(Credentials old, Credentials renewed) {
        var issued = new IssuedToken(renewed.accessToken(), renewed.expiresAt());
        when(service.getCredentials(10L, 20L, 2L)).thenReturn(old);
        when(client.issueToken("app-id", "app-secret", NaverTokenType.SELF, null)).thenReturn(issued);
        when(client.getSellerAccount(renewed.accessToken())).thenReturn(new SellerAccount("seller-login", "seller-uid"));
        when(service.updateToken(10L, 20L, 2L, old, issued)).thenReturn(renewed);
        when(client.getChannels(renewed.accessToken())).thenReturn(List.of(store()));
        return issued;
    }

    private NaverConnectRequest request() {
        var request = new NaverConnectRequest();
        request.setClientId("app-id");
        request.setClientSecret("app-secret");
        return request;
    }

    private Credentials credentials(String token, LocalDateTime expiry) {
        return new Credentials("seller-uid", "app-id", "app-secret", NaverTokenType.SELF, null, token, expiry);
    }

    private Channel store() {
        return new Channel(123L, "STOREFARM", "검증된 스토어명", "https://smartstore.naver.com/verified-store");
    }
}
