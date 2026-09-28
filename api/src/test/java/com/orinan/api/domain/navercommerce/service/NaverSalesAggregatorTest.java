package com.orinan.api.domain.navercommerce.service;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.navercommerce.client.NaverStoreClient.OrderLine;
import com.orinan.api.domain.navercommerce.controller.model.NaverStoreResponse.Store;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

class NaverSalesAggregatorTest {
    private final NaverSalesAggregator aggregator = new NaverSalesAggregator();
    private final Store store = new Store(10L, 20L, "123", "스토어", null, "연결", false);
    private final LocalDate start = LocalDate.of(2026, 9, 1);

    private OrderLine line(String id, String order, String product, String status, String paidAt,
                           long quantity, long amount, Long remaining) {
        return new OrderLine(id, order, product, "상품 " + product, OffsetDateTime.parse(paidAt), status,
                quantity, null, BigDecimal.valueOf(amount), remaining == null ? null : BigDecimal.valueOf(remaining));
    }

    @Test void countsDistinctOrdersAndIncludesPaidCanceledAndReturnedLinesWithoutDoubleCounting() {
        var first = line("1", "order1", "p1", "PAYED", "2026-09-01T01:00:00+09:00", 2, 20000, 20000L);
        var canceled = line("2", "order1", "p2", "CANCELED", "2026-09-01T01:00:00+09:00", 1, 10000, 0L);
        var returned = line("3", "order2", "p1", "RETURNED", "2026-09-02T23:59:59+09:00", 1, 12000, 0L);
        var result = aggregator.aggregate(store, start, start.plusDays(2), List.of(first, canceled, returned, first));
        assertThat(result.complete()).isTrue();
        assertThat(result.summary().paymentAmount()).isEqualByComparingTo("42000");
        assertThat(result.summary().remainingPaymentAmount()).isEqualByComparingTo("20000");
        assertThat(result.summary().paidOrderCount()).isEqualTo(2);
        assertThat(result.summary().productOrderCount()).isEqualTo(3);
        assertThat(result.summary().quantity()).isEqualTo(4);
        assertThat(result.summary().averageOrderAmount()).isEqualByComparingTo("21000");
        assertThat(result.summary().canceledProductOrderCount()).isEqualTo(1);
        assertThat(result.summary().returnedProductOrderCount()).isEqualTo(1);
        assertThat(result.daily()).hasSize(3);
        assertThat(result.daily().get(2).paymentAmount()).isZero();
        assertThat(result.topProducts().get(0).productId()).isEqualTo("p1");
        assertThat(result.topProducts().get(0).paymentAmount()).isEqualByComparingTo("32000");
    }

    @Test void unknownRemainingOnlyInvalidatesAffectedTotalsNotOtherDaysOrProducts() {
        var known = line("1", "a", "p1", "DELIVERED", "2026-09-01T00:00:00+09:00", 1, 100, 50L);
        var unknown = line("2", "b", "p2", "PURCHASE_DECIDED", "2026-09-02T00:00:00+09:00", 1, 200, null);
        var result = aggregator.aggregate(store, start, start.plusDays(1), List.of(known, unknown));
        assertThat(result.summary().remainingPaymentAmount()).isNull();
        assertThat(result.summary().paymentAmount()).isEqualByComparingTo("300");
        assertThat(result.daily().get(0).remainingPaymentAmount()).isEqualByComparingTo("50");
        assertThat(result.daily().get(1).remainingPaymentAmount()).isNull();
        assertThat(result.topProducts().get(0).remainingPaymentAmount()).isNull();
        assertThat(result.topProducts().get(1).remainingPaymentAmount()).isEqualByComparingTo("50");
    }

    @Test void groupsByKoreanCalendarDateAndRejectsOutOfPeriodPayments() {
        var midnight = line("1", "a", "p", "PAYED", "2026-08-31T15:00:00Z", 1, 100, 100L);
        assertThat(aggregator.aggregate(store, start, start, List.of(midnight)).daily().get(0).paidOrderCount()).isEqualTo(1);
        var before = line("2", "a", "p", "PAYED", "2026-08-31T14:59:59.999Z", 1, 100, 100L);
        assertThatThrownBy(() -> aggregator.aggregate(store, start, start, List.of(before))).isInstanceOf(ApiException.class);
    }

    @Test void incompleteOrChangingPaymentDataNeverBecomesPlausibleTotals() {
        var first = line("1", "a", "p", "PAYED", "2026-09-01T00:00:00+09:00", 1, 100, 100L);
        var changed = line("1", "a", "p", "CANCELED", "2026-09-01T00:00:00+09:00", 1, 100, 0L);
        var unknownStatus = line("2", "b", "p", "NEW_UNSUPPORTED_STATUS", "2026-09-01T00:00:00+09:00", 1, 100, 100L);
        var nullStatus = line("3", "c", "p", null, "2026-09-01T00:00:00+09:00", 1, 100, 100L);
        for (var rows : List.of(List.of(first, changed), List.of(unknownStatus), List.of(nullStatus))) {
            assertThatThrownBy(() -> aggregator.aggregate(store, start, start, rows)).isInstanceOf(ApiException.class);
        }
    }

    @Test void emptyPeriodAndUnpaidOrdersYieldZeroWithAllCalendarDays() {
        var unpaid = line("1", "a", "p", "CANCELED_BY_NOPAYMENT", "2026-09-01T00:00:00+09:00", 1, 100, null);
        var result = aggregator.aggregate(store, start, start.plusDays(1), List.of(unpaid));
        assertThat(result.summary().paymentAmount()).isZero();
        assertThat(result.summary().remainingPaymentAmount()).isZero();
        assertThat(result.summary().averageOrderAmount()).isZero();
        assertThat(result.summary().quantity()).isZero();
        assertThat(result.daily()).hasSize(2);
        assertThat(result.topProducts()).isEmpty();
    }

    @Test void topProductsAreLimitedToTenSortedByInitialPayment() {
        var rows = IntStream.range(1, 13).mapToObj(i -> line("id" + i, "order" + i, "p" + i,
                "PAYED", "2026-09-01T00:00:00+09:00", 1, i * 100, (long) i * 100)).toList();
        var result = aggregator.aggregate(store, start, start, rows);
        assertThat(result.topProducts()).hasSize(10);
        assertThat(result.topProducts().get(0).productId()).isEqualTo("p12");
        assertThat(result.summary().paidOrderCount()).isEqualTo(12);
    }
}
