package com.orinan.adminapi.domain.overview.business;

import com.orinan.adminapi.annotation.Business;
import com.orinan.adminapi.domain.overview.controller.model.AdminOverviewResponse;
import com.orinan.adminapi.domain.overview.converter.AdminOverviewConverter;
import com.orinan.adminapi.domain.overview.service.AdminOverviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Business
@RequiredArgsConstructor
public class AdminOverviewBusiness {
    private final AdminOverviewService overviewService;
    private final AdminOverviewConverter overviewConverter;

    @Transactional(readOnly = true)
    public AdminOverviewResponse overview() {
        var now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        return overviewConverter.toResponse(overviewService.summarize(now), now);
    }
}
