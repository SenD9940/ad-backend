package com.orinan.api.domain.imweb.service;

import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.imweb.controller.model.ImwebCommerceResponse.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

import static com.orinan.api.domain.imweb.service.ImwebCommerceData.*;

@Component
public class ImwebSalesAggregator {
    private static final Set<String> PAID = Set.of("PAYMENT_COMPLETE", "PARTIAL_REFUND_COMPLETE", "REFUND_COMPLETE", "REFUND_PROCESSING", "REFUND_FAILED");
    private static final Set<String> STATUSES = Set.of("PAYMENT_PREPARATION", "PAYMENT_OVERDUE", "PAYMENT_COMPLETE",
            "PARTIAL_REFUND_COMPLETE", "REFUND_COMPLETE", "PAYMENT_FAILED", "PAYMENT_EXIT", "CANCELLED_BEFORE_DEPOSIT",
            "REFUND_PROCESSING", "REFUND_FAILED");

    public Sales aggregate(ImwebAccessService.Context context, LocalDate since, LocalDate until, List<JsonNode> orders) {
        Map<LocalDate, Totals> days = new LinkedHashMap<>();
        since.datesUntil(until.plusDays(1)).forEach(day -> days.put(day, new Totals()));
        Totals total = new Totals();
        Set<Long> ids = new HashSet<>();
        for (var order : orders) {
            if (!ids.add(integer(order, "orderNo", 1, Long.MAX_VALUE))) throw invalid();
            LocalDate date = timestamp(order, "wtime").atZone(SeoulDateTimes.ZONE).toLocalDate();
            if (!days.containsKey(date) || !context.currency().equals(text(order, "currency", 8))) throw invalid();
            // Unit attribution comes from the verified unitCode query; the order schema does not return unitCode.
            BigDecimal payment = money(order, "totalPaymentPrice", false);
            BigDecimal refund = money(order, "totalRefundedPrice", false);
            if (refund.compareTo(payment) > 0 || !order.path("payments").isArray()) throw invalid();
            boolean paid = payment.signum() > 0;
            for (var item : order.path("payments")) {
                String cancelled = text(item, "isCancel", 1);
                if (!Set.of("Y", "N").contains(cancelled)) throw invalid();
                var status = item.path("paymentStatus");
                // Status is optional upstream, but zero-value orders need it to distinguish paid from pending.
                if (status.isNull() || status.isMissingNode()) {
                    if (!paid && "N".equals(cancelled)) throw invalid();
                } else {
                    if (!status.isString() || !STATUSES.contains(status.asString())) throw invalid();
                    if ("N".equals(cancelled) && PAID.contains(status.asString())) paid = true;
                }
            }
            days.get(date).add(payment, refund, paid); total.add(payment, refund, paid);
        }
        List<Daily> daily = days.entrySet().stream().map(entry -> {
            Summary s = entry.getValue().summary();
            return new Daily(entry.getKey(), s.paymentAmount(), s.refundedAmount(), s.remainingPaymentAmount(),
                    s.paidOrderCount(), s.orderCount(), s.averageOrderAmount());
        }).toList();
        return new Sales(context.assetId(), context.unitCode(), since, until, "Asia/Seoul", "ORDER_CREATED",
                context.currency(), total.summary(), daily, true, Instant.now(),
                "주문 생성일 기준입니다. 해당 기간에 생성된 주문의 현재 결제·환불 완료 금액이며, 결제일별 매출이나 정산액과 다를 수 있습니다. 환불 예정 금액은 차감하지 않습니다.");
    }

    private static class Totals {
        BigDecimal payment = BigDecimal.ZERO, refund = BigDecimal.ZERO;
        long paidCount, orderCount;
        void add(BigDecimal amount, BigDecimal refunded, boolean paid) {
            payment = payment.add(amount); refund = refund.add(refunded); orderCount++;
            if (paid) paidCount++;
        }
        Summary summary() {
            return new Summary(payment, refund, payment.subtract(refund), paidCount, orderCount,
                    paidCount == 0 ? BigDecimal.ZERO : payment.divide(BigDecimal.valueOf(paidCount), 2, RoundingMode.HALF_UP));
        }
    }
}
