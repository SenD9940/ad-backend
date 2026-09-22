package com.orinan.api.domain.metaad.controller.model;

public record MetaAdAccountSummary(Long assetId, Long connectionId, String adAccountId, String name,
                                   String currency, String timezoneName,
                                   MetaAdMetrics metrics, MetaAdDailyAverage dailyAverage) {
}
