package com.orinan.adminapi.domain.overview.controller;

import com.orinan.adminapi.common.api.Api;
import com.orinan.adminapi.domain.overview.business.AdminOverviewBusiness;
import com.orinan.adminapi.domain.overview.controller.model.AdminOverviewResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin-api/overview")
public class AdminOverviewApiController {
    private final AdminOverviewBusiness overviewBusiness;
    @GetMapping
    public Api<AdminOverviewResponse> overview() { return Api.OK(overviewBusiness.overview()); }
}
