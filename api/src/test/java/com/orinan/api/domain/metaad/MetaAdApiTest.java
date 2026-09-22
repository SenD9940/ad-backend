package com.orinan.api.domain.metaad;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.metaad.business.MetaAdBusiness;
import com.orinan.api.domain.metaad.controller.MetaAdApiController;
import com.orinan.api.domain.metaad.controller.model.*;
import com.orinan.api.domain.metaad.service.MetaAdService;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.Campaign;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.user.converter.UserConverter;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.exceptionhandler.ApiExceptionHandler;
import com.orinan.api.exceptionhandler.GlobalExceptionHandler;
import com.orinan.api.exceptionhandler.ValidExceptionHandler;
import com.orinan.api.resolver.UserSessionResolver;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MetaAdApiTest {

    private final MetaAdBusiness business = mock(MetaAdBusiness.class);
    private final UserService users = mock(UserService.class);
    private final UserConverter converter = mock(UserConverter.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var user = UserEntity.builder().id(2L).build();
        when(users.findByIdAndStatusWithThrow(2L, UserStatus.REGISTERED)).thenReturn(user);
        when(converter.toResponse(user)).thenReturn(UserResponse.builder().id(2L).build());
        mvc = mvcFor(business);
    }

    private MockMvc mvcFor(MetaAdBusiness handler) {
        var mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        return MockMvcBuilders.standaloneSetup(new MetaAdApiController(handler))
                .setCustomArgumentResolvers(new UserSessionResolver(users, converter))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .setControllerAdvice(new ValidExceptionHandler(), new ApiExceptionHandler(), new GlobalExceptionHandler()).build();
    }

    @Test
    void campaignsUseTheSessionUserAndExposeSnakeCaseFields() throws Exception {
        when(business.getCampaigns(10L, 30L, 2L)).thenReturn(
                List.of(new Campaign("1234", "판매 캠페인", "ACTIVE", "PAUSED", "OUTCOME_SALES")));

        mvc.perform(get("/api/workspaces/10/meta/ad-accounts/30/campaigns").requestAttr("userId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body[0].id").value("1234"))
                .andExpect(jsonPath("$.body[0].name").value("판매 캠페인"))
                .andExpect(jsonPath("$.body[0].effective_status").value("PAUSED"))
                .andExpect(jsonPath("$.body[0].effectiveStatus").doesNotExist())
                .andExpect(jsonPath("$.body[0].access_token").doesNotExist());

        verify(business).getCampaigns(10L, 30L, 2L);
    }

    @Test
    void malformedOrMissingDatesAreRejectedBeforeCallingBusiness() throws Exception {
        for (String path : List.of("/api/workspaces/10/meta/ad-accounts/30/insights", "/api/workspaces/10/meta/insights")) {
            mvc.perform(get(path).requestAttr("userId", 2L)).andExpect(status().isBadRequest());
            mvc.perform(get(path).requestAttr("userId", 2L).param("since", "2026-09-01"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get(path).requestAttr("userId", 2L).param("until", "2026-09-02"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get(path).requestAttr("userId", 2L).param("since", "not-a-date").param("until", "2026-09-02"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get(path).requestAttr("userId", 2L).param("since", "2026-09-01").param("until", "2026-02-30"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(business);
    }

    @Test
    void unauthorizedWorkspaceAccessReturnsForbidden() throws Exception {
        when(business.getCampaigns(10L, 30L, 2L))
                .thenThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY));

        mvc.perform(get("/api/workspaces/10/meta/ad-accounts/30/campaigns").requestAttr("userId", 2L))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountPerformanceForwardsIsoDatesAndReturnsCampaignMetricsWithSnakeCase() throws Exception {
        var since = LocalDate.of(2026, 9, 1);
        var until = LocalDate.of(2026, 9, 2);
        var account = accountSummary();
        when(business.getAccountPerformance(10L, 30L, 2L, since, until)).thenReturn(
                new MetaAdAccountPerformanceResponse(since, until, 2, account,
                        List.of(new MetaCampaignPerformance("1234", "판매 캠페인", account.metrics(), account.dailyAverage()))));

        var response = mvc.perform(get("/api/workspaces/10/meta/ad-accounts/30/insights").requestAttr("userId", 2L)
                        .param("since", "2026-09-01").param("until", "2026-09-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.since").value("2026-09-01"))
                .andExpect(jsonPath("$.body.until").value("2026-09-02"))
                .andExpect(jsonPath("$.body.days").value(2))
                .andExpect(jsonPath("$.body.account.asset_id").value(30))
                .andExpect(jsonPath("$.body.account.connection_id").value(20))
                .andExpect(jsonPath("$.body.account.ad_account_id").value("act_123"))
                .andExpect(jsonPath("$.body.account.currency").value("USD"))
                .andExpect(jsonPath("$.body.account.timezone_name").value("Asia/Seoul"))
                .andExpect(jsonPath("$.body.account.metrics.spend").value(100))
                .andExpect(jsonPath("$.body.account.metrics.purchase_value").value(350))
                .andExpect(jsonPath("$.body.account.metrics.purchaseValue").doesNotExist())
                .andExpect(jsonPath("$.body.account.metrics.roas").isNumber())
                .andExpect(jsonPath("$.body.account.metrics.roas").value(3.5))
                .andExpect(jsonPath("$.body.account.daily_average.spend").value(50))
                .andExpect(jsonPath("$.body.campaigns[0].campaign_id").value("1234"))
                .andExpect(jsonPath("$.body.campaigns[0].campaign_name").value("판매 캠페인"))
                .andExpect(jsonPath("$.body.campaigns[0].metrics.purchase_value").value(350))
                .andExpect(jsonPath("$.body.campaigns[0].metrics.purchaseValue").doesNotExist())
                .andExpect(jsonPath("$.body.campaigns[0].metrics.roas").value(3.5))
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).doesNotContain("access_token", "client_secret", "accessToken");
        verify(business).getAccountPerformance(10L, 30L, 2L, since, until);
    }

    @Test
    void workspacePerformanceReturnsAccountSummariesAndTotalsByCurrency() throws Exception {
        var since = LocalDate.of(2026, 9, 1);
        var until = LocalDate.of(2026, 9, 2);
        var account = accountSummary();
        when(business.getWorkspacePerformance(10L, 2L, since, until)).thenReturn(
                new MetaAdWorkspacePerformanceResponse(since, until, 2, 1, List.of(account),
                        List.of(new MetaAdCurrencyPerformance("USD", 1, account.metrics(), account.dailyAverage()))));

        var response = mvc.perform(get("/api/workspaces/10/meta/insights").requestAttr("userId", 2L)
                        .param("since", "2026-09-01").param("until", "2026-09-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.account_count").value(1))
                .andExpect(jsonPath("$.body.accounts[0].asset_id").value(30))
                .andExpect(jsonPath("$.body.accounts[0].metrics.purchase_value").value(350))
                .andExpect(jsonPath("$.body.accounts[0].metrics.roas").value(3.5))
                .andExpect(jsonPath("$.body.totals_by_currency[0].currency").value("USD"))
                .andExpect(jsonPath("$.body.totals_by_currency[0].account_count").value(1))
                .andExpect(jsonPath("$.body.totals_by_currency[0].metrics.ctr").value(1))
                .andExpect(jsonPath("$.body.totals_by_currency[0].metrics.cpc").value(10))
                .andExpect(jsonPath("$.body.totals_by_currency[0].metrics.cpm").value(100))
                .andExpect(jsonPath("$.body.totals_by_currency[0].metrics.purchase_value").value(350))
                .andExpect(jsonPath("$.body.totals_by_currency[0].metrics.purchaseValue").doesNotExist())
                .andExpect(jsonPath("$.body.totals_by_currency[0].metrics.roas").isNumber())
                .andExpect(jsonPath("$.body.totals_by_currency[0].metrics.roas").value(3.5))
                .andExpect(jsonPath("$.body.totals_by_currency[0].daily_average.spend").value(50))
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).doesNotContain("access_token", "client_secret", "accessToken");
        verify(business).getWorkspacePerformance(10L, 2L, since, until);
    }

    @Test
    void invalidDateRangesReturnBadRequestWithProductionExceptionHandlers() throws Exception {
        var service = mock(MetaAdService.class);
        var client = mock(MetaGraphClient.class);
        var validatingMvc = mvcFor(new MetaAdBusiness(service, client));

        for (String path : List.of("/api/workspaces/10/meta/ad-accounts/30/insights", "/api/workspaces/10/meta/insights")) {
            validatingMvc.perform(get(path).requestAttr("userId", 2L).param("since", "2026-09-02").param("until", "2026-09-01"))
                    .andExpect(status().isBadRequest());
            validatingMvc.perform(get(path).requestAttr("userId", 2L).param("since", "2026-01-01").param("until", "2027-01-02"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service, client);
    }

    private MetaAdAccountSummary accountSummary() {
        return new MetaAdAccountSummary(30L, 20L, "act_123", "광고 계정", "USD", "Asia/Seoul",
                new MetaAdMetrics(new BigDecimal("100"), 1000, 10, BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("100"),
                        new BigDecimal("350"), new BigDecimal("3.5")),
                new MetaAdDailyAverage(new BigDecimal("50"), new BigDecimal("500"), new BigDecimal("5")));
    }
}
