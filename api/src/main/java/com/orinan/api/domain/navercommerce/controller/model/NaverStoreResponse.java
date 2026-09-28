package com.orinan.api.domain.navercommerce.controller.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class NaverStoreResponse {
    private NaverStoreResponse() {}

    public record Store(Long assetId, Long connectionId, String channelNo, String name,
                        String storeUrl, String connectionName, boolean requiresReauth) {}

    public record Product(String productId, String name, String status, String imageUrl,
                          BigDecimal salePrice, BigDecimal discountedPrice, Long stockQuantity) {}

    /** The upstream total counts seller products, so a channel total is deliberately nullable. */
    public record Products(Long assetId, String channelNo, List<Product> items, int page, int size,
                           boolean hasNext, Long totalElements, Instant fetchedAt) {}

    public record Summary(BigDecimal paymentAmount, BigDecimal remainingPaymentAmount,
                          long paidOrderCount, long productOrderCount, long quantity,
                          BigDecimal averageOrderAmount, long canceledProductOrderCount,
                          long returnedProductOrderCount) {}

    public record Daily(LocalDate date, BigDecimal paymentAmount, BigDecimal remainingPaymentAmount,
                        long paidOrderCount, long productOrderCount, long quantity,
                        BigDecimal averageOrderAmount, long canceledProductOrderCount,
                        long returnedProductOrderCount) {}

    public record ProductSales(String productId, String name, long productOrderCount, long quantity,
                               BigDecimal paymentAmount, BigDecimal remainingPaymentAmount) {}

    public record Sales(Long assetId, String channelNo, LocalDate since, LocalDate until,
                        String timeZone, String basis, String currency, Summary summary,
                        List<Daily> daily, List<ProductSales> topProducts, boolean complete,
                        Instant fetchedAt, String notice) {}
}
