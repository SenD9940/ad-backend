package com.orinan.api.domain.navercommerce;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.navercommerce.business.NaverOrderBusiness;
import com.orinan.api.domain.navercommerce.client.NaverOrderClient;
import com.orinan.api.domain.navercommerce.controller.NaverOrderController;
import com.orinan.api.domain.navercommerce.controller.model.NaverOrderActionRequest;
import com.orinan.api.domain.navercommerce.controller.model.NaverOrderResponse.*;
import com.orinan.api.domain.token.business.TokenBusiness;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.user.converter.UserConverter;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.exceptionhandler.ApiExceptionHandler;
import com.orinan.api.exceptionhandler.GlobalExceptionHandler;
import com.orinan.api.exceptionhandler.ValidExceptionHandler;
import com.orinan.api.interceptor.AuthorizationInterceptor;
import com.orinan.api.resolver.UserSessionResolver;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NaverOrderApiTest {
    private static final String BASE = "/api/workspaces/10/naver/stores/30";
    private static final String ID = "2026092900000001";
    private static final String VERSION = "a".repeat(64);
    private static final String REQUEST = "00000000-0000-4000-8000-000000000001";
    private final NaverOrderBusiness business = mock(NaverOrderBusiness.class);
    private final TokenBusiness tokens = mock(TokenBusiness.class);
    private final UserService users = mock(UserService.class);
    private final UserConverter converter = mock(UserConverter.class);
    @SuppressWarnings("unchecked") private final RedisTemplate<String, String> redis = mock(RedisTemplate.class);
    private MockMvc mvc;

    @BeforeEach void setUp() {
        var user = UserEntity.builder().id(2L).build();
        when(tokens.validateAccessToken("service-token")).thenReturn(2L);
        when(users.findByIdAndStatusWithThrow(2L, UserStatus.REGISTERED)).thenReturn(user);
        when(converter.toResponse(user)).thenReturn(UserResponse.builder().id(2L).build());
        var mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        mvc = MockMvcBuilders.standaloneSetup(new NaverOrderController(business))
                .addInterceptors(new AuthorizationInterceptor(tokens, users, redis))
                .setCustomArgumentResolvers(new UserSessionResolver(users, converter))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .setControllerAdvice(new ValidExceptionHandler(), new ApiExceptionHandler(), new GlobalExceptionHandler()).build();
    }

    @Test void optionsUseAuthenticatedUserAndExposeFixedLabelsWithoutCaching() throws Exception {
        when(business.options(10L, 30L, 2L)).thenReturn(new Options(List.of(new Option("ORDERED_DATETIME", "주문일")),
                List.of(), List.of(), List.of(new Option("CJGLS", "CJ대한통운")), false, "여러 채널"));
        mvc.perform(get(BASE + "/order-options").header("Authorization", "Bearer service-token").requestAttr("userId", 999L))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body.range_types[0].code").value("ORDERED_DATETIME"))
                .andExpect(jsonPath("$.body.carriers[0].label").value("CJ대한통운"))
                .andExpect(jsonPath("$.body.settlement_available").value(false));
        verify(business).options(10L, 30L, 2L);
    }

    @Test void orderListUsesDefaultPagingAndKeepsMissingRemainingPaymentUnknown() throws Exception {
        var date = LocalDate.of(2026, 1, 1);
        when(business.orders(10L, 30L, 2L, date, "ORDERED_DATETIME", null, 1, 20)).thenReturn(
                new Orders(30L, "123", date, "ORDERED_DATETIME", null, List.of(order()), 1, 20, true, Instant.now(), "배송비 별도"));
        var response = mvc.perform(get(BASE + "/orders").header("Authorization", "Bearer service-token").param("date", "2026-01-01"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body.asset_id").value(30)).andExpect(jsonPath("$.body.has_next").value(true))
                .andExpect(jsonPath("$.body.items[0].product_order_id").value(ID))
                .andExpect(jsonPath("$.body.items[0].initial_payment_amount").value(20000))
                .andExpect(jsonPath("$.body.items[0].remaining_payment_amount").isEmpty())
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("access_token", "client_secret", "service-token", "recipient");
        verify(business).orders(10L, 30L, 2L, date, "ORDERED_DATETIME", null, 1, 20);
    }

    @Test void snakeCaseRangeAndStatusFilterAreForwardedExactly() throws Exception {
        mvc.perform(get(BASE + "/orders").header("Authorization", "Bearer service-token")
                        .param("date", "2026-01-02").param("range_type", "CLAIM_REQUESTED_DATETIME")
                        .param("status", "PAYED").param("page", "3").param("size", "50"))
                .andExpect(status().isOk());
        verify(business).orders(10L, 30L, 2L, LocalDate.of(2026, 1, 2), "CLAIM_REQUESTED_DATETIME", "PAYED", 3, 50);
    }

    @Test void authorizedDetailReturnsRecipientAndSnapshotOnlyWithNoStore() throws Exception {
        when(business.detail(10L, 30L, 2L, ID)).thenReturn(new Detail(30L, "123", order(), "신용카드", null, null,
                new Recipient("수령인", "010-0000-0000", null, "00000", "기본주소", "상세주소", "KR"),
                "문 앞", null, List.of(), List.of(), VERSION, List.of(new Action("CONFIRM", "발주 확인", "확인")), "", Instant.now()));
        mvc.perform(get(BASE + "/orders/" + ID).header("Authorization", "Bearer service-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body.recipient.name").value("수령인"))
                .andExpect(jsonPath("$.body.payment_means").value("신용카드"))
                .andExpect(jsonPath("$.body.version").value(VERSION))
                .andExpect(jsonPath("$.body.actions[0].code").value("CONFIRM"));
        verify(business).detail(10L, 30L, 2L, ID);
    }

    @Test void actionsDecodeSnakeCaseAndUseServiceUserRatherThanCallerAttribute() throws Exception {
        when(business.act(eq(10L), eq(30L), eq(2L), eq(ID), any())).thenReturn(new ActionResult(ID, "DISPATCH", "ACCEPTED", "처리됨"));
        String payload = "{\"action\":\"DISPATCH\",\"expected_version\":\"" + VERSION + "\",\"request_id\":\"" + REQUEST
                + "\",\"delivery_method\":\"DELIVERY\",\"delivery_company_code\":\"CJGLS\",\"tracking_number\":\"12345\",\"dispatch_date\":\"2026-01-01T13:00:00+09:00\"}";
        mvc.perform(post(BASE + "/orders/" + ID + "/actions").header("Authorization", "Bearer service-token")
                        .requestAttr("userId", 999L).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body.product_order_id").value(ID))
                .andExpect(jsonPath("$.body.status").value("ACCEPTED"));
        var captor = ArgumentCaptor.forClass(NaverOrderActionRequest.class);
        verify(business).act(eq(10L), eq(30L), eq(2L), eq(ID), captor.capture());
        assertThat(captor.getValue()).usingRecursiveComparison().ignoringFields("dispatchDate")
                .isEqualTo(new NaverOrderActionRequest("DISPATCH", VERSION, REQUEST, "DELIVERY", "CJGLS", "12345", null, null));
        assertThat(captor.getValue().dispatchDate().toInstant()).isEqualTo(Instant.parse("2026-01-01T04:00:00Z"));
        assertThat(captor.getValue().toString()).doesNotContain("12345", VERSION);
    }

    @Test void invalidMutationSchemaNeverReachesBusiness() throws Exception {
        for (String payload : List.of("{}", actionJson().replace("CONFIRM", "REFUND"),
                actionJson().replace(VERSION, "bad-version"), actionJson().replace(REQUEST, "bad-request-id"),
                actionJson().replace("\"request_id\"", "\"requestId\""), "{malformed")) {
            mvc.perform(post(BASE + "/orders/" + ID + "/actions").header("Authorization", "Bearer service-token")
                            .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(business);
    }

    @Test void dateAndPagingSyntaxErrorsNeverReachBusiness() throws Exception {
        mvc.perform(get(BASE + "/orders").header("Authorization", "Bearer service-token")).andExpect(status().isBadRequest());
        for (String date : List.of("2026-02-30", "not-date", "2026/01/01", "2026-01-01T12:00:00")) {
            mvc.perform(get(BASE + "/orders").header("Authorization", "Bearer service-token").param("date", date)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(BASE + "/orders").header("Authorization", "Bearer service-token").param("date", "2026-01-01").param("page", "bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/settlements").header("Authorization", "Bearer service-token").param("since", "2026-01-01"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(business);
    }

    @Test void settlementResponsePreservesSignedAndNullFinancialFields() throws Exception {
        var date = LocalDate.of(2026, 1, 1);
        when(business.settlements(10L, 30L, 2L, date, date, 1, 20)).thenReturn(new Settlements(30L, "123", date, date, "SETTLEMENT_EXPECTED",
                List.of(new SettlementDay(date, date, date, null, "CHARGE_AMT", new BigDecimal("-1000"), new BigDecimal("2000"),
                        null, new BigDecimal("-3000"), null)), 1, 20, false, Instant.now(), "정산 예정일"));
        mvc.perform(get(BASE + "/settlements").header("Authorization", "Bearer service-token")
                        .param("since", "2026-01-01").param("until", "2026-01-01"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body.basis").value("SETTLEMENT_EXPECTED"))
                .andExpect(jsonPath("$.body.items[0].settle_amount").value(-1000))
                .andExpect(jsonPath("$.body.items[0].commission_settle_amount").isEmpty())
                .andExpect(jsonPath("$.body.items[0].settle_complete_date").isEmpty());
    }

    @Test void absentBlacklistedAndNonMemberAccessDoNotExposePrivateOrderData() throws Exception {
        mvc.perform(get(BASE + "/orders/" + ID)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/orders/" + ID + "/actions").contentType(MediaType.APPLICATION_JSON).content(actionJson()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(business);
        when(redis.hasKey("blacklist:service-token")).thenReturn(true);
        mvc.perform(get(BASE + "/orders/" + ID).header("Authorization", "Bearer service-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(business);
        when(redis.hasKey("blacklist:service-token")).thenReturn(false);
        when(business.detail(10L, 30L, 2L, ID)).thenThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY));
        mvc.perform(get(BASE + "/orders/" + ID).header("Authorization", "Bearer service-token"))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body").isEmpty());
    }

    @Test void unknownMutationOutcomeIsAnErrorWithReconciliationMessageAndNoStore() throws Exception {
        when(business.act(eq(10L), eq(30L), eq(2L), eq(ID), any())).thenThrow(new NaverOrderClient.UnknownWrite());
        mvc.perform(post(BASE + "/orders/" + ID + "/actions").header("Authorization", "Bearer service-token")
                        .contentType(MediaType.APPLICATION_JSON).content(actionJson()))
                .andExpect(status().isInternalServerError()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body").isEmpty())
                .andExpect(jsonPath("$.result.result_message").value(org.hamcrest.Matchers.containsString("최신 주문 상태")));
    }

    private String actionJson() { return "{\"action\":\"CONFIRM\",\"expected_version\":\"" + VERSION + "\",\"request_id\":\"" + REQUEST + "\"}"; }
    private Order order() { return new Order(ID, "2026092900000002", "상품", null, "PAYED", "NOT_YET", null,
            OffsetDateTime.parse("2026-01-01T12:00:00+09:00"), OffsetDateTime.parse("2026-01-01T12:00:01+09:00"),
            2L, null, new BigDecimal("20000"), null, null); }
}
