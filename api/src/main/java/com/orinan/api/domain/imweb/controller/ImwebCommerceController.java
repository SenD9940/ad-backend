package com.orinan.api.domain.imweb.controller;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.domain.imweb.business.ImwebCommerceBusiness;
import com.orinan.api.domain.imweb.business.ImwebProductCreationBusiness;
import com.orinan.api.domain.imweb.controller.model.ImwebCommerceResponse.*;
import com.orinan.api.domain.imweb.controller.model.ImwebProductCreateRequest;
import com.orinan.api.domain.user.controller.model.UserResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/imweb/stores/{assetId}")
public class ImwebCommerceController {
    private final ImwebCommerceBusiness commerce;
    private final ImwebProductCreationBusiness creation;

    @GetMapping("/products")
    public Api<Products> products(@PathVariable Long workspaceId, @PathVariable Long assetId,
                                  @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                  @UserSession UserResponse user, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(commerce.products(workspaceId, assetId, user.getId(), page, size));
    }

    @GetMapping("/sales")
    public Api<Sales> sales(@PathVariable Long workspaceId, @PathVariable Long assetId,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate until,
                            @UserSession UserResponse user, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(commerce.sales(workspaceId, assetId, user.getId(), since, until));
    }

    @GetMapping("/product-options")
    public Api<Options> options(@PathVariable Long workspaceId, @PathVariable Long assetId,
                                @UserSession UserResponse user, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(commerce.options(workspaceId, assetId, user.getId()));
    }

    @PostMapping(value = "/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Api<Created> create(@PathVariable Long workspaceId, @PathVariable Long assetId,
                              @RequestPart("request") @Valid ImwebProductCreateRequest request,
                              @RequestPart(value = "files", required = false) List<MultipartFile> files,
                              @UserSession UserResponse user, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(creation.create(workspaceId, assetId, user.getId(), request, files));
    }
}
