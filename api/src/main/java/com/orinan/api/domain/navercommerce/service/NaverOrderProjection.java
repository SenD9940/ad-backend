package com.orinan.api.domain.navercommerce.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.navercommerce.controller.model.NaverOrderActionRequest;
import com.orinan.api.domain.navercommerce.controller.model.NaverOrderResponse.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Provider payloads stay server-side; only fields needed for order operations leave this projection. */
@Component
public class NaverOrderProjection {
    private static final Set<String> TERMINAL_CLAIMS = Set.of("CANCEL_DONE", "CANCEL_REJECT", "RETURN_DONE",
            "RETURN_REJECT", "EXCHANGE_DONE", "EXCHANGE_REJECT", "ADMIN_CANCEL_DONE", "ADMIN_CANCEL_REJECT",
            "PURCHASE_DECISION_HOLDBACK_RELEASE");
    private final JsonMapper mapper;
    private final List<Option> rangeTypes;
    private final List<Option> statuses;
    private final List<Option> deliveryMethods;
    private final List<Option> carriers;
    private final Set<String> carrierCodes;
    private final Set<String> methodCodes;

    public NaverOrderProjection(JsonMapper mapper) {
        this.mapper = mapper;
        try (var input = new ClassPathResource("naver-order-options.json").getInputStream()) {
            JsonNode document = mapper.readTree(input);
            rangeTypes = options(document, "rangeTypes");
            statuses = options(document, "statuses");
            deliveryMethods = options(document, "deliveryMethods");
            carriers = options(document, "carriers");
            carrierCodes = codes(carriers);
            methodCodes = codes(deliveryMethods);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not load Naver order options", exception);
        }
    }

    public Options options(boolean settlementAvailable) {
        return new Options(rangeTypes, statuses, deliveryMethods, carriers, settlementAvailable,
                settlementAvailable ? null : "정산은 판매자 계정 단위로 제공되어 여러 채널의 금액을 분리할 수 없습니다. 스마트스토어센터에서 확인해 주세요.");
    }

    public Order summary(JsonNode content) {
        requireObject(content);
        JsonNode product = content.path("productOrder");
        JsonNode order = content.path("order");
        requireObject(product);
        requireObject(order);
        Long initialQuantity = quantity(product, "initialQuantity");
        if (initialQuantity == null) initialQuantity = quantity(product, "quantity");
        BigDecimal initialPayment = money(product, "initialPaymentAmount");
        if (initialPayment == null) initialPayment = money(product, "totalPaymentAmount");
        Long remainingQuantity = quantity(product, "remainQuantity");
        if (initialQuantity != null && remainingQuantity != null && remainingQuantity > initialQuantity) throw invalid();
        return new Order(id(product, "productOrderId"), id(order, "orderId"), requiredText(product, "productName", 4000),
                text(product, "productOption", 4000), requiredText(product, "productOrderStatus", 100),
                text(product, "placeOrderStatus", 100), text(product, "claimStatus", 100), date(order, "orderDate"),
                date(order, "paymentDate"), initialQuantity, remainingQuantity, initialPayment,
                money(product, "remainPaymentAmount"), text(content.path("delivery"), "deliveryStatus", 100));
    }

    public Detail detail(long assetId, String channelNo, JsonNode content) {
        Order order = summary(content);
        JsonNode product = content.path("productOrder");
        if (channelNo == null || !channelNo.equals(id(product, "merchantChannelId"))) {
            throw new ApiException(ApiCode.BAD_REQUEST, "선택한 스마트스토어의 주문을 찾을 수 없습니다.");
        }
        JsonNode checkout = content.path("order");
        JsonNode address = product.path("shippingAddress");
        Recipient recipient = null;
        if (!absent(address)) {
            requireObject(address);
            recipient = new Recipient(text(address, "name", 300), text(address, "tel1", 100), text(address, "tel2", 100),
                    text(address, "zipCode", 100), text(address, "baseAddress", 1000),
                    text(address, "detailedAddress", 1000), text(address, "country", 100));
        }
        List<Claim> current = currentClaims(content.path("currentClaim"));
        List<Claim> completed = completedClaims(content.path("completedClaims"));
        List<Action> actions = actions(order, product, current);
        return new Detail(assetId, channelNo, order, text(checkout, "paymentMeans", 300), date(checkout, "paymentDueDate"),
                date(product, "shippingDueDate"), recipient, text(product, "shippingMemo", 4000),
                delivery(content.path("delivery")), current, completed, version(content), actions,
                notice(product, current, actions), Instant.now());
    }

    /** Only parcel dispatch accepts a carrier/invoice; return-only provider enum values are not outbound methods. */
    public void validateDispatch(NaverOrderActionRequest request) {
        if (request == null || !"DISPATCH".equals(request.action()) || !methodCodes.contains(orEmpty(request.deliveryMethod()))) {
            throw badRequest("지원하는 배송 방법을 선택해 주세요.");
        }
        if (request.dispatchDate() == null) throw badRequest("발송 일시를 입력해 주세요.");
        if ("DELIVERY".equals(request.deliveryMethod())) {
            if (!carrierCodes.contains(orEmpty(request.deliveryCompanyCode()))) throw badRequest("택배사를 선택해 주세요.");
            String tracking = request.trackingNumber();
            if (tracking == null || !tracking.matches("[A-Za-z0-9][A-Za-z0-9-]{0,99}")) {
                throw badRequest("송장 번호를 영문, 숫자, 하이픈으로 입력해 주세요.");
            }
        } else if (request.deliveryCompanyCode() != null || request.trackingNumber() != null) {
            throw badRequest("택배 배송 외에는 택배사와 송장 번호를 보내지 마세요.");
        }
    }

    private List<Action> actions(Order order, JsonNode product, List<Claim> claims) {
        var actions = new ArrayList<Action>();
        String gift = text(product, "giftReceivingStatus", 100);
        Boolean direct = bool(product, "logisticsDirectContracted");
        boolean knownGift = gift == null || "RECEIVED".equals(gift);
        boolean terminal = claims.stream().allMatch(claim -> TERMINAL_CLAIMS.contains(orEmpty(claim.status())));
        String productClaim = order.claimStatus();
        boolean safeProductClaim = productClaim == null || TERMINAL_CLAIMS.contains(productClaim);
        Long quantity = order.remainingQuantity();
        if (quantity == null && claims.isEmpty() && productClaim == null) quantity = order.initialQuantity();
        boolean fulfill = "PAYED".equals(order.status()) && quantity != null && quantity > 0
                && terminal && safeProductClaim && knownGift && !Boolean.TRUE.equals(direct);
        if (fulfill) {
            if (Set.of("NOT_YET", "CANCEL").contains(orEmpty(order.placeOrderStatus()))) {
                actions.add(new Action("CONFIRM", "발주 확인", "남아 있는 주문 수량의 발주를 확인합니다."));
            }
            if ("OK".equals(order.placeOrderStatus())) {
                actions.add(new Action("DISPATCH", "발송 처리", "입력한 배송 정보로 남아 있는 주문 수량을 발송 처리합니다."));
            }
        }
        if (claims.size() != 1) return List.copyOf(actions);
        Claim claim = claims.get(0);
        boolean identified = claim.claimId() != null && claim.quantity() != null && claim.quantity() > 0
                && order.initialQuantity() != null && claim.quantity() <= order.initialQuantity();
        String productClaimType = text(product, "claimType", 100);
        String productClaimId = optionalId(product, "claimId");
        identified = identified && (productClaim == null || productClaim.equals(claim.status()))
                && (productClaimType == null || productClaimType.equals(claim.type()))
                && (productClaimId == null || productClaimId.equals(claim.claimId()));
        if (!identified) return List.copyOf(actions);
        if ("CANCEL".equals(claim.type()) && "CANCEL_REQUEST".equals(claim.status())
                && "PAYED".equals(order.status())) {
            actions.add(new Action("APPROVE_CANCEL", "취소 승인", "현재 클레임에 요청된 수량의 취소를 승인합니다. 실제 환불은 네이버가 처리합니다."));
        }
        if ("RETURN".equals(claim.type()) && Set.of("RETURN_REQUEST", "COLLECTING", "COLLECT_DONE").contains(orEmpty(claim.status()))
                && Set.of("DELIVERING", "DELIVERED", "EXCHANGED").contains(order.status())
                && (claim.holdbackStatus() == null || "RELEASED".equals(claim.holdbackStatus()))) {
            actions.add(new Action("APPROVE_RETURN", "반품 승인", "반품 상품 입고를 확인한 후 승인합니다. 네이버 정책에 따라 같은 주문의 다른 반품 환불이 함께 처리될 수 있습니다."));
        }
        return List.copyOf(actions);
    }

    private String notice(JsonNode product, List<Claim> claims, List<Action> actions) {
        if (claims.stream().anyMatch(claim -> "HOLDBACK".equals(claim.holdbackStatus()))) {
            return "보류된 클레임의 비용·보상 조건은 스마트스토어센터에서 확인해 주세요. 보류를 자동으로 해제하지 않습니다.";
        }
        if ("WAIT_FOR_RECEIVING".equals(text(product, "giftReceivingStatus", 100))) return "선물 수신자가 선물을 수락한 뒤 발주·발송할 수 있습니다.";
        if (Boolean.TRUE.equals(bool(product, "logisticsDirectContracted"))) return "물류 직계약 주문의 발주·발송은 연동된 물류사에서 처리합니다.";
        if (claims.stream().anyMatch(claim -> Set.of("CANCEL_REQUEST", "CANCELING").contains(orEmpty(claim.status())))) {
            return "취소 진행 중에는 발송할 수 없습니다. 요청된 수량과 취소 상태를 확인해 주세요.";
        }
        if (actions.isEmpty()) return "현재 상태에서는 지원하는 처리 작업이 없습니다. 추가 처리는 스마트스토어센터에서 확인해 주세요.";
        return "처리 직전 최신 주문 상태를 다시 확인합니다. 승인 응답은 구매자에게 실제 환불이 입금되었다는 의미가 아닙니다.";
    }

    private List<Claim> currentClaims(JsonNode node) {
        if (absent(node)) return List.of();
        requireObject(node);
        var result = new ArrayList<Claim>();
        for (String type : List.of("cancel", "return", "exchange")) {
            JsonNode claim = node.path(type);
            if (!absent(claim)) result.add(claim(type.toUpperCase(java.util.Locale.ROOT), claim, false));
        }
        return List.copyOf(result);
    }

    private List<Claim> completedClaims(JsonNode node) {
        if (absent(node)) return List.of();
        if (!node.isArray() || node.size() > 100) throw invalid();
        var result = new ArrayList<Claim>();
        Set<String> ids = new HashSet<>();
        for (JsonNode item : node) {
            requireObject(item);
            Claim claim = claim(requiredText(item, "claimType", 100), item, true);
            if (claim.claimId() != null && !ids.add(claim.claimId())) throw invalid();
            result.add(claim);
        }
        return List.copyOf(result);
    }

    private Claim claim(String type, JsonNode node, boolean completed) {
        requireObject(node);
        String prefix = type.toLowerCase(java.util.Locale.ROOT);
        String reason = text(node, completed ? "claimRequestReason" : prefix + "Reason", 200);
        OffsetDateTime completedAt = date(node, completed ? "claimCompleteOperationDate" : prefix + "CompletedDate");
        return new Claim(type, optionalId(node, "claimId"), text(node, "claimStatus", 100), quantity(node, "requestQuantity"),
                reason, date(node, "claimRequestDate"), completedAt, text(node, "collectStatus", 100),
                text(node, "holdbackStatus", 100), text(node, "holdbackReason", 200),
                text(node, "refundStandbyStatus", 300), date(node, "refundExpectedDate"));
    }

    private Delivery delivery(JsonNode node) {
        if (absent(node)) return null;
        requireObject(node);
        return new Delivery(text(node, "deliveryMethod", 100), text(node, "deliveryCompany", 100),
                text(node, "trackingNumber", 100), text(node, "deliveryStatus", 100), date(node, "sendDate"),
                date(node, "pickupDate"), date(node, "deliveredDate"), bool(node, "isWrongTrackingNumber"));
    }

    /** Sorting every object key keeps property order irrelevant while retaining addresses, claims and amounts. */
    private String version(JsonNode content) {
        try {
            byte[] bytes = mapper.writeValueAsString(canonical(content)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private Object canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            for (var field : node.properties()) sorted.put(field.getKey(), canonical(field.getValue()));
            return sorted;
        }
        if (node.isArray()) {
            var array = new ArrayList<Object>();
            for (JsonNode item : node) array.add(canonical(item));
            return array;
        }
        return node;
    }

    private List<Option> options(JsonNode document, String field) {
        JsonNode values = document.path(field);
        if (!values.isArray() || values.isEmpty()) throw new IllegalStateException("Missing order options");
        var result = new ArrayList<Option>();
        var seen = new HashSet<String>();
        for (JsonNode item : values) {
            String code = requiredText(item, "code", 100);
            if (!seen.add(code)) throw new IllegalStateException("Duplicate order option");
            result.add(new Option(code, requiredText(item, "label", 300)));
        }
        return List.copyOf(result);
    }

    private Set<String> codes(List<Option> values) {
        return values.stream().map(Option::code).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String optionalId(JsonNode node, String field) {
        String value = text(node, field, 30);
        if (value != null && !value.matches("[0-9]{1,30}")) throw invalid();
        return value;
    }

    private String id(JsonNode node, String field) {
        String value = optionalId(node, field);
        if (value == null) throw invalid();
        return value;
    }

    private String requiredText(JsonNode node, String field, int limit) {
        String value = text(node, field, limit);
        if (value == null || value.isBlank()) throw invalid();
        return value;
    }

    private String text(JsonNode node, String field, int limit) {
        JsonNode value = node.path(field);
        if (absent(value)) return null;
        if (!value.isString() || value.asString().length() > limit) throw invalid();
        return value.asString().isEmpty() ? null : value.asString();
    }

    private Long quantity(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (absent(value)) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) throw invalid();
        return value.asLong();
    }

    private BigDecimal money(JsonNode node, String field) {
        Long value = quantity(node, field);
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private Boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (absent(value)) return null;
        if (!value.isBoolean()) throw invalid();
        return value.asBoolean();
    }

    private OffsetDateTime date(JsonNode node, String field) {
        String value = text(node, field, 100);
        if (value == null) return null;
        try { return OffsetDateTime.parse(value); }
        catch (RuntimeException exception) { throw invalid(); }
    }

    private boolean absent(JsonNode node) { return node.isMissingNode() || node.isNull(); }
    private void requireObject(JsonNode node) { if (node == null || !node.isObject()) throw invalid(); }
    private String orEmpty(String value) { return value == null ? "" : value; }
    private ApiException invalid() { return new ApiException(ApiCode.SERVER_ERROR, "네이버 주문 응답을 확인할 수 없습니다. 다시 조회해 주세요."); }
    private ApiException badRequest(String message) { return new ApiException(ApiCode.BAD_REQUEST, message); }
}
