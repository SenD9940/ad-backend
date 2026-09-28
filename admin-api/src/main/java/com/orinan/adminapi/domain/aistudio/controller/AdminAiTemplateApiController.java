package com.orinan.adminapi.domain.aistudio.controller;

import com.orinan.adminapi.common.api.Api;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.adminapi.domain.aistudio.business.AdminAiTemplateBusiness;
import com.orinan.adminapi.domain.aistudio.controller.model.*;
import com.orinan.adminapi.domain.auth.model.AdminPrincipal;
import com.orinan.db.aistudio.enums.AiStudioKind;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin-api/ai-studio/templates")
public class AdminAiTemplateApiController {
    private final AdminAiTemplateBusiness business;

    @GetMapping
    public Api<PageResponse<AdminAiTemplateResponse>> list(@RequestParam(required = false) AiStudioKind kind,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) { return Api.OK(business.list(kind, categoryId, q, page, size)); }
    @GetMapping("/{id}")
    public Api<AdminAiTemplateResponse> detail(@PathVariable long id) { return Api.OK(business.detail(id)); }
    @PostMapping
    public Api<AdminAiTemplateResponse> create(@AuthenticationPrincipal AdminPrincipal actor,
            @Valid @RequestBody AdminAiTemplateCreateRequest request) { return Api.OK(business.create(actor.id(), request)); }
    @PatchMapping("/{id}")
    public Api<AdminAiTemplateResponse> update(@AuthenticationPrincipal AdminPrincipal actor, @PathVariable long id,
            @Valid @RequestBody AdminAiTemplateUpdateRequest request) { return Api.OK(business.update(actor.id(), id, request)); }
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Api<AdminAiImageUploadResponse> upload(@AuthenticationPrincipal AdminPrincipal actor,
            @RequestPart("file") MultipartFile file) { return Api.OK(business.upload(actor.id(), file)); }

    @ExceptionHandler({MissingServletRequestPartException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<?> imageRequest(Exception ignored) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Api.ERROR(400, "10MB 이하 PNG 또는 JPEG 이미지를 file 항목에 첨부해 주세요."));
    }
}
