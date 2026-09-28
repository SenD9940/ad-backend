package com.orinan.api.domain.metaad.controller.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record MetaAdCreateRequest(
        @NotNull @Valid MetaCampaignCreateRequest campaign,
        @NotNull @Valid AdSet adSet,
        @NotNull @Valid Ad ad
) {
    public record AdSet(
            @NotBlank @Size(max = 255) String name,
            @NotNull @Positive Long dailyBudget,
            @NotEmpty @Size(max = 25) List<@NotNull @Pattern(regexp = "[A-Z]{2}") String> countries,
            @NotNull @Min(18) @Max(65) Integer ageMin,
            @NotNull @Min(18) @Max(65) Integer ageMax,
            @Pattern(regexp = "[0-9]{1,32}") String pixelId
    ) {
    }

    public record Ad(
            @NotBlank @Size(max = 255) String name,
            @NotNull @Positive Long pageAssetId,
            @Positive Long instagramAssetId,
            @Size(max = 2048) String imageUrl,
            @NotBlank @Size(max = 2048) String linkUrl,
            @NotBlank @Size(max = 5000) String message,
            @NotBlank @Size(max = 255) String headline,
            @Size(max = 255) String description,
            @NotNull CallToAction callToAction,
            @Size(max = 1024) String imageKey
    ) {
        @JsonIgnore
        @AssertTrue(message = "image_key 또는 image_url 중 하나만 입력해 주세요.")
        public boolean isImageSourceValid() {
            return (imageKey == null && imageUrl != null && !imageUrl.isBlank())
                    || (imageUrl == null && imageKey != null && !imageKey.isBlank());
        }
    }

    public enum CallToAction {
        LEARN_MORE, SHOP_NOW, SIGN_UP, CONTACT_US, BOOK_TRAVEL, DOWNLOAD, GET_QUOTE, APPLY_NOW, GET_OFFER
    }
}
