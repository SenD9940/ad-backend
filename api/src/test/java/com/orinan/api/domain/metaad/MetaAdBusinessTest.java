package com.orinan.api.domain.metaad;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.metaad.business.MetaAdBusiness;
import com.orinan.api.domain.metaad.service.MetaAdService;
import com.orinan.api.domain.metaad.service.MetaAdService.SavedAdAccount;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.AdAccount;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.Campaign;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.Insights;
import com.orinan.api.domain.user.exception.UserErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MetaAdBusinessTest {

    private static final LocalDate SINCE = LocalDate.of(2026, 9, 1);
    private static final LocalDate UNTIL = LocalDate.of(2026, 9, 2);
    private static final SavedAdAccount ACCOUNT = new SavedAdAccount(30L, 20L, "act_123", "저장된 광고 계정", "shared-secret-token");

    private final MetaAdService service = mock(MetaAdService.class);
    private final MetaGraphClient client = mock(MetaGraphClient.class);
    private final MetaAdBusiness business = new MetaAdBusiness(service, client);

    @Test
    void campaignsUseTheSavedAccountAndRecheckAuthorizationAfterRemoteRead() {
        var campaigns = List.of(new Campaign("1234", "캠페인", "ACTIVE", "ACTIVE", "OUTCOME_SALES"));
        when(service.getAdAccount(10L, 30L, 2L)).thenReturn(ACCOUNT);
        when(client.listCampaigns("act_123", "shared-secret-token")).thenReturn(campaigns);

        assertThat(business.getCampaigns(10L, 30L, 2L)).isEqualTo(campaigns);

        var order = inOrder(service, client);
        order.verify(service).getAdAccount(10L, 30L, 2L);
        order.verify(client).listCampaigns("act_123", "shared-secret-token");
        order.verify(service).verifyUnchanged(10L, 2L, List.of(ACCOUNT));
    }

    @Test
    void forbiddenUserCannotTriggerMetaRequests() {
        var failure = new ApiException(UserErrorCode.USER_PERMISSION_DENY);
        when(service.getAdAccount(10L, 30L, 2L)).thenThrow(failure);
        when(service.getAdAccounts(10L, 2L)).thenThrow(failure);

        assertThatThrownBy(() -> business.getCampaigns(10L, 30L, 2L)).isSameAs(failure);
        assertThatThrownBy(() -> business.getAccountPerformance(10L, 30L, 2L, SINCE, UNTIL)).isSameAs(failure);
        assertThatThrownBy(() -> business.getWorkspacePerformance(10L, 2L, SINCE, UNTIL)).isSameAs(failure);
        verifyNoInteractions(client);
    }

    @Test
    void aMembershipOrCredentialChangeDuringCampaignLookupPreventsReturningResults() {
        when(service.getAdAccount(10L, 30L, 2L)).thenReturn(ACCOUNT);
        when(client.listCampaigns("act_123", "shared-secret-token")).thenReturn(
                List.of(new Campaign("1234", "캠페인", "ACTIVE", "ACTIVE", "OUTCOME_SALES")));
        var failure = new ApiException(UserErrorCode.USER_PERMISSION_DENY);
        doThrow(failure).when(service).verifyUnchanged(10L, 2L, List.of(ACCOUNT));

        assertThatThrownBy(() -> business.getCampaigns(10L, 30L, 2L)).isSameAs(failure);
    }

    @Test
    void invalidDateRangesFailBeforeDatabaseOrMetaAccess() {
        for (LocalDate[] range : List.of(new LocalDate[]{null, UNTIL}, new LocalDate[]{SINCE, null},
                new LocalDate[]{UNTIL, SINCE}, new LocalDate[]{SINCE, SINCE.plusDays(366)})) {
            assertThatThrownBy(() -> business.getAccountPerformance(10L, 30L, 2L, range[0], range[1]))
                    .isInstanceOfSatisfying(ApiException.class, exception ->
                            assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST));
            assertThatThrownBy(() -> business.getWorkspacePerformance(10L, 2L, range[0], range[1]))
                    .isInstanceOfSatisfying(ApiException.class, exception ->
                            assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST));
        }
        verifyNoInteractions(service, client);
    }

    @Test
    void accountTotalsComeFromAccountInsightsAndCampaignsHaveTheirOwnMetrics() {
        when(service.getAdAccount(10L, 30L, 2L)).thenReturn(ACCOUNT);
        stubAccount(ACCOUNT, "USD", "1000", 10000, 910, "3500");
        when(client.getCampaignInsights(ACCOUNT.externalId(), ACCOUNT.accessToken(), SINCE, UNTIL)).thenReturn(
                List.of(new Insights("1234", "기간 중 집행한 캠페인", new BigDecimal("75"), 1000, 20, new BigDecimal("225"))));

        var response = business.getAccountPerformance(10L, 30L, 2L, SINCE, UNTIL);

        assertThat(response.since()).isEqualTo(SINCE);
        assertThat(response.until()).isEqualTo(UNTIL);
        assertThat(response.days()).isEqualTo(2);
        assertThat(response.account().assetId()).isEqualTo(30L);
        assertThat(response.account().connectionId()).isEqualTo(20L);
        assertThat(response.account().adAccountId()).isEqualTo("act_123");
        assertThat(response.account().name()).isEqualTo("현재 Meta 계정 이름");
        assertThat(response.account().timezoneName()).isEqualTo("Asia/Seoul");
        assertThat(response.account().metrics().spend()).isEqualByComparingTo("1000");
        assertThat(response.account().metrics().impressions()).isEqualTo(10000);
        assertThat(response.account().metrics().clicks()).isEqualTo(910);
        assertThat(response.account().metrics().ctr()).isEqualByComparingTo("9.1");
        assertThat(response.account().metrics().cpc()).isEqualByComparingTo("1.098901");
        assertThat(response.account().metrics().cpm()).isEqualByComparingTo("100");
        assertThat(response.account().metrics().purchaseValue()).isEqualByComparingTo("3500");
        assertThat(response.account().metrics().roas()).isEqualByComparingTo("3.5");
        assertThat(response.account().dailyAverage().spend()).isEqualByComparingTo("500");
        assertThat(response.account().dailyAverage().impressions()).isEqualByComparingTo("5000");
        assertThat(response.account().dailyAverage().clicks()).isEqualByComparingTo("455");
        assertThat(response.campaigns()).singleElement().satisfies(campaign -> {
            assertThat(campaign.campaignId()).isEqualTo("1234");
            assertThat(campaign.campaignName()).isEqualTo("기간 중 집행한 캠페인");
            assertThat(campaign.metrics().spend()).isEqualByComparingTo("75");
            assertThat(campaign.metrics().ctr()).isEqualByComparingTo("2");
            assertThat(campaign.metrics().cpc()).isEqualByComparingTo("3.75");
            assertThat(campaign.metrics().cpm()).isEqualByComparingTo("75");
            assertThat(campaign.metrics().purchaseValue()).isEqualByComparingTo("225");
            assertThat(campaign.metrics().roas()).isEqualByComparingTo("3");
            assertThat(campaign.dailyAverage().spend()).isEqualByComparingTo("37.5");
            assertThat(campaign.dailyAverage().impressions()).isEqualByComparingTo("500");
            assertThat(campaign.dailyAverage().clicks()).isEqualByComparingTo("10");
        });
        var order = inOrder(client, service);
        order.verify(client).getCampaignInsights(ACCOUNT.externalId(), ACCOUNT.accessToken(), SINCE, UNTIL);
        order.verify(service).verifyUnchanged(10L, 2L, List.of(ACCOUNT));
    }

    @Test
    void workspaceTotalsUseWeightedRatiosAndKeepDifferentCurrenciesSeparate() {
        var second = new SavedAdAccount(31L, 21L, "act_456", "두 번째 계정", "second-token");
        var third = new SavedAdAccount(32L, 22L, "act_789", "원화 계정", "third-token");
        var accounts = List.of(ACCOUNT, second, third);
        when(service.getAdAccounts(10L, 2L)).thenReturn(accounts);
        stubAccount(ACCOUNT, "USD", "100", 1000, 10, "500");
        stubAccount(second, "USD", "900", 9000, 900, "1800");
        stubAccount(third, "KRW", "20000", 500, 5, "70000");

        var response = business.getWorkspacePerformance(10L, 2L, SINCE, UNTIL);

        assertThat(response.accountCount()).isEqualTo(3);
        assertThat(response.days()).isEqualTo(2);
        assertThat(response.accounts()).extracting(account -> account.assetId()).containsExactly(30L, 31L, 32L);
        assertThat(response.accounts().get(0).metrics().roas()).isEqualByComparingTo("5");
        assertThat(response.accounts().get(1).metrics().roas()).isEqualByComparingTo("2");
        assertThat(response.totalsByCurrency()).hasSize(2);
        var usd = response.totalsByCurrency().stream().filter(group -> "USD".equals(group.currency())).findFirst().orElseThrow();
        assertThat(usd.accountCount()).isEqualTo(2);
        assertThat(usd.metrics().spend()).isEqualByComparingTo("1000");
        assertThat(usd.metrics().impressions()).isEqualTo(10000);
        assertThat(usd.metrics().clicks()).isEqualTo(910);
        assertThat(usd.metrics().ctr()).isEqualByComparingTo("9.100000");
        assertThat(usd.metrics().cpc()).isEqualByComparingTo("1.098901");
        assertThat(usd.metrics().cpm()).isEqualByComparingTo("100.000000");
        assertThat(usd.metrics().purchaseValue()).isEqualByComparingTo("2300");
        assertThat(usd.metrics().roas()).isEqualByComparingTo("2.3");
        assertThat(usd.dailyAverage().spend()).isEqualByComparingTo("500");
        assertThat(usd.dailyAverage().impressions()).isEqualByComparingTo("5000");
        assertThat(usd.dailyAverage().clicks()).isEqualByComparingTo("455");
        var krw = response.totalsByCurrency().stream().filter(group -> "KRW".equals(group.currency())).findFirst().orElseThrow();
        assertThat(krw.accountCount()).isEqualTo(1);
        assertThat(krw.metrics().spend()).isEqualByComparingTo("20000");
        assertThat(krw.metrics().ctr()).isEqualByComparingTo("1");
        assertThat(krw.metrics().cpc()).isEqualByComparingTo("4000");
        assertThat(krw.metrics().cpm()).isEqualByComparingTo("40000");
        assertThat(krw.metrics().purchaseValue()).isEqualByComparingTo("70000");
        assertThat(krw.metrics().roas()).isEqualByComparingTo("3.5");
        assertThat(krw.dailyAverage().spend()).isEqualByComparingTo("10000");
        verify(service).verifyUnchanged(10L, 2L, accounts);
        verify(client, never()).getCampaignInsights(anyString(), anyString(), any(), any());
    }

    @Test
    void accountWithoutDeliveryReturnsZeroTotalsAndUndefinedRatios() {
        when(service.getAdAccount(10L, 30L, 2L)).thenReturn(ACCOUNT);
        when(client.getAdAccount(ACCOUNT.externalId(), ACCOUNT.accessToken())).thenReturn(
                new AdAccount(ACCOUNT.externalId(), "계정", "KRW", "Asia/Seoul"));
        when(client.getAccountInsights(ACCOUNT.externalId(), ACCOUNT.accessToken(), SINCE, UNTIL)).thenReturn(List.of());
        when(client.getCampaignInsights(ACCOUNT.externalId(), ACCOUNT.accessToken(), SINCE, UNTIL)).thenReturn(List.of());

        var response = business.getAccountPerformance(10L, 30L, 2L, SINCE, UNTIL);

        assertThat(response.campaigns()).isEmpty();
        assertThat(response.account().metrics().spend()).isZero();
        assertThat(response.account().metrics().impressions()).isZero();
        assertThat(response.account().metrics().clicks()).isZero();
        assertThat(response.account().metrics().ctr()).isNull();
        assertThat(response.account().metrics().cpc()).isNull();
        assertThat(response.account().metrics().cpm()).isNull();
        assertThat(response.account().metrics().purchaseValue()).isZero();
        assertThat(response.account().metrics().roas()).isNull();
        assertThat(response.account().dailyAverage().spend()).isZero();
        assertThat(response.account().dailyAverage().impressions()).isZero();
        assertThat(response.account().dailyAverage().clicks()).isZero();
        verify(service).verifyUnchanged(10L, 2L, List.of(ACCOUNT));
    }

    @Test
    void spendWithoutClicksStillHasCtrAndCpmButNoCpc() {
        when(service.getAdAccounts(10L, 2L)).thenReturn(List.of(ACCOUNT));
        stubAccount(ACCOUNT, "USD", "10", 100, 0);

        var metrics = business.getWorkspacePerformance(10L, 2L, SINCE, UNTIL).totalsByCurrency().get(0).metrics();

        assertThat(metrics.ctr()).isZero();
        assertThat(metrics.cpc()).isNull();
        assertThat(metrics.cpm()).isEqualByComparingTo("100");
        assertThat(metrics.purchaseValue()).isZero();
        assertThat(metrics.roas()).isZero();
    }

    @Test
    void attributedPurchasesWithoutSpendHaveUndefinedRoas() {
        when(service.getAdAccounts(10L, 2L)).thenReturn(List.of(ACCOUNT));
        stubAccount(ACCOUNT, "USD", "0", 0, 0, "12.34");

        var response = business.getWorkspacePerformance(10L, 2L, SINCE, UNTIL);

        assertThat(response.accounts().get(0).metrics().purchaseValue()).isEqualByComparingTo("12.34");
        assertThat(response.accounts().get(0).metrics().roas()).isNull();
        assertThat(response.totalsByCurrency().get(0).metrics().purchaseValue()).isEqualByComparingTo("12.34");
        assertThat(response.totalsByCurrency().get(0).metrics().roas()).isNull();
    }

    @Test
    void roasUsesSixDecimalPlacesAndRoundsHalfUp() {
        when(service.getAdAccount(10L, 30L, 2L)).thenReturn(ACCOUNT);
        stubAccount(ACCOUNT, "USD", "6", 100, 1, "1");
        when(client.getCampaignInsights(ACCOUNT.externalId(), ACCOUNT.accessToken(), SINCE, UNTIL)).thenReturn(
                List.of(new Insights("1234", "판매 캠페인", new BigDecimal("2"), 100, 1, new BigDecimal("2.469129"))));

        var response = business.getAccountPerformance(10L, 30L, 2L, SINCE, UNTIL);

        assertThat(response.account().metrics().roas()).isEqualTo(new BigDecimal("0.166667"));
        assertThat(response.campaigns().get(0).metrics().roas()).isEqualTo(new BigDecimal("1.234565"));
    }

    @Test
    void emptyWorkspaceReturnsEmptyGroupsWithoutRequestingMeta() {
        when(service.getAdAccounts(10L, 2L)).thenReturn(List.of());

        var response = business.getWorkspacePerformance(10L, 2L, SINCE, UNTIL);

        assertThat(response.days()).isEqualTo(2);
        assertThat(response.accountCount()).isZero();
        assertThat(response.accounts()).isEmpty();
        assertThat(response.totalsByCurrency()).isEmpty();
        verify(service).verifyUnchanged(10L, 2L, List.of());
        verifyNoInteractions(client);
    }

    @Test
    void dailyAveragesIncludeEveryCalendarDayAndTheMaximumInclusivePeriodIsAllowed() {
        when(service.getAdAccounts(10L, 2L)).thenReturn(List.of(ACCOUNT));
        when(client.getAdAccount(ACCOUNT.externalId(), ACCOUNT.accessToken())).thenReturn(
                new AdAccount(ACCOUNT.externalId(), "계정", "USD", "Asia/Seoul"));
        when(client.getAccountInsights(eq(ACCOUNT.externalId()), eq(ACCOUNT.accessToken()), eq(SINCE), any()))
                .thenReturn(List.of(new Insights(null, null, new BigDecimal("100"), 100, 1, BigDecimal.ZERO)));

        var singleDay = business.getWorkspacePerformance(10L, 2L, SINCE, SINCE);
        var fullPeriod = business.getWorkspacePerformance(10L, 2L, SINCE, SINCE.plusDays(365));

        assertThat(singleDay.days()).isEqualTo(1);
        assertThat(singleDay.accounts().get(0).dailyAverage().spend()).isEqualByComparingTo("100");
        assertThat(fullPeriod.days()).isEqualTo(366);
        assertThat(fullPeriod.accounts().get(0).dailyAverage().spend()).isEqualByComparingTo("0.273224");
        assertThat(fullPeriod.accounts().get(0).dailyAverage().impressions()).isEqualByComparingTo("0.273224");
        assertThat(fullPeriod.accounts().get(0).dailyAverage().clicks()).isEqualByComparingTo("0.002732");
    }

    @Test
    void oneFailedAccountAbortsTheWorkspaceResponseWithoutReturningPartialTotals() {
        var second = new SavedAdAccount(31L, 21L, "act_456", "두 번째 계정", "second-token");
        var third = new SavedAdAccount(32L, 22L, "act_789", "세 번째 계정", "third-token");
        when(service.getAdAccounts(10L, 2L)).thenReturn(List.of(ACCOUNT, second, third));
        stubAccount(ACCOUNT, "USD", "100", 1000, 10);
        var failure = new ApiException(ApiCode.SERVER_ERROR, "Meta 요청에 실패했습니다.");
        when(client.getAdAccount(second.externalId(), second.accessToken())).thenThrow(failure);

        assertThatThrownBy(() -> business.getWorkspacePerformance(10L, 2L, SINCE, UNTIL)).isSameAs(failure);

        verify(client, never()).getAdAccount(third.externalId(), third.accessToken());
        verify(service, never()).verifyUnchanged(anyLong(), anyLong(), anyList());
    }

    @Test
    void aMembershipOrCredentialChangeDuringInsightsLookupPreventsReturningResults() {
        when(service.getAdAccount(10L, 30L, 2L)).thenReturn(ACCOUNT);
        when(service.getAdAccounts(10L, 2L)).thenReturn(List.of(ACCOUNT));
        stubAccount(ACCOUNT, "USD", "100", 1000, 10);
        when(client.getCampaignInsights(ACCOUNT.externalId(), ACCOUNT.accessToken(), SINCE, UNTIL)).thenReturn(List.of());
        var failure = new ApiException(UserErrorCode.USER_PERMISSION_DENY);
        doThrow(failure).when(service).verifyUnchanged(10L, 2L, List.of(ACCOUNT));

        assertThatThrownBy(() -> business.getAccountPerformance(10L, 30L, 2L, SINCE, UNTIL)).isSameAs(failure);
        assertThatThrownBy(() -> business.getWorkspacePerformance(10L, 2L, SINCE, UNTIL)).isSameAs(failure);
    }

    @Test
    void aggregatingCountsBeyondLongRangeFailsInsteadOfReturningNegativePerformance() {
        var second = new SavedAdAccount(31L, 21L, "act_456", "두 번째 계정", "second-token");
        when(service.getAdAccounts(10L, 2L)).thenReturn(List.of(ACCOUNT, second));
        stubAccount(ACCOUNT, "USD", "100", Long.MAX_VALUE, 10);
        stubAccount(second, "USD", "100", 1, 10);

        assertThatThrownBy(() -> business.getWorkspacePerformance(10L, 2L, SINCE, UNTIL))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.SERVER_ERROR));
    }

    private void stubAccount(SavedAdAccount saved, String currency, String spend, long impressions, long clicks) {
        stubAccount(saved, currency, spend, impressions, clicks, "0");
    }

    private void stubAccount(SavedAdAccount saved, String currency, String spend, long impressions, long clicks,
                             String purchaseValue) {
        when(client.getAdAccount(saved.externalId(), saved.accessToken())).thenReturn(
                new AdAccount(saved.externalId(), "현재 Meta 계정 이름", currency, "Asia/Seoul"));
        when(client.getAccountInsights(saved.externalId(), saved.accessToken(), SINCE, UNTIL)).thenReturn(
                List.of(new Insights(null, null, new BigDecimal(spend), impressions, clicks, new BigDecimal(purchaseValue))));
    }
}
