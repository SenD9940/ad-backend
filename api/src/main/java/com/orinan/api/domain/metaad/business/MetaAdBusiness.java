package com.orinan.api.domain.metaad.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.metaad.controller.model.*;
import com.orinan.api.domain.metaad.service.MetaAdService;
import com.orinan.api.domain.metaad.service.MetaAdService.SavedAdAccount;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Business
@RequiredArgsConstructor
public class MetaAdBusiness {

    private final MetaAdService service;
    private final MetaGraphClient client;

    public List<MetaGraphClient.Campaign> getCampaigns(Long workspaceId, Long assetId, Long userId) {
        var account = service.getAdAccount(workspaceId, assetId, userId);
        var campaigns = client.listCampaigns(account.externalId(), account.accessToken());
        service.verifyUnchanged(workspaceId, userId, List.of(account));
        return campaigns;
    }

    public MetaAdAccountPerformanceResponse getAccountPerformance(Long workspaceId, Long assetId, Long userId,
                                                                   LocalDate since, LocalDate until) {
        long days = validatePeriod(since, until);
        var saved = service.getAdAccount(workspaceId, assetId, userId);
        var summary = accountSummary(saved, since, until, days);
        var campaigns = client.getCampaignInsights(saved.externalId(), saved.accessToken(), since, until).stream()
                .map(row -> {
                    var metrics = metrics(row.spend(), row.impressions(), row.clicks(), row.purchaseValue());
                    return new MetaCampaignPerformance(row.campaignId(), row.campaignName(), metrics, dailyAverage(metrics, days));
                }).toList();
        service.verifyUnchanged(workspaceId, userId, List.of(saved));
        return new MetaAdAccountPerformanceResponse(since, until, days, summary, campaigns);
    }

    public MetaAdWorkspacePerformanceResponse getWorkspacePerformance(Long workspaceId, Long userId,
                                                                      LocalDate since, LocalDate until) {
        long days = validatePeriod(since, until);
        var saved = service.getAdAccounts(workspaceId, userId);
        List<MetaAdAccountSummary> accounts = new ArrayList<>();
        for (var account : saved) {
            accounts.add(accountSummary(account, since, until, days));
        }
        service.verifyUnchanged(workspaceId, userId, saved);

        // A workspace may contain several currencies. Never sum or average unlike monetary units.
        var byCurrency = accounts.stream().collect(Collectors.groupingBy(
                MetaAdAccountSummary::currency, TreeMap::new, Collectors.toList()));
        var totals = byCurrency.entrySet().stream().map(entry -> {
            var metrics = sum(entry.getValue().stream().map(MetaAdAccountSummary::metrics).toList());
            return new MetaAdCurrencyPerformance(entry.getKey(), entry.getValue().size(), metrics, dailyAverage(metrics, days));
        }).toList();
        return new MetaAdWorkspacePerformanceResponse(since, until, days, accounts.size(), List.copyOf(accounts), totals);
    }

    private MetaAdAccountSummary accountSummary(SavedAdAccount saved, LocalDate since, LocalDate until, long days) {
        var account = client.getAdAccount(saved.externalId(), saved.accessToken());
        var rows = client.getAccountInsights(saved.externalId(), saved.accessToken(), since, until);
        // Read account-level insights directly: campaign lists can omit archived or inactive objects.
        var metrics = sum(rows.stream().map(row -> metrics(row.spend(), row.impressions(), row.clicks(), row.purchaseValue())).toList());
        return new MetaAdAccountSummary(saved.assetId(), saved.connectionId(), account.id(), account.name(),
                account.currency(), account.timezoneName(), metrics, dailyAverage(metrics, days));
    }

    private long validatePeriod(LocalDate since, LocalDate until) {
        if (since == null || until == null || until.isBefore(since)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "조회 시작일과 종료일을 올바르게 입력해 주세요.");
        }
        long days = ChronoUnit.DAYS.between(since, until) + 1;
        if (days > 366) {
            throw new ApiException(ApiCode.BAD_REQUEST, "성과는 한 번에 최대 366일까지만 조회할 수 있습니다.");
        }
        return days;
    }

    private MetaAdMetrics sum(List<MetaAdMetrics> values) {
        BigDecimal spend = BigDecimal.ZERO;
        BigDecimal purchaseValue = BigDecimal.ZERO;
        long impressions = 0;
        long clicks = 0;
        try {
            for (var value : values) {
                spend = spend.add(value.spend());
                purchaseValue = purchaseValue.add(value.purchaseValue());
                impressions = Math.addExact(impressions, value.impressions());
                clicks = Math.addExact(clicks, value.clicks());
            }
        } catch (ArithmeticException exception) {
            throw new ApiException(ApiCode.SERVER_ERROR, "Meta 성과 수치가 집계 가능한 범위를 초과했습니다.");
        }
        return metrics(spend, impressions, clicks, purchaseValue);
    }

    private MetaAdMetrics metrics(BigDecimal spend, long impressions, long clicks, BigDecimal purchaseValue) {
        // Weighted ratios are derived from the totals, never from averages of account ratios.
        return new MetaAdMetrics(spend, impressions, clicks,
                ratio(BigDecimal.valueOf(clicks).multiply(BigDecimal.valueOf(100)), impressions),
                ratio(spend, clicks), ratio(spend.multiply(BigDecimal.valueOf(1000)), impressions),
                purchaseValue, spend.signum() == 0 ? null : purchaseValue.divide(spend, 6, RoundingMode.HALF_UP));
    }

    private BigDecimal ratio(BigDecimal numerator, long denominator) {
        return denominator == 0 ? null : numerator.divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private MetaAdDailyAverage dailyAverage(MetaAdMetrics metrics, long days) {
        return new MetaAdDailyAverage(ratio(metrics.spend(), days),
                ratio(BigDecimal.valueOf(metrics.impressions()), days), ratio(BigDecimal.valueOf(metrics.clicks()), days));
    }
}
