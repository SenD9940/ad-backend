package com.orinan.api.domain.platformconnection.naver.selftest;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaverSelfTestBusinessTest {
    private final NaverSelfTestPolicy policy = mock(NaverSelfTestPolicy.class);
    private final NaverCommerceClient client = mock(NaverCommerceClient.class);
    private final NaverSelfTestService service = mock(NaverSelfTestService.class);
    private final NaverSelfTestBusiness business = new NaverSelfTestBusiness(policy,client,service);
    private final NaverSelfTestPolicy.Credentials credentials = new NaverSelfTestPolicy.Credentials("app","server-secret");

    @Test void usesOnlyConfiguredSelfCredentialsAndVerifiedSellerBeforePersistence() {
        var token = new NaverCommerceClient.IssuedToken("token",SeoulDateTimes.now().plusHours(1));
        var account = new NaverCommerceClient.SellerAccount("verified-seller","verified-uid");
        when(policy.requireConfiguredOwner(1L,2L)).thenReturn(credentials);
        when(client.issueToken("app","server-secret",NaverTokenType.SELF,null)).thenReturn(token);
        when(client.getSellerAccount("token")).thenReturn(account);
        business.connect(1L,2L);
        var ordered = inOrder(policy,client,service);
        ordered.verify(policy).requireConfiguredOwner(1L,2L);
        ordered.verify(client).issueToken("app","server-secret",NaverTokenType.SELF,null);
        ordered.verify(client).getSellerAccount("token");
        ordered.verify(service).save(1L,2L,credentials,token,account);
        verifyNoMoreInteractions(client);
    }

    @Test void unauthorizedOrDisabledConnectionNeverCallsNaver() {
        when(policy.requireConfiguredOwner(1L,2L)).thenThrow(new ApiException(ApiCode.BAD_REQUEST));
        assertThatThrownBy(() -> business.connect(1L,2L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(client,service);
    }

    @Test void failedTokenDoesNotSaveOrRetry() {
        when(policy.requireConfiguredOwner(1L,2L)).thenReturn(credentials);
        when(client.issueToken("app","server-secret",NaverTokenType.SELF,null)).thenThrow(new ApiException(ApiCode.BAD_REQUEST));
        assertThatThrownBy(() -> business.connect(1L,2L)).isInstanceOf(ApiException.class);
        verify(client,times(1)).issueToken("app","server-secret",NaverTokenType.SELF,null);
        verify(client,never()).getSellerAccount(any()); verifyNoInteractions(service);
    }
}
