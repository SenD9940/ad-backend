package com.orinan.api.domain.imweb;
import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.imweb.business.ImwebConnectionBusiness;
import com.orinan.api.domain.imweb.client.ImwebApiClient;
import com.orinan.api.domain.imweb.client.ImwebProperties;
import com.orinan.api.domain.imweb.service.*;
import com.orinan.api.domain.imweb.service.ImwebAccessService.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImwebConnectionBusinessTest {
    ImwebAccessService access=mock(ImwebAccessService.class);
    ImwebApiClient client=mock(ImwebApiClient.class);
    ImwebOAuthStateService states=mock(ImwebOAuthStateService.class);
    ImwebRefreshLease leases=mock(ImwebRefreshLease.class);
    ImwebProperties properties=ImwebPropertiesTest.configured();
    ImwebConnectionBusiness business=new ImwebConnectionBusiness(access,client,properties,states,leases);
    JsonMapper json=new JsonMapper();
    String site="Stest123", unit="utest123";
    ImwebApiClient.Token token=new ImwebApiClient.Token("access", "refresh", SeoulDateTimes.now().plusHours(2), ImwebProperties.SCOPES);
    void state() { when(states.consume("state","cookie")).thenReturn(new ImwebOAuthStateService.Owner(1,2,site)); }
    void provider() { when(client.exchangeCode("code")).thenReturn(token); when(client.site("access")).thenReturn(json.readTree("{\"siteCode\":\"Stest123\",\"unitList\":[{\"unitCode\":\"utest123\",\"name\":\"My shop\"}]}")); }
    @Test void completionOrderRechecksOwnerAndFinishesIntegrationBeforeSite() {
        state(); provider(); business.complete("state","cookie","code",null);
        var order=inOrder(access,client);
        order.verify(access).requireOwner(1L,2L);order.verify(client).exchangeCode("code");
        order.verify(access).requireOwner(1L,2L);order.verify(client).completeIntegration("access");
        order.verify(access).requireOwner(1L,2L);order.verify(client).site("access");
        order.verify(access).save(1L,2L,site,"My shop",token);
    }
    @Test void cancellationConsumesStateButNeverCallsProvider() {
        state();assertThatThrownBy(()->business.complete("state","cookie",null,"access_denied")).isInstanceOf(ApiException.class);
        verifyNoInteractions(client);verify(access,never()).save(anyLong(),anyLong(),anyString(),anyString(),any());
    }
    @Test void wrongSiteIsNeverPersisted() {
        state();provider();when(client.site("access")).thenReturn(json.readTree("{\"siteCode\":\"Sother12\",\"unitList\":[]}"));
        assertThatThrownBy(()->business.complete("state","cookie","code",null)).isInstanceOf(ApiException.class);
        verify(access,never()).save(anyLong(),anyLong(),anyString(),anyString(),any());
    }
    @Test void completedIntegrationCanBeReauthorizedOnlyAfterSiteVerification() {
        state();provider();doThrow(new ImwebApiClient.IntegrationStateException()).when(client).completeIntegration("access");
        business.complete("state","cookie","code",null);
        verify(client).site("access");verify(access).save(1L,2L,site,"My shop",token);
    }
    @Test void removedOwnerCannotCompleteIntegration() {
        state();provider();doNothing().doThrow(new ApiException(ApiCode.BAD_REQUEST)).when(access).requireOwner(1L,2L);
        assertThatThrownBy(()->business.complete("state","cookie","code",null)).isInstanceOf(ApiException.class);
        verify(client,never()).completeIntegration(anyString());
    }
    @Test void missingGrantedPermissionIsRejectedBeforeRemoteWrite() {
        state();when(client.exchangeCode("code")).thenReturn(new ImwebApiClient.Token("access","refresh",token.expiresAt(),"product:read"));
        assertThatThrownBy(()->business.complete("state","cookie","code",null)).isInstanceOf(ApiException.class);
        verify(client,never()).completeIntegration(anyString());
    }
    Credentials credentials(boolean expired) {return new Credentials(3L,site,"old","old-refresh",expired?SeoulDateTimes.now().minusMinutes(1):SeoulDateTimes.now().plusHours(1),ImwebProperties.SCOPES,1);}
    Store store() {return new Store(4L,3L,site,unit,"Shop","https://shop.imweb.me","KRW","Shop",false);}
    void contextSetup(Credentials c) {
        when(access.store(1L,4L,2L)).thenReturn(store());when(access.credentials(1L,3L,2L)).thenReturn(c);
        when(client.site(anyString())).thenReturn(json.readTree("{\"siteCode\":\"Stest123\",\"unitList\":[{\"unitCode\":\"utest123\"}]}"));
        when(client.unit(anyString(),eq(unit))).thenReturn(json.readTree("{\"siteCode\":\"Stest123\",\"unitCode\":\"utest123\",\"currency\":\"KRW\",\"primaryDomain\":\"shop.imweb.me\"}"));
    }
    @Test void contextIsOnlyReturnedAfterStoreMembershipAndCredentialsRechecked() {
        contextSetup(credentials(false));Context context=business.context(1L,4L,2L);
        assertThat(context.accessToken()).isEqualTo("old");verify(access).requireUnchanged(context);
        verifyNoInteractions(leases);assertThat(context.toString()).doesNotContain("old");
    }
    @Test void tokenRotationUsesLeaseAndPersistsBeforeReturningNewContext() {
        var expected=credentials(true);contextSetup(expected);when(leases.acquire(3L)).thenReturn("lease");
        when(client.refreshToken("old-refresh")).thenReturn(token);
        when(access.replaceToken(1L,2L,expected,token)).thenReturn(new Credentials(3L,site,"access","refresh",token.expiresAt(),token.scopes(),2));
        Context context=business.context(1L,4L,2L);assertThat(context.credentialVersion()).isEqualTo(2);
        verify(leases,times(2)).requireOwned(3L,"lease");verify(leases).release(3L,"lease");
        verify(client,times(1)).refreshToken("old-refresh");
    }
    @Test void ambiguousRefreshCannotBeReplayedAndMarksExpectedConnectionForReauth() {
        var expected=credentials(true);contextSetup(expected);when(leases.acquire(3L)).thenReturn("lease");
        when(client.refreshToken("old-refresh")).thenThrow(new ApiException(ApiCode.SERVER_ERROR,"unknown"));
        assertThatThrownBy(()->business.context(1L,4L,2L)).isInstanceOf(ApiException.class);
        verify(access).requireReauth(1L,2L,expected);verify(leases).release(3L,"lease");verify(client,times(1)).refreshToken(anyString());
    }
    @Test void concurrentRefreshLeasePreventsRemoteRequest() {
        contextSetup(credentials(true));when(leases.acquire(3L)).thenThrow(new ApiException(ApiCode.BAD_REQUEST));
        assertThatThrownBy(()->business.context(1L,4L,2L)).isInstanceOf(ApiException.class);verify(client,never()).refreshToken(anyString());
    }
    @Test void cannotSaveUnverifiedUnitOrAcceptCallerProvidedNames() {
        contextSetup(credentials(false));when(access.stores(1L,2L)).thenReturn(List.of());
        assertThatThrownBy(()->business.selectUnits(1L,3L,2L,List.of("umissing123"))).isInstanceOf(ApiException.class);
        verify(access,never()).saveUnits(anyLong(),anyLong(),any(),anyList());
    }
    @Test void returnedUnitIsSanitizedAndSavedByUpstreamIdentity() {
        contextSetup(credentials(false));when(access.stores(1L,2L)).thenReturn(List.of());
        business.selectUnits(1L,3L,2L,List.of(unit,unit));
        verify(access).saveUnits(eq(1L),eq(2L),any(),eq(List.of(new Unit(unit,unit,"KRW","https://shop.imweb.me",false))));
    }
}
