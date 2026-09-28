package com.orinan.api.domain.support.payment;

import com.orinan.api.common.api.Api;
import com.orinan.api.common.exception.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequiredArgsConstructor
@RequestMapping("/open-api/support/payments/toss/webhook")
public class TossPaymentWebhookController {
    private final SupportPaymentService payments;

    @PostMapping
    public Api<SupportPaymentModels.Webhook> event(@RequestBody JsonNode payload, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (payload == null || !payload.isObject() || !payload.path("eventType").isString()
                || payload.path("eventType").asString().isBlank()) throw new ApiException(SupportPaymentErrorCode.INVALID_REQUEST);
        if ("PAYMENT_STATUS_CHANGED".equals(payload.path("eventType").asString())) {
            JsonNode id = payload.path("data").path("orderId");
            if (!id.isString() || !id.asString().matches("[A-Za-z0-9_-]{6,64}")) {
                throw new ApiException(SupportPaymentErrorCode.INVALID_REQUEST);
            }
            // The caller's payment status, amounts, credentials and key are intentionally ignored.
            payments.webhook(id.asString());
        }
        return Api.OK(new SupportPaymentModels.Webhook(true));
    }
}
