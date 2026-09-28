package com.orinan.api.domain.metaad.controller.model;

import java.math.BigDecimal;

public record MetaAdMetrics(BigDecimal spend, Long impressions, Long clicks,
                            BigDecimal ctr, BigDecimal cpc, BigDecimal cpm,
                            BigDecimal purchaseValue, BigDecimal roas) {
    public MetaAdMetrics(BigDecimal spend, long impressions, long clicks,
                         BigDecimal ctr, BigDecimal cpc, BigDecimal cpm,
                         BigDecimal purchaseValue, BigDecimal roas) {
        this(spend, Long.valueOf(impressions), Long.valueOf(clicks), ctr, cpc, cpm, purchaseValue, roas);
    }
}
