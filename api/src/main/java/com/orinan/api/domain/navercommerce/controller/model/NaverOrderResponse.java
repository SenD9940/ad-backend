package com.orinan.api.domain.navercommerce.controller.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class NaverOrderResponse {
    private NaverOrderResponse() {}
    public record Option(String code, String label) {}
    public record Options(List<Option> rangeTypes, List<Option> statuses, List<Option> deliveryMethods,
                          List<Option> carriers, boolean settlementAvailable, String settlementUnavailableReason) {}
    public record Order(String productOrderId, String orderId, String productName, String productOption,
                        String status, String placeOrderStatus, String claimStatus, OffsetDateTime orderDate,
                        OffsetDateTime paymentDate, Long initialQuantity, Long remainingQuantity,
                        BigDecimal initialPaymentAmount, BigDecimal remainingPaymentAmount, String deliveryStatus) {}
    public record Orders(long assetId, String channelNo, LocalDate date, String rangeType, String status,
                         List<Order> items, int page, int size, boolean hasNext, Instant fetchedAt, String notice) {}
    public record Recipient(String name, String tel1, String tel2, String zipCode, String baseAddress,
                            String detailedAddress, String country) {
        @Override public String toString() { return "Recipient[REDACTED]"; }
    }
    public record Delivery(String method, String company, String trackingNumber, String status,
                           OffsetDateTime sendDate, OffsetDateTime pickupDate, OffsetDateTime deliveredDate,
                           Boolean wrongTrackingNumber) {}
    public record Claim(String type, String claimId, String status, Long quantity, String reason,
                        OffsetDateTime requestedAt, OffsetDateTime completedAt, String collectStatus,
                        String holdbackStatus, String holdbackReason, String refundStandbyStatus,
                        OffsetDateTime refundExpectedDate) {}
    public record Action(String code, String label, String description) {}
    public record Detail(long assetId, String channelNo, Order order, String paymentMeans,
                         OffsetDateTime paymentDueDate, OffsetDateTime shippingDueDate, Recipient recipient,
                         String shippingMemo, Delivery delivery, List<Claim> currentClaims, List<Claim> completedClaims,
                         String version, List<Action> actions, String actionNotice, Instant fetchedAt) {
        @Override public String toString() { return "OrderDetail[REDACTED]"; }
    }
    public record ActionResult(String productOrderId, String action, String status, String notice) {}
    public record SettlementDay(LocalDate settleBasisStartDate, LocalDate settleBasisEndDate,
                                LocalDate settleExpectDate, LocalDate settleCompleteDate, String settleMethodType,
                                BigDecimal settleAmount, BigDecimal paySettleAmount, BigDecimal commissionSettleAmount,
                                BigDecimal benefitSettleAmount, BigDecimal deductionRestoreSettleAmount) {}
    public record Settlements(long assetId, String channelNo, LocalDate since, LocalDate until, String basis,
                              List<SettlementDay> items, int page, int size, boolean hasNext, Instant fetchedAt, String notice) {}
}
