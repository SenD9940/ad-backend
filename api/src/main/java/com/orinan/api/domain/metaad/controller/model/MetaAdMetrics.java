package com.orinan.api.domain.metaad.controller.model;

import java.math.BigDecimal;

public record MetaAdMetrics(BigDecimal spend, long impressions, long clicks,
                            BigDecimal ctr, BigDecimal cpc, BigDecimal cpm,
                            BigDecimal purchaseValue, BigDecimal roas) {
}
