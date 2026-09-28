package com.orinan.api.domain.metaad.controller;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.common.api.Result;
import com.orinan.api.domain.metaad.business.MetaAdBusiness;
import com.orinan.api.domain.metaad.controller.model.MetaAdAccountPerformanceResponse;
import com.orinan.api.domain.metaad.controller.model.MetaAdWorkspacePerformanceResponse;
import com.orinan.api.domain.metaad.controller.model.MetaCampaignCreateRequest;
import com.orinan.api.domain.metaad.controller.model.MetaAdCreateRequest;
import com.orinan.api.domain.metaad.controller.model.MetaAdCreateResponse;
import com.orinan.api.domain.metaad.controller.model.MetaAdImageUploadResponse;
import com.orinan.api.domain.metaad.controller.model.MetaAdUpdateRequest;
import com.orinan.api.domain.metaad.controller.model.MetaAdBudgetUpdateRequest;
import com.orinan.api.domain.metaad.exception.MetaAdCreationException;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import com.orinan.api.domain.user.controller.model.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/meta")
@RequiredArgsConstructor
public class MetaAdApiController {

    private final MetaAdBusiness business;

    @PatchMapping("/ad-accounts/{assetId}/ads/{adId}")
    public Api<MetaGraphClient.UpdatedAdObject> updateAd(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId,
            @PathVariable Long assetId,
            @PathVariable String adId,
            @RequestBody @Valid MetaAdUpdateRequest request
    ) {
        return Api.OK(business.updateAd(workspaceId, assetId, user.getId(), adId, request));
    }

    @PatchMapping("/ad-accounts/{assetId}/ad-sets/{adSetId}")
    public Api<MetaGraphClient.UpdatedAdObject> updateAdSet(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId,
            @PathVariable Long assetId,
            @PathVariable String adSetId,
            @RequestBody @Valid MetaAdBudgetUpdateRequest request
    ) {
        return Api.OK(business.updateAdSet(workspaceId, assetId, user.getId(), adSetId, request));
    }

    @PatchMapping("/ad-accounts/{assetId}/campaigns/{campaignId}")
    public Api<MetaGraphClient.UpdatedAdObject> updateCampaign(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId,
            @PathVariable Long assetId,
            @PathVariable String campaignId,
            @RequestBody @Valid MetaAdBudgetUpdateRequest request
    ) {
        return Api.OK(business.updateCampaign(workspaceId, assetId, user.getId(), campaignId, request));
    }

    @PostMapping(value = "/ad-accounts/{assetId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Api<MetaAdImageUploadResponse> uploadImage(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId,
            @PathVariable Long assetId,
            @RequestPart("file") MultipartFile file
    ) {
        return Api.OK(business.uploadImage(workspaceId, assetId, user.getId(), file));
    }

    @PostMapping("/ad-accounts/{assetId}/ads")
    public Api<MetaAdCreateResponse> createAd(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId,
            @PathVariable Long assetId,
            @RequestBody @Valid MetaAdCreateRequest request
    ) {
        return Api.OK(business.createAd(workspaceId, assetId, user.getId(), request));
    }

    @ExceptionHandler(MetaAdCreationException.class)
    public ResponseEntity<Api<MetaAdCreateResponse>> creationFailed(MetaAdCreationException exception) {
        return ResponseEntity.status(exception.getCodeIfs().getHttpStatusCode())
                .body(new Api<>(Result.ERROR(exception.getCodeIfs(), exception.getDescription()), exception.getResult()));
    }

    @PostMapping("/ad-accounts/{assetId}/campaigns")
    public Api<MetaGraphClient.CreatedCampaign> createCampaign(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId,
            @PathVariable Long assetId,
            @RequestBody @Valid MetaCampaignCreateRequest request
    ) {
        return Api.OK(business.createCampaign(workspaceId, assetId, user.getId(), request));
    }

    @GetMapping("/ad-accounts/{assetId}/campaigns")
    public Api<List<MetaGraphClient.Campaign>> getCampaigns(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId,
            @PathVariable Long assetId
    ) {
        return Api.OK(business.getCampaigns(workspaceId, assetId, user.getId()));
    }

    @GetMapping("/ad-accounts/{assetId}/insights")
    public Api<MetaAdAccountPerformanceResponse> getAccountPerformance(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId,
            @PathVariable Long assetId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate until
    ) {
        return Api.OK(business.getAccountPerformance(workspaceId, assetId, user.getId(), since, until));
    }

    @GetMapping("/insights")
    public Api<MetaAdWorkspacePerformanceResponse> getWorkspacePerformance(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate until
    ) {
        return Api.OK(business.getWorkspacePerformance(workspaceId, user.getId(), since, until));
    }
}
