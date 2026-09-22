package com.orinan.api.domain.metaad.controller;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.domain.metaad.business.MetaAdBusiness;
import com.orinan.api.domain.metaad.controller.model.MetaAdAccountPerformanceResponse;
import com.orinan.api.domain.metaad.controller.model.MetaAdWorkspacePerformanceResponse;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import com.orinan.api.domain.user.controller.model.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/meta")
@RequiredArgsConstructor
public class MetaAdApiController {

    private final MetaAdBusiness business;

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
