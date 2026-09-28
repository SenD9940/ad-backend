package com.orinan.api.domain.metaad.controller.model;

public record MetaAdCreateResponse(Long assetId, String adAccountId, String campaignId, String adSetId,
                                    String creativeId, String adId, Status status, Step failedStep, String message) {
    public enum Status {
        CREATED, FAILED, UNKNOWN
    }

    public enum Step {
        CAMPAIGN, AD_SET, CREATIVE, AD
    }
}
