package com.orinan.api.domain.navercommerce.service;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.navercommerce.controller.model.NaverOrderActionRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NaverOrderProjectionTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final NaverOrderProjection projection = new NaverOrderProjection(json);

    @Test void optionsExposeDocumentedFiltersAndOutboundMethodsWithoutKnownReversedCarrierRow() {
        var options = projection.options(false);
        assertThat(options.rangeTypes()).extracting("code").contains("CLAIM_REQUESTED_DATETIME").doesNotContain("CLAIM_REQUEST_DATETIME");
        assertThat(options.carriers()).extracting("code").contains("CJGLS", "HYUNDAI", "HANJIN", "KGB", "EPOST")
                .doesNotContain("GS더프레시");
        assertThat(options.deliveryMethods()).extracting("code").doesNotContain("RETURN_DELIVERY", "UNKNOWN", "GDFW_ISSUE_SVC");
        assertThat(options.settlementUnavailableReason()).isNotBlank();
        assertThat(projection.options(true).settlementUnavailableReason()).isNull();
    }

    @Test void paymentProjectionUsesProductAmountsAndPreservesUnknownRemainingAmount() {
        ObjectNode body = body();
        object(body, "order").put("generalPaymentAmount", 99999999);
        var product = object(body, "productOrder");
        product.remove("initialPaymentAmount");
        product.put("totalPaymentAmount", 30000).remove("remainPaymentAmount");
        var result = projection.summary(body);
        assertThat(result.initialPaymentAmount()).isEqualByComparingTo("30000");
        assertThat(result.remainingPaymentAmount()).isNull();
        product.remove("totalPaymentAmount");
        assertThat(projection.summary(body).initialPaymentAmount()).isNull();
    }

    @Test void partiallyCanceledOrderCanConfirmOrShipOnlyItsRemainingQuantity() {
        ObjectNode body = body();
        object(body, "productOrder").put("claimStatus", "CANCEL_DONE").put("remainQuantity", 1);
        body.set("currentClaim", json.readTree("{\"cancel\":{\"claimId\":\"9001\",\"claimStatus\":\"CANCEL_DONE\",\"requestQuantity\":2}}"));
        assertThat(projection.detail(10, "123", body).actions()).extracting("code").containsExactly("CONFIRM");
        object(body, "productOrder").put("placeOrderStatus", "OK");
        assertThat(projection.detail(10, "123", body).actions()).extracting("code").containsExactly("DISPATCH");
        object(body, "productOrder").put("remainQuantity", 0).put("productOrderStatus", "CANCELED");
        assertThat(projection.detail(10, "123", body).actions()).isEmpty();
    }

    @Test void activeCancelNeverImplicitlyRejectsBuyerClaimByOfferingDispatch() {
        ObjectNode body = body();
        object(body, "productOrder").put("claimStatus", "CANCEL_REQUEST").put("placeOrderStatus", "OK");
        body.set("currentClaim", json.readTree("{\"cancel\":{\"claimId\":\"9001\",\"claimStatus\":\"CANCEL_REQUEST\",\"requestQuantity\":2}}"));
        var detail = projection.detail(10, "123", body);
        assertThat(detail.actions()).extracting("code").containsExactly("APPROVE_CANCEL");
        assertThat(detail.currentClaims().get(0).quantity()).isEqualTo(2);
        object(object(body, "currentClaim"), "cancel").put("claimStatus", "CANCELING");
        object(body, "productOrder").put("claimStatus", "CANCELING");
        assertThat(projection.detail(10, "123", body).actions()).isEmpty();
    }

    @Test void giftsAndLogisticsManagedOrdersCannotOfferFulfillment() {
        ObjectNode body = body();
        object(body, "productOrder").put("giftReceivingStatus", "WAIT_FOR_RECEIVING");
        assertThat(projection.detail(10, "123", body).actions()).isEmpty();
        object(body, "productOrder").put("giftReceivingStatus", "RECEIVED").put("logisticsDirectContracted", true);
        assertThat(projection.detail(10, "123", body).actions()).isEmpty();
    }

    @Test void unknownOrderClaimAndGiftStatesDoNotEnableActions() {
        for (var field : List.of("productOrderStatus", "claimStatus", "giftReceivingStatus", "placeOrderStatus")) {
            ObjectNode body = body();
            object(body, "productOrder").put(field, "FUTURE_UNKNOWN");
            assertThat(projection.detail(10, "123", body).actions()).as(field).isEmpty();
        }
    }

    @Test void holdbackReturnGoesToCenterWithoutSilentlyReleasingCostOrInsuranceConditions() {
        ObjectNode body = returnBody();
        var claim = object(object(body, "currentClaim"), "return");
        claim.put("holdbackStatus", "HOLDBACK").put("claimDeliveryFeePayMethod", "환불금에서 차감");
        var detail = projection.detail(10, "123", body);
        assertThat(detail.actions()).isEmpty();
        assertThat(detail.actionNotice()).contains("보류", "스마트스토어센터");
        claim.put("holdbackStatus", "RELEASED");
        assertThat(projection.detail(10, "123", body).actions()).extracting("code").containsExactly("APPROVE_RETURN");
    }

    @Test void currentAndCompletedClaimsKeepDifferentIdsQuantitiesAndRefundTiming() {
        ObjectNode body = returnBody();
        body.set("completedClaims", json.readTree("""
                [{"claimType":"CANCEL","claimId":"8001","claimStatus":"CANCEL_DONE","requestQuantity":1,
                  "claimRequestReason":"INTENT_CHANGED","claimRequestDate":"2026-09-27T12:00:00+09:00",
                  "refundExpectedDate":"2026-09-30T00:00:00+09:00","refundStandbyStatus":"WAITING",
                  "claimCompleteOperationDate":"2026-09-28T12:00:00+09:00"}]
                """));
        var result = projection.detail(10, "123", body);
        assertThat(result.currentClaims().get(0).claimId()).isEqualTo("9001");
        assertThat(result.completedClaims().get(0).claimId()).isEqualTo("8001");
        assertThat(result.completedClaims().get(0).quantity()).isEqualTo(1);
        assertThat(result.completedClaims().get(0).refundStandbyStatus()).isEqualTo("WAITING");
        assertThat(result.completedClaims().get(0).refundExpectedDate()).isEqualTo(OffsetDateTime.parse("2026-09-30T00:00:00+09:00"));
        assertThat(result.completedClaims().get(0).completedAt()).isEqualTo(OffsetDateTime.parse("2026-09-28T12:00:00+09:00"));
    }

    @Test void foreignChannelCannotRevealRecipientOrActions() {
        assertThatThrownBy(() -> projection.detail(10, "456", body())).isInstanceOf(ApiException.class)
                .hasMessageContaining("선택한 스마트스토어").hasNoCause();
    }

    @Test void versionIgnoresObjectOrderingButChangesWhenRecipientClaimOrPaymentChanges() {
        ObjectNode body = body();
        String version = projection.detail(10, "123", body).version();
        ObjectNode reordered = json.createObjectNode();
        reordered.set("productOrder", body.path("productOrder"));
        reordered.set("order", body.path("order"));
        assertThat(projection.detail(10, "123", reordered).version()).isEqualTo(version).matches("[a-f0-9]{64}");
        for (var value : List.of("address", "quantity", "money")) {
            ObjectNode changed = body();
            if (value.equals("address")) object(object(changed, "productOrder"), "shippingAddress").put("detailedAddress", "변경된 주소");
            if (value.equals("quantity")) object(changed, "productOrder").put("remainQuantity", 2);
            if (value.equals("money")) object(changed, "productOrder").put("remainPaymentAmount", 20000);
            assertThat(projection.detail(10, "123", changed).version()).as(value).isNotEqualTo(version);
        }
    }

    @Test void detailAndRecipientToStringDoNotLeakPersonalInformation() {
        var detail = projection.detail(10, "123", body());
        assertThat(detail.recipient().name()).isEqualTo("테스트구매자");
        assertThat(detail.toString()).doesNotContain("테스트구매자", "01012345678", "기존 주소");
        assertThat(detail.recipient().toString()).isEqualTo("Recipient[REDACTED]");
    }

    @Test void malformedQuantitiesAmountsAndTimestampsDoNotBecomePlausibleData() {
        for (String value : List.of("-1", "0.5", "\"100\"", "{}")) {
            ObjectNode body = body();
            object(body, "productOrder").set("remainPaymentAmount", json.readTree(value));
            assertThatThrownBy(() -> projection.summary(body)).isInstanceOf(ApiException.class).hasNoCause();
        }
        ObjectNode body = body();
        object(body, "order").put("paymentDate", "bad_private_value");
        assertThatThrownBy(() -> projection.summary(body)).isInstanceOf(ApiException.class)
                .hasMessageNotContaining("bad_private_value").hasNoCause();
    }

    @Test void unknownQuantityAfterClaimAndAmbiguousMultipleClaimsDoNotEnableWrites() {
        ObjectNode body = body();
        object(body, "productOrder").put("claimStatus", "CANCEL_DONE").remove("remainQuantity");
        assertThat(projection.detail(10, "123", body).actions()).isEmpty();
        body = returnBody();
        object(body, "currentClaim").set("cancel", json.readTree("{\"claimId\":\"8001\",\"claimStatus\":\"CANCEL_REQUEST\",\"requestQuantity\":1}"));
        assertThat(projection.detail(10, "123", body).actions()).isEmpty();
    }

    @Test void conflictingCurrentClaimIdentifiersCannotApproveDifferentProviderClaim() {
        ObjectNode body = returnBody();
        object(body, "productOrder").put("claimId", "9999");
        assertThat(projection.detail(10, "123", body).actions()).isEmpty();
        object(body, "productOrder").put("claimId", "9001").put("claimType", "EXCHANGE");
        assertThat(projection.detail(10, "123", body).actions()).isEmpty();
    }

    @Test void dispatchRequiresKnownCarrierAndTrackingAndRejectsReturnOnlyOrExtraFields() {
        projection.validateDispatch(dispatch("DELIVERY", "CJGLS", "123456789012"));
        projection.validateDispatch(dispatch("DIRECT_DELIVERY", null, null));
        for (var request : List.of(dispatch("DELIVERY", "INVENTED", "123"), dispatch("DELIVERY", "CJGLS", null),
                dispatch("DELIVERY", "CJGLS", " 123 "), dispatch("RETURN_DELIVERY", null, null),
                dispatch("VISIT_RECEIPT", "CJGLS", "123"), dispatch("NOTHING", "", null))) {
            assertThatThrownBy(() -> projection.validateDispatch(request)).isInstanceOf(ApiException.class);
        }
    }

    private NaverOrderActionRequest dispatch(String method, String carrier, String tracking) {
        return new NaverOrderActionRequest("DISPATCH", "a".repeat(64), "12345678-1234-1234-1234-123456789012",
                method, carrier, tracking, OffsetDateTime.parse("2026-09-29T12:00:00+09:00"), null);
    }

    private ObjectNode returnBody() {
        ObjectNode body = body();
        object(body, "productOrder").put("productOrderStatus", "DELIVERED").put("claimStatus", "COLLECT_DONE");
        body.set("currentClaim", json.readTree("""
                {"return":{"claimId":"9001","claimStatus":"COLLECT_DONE","requestQuantity":2,
                "collectStatus":"DELIVERED","claimRequestDate":"2026-09-29T00:00:00+09:00"}}
                """));
        return body;
    }

    private ObjectNode body() {
        return (ObjectNode) json.readTree("""
                {"order":{"orderId":"202609290001","orderDate":"2026-09-29T00:00:00+09:00",
                  "paymentDate":"2026-09-29T00:01:00+09:00","paymentMeans":"신용카드"},
                 "productOrder":{"merchantChannelId":"123","productOrderId":"2026092912345678","productName":"테스트상품",
                  "productOrderStatus":"PAYED","placeOrderStatus":"NOT_YET","initialQuantity":3,"remainQuantity":3,
                  "initialPaymentAmount":30000,"remainPaymentAmount":30000,
                  "shippingAddress":{"name":"테스트구매자","tel1":"01012345678","baseAddress":"기존 주소"}}}
                """);
    }

    private ObjectNode object(JsonNode parent, String name) { return (ObjectNode) parent.path(name); }
}
