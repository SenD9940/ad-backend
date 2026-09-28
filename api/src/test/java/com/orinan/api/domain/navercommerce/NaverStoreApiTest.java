package com.orinan.api.domain.navercommerce;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.navercommerce.business.NaverStoreBusiness;
import com.orinan.api.domain.navercommerce.controller.NaverStoreController;
import com.orinan.api.domain.navercommerce.controller.model.NaverStoreResponse.*;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NaverStoreApiTest {
    private final NaverStoreBusiness business = mock(NaverStoreBusiness.class);
    private final TokenBusiness tokens = mock(TokenBusiness.class);
    private final UserService users = mock(UserService.class);
    private final UserConverter converter = mock(UserConverter.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> redis = mock(RedisTemplate.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var user = UserEntity.builder().id(2L).build();
        when(tokens.validateAccessToken("service-token")).thenReturn(2L);
        when(users.findByIdAndStatusWithThrow(2L, UserStatus.REGISTERED)).thenReturn(user);
        when(converter.toResponse(user)).thenReturn(UserResponse.builder().id(2L).build());
        var mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        mvc = MockMvcBuilders.standaloneSetup(new NaverStoreController(business))
                .addInterceptors(new AuthorizationInterceptor(tokens, users, redis))
                .setCustomArgumentResolvers(new UserSessionResolver(users, converter))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .setControllerAdvice(new ValidExceptionHandler(), new ApiExceptionHandler(), new GlobalExceptionHandler()).build();
    }

    @Test
    void storesUseAuthenticatedUserAndReturnSnakeCaseWithoutCachingOrCredentials() throws Exception {
        when(business.stores(10L, 2L)).thenReturn(List.of(new Store(30L, 20L, "123456", "내 스토어",
                "https://smartstore.naver.com/example", "판매자", false)));

        var response = mvc.perform(get("/api/workspaces/10/naver/stores")
                        .header("Authorization", "Bearer service-token").requestAttr("userId", 999L))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body[0].asset_id").value(30))
                .andExpect(jsonPath("$.body[0].connection_id").value(20))
                .andExpect(jsonPath("$.body[0].channel_no").value("123456"))
                .andExpect(jsonPath("$.body[0].requires_reauth").value(false))
                .andExpect(jsonPath("$.body[0].channelNo").doesNotExist())
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).doesNotContain("access_token", "client_secret", "service-token");
        verify(business).stores(10L, 2L);
        verifyNoMoreInteractions(business);
    }

    @Test
    void productEndpointDefaultsPagingAndKeepsUnknownChannelTotalAndStockNullable() throws Exception {
        var result = new Products(30L, "123456", List.of(new Product("777", "상품", "SALE", null,
                new BigDecimal("20000"), new BigDecimal("18000"), null)), 1, 20, true, null, Instant.parse("2026-09-23T01:00:00Z"));
        when(business.products(10L, 30L, 2L, 1, 20)).thenReturn(result);

        mvc.perform(get("/api/workspaces/10/naver/stores/30/products").header("Authorization", "Bearer service-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body.page").value(1)).andExpect(jsonPath("$.body.size").value(20))
                .andExpect(jsonPath("$.body.has_next").value(true))
                .andExpect(jsonPath("$.body.total_elements").isEmpty())
                .andExpect(jsonPath("$.body.items[0].product_id").value("777"))
                .andExpect(jsonPath("$.body.items[0].sale_price").value(20000))
                .andExpect(jsonPath("$.body.items[0].discounted_price").value(18000))
                .andExpect(jsonPath("$.body.items[0].stock_quantity").isEmpty());
        verify(business).products(10L, 30L, 2L, 1, 20);
    }

    @Test
    void salesForwardsIsoDatesAndReturnsAggregateFinancialFieldsOnly() throws Exception {
        var since = LocalDate.of(2026, 9, 1);
        var until = LocalDate.of(2026, 9, 2);
        var amount = new BigDecimal("24000");
        when(business.sales(10L, 30L, 2L, since, until)).thenReturn(new Sales(30L, "123456", since, until,
                "Asia/Seoul", "PAYMENT_DATE", "KRW",
                new Summary(amount, null, 2, 3, 4, new BigDecimal("12000"), 1, 0),
                List.of(new Daily(since, amount, null, 2, 3, 4, new BigDecimal("12000"), 1, 0),
                        new Daily(until, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, BigDecimal.ZERO, 0, 0)),
                List.of(new ProductSales("777", "상품", 3, 4, amount, null)), true,
                Instant.parse("2026-09-23T01:00:00Z"), "결제일 기준"));

        var response = mvc.perform(get("/api/workspaces/10/naver/stores/30/sales")
                        .header("Authorization", "Bearer service-token").param("since", "2026-09-01").param("until", "2026-09-02"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body.since").value("2026-09-01"))
                .andExpect(jsonPath("$.body.until").value("2026-09-02"))
                .andExpect(jsonPath("$.body.time_zone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.body.basis").value("PAYMENT_DATE"))
                .andExpect(jsonPath("$.body.summary.payment_amount").value(24000))
                .andExpect(jsonPath("$.body.summary.remaining_payment_amount").isEmpty())
                .andExpect(jsonPath("$.body.summary.paid_order_count").value(2))
                .andExpect(jsonPath("$.body.summary.average_order_amount").value(12000))
                .andExpect(jsonPath("$.body.daily[0].date").value("2026-09-01"))
                .andExpect(jsonPath("$.body.top_products[0].product_id").value("777"))
                .andExpect(jsonPath("$.body.complete").value(true)).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("orderer", "shipping_address", "access_token", "client_secret");
        verify(business).sales(10L, 30L, 2L, since, until);
    }

    @Test
    void missingOrMalformedDatesAndPageNumbersFailBeforeBusiness() throws Exception {
        String path = "/api/workspaces/10/naver/stores/30/sales";
        mvc.perform(get(path).header("Authorization", "Bearer service-token")).andExpect(status().isBadRequest());
        mvc.perform(get(path).header("Authorization", "Bearer service-token").param("since", "2026-09-01"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(path).header("Authorization", "Bearer service-token").param("until", "2026-09-02"))
                .andExpect(status().isBadRequest());
        for (String badDate : List.of("2026-02-30", "not-a-date", "2026/09/01", "2026-09-01T00:00:00")) {
            mvc.perform(get(path).header("Authorization", "Bearer service-token").param("since", badDate).param("until", "2026-09-02"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/workspaces/10/naver/stores/30/products").header("Authorization", "Bearer service-token").param("page", "invalid"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(business);
    }

    @Test
    void absentBlacklistedOrNonMemberAccessNeverReturnsStoreData() throws Exception {
        mvc.perform(get("/api/workspaces/10/naver/stores")).andExpect(status().isUnauthorized());
        verifyNoInteractions(business);
        when(redis.hasKey("blacklist:service-token")).thenReturn(true);
        mvc.perform(get("/api/workspaces/10/naver/stores").header("Authorization", "Bearer service-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(business);
        when(redis.hasKey("blacklist:service-token")).thenReturn(false);
        when(business.stores(10L, 2L)).thenThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY));
        mvc.perform(get("/api/workspaces/10/naver/stores").header("Authorization", "Bearer service-token"))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body").isEmpty());
    }
}
