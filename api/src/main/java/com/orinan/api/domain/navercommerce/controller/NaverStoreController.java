package com.orinan.api.domain.navercommerce.controller;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.domain.navercommerce.business.NaverStoreBusiness;
import com.orinan.api.domain.navercommerce.controller.model.NaverStoreResponse.*;
import com.orinan.api.domain.user.controller.model.UserResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/naver")
public class NaverStoreController {
    private final NaverStoreBusiness business;

    @GetMapping("/stores")
    public Api<List<Store>> stores(@PathVariable Long workspaceId, @UserSession UserResponse user,
                                   HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(business.stores(workspaceId, user.getId()));
    }

    @GetMapping("/stores/{assetId}/products")
    public Api<Products> products(@PathVariable Long workspaceId, @PathVariable Long assetId,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @UserSession UserResponse user, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(business.products(workspaceId, assetId, user.getId(), page, size));
    }

    @GetMapping("/stores/{assetId}/sales")
    public Api<Sales> sales(@PathVariable Long workspaceId, @PathVariable Long assetId,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate until,
                            @UserSession UserResponse user, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(business.sales(workspaceId, assetId, user.getId(), since, until));
    }
}
