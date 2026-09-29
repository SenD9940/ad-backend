package com.orinan.api.domain.imweb.controller.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ImwebCommerceResponse {
    private ImwebCommerceResponse() {}

    public record Product(String productId, String name, String status, String imageUrl,
                          BigDecimal salePrice, BigDecimal originalPrice, Long stockQuantity) {}
    public record Products(long assetId, String unitCode, List<Product> items, int page, int size,
                           boolean hasNext, long totalElements, Instant fetchedAt) {}
    public record Summary(BigDecimal paymentAmount, BigDecimal refundedAmount, BigDecimal remainingPaymentAmount,
                          long paidOrderCount, long orderCount, BigDecimal averageOrderAmount) {}
    public record Daily(LocalDate date, BigDecimal paymentAmount, BigDecimal refundedAmount,
                        BigDecimal remainingPaymentAmount, long paidOrderCount, long orderCount,
                        BigDecimal averageOrderAmount) {}
    public record Sales(long assetId, String unitCode, LocalDate since, LocalDate until,
                        String timeZone, String basis, String currency, Summary summary, List<Daily> daily,
                        boolean complete, Instant fetchedAt, String notice) {}
    public record Category(String code, String name) {}
    public record Options(List<Category> categories, String currency, String unitCode,
                          boolean enabled, String disabledReason) {}
    public record Created(String productId, String productCode, String status, boolean detailApplied, String notice) {}
}
