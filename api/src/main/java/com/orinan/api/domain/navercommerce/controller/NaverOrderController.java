package com.orinan.api.domain.navercommerce.controller;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.domain.navercommerce.business.NaverOrderBusiness;
import com.orinan.api.domain.navercommerce.controller.model.NaverOrderActionRequest;
import com.orinan.api.domain.navercommerce.controller.model.NaverOrderResponse.*;
import com.orinan.api.domain.user.controller.model.UserResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/naver/stores/{assetId}")
public class NaverOrderController {
    private final NaverOrderBusiness business;
    @GetMapping("/order-options")
    public Api<Options> options(@PathVariable Long workspaceId, @PathVariable Long assetId,
                               @UserSession UserResponse user, HttpServletResponse response) {
        noStore(response); return Api.OK(business.options(workspaceId, assetId, user.getId()));
    }
    @GetMapping("/orders")
    public Api<Orders> orders(@PathVariable Long workspaceId, @PathVariable Long assetId,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                             @RequestParam(name = "range_type", defaultValue = "ORDERED_DATETIME") String rangeType,
                             @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "20") int size, @UserSession UserResponse user, HttpServletResponse response) {
        noStore(response); return Api.OK(business.orders(workspaceId, assetId, user.getId(), date, rangeType, status, page, size));
    }
    @GetMapping("/orders/{productOrderId}")
    public Api<Detail> detail(@PathVariable Long workspaceId, @PathVariable Long assetId, @PathVariable String productOrderId,
                             @UserSession UserResponse user, HttpServletResponse response) {
        noStore(response); return Api.OK(business.detail(workspaceId, assetId, user.getId(), productOrderId));
    }
    @PostMapping("/orders/{productOrderId}/actions")
    public Api<ActionResult> act(@PathVariable Long workspaceId, @PathVariable Long assetId, @PathVariable String productOrderId,
                                @RequestBody @Valid NaverOrderActionRequest request, @UserSession UserResponse user, HttpServletResponse response) {
        noStore(response); return Api.OK(business.act(workspaceId, assetId, user.getId(), productOrderId, request));
    }
    @GetMapping("/settlements")
    public Api<Settlements> settlements(@PathVariable Long workspaceId, @PathVariable Long assetId,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate until,
                                       @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                       @UserSession UserResponse user, HttpServletResponse response) {
        noStore(response); return Api.OK(business.settlements(workspaceId, assetId, user.getId(), since, until, page, size));
    }
    private void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
}
