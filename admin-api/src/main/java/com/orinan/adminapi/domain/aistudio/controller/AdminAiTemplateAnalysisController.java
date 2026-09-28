package com.orinan.adminapi.domain.aistudio.controller;

import com.orinan.adminapi.common.api.Api;
import com.orinan.adminapi.domain.aistudio.business.AdminAiTemplateAnalysisBusiness;
import com.orinan.adminapi.domain.aistudio.controller.model.*;
import com.orinan.adminapi.domain.auth.model.AdminPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin-api/ai-studio/templates/analyze")
public class AdminAiTemplateAnalysisController {
    private final AdminAiTemplateAnalysisBusiness business;

    @PostMapping
    public Api<AdminAiTemplateAnalysisResponse> analyze(@AuthenticationPrincipal AdminPrincipal actor,
            @Valid @RequestBody AdminAiTemplateAnalysisRequest request) {
        return Api.OK(business.analyze(actor.id(), request));
    }
}
