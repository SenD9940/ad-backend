package com.orinan.api.domain.metaad.controller.model;

public record MetaCampaignPerformance(String campaignId, String campaignName,
                                      MetaAdMetrics metrics, MetaAdDailyAverage dailyAverage) {
}
