package com.orinan.api.domain.navercommerce;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.navercommerce.business.NaverOrderBusiness;
import com.orinan.api.domain.navercommerce.client.NaverOrderClient;
import com.orinan.api.domain.navercommerce.controller.model.NaverOrderActionRequest;
import com.orinan.api.domain.navercommerce.controller.model.NaverOrderResponse.*;
import com.orinan.api.domain.navercommerce.controller.model.NaverStoreResponse.Store;
import com.orinan.api.domain.navercommerce.service.NaverOrderProjection;
import com.orinan.api.domain.navercommerce.service.NaverOrderSessionService;
import com.orinan.api.domain.navercommerce.service.NaverOrderWriteGuard;
import com.orinan.api.domain.navercommerce.service.NaverStoreAccessService;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import com.orinan.api.domain.platformconnection.service.NaverConnectionService;
import com.orinan.api.domain.platformconnection.service.NaverConnectionService.Credentials;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaverOrderBusinessTest {
    private static final String ID = "2026092900000001";
    private static final String VERSION = "a".repeat(64);
    private static final String REQUEST = "00000000-0000-4000-8000-000000000001";
    private final NaverStoreAccessService access = mock(NaverStoreAccessService.class);
    private final NaverConnectionService connections = mock(NaverConnectionService.class);
    private final NaverCommerceClient commerce = mock(NaverCommerceClient.class);
    private final NaverOrderClient client = mock(NaverOrderClient.class);
    private final NaverOrderProjection projection = mock(NaverOrderProjection.class);
    private final NaverOrderWriteGuard guard = mock(NaverOrderWriteGuard.class);
    private final NaverOrderSessionService session = mock(NaverOrderSessionService.class);
    private final NaverOrderBusiness business = new NaverOrderBusiness(access, connections, commerce, client, projection, guard, session);
    private final JsonMapper json = JsonMapper.builder().build();
    private final Store store = new Store(10L, 20L, "123", "스토어", null, "연결", false);
    private final Credentials credentials = credentials("old", false);
    private final NaverOrderWriteGuard.Lease lease = new NaverOrderWriteGuard.Lease("lock", "owner");
    private final LocalDate day = LocalDate.of(2026, 1, 1);

    @BeforeEach void setUp() {
        when(access.get(1L, 10L, 2L)).thenReturn(store);
        when(connections.getCredentials(1L, 20L, 2L)).thenReturn(credentials);
        when(commerce.getChannels(any())).thenReturn(List.of(channel(123)));
        when(guard.acquire("seller", ID, REQUEST)).thenReturn(lease);
        when(client.detail(eq("app"), any(), eq(ID), anyLong())).thenReturn(node("{\"data\":[" + content("123") + "]}"));
        when(projection.detail(eq(10L), eq("123"), any())).thenReturn(detail(VERSION, "CONFIRM"));
    }

    @Test void orderListFiltersOtherChannelsButRetainsSellerPagination() {
        String own = "{\"productOrderId\":\"" + ID + "\",\"content\":" + content("123") + "}";
        String other = "{\"productOrderId\":\"111\",\"content\":{\"productOrder\":{\"merchantChannelId\":\"456\"},\"ordererName\":\"PRIVATE\"}}";
        when(client.orders(eq("app"), eq("old"), any(), any(), eq("ORDERED_DATETIME"), isNull(), eq(2), eq(20), anyLong()))
                .thenReturn(node("{\"data\":{\"contents\":[" + own + "," + other + "],\"pagination\":{\"page\":2,\"size\":20,\"hasNext\":true}}}"));
        when(projection.summary(any())).thenReturn(order());
        var result = business.orders(1L, 10L, 2L, day, "ORDERED_DATETIME", null, 2, 20);
        assertThat(result.items()).containsExactly(order());
        assertThat(result.hasNext()).isTrue();
        assertThat(result.page()).isEqualTo(2);
        verify(projection, times(1)).summary(any());
        verify(client).orders(eq("app"), eq("old"), eq(day.atStartOfDay(SeoulDateTimes.ZONE).toOffsetDateTime()),
                eq(day.plusDays(1).atStartOfDay(SeoulDateTimes.ZONE).toOffsetDateTime().minusNanos(1_000_000)),
                eq("ORDERED_DATETIME"), isNull(), eq(2), eq(20), anyLong());
    }

    @Test void malformedPagesAndMismatchedIdsCannotBecomeAValidOrderList() {
        when(projection.summary(any())).thenReturn(order());
        for (String body : List.of("{\"data\":{\"contents\":[],\"pagination\":{\"page\":1,\"size\":20,\"hasNext\":true}}}",
                "{\"data\":{\"contents\":[],\"pagination\":{\"page\":2,\"size\":20,\"hasNext\":false}}}",
                "{\"data\":{\"contents\":[{\"productOrderId\":\"different\",\"content\":" + content("123") + "}],\"pagination\":{\"page\":1,\"size\":20,\"hasNext\":false}}}")) {
            when(client.orders(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyLong())).thenReturn(node(body));
            assertThatThrownBy(() -> business.orders(1L, 10L, 2L, day, "ORDERED_DATETIME", null, 1, 20)).isInstanceOf(ApiException.class);
        }
    }

    @Test void foreignChannelDetailRejectionPreventsAllWritesAndReleasesLease() {
        when(client.detail(eq("app"), any(), eq(ID), anyLong())).thenReturn(node("{\"data\":[" + content("456") + "]}"));
        when(projection.detail(eq(10L), eq("123"), any())).thenAnswer(invocation ->
                new NaverOrderProjection(json).detail(10L, "123", invocation.getArgument(2)));
        assertThatThrownBy(() -> business.act(1L, 10L, 2L, ID, action("CONFIRM"))).hasMessageContaining("선택한 스마트스토어");
        verify(projection).detail(eq(10L), eq("123"), argThat(row -> "456".equals(row.path("productOrder").path("merchantChannelId").asString())));
        noWrites();
        verify(guard).release(lease);
    }

    @Test void staleSnapshotAndUnavailableActionAreRejectedAfterFreshProviderRead() {
        when(projection.detail(eq(10L), eq("123"), any())).thenReturn(detail("b".repeat(64), "CONFIRM"));
        assertThatThrownBy(() -> business.act(1L, 10L, 2L, ID, action("CONFIRM"))).hasMessageContaining("변경되었습니다");
        when(projection.detail(eq(10L), eq("123"), any())).thenReturn(detail(VERSION, "DISPATCH"));
        assertThatThrownBy(() -> business.act(1L, 10L, 2L, ID, action("CONFIRM"))).hasMessageContaining("현재 주문 상태");
        verify(client, times(2)).detail(eq("app"), eq("old"), eq(ID), anyLong());
        noWrites();
    }

    @Test void permissionRevokedAfterFreshReadOrLostLeaseBlocksMutation() {
        var checks = new AtomicInteger();
        doAnswer(invocation -> { if (checks.incrementAndGet() == 5) throw new ApiException(ApiCode.BAD_REQUEST, "revoked"); return null; })
                .when(access).requireUnchanged(1L, 2L, store);
        assertThatThrownBy(() -> business.act(1L, 10L, 2L, ID, action("CONFIRM"))).hasMessageContaining("revoked");
        noWrites();
        doNothing().when(access).requireUnchanged(1L, 2L, store);
        doThrow(new ApiException(ApiCode.BAD_REQUEST, "lease expired")).when(guard).requireOwned(lease);
        assertThatThrownBy(() -> business.act(1L, 10L, 2L, ID, action("CONFIRM"))).hasMessageContaining("lease expired");
        noWrites();
    }

    @Test void confirmUsesItsSpecialSuccessShapeAndWarnsOnReceiverAddressChange() {
        when(client.confirm("old", ID)).thenReturn(node("{\"data\":{\"successProductOrderInfos\":[{\"productOrderId\":\"" + ID + "\",\"isReceiverAddressChanged\":true}]}}"));
        var result = business.act(1L, 10L, 2L, ID, action("CONFIRM"));
        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(result.notice()).contains("주소 변경");
        verify(client, times(1)).confirm("old", ID);
        verify(guard).requireOwned(lease);
        verify(guard).release(lease);
        verify(guard, never()).hold(any());
    }

    @Test void sessionRevokedImmediatelyBeforeWriteBlocksMutationAndReleasesLease() {
        var checks = new AtomicInteger();
        doAnswer(invocation -> {
            if (checks.incrementAndGet() == 5) throw new ApiException(ApiCode.BAD_REQUEST, "session revoked");
            return null;
        }).when(session).requireCurrent(2L);
        assertThatThrownBy(() -> business.act(1L, 10L, 2L, ID, action("CONFIRM"))).hasMessageContaining("session revoked");
        assertThat(checks).hasValue(5);
        noWrites();
        verify(guard).release(lease);
        verify(guard, never()).hold(any());
    }

    @Test void providerBusinessRejectionIsConclusiveAndDoesNotHoldOrReplay() {
        when(client.confirm("old", ID)).thenReturn(node("{\"data\":{\"failProductOrderInfos\":[{\"productOrderId\":\"" + ID + "\",\"message\":\"PRIVATE-PROVIDER-DATA\"}]}}"));
        assertThatThrownBy(() -> business.act(1L, 10L, 2L, ID, action("CONFIRM"))).isInstanceOf(ApiException.class)
                .isNotInstanceOf(NaverOrderClient.UnknownWrite.class).hasMessageContaining("거절").hasMessageNotContaining("PRIVATE-PROVIDER-DATA");
        verify(client, times(1)).confirm("old", ID);
        verify(guard).release(lease);
        verify(guard, never()).hold(any());
    }

    @Test void ambiguousHttpSuccessHoldsTheLeaseInsteadOfClaimingSuccess() {
        for (String response : List.of("{}", "{\"data\":{\"successProductOrderIds\":[\"" + ID + "\"]}}",
                "{\"data\":{\"successProductOrderInfos\":[{\"productOrderId\":\"other\"}]}}",
                "{\"data\":{\"successProductOrderInfos\":[{\"productOrderId\":\"" + ID + "\"}],\"failProductOrderInfos\":[{\"productOrderId\":\"" + ID + "\"}]}}")) {
            when(client.confirm("old", ID)).thenReturn(node(response));
            assertThatThrownBy(() -> business.act(1L, 10L, 2L, ID, action("CONFIRM"))).isInstanceOf(NaverOrderClient.UnknownWrite.class);
        }
        verify(client, times(4)).confirm("old", ID);
        verify(guard, times(4)).hold(lease);
        verify(guard, never()).release(any());
    }

    @Test void unknownWriteAndServerFailureHoldButKnownBadRequestReleasesWithoutRefresh() {
        doThrow(new NaverOrderClient.UnknownWrite()).when(client).confirm("old", ID);
        assertThatThrownBy(() -> business.act(1L, 10L, 2L, ID, action("CONFIRM"))).isInstanceOf(NaverOrderClient.UnknownWrite.class);
        doThrow(new ApiException(ApiCode.SERVER_ERROR)).when(client).confirm("old", ID);
        assertThatThrownBy(() -> business.act(1L, 10L, 2L, ID, action("CONFIRM"))).isInstanceOf(NaverOrderClient.UnknownWrite.class);
        doThrow(new ApiException(ApiCode.BAD_REQUEST)).when(client).confirm("old", ID);
        assertThatThrownBy(() -> business.act(1L, 10L, 2L, ID, action("CONFIRM"))).isNotInstanceOf(NaverOrderClient.UnknownWrite.class);
        verify(client, times(3)).confirm("old", ID);
        verify(guard, times(2)).hold(lease);
        verify(guard).release(lease);
        verify(commerce, never()).issueToken(any(), any(), any(), any());
    }

    @Test void cleanupFailureDoesNotHideAnAcceptedMutation() {
        when(client.confirm("old", ID)).thenReturn(node("{\"data\":{\"successProductOrderInfos\":[{\"productOrderId\":\"" + ID + "\"}]}}"));
        doThrow(new IllegalStateException("redis offline")).when(guard).release(lease);
        assertThat(business.act(1L, 10L, 2L, ID, action("CONFIRM")).status()).isEqualTo("ACCEPTED");
        verify(client, times(1)).confirm("old", ID);
    }

    @Test void returnRequiresExplicitReceiptAcknowledgementAndUsesReturnApprovalOnly() {
        when(projection.detail(eq(10L), eq("123"), any())).thenReturn(detail(VERSION, "APPROVE_RETURN"));
        assertThatThrownBy(() -> business.act(1L, 10L, 2L, ID, action("APPROVE_RETURN"))).hasMessageContaining("수령");
        verifyNoInteractions(guard);
        when(client.approveReturn("old", ID)).thenReturn(node("{\"data\":{\"successProductOrderIds\":[\"" + ID + "\"]}}"));
        var accepted = new NaverOrderActionRequest("APPROVE_RETURN", VERSION, REQUEST, null, null, null, null, true);
        assertThat(business.act(1L, 10L, 2L, ID, accepted).status()).isEqualTo("ACCEPTED");
        verify(client).approveReturn("old", ID);
        verify(client, never()).approveCancel(any(), any());
    }

    @Test void settlementPreservesNegativeAndUnknownAmountsAndDoesNotExposeAccountNumbers() {
        when(client.settlement(eq("app"), eq("old"), eq(day), eq(day.plusDays(1)), eq(1), eq(20), anyLong()))
                .thenReturn(node("{\"elements\":[{\"settleExpectDate\":\"2026-01-01\",\"settleAmount\":-2000,\"paySettleAmount\":3000,\"commissionSettleAmount\":null,\"accountNumber\":\"PRIVATE-ACCOUNT\"}],\"pagination\":{\"page\":1,\"size\":20,\"totalPages\":1,\"totalElements\":1}}"));
        var result = business.settlements(1L, 10L, 2L, day, day.plusDays(1), 1, 20);
        assertThat(result.basis()).isEqualTo("SETTLEMENT_EXPECTED");
        assertThat(result.items().get(0).settleAmount()).isEqualByComparingTo("-2000");
        assertThat(result.items().get(0).commissionSettleAmount()).isNull();
        assertThat(result.items().get(0).settleCompleteDate()).isNull();
        assertThat(json.writeValueAsString(result)).doesNotContain("PRIVATE-ACCOUNT", "accountNumber");
    }

    @Test void multiChannelSettlementAndMissingSavedChannelFailBeforeDataRead() {
        when(commerce.getChannels("old")).thenReturn(List.of(channel(123), channel(456)));
        assertThatThrownBy(() -> business.settlements(1L, 10L, 2L, day, day, 1, 20)).hasMessageContaining("여러 채널");
        when(commerce.getChannels("old")).thenReturn(List.of(channel(456)));
        assertThatThrownBy(() -> business.detail(1L, 10L, 2L, ID)).hasMessageContaining("접근할 수 없습니다");
        verifyNoInteractions(client);
    }

    @Test void malformedSettlementTotalsDatesAndAmountsFailClosed() {
        for (String row : List.of("{\"settleExpectDate\":\"2026-01-02\",\"settleAmount\":0}",
                "{\"settleExpectDate\":null,\"settleAmount\":0}",
                "{\"settleExpectDate\":\"2026-01-01\",\"settleAmount\":\"123\"}")) {
            when(client.settlement(any(), any(), any(), any(), anyInt(), anyInt(), anyLong())).thenReturn(node(
                    "{\"elements\":[" + row + "],\"pagination\":{\"page\":1,\"size\":20,\"totalPages\":1,\"totalElements\":1}}"));
            assertThatThrownBy(() -> business.settlements(1L, 10L, 2L, day, day, 1, 20)).isInstanceOf(ApiException.class);
        }
        when(client.settlement(any(), any(), any(), any(), anyInt(), anyInt(), anyLong())).thenReturn(node(
                "{\"elements\":[],\"pagination\":{\"page\":1,\"size\":20,\"totalPages\":1,\"totalElements\":1}}"));
        assertThatThrownBy(() -> business.settlements(1L, 10L, 2L, day, day, 1, 20)).isInstanceOf(ApiException.class);
    }

    @Test void expiredTokenRefreshChecksSellerIdentityBeforeReadingPrivateOrderData() {
        var expired = credentials("old", true);
        when(connections.getCredentials(1L, 20L, 2L)).thenReturn(expired);
        refresh(expired);
        business.detail(1L, 10L, 2L, ID);
        verify(client).detail(eq("app"), eq("new"), eq(ID), anyLong());
        verify(commerce, never()).getChannels("old");
    }

    @Test void settlementExponentCannotBypassMaximumMoneyMagnitude() {
        when(client.settlement(any(), any(), any(), any(), anyInt(), anyInt(), anyLong())).thenReturn(node(
                "{\"elements\":[{\"settleExpectDate\":\"2026-01-01\",\"settleAmount\":1e100}],"
                        + "\"pagination\":{\"page\":1,\"size\":20,\"totalPages\":1,\"totalElements\":1}}"));
        assertThatThrownBy(() -> business.settlements(1L, 10L, 2L, day, day, 1, 20)).isInstanceOf(ApiException.class);
    }

    @Test void wrongSellerDuringRefreshMarksReauthenticationAndNeverFetchesPrivateOrders() {
        var expired = credentials("old", true);
        when(connections.getCredentials(1L, 20L, 2L)).thenReturn(expired);
        refresh(expired);
        when(commerce.getSellerAccount("new")).thenReturn(new NaverCommerceClient.SellerAccount("other", "wrong-seller"));
        assertThatThrownBy(() -> business.detail(1L, 10L, 2L, ID)).hasMessageContaining("판매자가 변경");
        verify(connections).markRequiresReauth(1L, 20L, 2L, expired);
        verify(connections, never()).updateToken(any(), any(), any(), any(), any());
        verifyNoInteractions(client);
    }

    @Test void readAuthenticationOnlyRefreshesOnceAndPermissionRevocationPreventsDataReturn() {
        refresh(credentials);
        when(client.detail(eq("app"), any(), eq(ID), anyLong())).thenThrow(new NaverCommerceClient.AuthenticationException());
        assertThatThrownBy(() -> business.detail(1L, 10L, 2L, ID)).isInstanceOf(NaverCommerceClient.AuthenticationException.class);
        verify(client, times(2)).detail(eq("app"), any(), eq(ID), anyLong());
        verify(commerce, times(1)).issueToken("app", "secret", NaverTokenType.SELF, null);
        verify(connections).markRequiresReauth(eq(1L), eq(20L), eq(2L), argThat(c -> "new".equals(c.accessToken())));
        doReturn(node("{\"data\":[" + content("123") + "]}")).when(client).detail(eq("app"), any(), eq(ID), anyLong());
        doNothing().doThrow(new ApiException(ApiCode.BAD_REQUEST, "revoked")).when(access).requireUnchanged(1L, 2L, store);
        assertThatThrownBy(() -> business.detail(1L, 10L, 2L, ID)).hasMessageContaining("revoked");
    }

    @Test void invalidInputsFailBeforeCredentialsAndMutationGuard() {
        assertThatThrownBy(() -> business.orders(1L, 10L, 2L, day, "BAD", null, 1, 20)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> business.orders(1L, 10L, 2L, LocalDate.now(SeoulDateTimes.ZONE).plusDays(1), "ORDERED_DATETIME", null, 1, 20)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> business.settlements(1L, 10L, 2L, day, day.plusDays(28), 1, 20)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> business.detail(1L, 10L, 2L, "../123")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> business.act(1L, 10L, 2L, ID, new NaverOrderActionRequest("APPROVE_CANCEL", "bad", REQUEST, null, null, null, null, null))).isInstanceOf(ApiException.class);
        verifyNoInteractions(access, connections, commerce, client, guard);
    }

    private void noWrites() {
        verify(client, never()).confirm(any(), any()); verify(client, never()).dispatch(any(), any(), any());
        verify(client, never()).approveCancel(any(), any()); verify(client, never()).approveReturn(any(), any());
    }
    private Credentials credentials(String token, boolean expired) { return new Credentials("seller", "app", "secret", NaverTokenType.SELF,
            null, token, expired ? SeoulDateTimes.now().minusMinutes(1) : SeoulDateTimes.now().plusHours(1)); }
    private void refresh(Credentials previous) {
        var next = credentials("new", false);
        var issued = new NaverCommerceClient.IssuedToken("new", next.expiresAt());
        when(commerce.issueToken("app", "secret", NaverTokenType.SELF, null)).thenReturn(issued);
        when(commerce.getSellerAccount("new")).thenReturn(new NaverCommerceClient.SellerAccount("account", "seller"));
        when(connections.updateToken(1L, 20L, 2L, previous, issued)).thenReturn(next);
    }
    private NaverCommerceClient.Channel channel(long id) { return new NaverCommerceClient.Channel(id, "STOREFARM", "스토어", null); }
    private NaverOrderActionRequest action(String action) { return new NaverOrderActionRequest(action, VERSION, REQUEST, null, null, null, null, null); }
    private Order order() { return new Order(ID, "order", "상품", null, "PAYED", "NOT_YET", null,
            OffsetDateTime.parse("2026-01-01T10:00:00+09:00"), OffsetDateTime.parse("2026-01-01T10:00:01+09:00"),
            2L, 1L, new BigDecimal("20000"), new BigDecimal("10000"), null); }
    private Detail detail(String version, String action) { return new Detail(10L, "123", order(), "신용카드", null, null,
            null, null, null, List.of(), List.of(), version, List.of(new Action(action, action, "")), "", Instant.now()); }
    private String content(String channel) { return "{\"order\":{\"orderId\":\"2026092900000002\"},\"productOrder\":{\"productOrderId\":\"" + ID
            + "\",\"merchantChannelId\":\"" + channel + "\",\"productName\":\"상품\",\"productOrderStatus\":\"PAYED\"}}"; }
    private JsonNode node(String value) { return json.readTree(value); }
}
