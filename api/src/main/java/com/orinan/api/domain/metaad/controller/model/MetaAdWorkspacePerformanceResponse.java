package com.orinan.api.domain.metaad.controller.model;

import java.time.LocalDate;
import java.util.List;

public record MetaAdWorkspacePerformanceResponse(LocalDate since, LocalDate until, long days,
                                                 int accountCount, List<MetaAdAccountSummary> accounts,
                                                 List<MetaAdCurrencyPerformance> totalsByCurrency) {
}
