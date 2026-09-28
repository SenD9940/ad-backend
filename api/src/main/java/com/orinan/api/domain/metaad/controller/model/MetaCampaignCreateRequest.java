package com.orinan.api.domain.metaad.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record MetaCampaignCreateRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull Objective objective,
        @NotNull @Size(max = 6) List<@NotNull SpecialAdCategory> specialAdCategories,
        @Size(max = 250) List<@NotNull @Pattern(regexp = "[A-Z]{2}") String> specialAdCategoryCountry
) {
    public enum Objective {
        OUTCOME_AWARENESS, OUTCOME_TRAFFIC, OUTCOME_ENGAGEMENT,
        OUTCOME_LEADS, OUTCOME_APP_PROMOTION, OUTCOME_SALES
    }

    public enum SpecialAdCategory {
        CREDIT, EMPLOYMENT, FINANCIAL_PRODUCTS_SERVICES, HOUSING,
        ISSUES_ELECTIONS_POLITICS, ONLINE_GAMBLING_AND_GAMING
    }
}
