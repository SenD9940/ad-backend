package com.orinan.api.domain.metaad.controller.model;

import java.time.LocalDate;
import java.util.List;

public record MetaAdAccountPerformanceResponse(LocalDate since, LocalDate until, long days,
                                               MetaAdAccountSummary account,
                                               List<MetaCampaignPerformance> campaigns) {
}
