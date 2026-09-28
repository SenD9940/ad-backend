package com.orinan.adminapi.domain.overview.service;

import com.orinan.db.adminoverview.AdminOverviewRepository;
import com.orinan.db.adminoverview.projection.AdminOverviewProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminOverviewService {
    private final AdminOverviewRepository overviewRepository;
    public AdminOverviewProjection summarize(LocalDateTime now) { return overviewRepository.summarize(now); }
}
