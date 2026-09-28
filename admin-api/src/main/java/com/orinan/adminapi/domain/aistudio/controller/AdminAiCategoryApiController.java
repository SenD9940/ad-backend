package com.orinan.adminapi.domain.aistudio.controller;

import com.orinan.adminapi.common.api.Api;
import com.orinan.adminapi.domain.aistudio.business.AdminAiCategoryBusiness;
import com.orinan.adminapi.domain.aistudio.controller.model.*;
import com.orinan.adminapi.domain.auth.model.AdminPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin-api/ai-studio/categories")
public class AdminAiCategoryApiController {
    private final AdminAiCategoryBusiness business;
    @GetMapping
    public Api<List<AdminAiCategoryResponse>> list() { return Api.OK(business.list()); }
    @PostMapping
    public Api<AdminAiCategoryResponse> create(@AuthenticationPrincipal AdminPrincipal actor,
            @Valid @RequestBody AdminAiCategoryRequest request) { return Api.OK(business.create(actor.id(), request)); }
    @PatchMapping("/{id}")
    public Api<AdminAiCategoryResponse> update(@AuthenticationPrincipal AdminPrincipal actor, @PathVariable long id,
            @Valid @RequestBody AdminAiCategoryRequest request) { return Api.OK(business.update(actor.id(), id, request)); }
    @DeleteMapping("/{id}")
    public Api<AdminAiCategoryDeleteResponse> delete(@AuthenticationPrincipal AdminPrincipal actor, @PathVariable long id) {
        return Api.OK(business.delete(actor.id(), id));
    }
}
