package com.orinan.api.domain.navercommerce.controller.model;

import java.util.List;
import com.orinan.api.domain.navercommerce.service.NaverProductNoticeSchema.NoticeType;

public final class NaverProductCreationResponse {
    private NaverProductCreationResponse() {}
    public record Category(String id, String name) {}
    public record Origin(String code, String name) {}
    public record Address(String id, String name, String address, String type, boolean overseas) {
        @Override public String toString() { return "NaverProductAddress[REDACTED]"; }
    }
    public record DeliveryCompany(String code, String name) {}
    public record Options(List<Category> categories, List<Origin> origins, List<Address> addresses,
                          List<DeliveryCompany> deliveryCompanies) {}
    public record Notices(List<NoticeType> types) {}
    public record Created(Status status, String originProductNo, String smartstoreChannelProductNo, String message) {}
    public enum Status { CREATED, UNKNOWN }
}
