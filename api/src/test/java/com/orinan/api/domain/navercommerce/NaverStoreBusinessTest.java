package com.orinan.api.domain.navercommerce;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.navercommerce.business.NaverStoreBusiness;
import com.orinan.api.domain.navercommerce.client.NaverStoreClient;
import com.orinan.api.domain.navercommerce.controller.model.NaverStoreResponse.Store;
import com.orinan.api.domain.navercommerce.service.NaverSalesAggregator;
import com.orinan.api.domain.navercommerce.service.NaverStoreAccessService;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import com.orinan.api.domain.platformconnection.service.NaverConnectionService;
import com.orinan.api.domain.platformconnection.service.NaverConnectionService.Credentials;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaverStoreBusinessTest {
    private final NaverStoreAccessService access = mock(NaverStoreAccessService.class);
    private final NaverConnectionService connections = mock(NaverConnectionService.class);
    private final NaverCommerceClient commerce = mock(NaverCommerceClient.class);
    private final NaverStoreClient client = mock(NaverStoreClient.class);
    private final NaverSalesAggregator aggregator = spy(new NaverSalesAggregator());
    private final NaverStoreBusiness business = new NaverStoreBusiness(access, connections, commerce, client, aggregator);
    private final Store store = new Store(10L, 20L, "123", "스토어", null, "연결", false);
    private final Credentials credentials = credentials("old", false);
    private final LocalDate start = LocalDate.of(2026, 1, 1);

    private Credentials credentials(String token, boolean expired) {
        return new Credentials("seller", "app", "secret", NaverTokenType.SELF, null, token,
                expired ? SeoulDateTimes.now().minusMinutes(1) : SeoulDateTimes.now().plusHours(1));
    }
    @BeforeEach void setup() {
        when(access.get(1L, 10L, 2L)).thenReturn(store);
        when(connections.getCredentials(1L, 20L, 2L)).thenReturn(credentials);
        when(commerce.getChannels(any())).thenReturn(List.of(new NaverCommerceClient.Channel(123, "STOREFARM", "스토어", null)));
    }
    private void emptyProducts() {
        when(client.searchProducts(eq("app"), any(), eq("STOREFARM"), eq(1), eq(20), anyLong()))
                .thenReturn(new NaverStoreClient.ProductPage(List.of(), 1, 20, 40, 2, true));
    }
    private Credentials refreshSetup(Credentials old) {
        var next = credentials("new", false);
        var issued = new NaverCommerceClient.IssuedToken("new", next.expiresAt());
        when(commerce.issueToken("app", "secret", NaverTokenType.SELF, null)).thenReturn(issued);
        when(commerce.getSellerAccount("new")).thenReturn(new NaverCommerceClient.SellerAccount("account", "seller"));
        when(connections.updateToken(1L, 20L, 2L, old, issued)).thenReturn(next);
        return next;
    }

    @Test void catalogUsesSavedChannelButDoesNotMislabelSellerTotalAsChannelTotal() {
        emptyProducts();
        var result = business.products(1L, 10L, 2L, 1, 20);
        assertThat(result.channelNo()).isEqualTo("123");
        assertThat(result.totalElements()).isNull();
        assertThat(result.hasNext()).isTrue();
        verify(access, times(2)).requireUnchanged(1L, 2L, store);
        verify(connections, times(2)).requireUnchanged(1L, 20L, 2L, credentials);
    }

    @Test void missingSavedChannelAndAmbiguousCatalogNeverFetchProducts() {
        when(commerce.getChannels("old")).thenReturn(List.of(new NaverCommerceClient.Channel(456, "STOREFARM", "다른 스토어", null)));
        assertThatThrownBy(() -> business.products(1L, 10L, 2L, 1, 20)).isInstanceOf(ApiException.class);
        when(commerce.getChannels("old")).thenReturn(List.of(new NaverCommerceClient.Channel(123, "STOREFARM", "스토어", null),
                new NaverCommerceClient.Channel(456, "STOREFARM", "다른 스토어", null)));
        assertThatThrownBy(() -> business.products(1L, 10L, 2L, 1, 20)).isInstanceOf(ApiException.class);
        verifyNoInteractions(client);
    }

    @Test void invalidPeriodsAndPaginationFailBeforeCredentialOrProviderAccess() {
        for (var dates : List.of(new LocalDate[]{start.plusDays(1), start}, new LocalDate[]{start, start.plusDays(31)},
                new LocalDate[]{LocalDate.now(SeoulDateTimes.ZONE), LocalDate.now(SeoulDateTimes.ZONE).plusDays(1)})) {
            assertThatThrownBy(() -> business.sales(1L, 10L, 2L, dates[0], dates[1])).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> business.products(1L, 10L, 2L, 0, 20)).isInstanceOf(ApiException.class);
        verifyNoInteractions(access, connections, commerce, client, aggregator);
    }

    @Test void expiredTokenIsRefreshedWithSameSellerBeforeReads() {
        var expired = credentials("old", true);
        when(connections.getCredentials(1L, 20L, 2L)).thenReturn(expired);
        refreshSetup(expired);
        emptyProducts();
        business.products(1L, 10L, 2L, 1, 20);
        verify(commerce, never()).getChannels("old");
        verify(client).searchProducts(eq("app"), eq("new"), eq("STOREFARM"), eq(1), eq(20), anyLong());
    }

    @Test void authenticationFailureRefreshesOnlyOnceAndRechecksAccountAndAuthorization() {
        refreshSetup(credentials);
        when(client.searchProducts(eq("app"), any(), eq("STOREFARM"), eq(1), eq(20), anyLong()))
                .thenThrow(new NaverCommerceClient.AuthenticationException());
        assertThatThrownBy(() -> business.products(1L, 10L, 2L, 1, 20)).isInstanceOf(ApiException.class);
        verify(commerce, times(1)).issueToken("app", "secret", NaverTokenType.SELF, null);
        verify(client, times(2)).searchProducts(eq("app"), any(), eq("STOREFARM"), eq(1), eq(20), anyLong());
        verify(connections).markRequiresReauth(eq(1L), eq(20L), eq(2L), argThat(c -> "new".equals(c.accessToken())));
    }

    @Test void sellerChangeDuringRefreshNeverReadsCatalogOrStoresToken() {
        when(commerce.getChannels("old")).thenThrow(new NaverCommerceClient.AuthenticationException());
        refreshSetup(credentials);
        when(commerce.getSellerAccount("new")).thenReturn(new NaverCommerceClient.SellerAccount("other", "other-uid"));
        assertThatThrownBy(() -> business.products(1L, 10L, 2L, 1, 20)).isInstanceOf(ApiException.class);
        verify(connections).markRequiresReauth(1L, 20L, 2L, credentials);
        verify(connections, never()).updateToken(any(), any(), any(), any(), any());
        verifyNoInteractions(client);
    }

    @Test void permissionRevokedDuringRemoteReadPreventsReturningData() {
        emptyProducts();
        doNothing().doThrow(new ApiException(ApiCode.BAD_REQUEST)).when(access).requireUnchanged(1L, 2L, store);
        assertThatThrownBy(() -> business.products(1L, 10L, 2L, 1, 20)).isInstanceOf(ApiException.class);
        verify(client).searchProducts(eq("app"), eq("old"), eq("STOREFARM"), eq(1), eq(20), anyLong());
    }

    @Test void allOrderPagesAndEachKoreanDayAreReadBeforeAggregation() {
        var deadline = new AtomicLong();
        when(client.getOrders(eq("app"), any(), eq(123L), any(), any(), anyInt(), eq(300), anyLong())).thenAnswer(invocation -> {
            OffsetDateTime from = invocation.getArgument(3), to = invocation.getArgument(4);
            int page = invocation.getArgument(5);
            assertThat(from.getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
            assertThat(from.toLocalTime()).hasToString("00:00");
            assertThat(to).isEqualTo(from.plusDays(1).minusNanos(1_000_000));
            long actualDeadline = invocation.getArgument(7);
            deadline.compareAndSet(0, actualDeadline);
            assertThat(actualDeadline).isEqualTo(deadline.get()).isGreaterThan(System.nanoTime());
            return new NaverStoreClient.OrderPage(List.of(), page, 300, page == 1);
        });
        var result = business.sales(1L, 10L, 2L, start, start.plusDays(1));
        assertThat(result.complete()).isTrue();
        assertThat(result.daily()).hasSize(2);
        verify(client, times(4)).getOrders(eq("app"), eq("old"), eq(123L), any(), any(), anyInt(), eq(300), anyLong());
        verify(aggregator).aggregate(store, start, start.plusDays(1), List.of());
    }

    @Test void upstreamErrorAndPageLimitNeverReturnPartialAggregates() {
        when(client.getOrders(eq("app"), any(), anyLong(), any(), any(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new NaverStoreClient.OrderPage(List.of(), 1, 300, true))
                .thenThrow(new ApiException(ApiCode.SERVER_ERROR));
        assertThatThrownBy(() -> business.sales(1L, 10L, 2L, start, start)).isInstanceOf(ApiException.class);
        reset(client);
        when(client.getOrders(eq("app"), any(), anyLong(), any(), any(), anyInt(), anyInt(), anyLong()))
                .thenAnswer(i -> new NaverStoreClient.OrderPage(List.of(), i.getArgument(5), 300, true));
        assertThatThrownBy(() -> business.sales(1L, 10L, 2L, start, start)).isInstanceOf(ApiException.class);
        verify(client, times(62)).getOrders(eq("app"), any(), anyLong(), any(), any(), anyInt(), anyInt(), anyLong());
        verifyNoInteractions(aggregator);
    }

    @Test void authenticationRefreshPreservesTheReportDeadlineAndApplicationRateLimitScope() {
        var next = refreshSetup(credentials);
        var deadline = new AtomicLong();
        when(client.getOrders(eq("app"), any(), eq(123L), any(), any(), eq(1), eq(300), anyLong()))
                .thenAnswer(invocation -> {
                    long actualDeadline = invocation.getArgument(7);
                    deadline.compareAndSet(0, actualDeadline);
                    assertThat(actualDeadline).isEqualTo(deadline.get());
                    if ("old".equals(invocation.getArgument(1))) throw new NaverCommerceClient.AuthenticationException();
                    assertThat((String) invocation.getArgument(1)).isEqualTo(next.accessToken());
                    return new NaverStoreClient.OrderPage(List.of(), 1, 300, false);
                });
        assertThat(business.sales(1L, 10L, 2L, start, start).complete()).isTrue();
        verify(client, times(2)).getOrders(eq("app"), any(), eq(123L), any(), any(), eq(1), eq(300), eq(deadline.get()));
        verify(aggregator, times(1)).aggregate(store, start, start, List.of());
    }
}
