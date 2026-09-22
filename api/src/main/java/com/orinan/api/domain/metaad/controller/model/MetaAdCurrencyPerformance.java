package com.orinan.api.domain.metaad.controller.model;

public record MetaAdCurrencyPerformance(String currency, int accountCount,
                                        MetaAdMetrics metrics, MetaAdDailyAverage dailyAverage) {
}
