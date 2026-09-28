package com.orinan.api.domain.support.payment;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.support.interceptor.SupportAccessInterceptor;
import com.orinan.api.domain.support.model.SupportContext;
import com.orinan.api.domain.support.payment.SupportPaymentModels.*;
import com.orinan.api.domain.support.service.SupportActionService;
import com.orinan.api.domain.support.service.SupportRoutePolicy;
import com.orinan.api.domain.support.service.SupportSessionService;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.user.converter.UserConverter;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.resolver.UserSessionResolver;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SupportPaymentControllerTest {
    private static final String BASE = "/api/workspaces/10/support/tickets/20";
    private static final String WEBHOOK = "/open-api/support/payments/toss/webhook";
    private static final String ORDER = "support_order_123";
    private static final String KEY = "private-payment-key";
    private static final String BODY = "{\"order_id\":\"" + ORDER + "\",\"payment_key\":\"" + KEY + "\",\"amount\":9900}";
    private final SupportPaymentService service = mock(SupportPaymentService.class);
    private final SupportSessionService sessions = mock(SupportSessionService.class);
    private final SupportActionService actions = mock(SupportActionService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        var users = mock(UserService.class); var converter = mock(UserConverter.class);
        var user = UserEntity.builder().id(2L).build();
        when(users.findByIdAndStatusWithThrow(2L, UserStatus.REGISTERED)).thenReturn(user);
        when(converter.toResponse(user)).thenReturn(UserResponse.builder().id(2L).build());
        when(sessions.authenticate("a".repeat(43))).thenReturn(new SupportContext(1, 20, 10, 3, 2,
                "OPERATE", LocalDateTime.of(2030, 1, 1, 0, 0)));
        when(actions.begin(any(), anyString(), anyString())).thenReturn(1L);
        mvc = MockMvcBuilders.standaloneSetup(new SupportPaymentController(service), new TossPaymentWebhookController(service))
                .setCustomArgumentResolvers(new UserSessionResolver(users, converter))
                .addInterceptors(new SupportAccessInterceptor(sessions, new SupportRoutePolicy(), actions))
                .setControllerAdvice(new SupportPaymentExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build())).build();
    }

    @Test void orderAndConfirmationUseAuthenticatedCustomerAndNeverExposePaymentKeys() throws Exception {
        when(service.createOrder(10, 20, 2)).thenReturn(new Order(ORDER, 20, "기술 지원 1회", 9900, "public-client-key", "server-customer", "READY"));
        when(service.confirm(10, 20, 2, new Confirm(KEY, ORDER, 9900))).thenReturn(new Result(ORDER, "PAID", 9900, 20));
        mvc.perform(post(BASE + "/payment-order").requestAttr("userId", 2L))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body.order_id").value(ORDER)).andExpect(jsonPath("$.body.ticket_id").value(20))
                .andExpect(jsonPath("$.body.amount").value(9900)).andExpect(jsonPath("$.body.secret_key").doesNotExist());
        mvc.perform(post(BASE + "/payment-confirm").requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.status").value("PAID"))
                .andExpect(jsonPath("$.body.payment_key").doesNotExist());
        verify(service).confirm(10, 20, 2, new Confirm(KEY, ORDER, 9900));
    }

    @Test void statusCanRecoverLatestOrderWithoutBrowserStorage() throws Exception {
        when(service.payment(10, 20, 2, null)).thenReturn(new Result(ORDER, "UNKNOWN", 9900, 20));
        mvc.perform(get(BASE + "/payment").requestAttr("userId", 2L)).andExpect(status().isOk())
                .andExpect(jsonPath("$.body.order_id").value(ORDER));
        verify(service).payment(10, 20, 2, null);
        mvc.perform(get(BASE + "/payment").param("orderId", ORDER).requestAttr("userId", 2L)).andExpect(status().isOk());
        verify(service).payment(10, 20, 2, ORDER);
    }

    @Test void invalidAndAdditionalConfirmationFieldsAreRejectedWithoutEchoingTheKey() throws Exception {
        for (String body : List.of("{}", BODY.replace("9900", "0"), BODY.replace(KEY, ""),
                BODY.replace("9900", "9900,\"customer_user_id\":99"), BODY.replace("9900", "9900,\"status\":\"PAID\""),
                "{\"payment_key\":\"" + KEY + "\", MALFORMED")) {
            mvc.perform(post(BASE + "/payment-confirm").requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(KEY))));
        }
        verifyNoInteractions(service);
    }

    @Test void webhookIgnoresUntrustedAmountsKeysAndStatusAndUsesOnlyTheOrderId() throws Exception {
        mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content("""
                {"eventType":"PAYMENT_STATUS_CHANGED","data":{"orderId":"support_order_123",
                 "status":"DONE","paymentKey":"forged-key","totalAmount":1,"balanceAmount":1}}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.body.received").value(true));
        verify(service).webhook(ORDER);
    }

    @Test void unrelatedEventsAreAcknowledgedWithoutQueryAndMalformedPaymentEventsAreRejected() throws Exception {
        mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content("{\"eventType\":\"UNKNOWN_EVENT\"}"))
                .andExpect(status().isOk());
        for (String body : List.of("{}", "[]", "{\"eventType\":\"PAYMENT_STATUS_CHANGED\"}",
                "{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"data\":{\"orderId\":\"../other\"}}")) {
            mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test void failedWebhookReturnsNonSuccessToRequestProviderRedelivery() throws Exception {
        doThrow(new ApiException(SupportPaymentErrorCode.RECONCILE_REQUIRED)).when(service).webhook(ORDER);
        mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"data\":{\"orderId\":\"" + ORDER + "\"}}"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void anOperatorSupportTokenCannotCreateConfirmOrInspectPaymentsOrSendWebhooks() throws Exception {
        mvc.perform(post(BASE + "/payment-order").header("X-Support-Token", "a".repeat(43))).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/payment-confirm").header("X-Support-Token", "a".repeat(43))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/payment").header("X-Support-Token", "a".repeat(43))).andExpect(status().isForbidden());
        mvc.perform(post(WEBHOOK).header("X-Support-Token", "a".repeat(43))
                .contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
