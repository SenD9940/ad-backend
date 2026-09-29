package com.orinan.api.domain.imweb;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.imweb.business.ImwebCommerceBusiness;
import com.orinan.api.domain.imweb.business.ImwebConnectionBusiness;
import com.orinan.api.domain.imweb.client.ImwebApiClient;
import com.orinan.api.domain.imweb.service.ImwebAccessService;
import com.orinan.api.domain.imweb.service.ImwebAccessService.Context;
import com.orinan.api.domain.imweb.service.ImwebSalesAggregator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImwebCommerceBusinessTest {
    private final ImwebConnectionBusiness connections = mock(ImwebConnectionBusiness.class);
    private final ImwebAccessService access = mock(ImwebAccessService.class);
    private final ImwebApiClient client = mock(ImwebApiClient.class);
    private final ImwebSalesAggregator aggregator = spy(new ImwebSalesAggregator());
    private final ImwebCommerceBusiness business = new ImwebCommerceBusiness(connections, access, client, aggregator);
    private final JsonMapper json = new JsonMapper();
    private final Context original = new Context(1, 2, 3, 4, "Stest123", "utest123", "KRW", "old-token", 1);
    private final Context refreshed = new Context(1, 2, 3, 4, "Stest123", "utest123", "KRW", "new-token", 2);
    private final LocalDate day = LocalDate.of(2026, 9, 1);

    @BeforeEach void setup() {
        when(connections.context(1L, 4L, 2L)).thenReturn(original);
        when(connections.refresh(original)).thenReturn(refreshed);
    }

    @Test void productReadRefreshesExactlyOnceAndChecksTheUpdatedCredentialContext() {
        when(client.read(eq("old-token"), eq("/products"), any())).thenThrow(new ImwebApiClient.AuthenticationException());
        when(client.read(eq("new-token"), eq("/products"), any())).thenReturn(page(1, 20, 1, List.of(product(1))));

        var result = business.products(1L, 4L, 2L, 1, 20);

        assertThat(result.items()).hasSize(1);
        verify(connections, times(1)).refresh(original);
        verify(client, times(1)).read(eq("old-token"), eq("/products"), any());
        verify(client, times(1)).read(eq("new-token"), eq("/products"), any());
        var order = inOrder(connections, access, client);
        order.verify(connections).context(1L, 4L, 2L);
        order.verify(access, times(2)).requireUnchanged(original);
        order.verify(client).read(eq("old-token"), eq("/products"), any());
        order.verify(connections).refresh(original);
        order.verify(access).requireUnchanged(refreshed);
        order.verify(client).read(eq("new-token"), eq("/products"), any());
        order.verify(access).requireUnchanged(refreshed);
    }

    @Test void secondAuthenticationFailureStopsWithoutAnotherRefreshOrEmptyResult() {
        when(client.read(anyString(), eq("/products"), any())).thenThrow(new ImwebApiClient.AuthenticationException());
        assertThatThrownBy(() -> business.products(1L, 4L, 2L, 1, 20)).isInstanceOf(ImwebApiClient.AuthenticationException.class);
        verify(connections, times(1)).refresh(original);
        verify(client, times(2)).read(anyString(), eq("/products"), any());
    }

    @Test void revokedCurrentContextAfterRemoteReadDoesNotReturnProducts() {
        when(client.read(eq("old-token"), eq("/products"), any())).thenAnswer(call -> {
            doThrow(new ApiException(ApiCode.BAD_REQUEST, "revoked")).when(access).requireUnchanged(original);
            return page(1, 20, 1, List.of(product(1)));
        });
        assertThatThrownBy(() -> business.products(1L, 4L, 2L, 1, 20)).isInstanceOf(ApiException.class).hasMessage("revoked");
    }

    @Test void productsUseVerifiedUnitAndSaleFilterAndKeepUnknownMoneyAndStockNull() {
        List<MultiValueMap<String, String>> requests = new ArrayList<>();
        when(client.read(eq("old-token"), eq("/products"), any())).thenAnswer(call -> {
            requests.add(new LinkedMultiValueMap<>(call.<MultiValueMap<String, String>>getArgument(2)));
            return page(1, 20, 1, List.of(product(1)));
        });
        var result = business.products(1L, 4L, 2L, 1, 20);
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).getFirst("unitCode")).isEqualTo("utest123");
        assertThat(requests.get(0).getFirst("prodStatus")).isEqualTo("sale");
        assertThat(result.items().get(0).salePrice()).isNull();
        assertThat(result.items().get(0).originalPrice()).isNull();
        assertThat(result.items().get(0).stockQuantity()).isNull();
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test void productsFromOtherSitesUnitsStatusesOrDuplicateIdsCannotLeakIntoSavedStore() {
        String valid = product(1).toString();
        for (String foreign : List.of(valid.replace("Stest123", "Sforeign"), valid.replace("utest123", "uforeign"), valid.replace("sale", "nosale"))) {
            when(client.read(eq("old-token"), eq("/products"), any())).thenReturn(page(1, 20, 1, List.of(json.readTree(foreign))));
            assertThatThrownBy(() -> business.products(1L, 4L, 2L, 1, 20)).isInstanceOf(ApiException.class);
        }
        when(client.read(eq("old-token"), eq("/products"), any())).thenReturn(page(1, 20, 2, List.of(product(1), product(1))));
        assertThatThrownBy(() -> business.products(1L, 4L, 2L, 1, 20)).isInstanceOf(ApiException.class);
    }

    @Test void salesFetchEveryPageWithStableUnitAndSeoulDateBoundsBeforeAggregating() {
        List<MultiValueMap<String, String>> requests = new ArrayList<>();
        when(client.read(eq("old-token"), eq("/orders"), any())).thenAnswer(call -> {
            var query = new LinkedMultiValueMap<>(call.<MultiValueMap<String, String>>getArgument(2)); requests.add(query);
            return "1".equals(query.getFirst("page")) ? page(1, 100, 101, orders(1, 100)) : page(2, 100, 101, orders(101, 1));
        });
        var result = business.sales(1L, 4L, 2L, day, day);
        assertThat(result.summary().orderCount()).isEqualTo(101);
        assertThat(result.summary().paymentAmount()).isEqualByComparingTo("10100");
        assertThat(result.complete()).isTrue();
        assertThat(requests).hasSize(2);
        assertThat(requests).extracting(q -> q.getFirst("page")).containsExactly("1", "2");
        for (var query : requests) {
            assertThat(query.getFirst("unitCode")).isEqualTo("utest123");
            assertThat(query.getFirst("startWtime")).isEqualTo("2026-08-31T15:00:00Z");
            assertThat(query.getFirst("endWtime")).isEqualTo("2026-09-01T14:59:59.999Z");
            assertThat(query.getFirst("includeOrderPending")).isEqualTo("Y");
        }
        verify(aggregator, times(1)).aggregate(eq(original), eq(day), eq(day), argThat(rows -> rows.size() == 101));
    }

    @Test void changingOrderTotalsAndDuplicateOrdersFailWholeReport() {
        when(client.read(eq("old-token"), eq("/orders"), any())).thenReturn(page(1, 100, 101, orders(1, 100)), page(2, 100, 102, orders(101, 2)));
        assertThatThrownBy(() -> business.sales(1L, 4L, 2L, day, day)).isInstanceOf(ApiException.class).hasMessageContaining("주문 정보가 변경");
        verify(aggregator, never()).aggregate(any(), any(), any(), anyList());

        when(client.read(eq("old-token"), eq("/orders"), any())).thenReturn(page(1, 100, 101, orders(1, 100)), page(2, 100, 101, orders(100, 1)));
        assertThatThrownBy(() -> business.sales(1L, 4L, 2L, day, day)).isInstanceOf(ApiException.class);
    }

    @Test void providerFailureOnLaterPageDoesNotReturnPartialSalesOrReplayGenericErrors() {
        when(client.read(eq("old-token"), eq("/orders"), any())).thenReturn(page(1, 100, 101, orders(1, 100)))
                .thenThrow(new ApiException(ApiCode.SERVER_ERROR, "rate-limited"));
        assertThatThrownBy(() -> business.sales(1L, 4L, 2L, day, day)).isInstanceOf(ApiException.class).hasMessage("rate-limited");
        verify(aggregator, never()).aggregate(any(), any(), any(), anyList());
        verify(connections, never()).refresh(any());
        verify(client, times(2)).read(eq("old-token"), eq("/orders"), any());
    }

    @Test void reportLargerThanBoundFailsBeforeAdditionalPagesOrAggregation() {
        when(client.read(eq("old-token"), eq("/orders"), any())).thenReturn(page(1, 100, 2001, orders(1, 100)));
        assertThatThrownBy(() -> business.sales(1L, 4L, 2L, day, day)).isInstanceOf(ApiException.class).hasMessageContaining("기간을 줄여");
        verify(client, times(1)).read(anyString(), eq("/orders"), any());
        verify(aggregator, never()).aggregate(any(), any(), any(), anyList());
    }

    @Test void salesCanRefreshOnlyOnceAcrossAllPages() {
        when(client.read(eq("old-token"), eq("/orders"), any())).thenThrow(new ImwebApiClient.AuthenticationException());
        when(client.read(eq("new-token"), eq("/orders"), any())).thenReturn(page(1, 100, 101, orders(1, 100)))
                .thenThrow(new ImwebApiClient.AuthenticationException());
        assertThatThrownBy(() -> business.sales(1L, 4L, 2L, day, day)).isInstanceOf(ImwebApiClient.AuthenticationException.class);
        verify(connections, times(1)).refresh(original);
        verify(client, times(2)).read(eq("new-token"), eq("/orders"), any());
        verify(aggregator, never()).aggregate(any(), any(), any(), anyList());
    }

    @Test void multiLanguageSitesRemainReadableButProductCreationIsDisabled() {
        when(client.site("old-token")).thenReturn(json.readTree("""
                {"siteCode":"Stest123","unitList":[{"unitCode":"utest123","currency":"KRW"},{"unitCode":"uother123","currency":"USD"}]}
                """));
        when(client.read(eq("old-token"), eq("/products/shop-categories"), any())).thenReturn(json.readTree("[]"));
        var options = business.options(1L, 4L, 2L);
        assertThat(options.enabled()).isFalse(); assertThat(options.disabledReason()).contains("여러 언어");
        assertThat(options.unitCode()).isEqualTo("utest123"); assertThat(options.currency()).isEqualTo("KRW");
    }

    @Test void optionsRejectLostUnitAndCurrencyChangesBeforeCategoryLookup() {
        for (String units : List.of("[{\"unitCode\":\"uother123\",\"currency\":\"KRW\"}]", "[{\"unitCode\":\"utest123\",\"currency\":\"USD\"}]")) {
            when(client.site("old-token")).thenReturn(json.readTree("{\"siteCode\":\"Stest123\",\"unitList\":" + units + "}"));
            assertThatThrownBy(() -> business.options(1L, 4L, 2L)).isInstanceOf(ApiException.class);
        }
        verify(client, never()).read(anyString(), anyString(), any());
    }

    @Test void invalidPageAndPeriodAreRejectedBeforeAccessOrProviderRequests() {
        assertThatThrownBy(() -> business.products(1L, 4L, 2L, 0, 20)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> business.products(1L, 4L, 2L, 1, 101)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> business.sales(1L, 4L, 2L, day, day.plusDays(31))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> business.sales(1L, 4L, 2L, day.plusDays(1), day)).isInstanceOf(ApiException.class);
        verifyNoInteractions(access, client, aggregator);
        verify(connections, never()).context(anyLong(), anyLong(), anyLong());
    }

    private JsonNode product(long id) {
        return json.readTree("{\"prodNo\":" + id + ",\"siteCode\":\"Stest123\",\"unitCode\":\"utest123\",\"name\":\"Sample product\",\"prodStatus\":\"sale\",\"stockUse\":\"N\",\"productImages\":[]}");
    }
    private List<JsonNode> orders(int start, int count) {
        return IntStream.range(start, start + count).mapToObj(id -> json.readTree("{\"orderNo\":" + id
                + ",\"wtime\":\"2026-09-01T03:00:00Z\",\"currency\":\"KRW\",\"totalPaymentPrice\":100,\"totalRefundedPrice\":0,\"payments\":[{\"isCancel\":\"N\",\"paymentStatus\":\"PAYMENT_COMPLETE\"}]}")).toList();
    }
    private JsonNode page(int page, int size, int total, List<JsonNode> items) {
        var data = json.createObjectNode().put("currentPage", page).put("pageSize", size).put("totalPage", (total + size - 1) / size).put("totalCount", total);
        var rows = data.putArray("list"); items.forEach(rows::add); return data;
    }
}
