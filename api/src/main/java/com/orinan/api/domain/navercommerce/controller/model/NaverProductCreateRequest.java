package com.orinan.api.domain.navercommerce.controller.model;

import jakarta.validation.constraints.*;
import java.util.Map;

/** Simple new physical product; images are separate multipart parts, never caller-provided URLs. */
public record NaverProductCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Pattern(regexp = "[0-9]{1,20}") String categoryId,
        @NotNull @Min(1) @Max(999999990) Long salePrice,
        @NotNull @Min(1) @Max(99999999) Integer stockQuantity,
        @NotBlank @Size(max = 50000) String detailContent,
        @NotBlank @Pattern(regexp = "[0-9]{2,12}") String originAreaCode,
        @Size(max = 200) String originAreaContent,
        @Size(max = 200) String importer,
        @NotNull TaxType taxType,
        @NotNull Boolean minorPurchasable,
        @NotBlank @Size(max = 30) @Pattern(regexp = "[0-9+() -]{7,30}") String afterServiceTelephoneNumber,
        @NotBlank @Size(max = 1000) String afterServiceGuideContent,
        @NotBlank @Size(max = 40) String deliveryCompany,
        @NotNull DeliveryFeeType deliveryFeeType,
        @NotNull @Min(0) @Max(100000) Integer deliveryFee,
        @Min(1) @Max(999999990) Integer freeConditionalAmount,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String shippingAddressId,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String returnAddressId,
        @NotNull @Min(0) @Max(1000000) Integer returnDeliveryFee,
        @NotNull @Min(0) @Max(1000000) Integer exchangeDeliveryFee,
        @NotBlank @Size(max = 40) String noticeType,
        @NotNull @Size(max = 60) Map<String, Object> noticeFields,
        @NotNull DisplayStatus displayStatus,
        @NotNull Boolean naverShoppingRegistration
) {
    public enum TaxType { TAX, DUTYFREE, SMALL }
    public enum DeliveryFeeType { FREE, PAID, CONDITIONAL_FREE }
    public enum DisplayStatus { ON, SUSPENSION }
    @Override public String toString() { return "NaverProductCreateRequest[REDACTED]"; }
}
