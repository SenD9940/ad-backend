package com.orinan.api.domain.navercommerce.controller;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.domain.navercommerce.business.NaverProductCreationBusiness;
import com.orinan.api.domain.navercommerce.controller.model.NaverProductCreateRequest;
import com.orinan.api.domain.navercommerce.controller.model.NaverProductCreationResponse.*;
import com.orinan.api.domain.user.controller.model.UserResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/naver/stores/{assetId}")
public class NaverProductCreationController {
    private final NaverProductCreationBusiness business;

    @GetMapping("/product-creation/options")
    public Api<Options> options(@PathVariable Long workspaceId, @PathVariable Long assetId,
                               @UserSession UserResponse user, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(business.options(workspaceId, assetId, user.getId()));
    }

    @GetMapping("/product-creation/notices")
    public Api<Notices> notices(@PathVariable Long workspaceId, @PathVariable Long assetId,
                               @RequestParam String categoryId, @UserSession UserResponse user,
                               HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(business.notices(workspaceId, assetId, user.getId(), categoryId));
    }

    @PostMapping(value = "/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Api<Created> create(@PathVariable Long workspaceId, @PathVariable Long assetId,
                              @RequestPart("request") @Valid NaverProductCreateRequest request,
                              @RequestPart("images") List<MultipartFile> images,
                              @UserSession UserResponse user, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(business.create(workspaceId, assetId, user.getId(), request, images));
    }
}
