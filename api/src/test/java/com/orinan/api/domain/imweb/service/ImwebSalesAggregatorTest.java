package com.orinan.api.domain.imweb.service;

import com.orinan.api.common.exception.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImwebSalesAggregatorTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final ImwebSalesAggregator aggregator = new ImwebSalesAggregator();
    private final ImwebAccessService.Context store = new ImwebAccessService.Context(1, 2, 3, 4,
            "S20260929test", "u20260929test", "KRW", "PRIVATE-TOKEN", 1);
    private final LocalDate first = LocalDate.of(2026, 9, 1);

    @Test void separatesPaidUnpaidRefundedAndFreeOrdersAndDoesNotSubtractPendingRefunds() {
        var paid = order(1, "2026-09-01T03:00:00Z", "KRW", "10000", "0", "PAYMENT_COMPLETE");
        var partial = order(2, "2026-09-02T03:00:00Z", "KRW", "6000", "2000", "PARTIAL_REFUND_COMPLETE");
        var returned = order(3, "2026-09-02T04:00:00Z", "KRW", "4000", "4000", "REFUND_COMPLETE");
        var unpaid = order(4, "2026-09-02T05:00:00Z", "KRW", "0", "0", "PAYMENT_PREPARATION");
        var free = order(5, "2026-09-02T06:00:00Z", "KRW", "0", "0", "PAYMENT_COMPLETE");
        var pending = order(6, "2026-09-02T07:00:00Z", "KRW", "8000", "0", "REFUND_PROCESSING");
        var sales = aggregator.aggregate(store, first, first.plusDays(2), List.of(paid, partial, returned, unpaid, free, pending));
        assertThat(sales.summary().paymentAmount()).isEqualByComparingTo("28000");
        assertThat(sales.summary().refundedAmount()).isEqualByComparingTo("6000");
        assertThat(sales.summary().remainingPaymentAmount()).isEqualByComparingTo("22000");
        assertThat(sales.summary().orderCount()).isEqualTo(6);
        assertThat(sales.summary().paidOrderCount()).isEqualTo(5);
        assertThat(sales.summary().averageOrderAmount()).isEqualByComparingTo("5600");
        assertThat(sales.daily()).hasSize(3);
        assertThat(sales.daily().get(0).paymentAmount()).isEqualByComparingTo("10000");
        assertThat(sales.daily().get(1).paymentAmount()).isEqualByComparingTo("18000");
        assertThat(sales.daily().get(2).paymentAmount()).isZero();
        assertThat(sales.complete()).isTrue();
        assertThat(sales.basis()).isEqualTo("ORDER_CREATED");
        assertThat(sales.timeZone()).isEqualTo("Asia/Seoul");
        assertThat(sales.notice()).contains("환불 예정 금액은 차감하지 않습니다");
    }

    @Test void groupsByOrderCreationDateInSeoulEvenWhenPaymentDateDiffers() {
        var boundary = order(1, "2026-08-31T15:00:00Z", "KRW", "100", "0", "PAYMENT_COMPLETE");
        var last = order(2, "2026-09-01T14:59:59.999Z", "KRW", "200", "0", "PAYMENT_COMPLETE");
        var sales = aggregator.aggregate(store, first, first, List.of(boundary, last));
        assertThat(sales.daily()).hasSize(1);
        assertThat(sales.daily().get(0).date()).isEqualTo(first);
        assertThat(sales.summary().paymentAmount()).isEqualByComparingTo("300");
        for (var time : List.of("2026-08-31T14:59:59.999Z", "2026-09-01T15:00:00Z")) {
            assertThatThrownBy(() -> aggregator.aggregate(store, first, first,
                    List.of(order(3, time, "KRW", "10", "0", "PAYMENT_COMPLETE")))).isInstanceOf(ApiException.class);
        }
    }

    @Test void duplicateOrdersAndMixedCurrenciesFailTheEntireReport() {
        var order = order(1, "2026-09-01T00:00:00Z", "KRW", "100", "0", "PAYMENT_COMPLETE");
        assertThatThrownBy(() -> aggregator.aggregate(store, first, first, List.of(order, order))).isInstanceOf(ApiException.class);
        var foreign = order(2, "2026-09-01T00:00:00Z", "USD", "10", "0", "PAYMENT_COMPLETE");
        assertThatThrownBy(() -> aggregator.aggregate(store, first, first, List.of(order, foreign))).isInstanceOf(ApiException.class);
    }

    @Test void missingMoneyInvalidDatesAndImpossibleRefundsCannotProducePlausibleTotals() {
        var valid = order(1, "2026-09-01T00:00:00Z", "KRW", "100", "0", "PAYMENT_COMPLETE").toString();
        for (var row : List.of(valid.replace("\"totalPaymentPrice\":100", "\"totalPaymentPrice\":null"),
                valid.replace("\"totalRefundedPrice\":0", "\"totalRefundedPrice\":101"),
                valid.replace("2026-09-01T00:00:00Z", "not-a-time"),
                valid.replace("\"totalPaymentPrice\":100", "\"totalPaymentPrice\":-1"))) {
            assertThatThrownBy(() -> aggregator.aggregate(store, first, first, List.of(json.readTree(row))))
                    .isInstanceOf(ApiException.class).hasNoCause();
        }
    }

    @Test void missingAndUnrecognizedPaymentStatesAreRejectedEspeciallyForFreeOrders() {
        var base = order(1, "2026-09-01T00:00:00Z", "KRW", "0", "0", "PAYMENT_COMPLETE").toString();
        for (var row : List.of(base.replace("\"paymentStatus\":\"PAYMENT_COMPLETE\"", "\"paymentStatus\":null"),
                base.replace("\"PAYMENT_COMPLETE\"", "\"UNKNOWN_FUTURE_STATE\""),
                base.replace("\"isCancel\":\"N\"", "\"isCancel\":true"),
                base.replace("\"payments\":[{", "\"payments\":[null,{"))) {
            assertThatThrownBy(() -> aggregator.aggregate(store, first, first, List.of(json.readTree(row))))
                    .isInstanceOf(ApiException.class).hasNoCause();
        }
    }

    @Test void averageUsesPaidOrdersAndKeepsFractionalCurrencyPrecision() {
        var usd = new ImwebAccessService.Context(1, 2, 3, 4, "site", "unit", "USD", "token", 1);
        var orders = List.of(order(1, "2026-09-01T00:00:00Z", "USD", "10.01", "0.01", "PARTIAL_REFUND_COMPLETE"),
                order(2, "2026-09-01T00:00:00Z", "USD", "10.00", "0", "PAYMENT_COMPLETE"),
                order(3, "2026-09-01T00:00:00Z", "USD", "0", "0", "PAYMENT_OVERDUE"));
        var summary = aggregator.aggregate(usd, first, first, orders).summary();
        assertThat(summary.paymentAmount()).isEqualByComparingTo("20.01");
        assertThat(summary.remainingPaymentAmount()).isEqualByComparingTo("20.00");
        assertThat(summary.averageOrderAmount()).isEqualByComparingTo("10.01");
        assertThat(summary.paidOrderCount()).isEqualTo(2);
    }

    @Test void noOrdersHasEveryCalendarDayAndNoBuyerInformationAppearsInSalesOutput() {
        var empty = aggregator.aggregate(store, first, first.plusDays(2), List.of());
        assertThat(empty.daily()).hasSize(3);
        assertThat(empty.summary().orderCount()).isZero();
        assertThat(empty.summary().averageOrderAmount()).isZero();
        var order = order(1, "2026-09-01T00:00:00Z", "KRW", "100", "0", "PAYMENT_COMPLETE");
        var sales = aggregator.aggregate(store, first, first, List.of(order));
        assertThat(json.writeValueAsString(sales)).doesNotContain("PRIVATE-BUYER", "PRIVATE-PHONE", "PRIVATE-EMAIL", "PRIVATE-TOKEN",
                "ordererName", "ordererCall", "memberCode");
    }

    private JsonNode order(long id, String created, String currency, String paid, String refunded, String status) {
        return json.readTree("""
                {"orderNo":%d,"wtime":"%s","currency":"%s","totalPaymentPrice":%s,"totalRefundedPrice":%s,
                 "totalRefundPendingPrice":1000,"ordererName":"PRIVATE-BUYER","ordererCall":"PRIVATE-PHONE","ordererEmail":"PRIVATE-EMAIL",
                 "payments":[{"isCancel":"N","paymentStatus":"%s","paidPrice":%s,"paymentCompleteTime":"2026-09-20T00:00:00Z"}]}
                """.formatted(id, created, currency, paid, refunded, status, paid));
    }
}
