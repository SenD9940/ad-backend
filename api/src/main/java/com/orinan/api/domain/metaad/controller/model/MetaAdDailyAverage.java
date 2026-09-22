package com.orinan.api.domain.metaad.controller.model;

import java.math.BigDecimal;

public record MetaAdDailyAverage(BigDecimal spend, BigDecimal impressions, BigDecimal clicks) {
}
