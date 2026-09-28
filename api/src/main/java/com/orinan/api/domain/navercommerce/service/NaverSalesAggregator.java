package com.orinan.api.domain.navercommerce.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.navercommerce.client.NaverStoreClient.OrderLine;
import com.orinan.api.domain.navercommerce.controller.model.NaverStoreResponse.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Component
public class NaverSalesAggregator {
    private static final Set<String> PAID_STATUSES = Set.of("PAYED", "DELIVERING", "DELIVERED", "PURCHASE_DECIDED", "EXCHANGED", "CANCELED", "RETURNED");
    private static final Set<String> UNPAID_STATUSES = Set.of("PAYMENT_WAITING", "CANCELED_BY_NOPAYMENT");

    public Sales aggregate(Store store, LocalDate since, LocalDate until, Collection<OrderLine> source) {
        Map<String, OrderLine> unique = new LinkedHashMap<>();
        for (var line : source) {
            if (line == null || line.status() == null) throw invalid();
            if (UNPAID_STATUSES.contains(line.status())) continue;
            if (!PAID_STATUSES.contains(line.status()) || line.paymentDate() == null
                    || line.initialPaymentAmount() == null || line.initialPaymentAmount().signum() < 0
                    || line.initialQuantity() < 0 || line.productOrderId() == null || line.orderId() == null
                    || line.productId() == null || line.productName() == null) throw invalid();
            var day = line.paymentDate().atZoneSameInstant(SeoulDateTimes.ZONE).toLocalDate();
            if (day.isBefore(since) || day.isAfter(until)) throw invalid();
            var previous = unique.putIfAbsent(line.productOrderId(), line);
            if (previous != null && !previous.equals(line)) {
                throw new ApiException(ApiCode.SERVER_ERROR, "조회 중 주문 정보가 변경되었습니다. 같은 기간을 다시 조회해 주세요.");
            }
        }
        var all = new Totals();
        var days = new LinkedHashMap<LocalDate, Totals>();
        for (LocalDate day = since; !day.isAfter(until); day = day.plusDays(1)) days.put(day, new Totals());
        Map<String, Totals> products = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        try {
            for (var line : unique.values()) {
                all.add(line);
                days.get(line.paymentDate().atZoneSameInstant(SeoulDateTimes.ZONE).toLocalDate()).add(line);
                products.computeIfAbsent(line.productId(), ignored -> new Totals()).add(line);
                names.putIfAbsent(line.productId(), line.productName());
            }
        } catch (ArithmeticException exception) { throw invalid(); }
        List<Daily> daily = days.entrySet().stream().map(entry -> {
            var s = entry.getValue().summary();
            return new Daily(entry.getKey(), s.paymentAmount(), s.remainingPaymentAmount(), s.paidOrderCount(),
                    s.productOrderCount(), s.quantity(), s.averageOrderAmount(), s.canceledProductOrderCount(), s.returnedProductOrderCount());
        }).toList();
        var top = products.entrySet().stream().sorted(Comparator
                .<Map.Entry<String, Totals>, BigDecimal>comparing(entry -> entry.getValue().amount).reversed()
                .thenComparing(Map.Entry::getKey)).limit(10).map(entry -> {
            var totals = entry.getValue();
            return new ProductSales(entry.getKey(), names.get(entry.getKey()), totals.count, totals.quantity,
                    totals.amount, totals.remaining());
        }).toList();
        return new Sales(store.assetId(), store.channelNo(), since, until, "Asia/Seoul", "PAYMENT_DATE", "KRW",
                all.summary(), daily, top, true, Instant.now(),
                "결제일 기준 상품주문의 최초 결제금액과 수량입니다. 취소·반품 주문을 포함하며 배송비·정산금과 다릅니다. "
                        + "잔여 결제금액은 조회 시점의 취소·반품 반영 값이며, 네이버가 제공하지 않은 경우 표시하지 않습니다. "
                        + "취소·반품 건수는 선택 기간에 결제된 상품주문의 현재 완료 상태 기준입니다.");
    }

    private ApiException invalid() { return new ApiException(ApiCode.SERVER_ERROR, "주문 집계에 필요한 정보를 확인할 수 없습니다. 다시 조회해 주세요."); }

    private static final class Totals {
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal remaining = BigDecimal.ZERO;
        boolean missingRemaining;
        long quantity, count, canceled, returned;
        Set<String> orderIds = new HashSet<>();
        void add(OrderLine line) {
            amount = amount.add(line.initialPaymentAmount());
            if (line.remainingPaymentAmount() == null) missingRemaining = true;
            else {
                if (line.remainingPaymentAmount().signum() < 0) throw new ArithmeticException();
                remaining = remaining.add(line.remainingPaymentAmount());
            }
            quantity = Math.addExact(quantity, line.initialQuantity());
            count = Math.addExact(count, 1);
            orderIds.add(line.orderId());
            if ("CANCELED".equals(line.status())) canceled++;
            if ("RETURNED".equals(line.status())) returned++;
        }
        BigDecimal remaining() { return missingRemaining ? null : remaining; }
        Summary summary() {
            return new Summary(amount, remaining(), orderIds.size(), count, quantity,
                    orderIds.isEmpty() ? BigDecimal.ZERO : amount.divide(BigDecimal.valueOf(orderIds.size()), 2, RoundingMode.HALF_UP), canceled, returned);
        }
    }
}
