package com.orinan.adminapi.domain.aistudio.business;

import com.orinan.adminapi.annotation.Business;
import com.orinan.adminapi.common.api.AdminPageRequest;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.domain.aistudio.controller.model.*;
import com.orinan.adminapi.domain.aistudio.converter.AdminAiTemplateConverter;
import com.orinan.adminapi.domain.aistudio.service.*;
import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.domain.user.service.AdminUserMutationGuard;
import com.orinan.db.aistudio.enums.AiStudioKind;
import com.orinan.db.aistudio.template.AiTemplateEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

@Business
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAiTemplateBusiness {
    private final AdminAiTemplateService templates;
    private final AdminAiCategoryService categories;
    private final AdminAiImageStorage storage;
    private final AdminAiTemplateConverter converter;
    private final AdminUserMutationGuard guard;
    private final AdminAuditService audit;

    public PageResponse<AdminAiTemplateResponse> list(AiStudioKind kind, Long categoryId, String q, int page, int size) {
        if (categoryId != null && categoryId < 1) throw invalid("올바른 카테고리를 선택해 주세요.");
        q = AdminPageRequest.query(q);
        String pattern = q == null ? null : "%" + q.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return converter.toPage(templates.search(kind, categoryId, pattern, AdminPageRequest.page(page, size)));
    }
    public AdminAiTemplateResponse detail(long id) { return converter.toResponse(templates.find(id)); }

    @Transactional
    public AdminAiTemplateResponse create(long actor, AdminAiTemplateCreateRequest request) {
        guard.lock(actor, actor);
        if (request.kind() == null) throw invalid("템플릿 유형을 선택해 주세요.");
        var category = categories.lock(request.categoryId());
        String image = imageKey(request.previewImageKey());
        validatePublication(request.published(), image);
        var entity = templates.save(new AiTemplateEntity(request.kind(), metadata(request.title(), 150, image, defaultTitle(category.getName(), request.kind())),
                description(request.description(), image, request.kind()), category,
                metadata(request.prompt(), 6000, image, defaultPrompt(request.kind())),
                image, request.published(), actor, now()));
        audit.record(actor, "AI_TEMPLATE_CREATE", "AI_TEMPLATE", entity.getId(), "AI 템플릿 등록", null, auditValue(entity));
        return converter.toResponse(entity);
    }

    @Transactional
    public AdminAiTemplateResponse update(long actor, long id, AdminAiTemplateUpdateRequest request) {
        guard.lock(actor, actor);
        if (request.empty()) throw invalid("수정할 값을 입력해 주세요.");
        // Lock the destination category before the template, matching deletion's lock order.
        var selectedCategory = request.categoryId() == null ? null : categories.lock(request.categoryId());
        var entity = templates.lock(id);
        String before = auditValue(entity);
        String image = request.previewImageKey() == null ? entity.getPreviewImageKey() : imageKey(request.previewImageKey());
        boolean published = request.published() == null ? entity.isPublished() : request.published();
        validatePublication(published, image);
        // Validate before mutating the managed entity.
        var category = selectedCategory == null ? entity.getCategory() : selectedCategory;
        var kind = request.kind() == null ? entity.getKind() : request.kind();
        String title = request.title() == null ? entity.getTitle() : metadata(request.title(), 150, image, defaultTitle(category.getName(), kind));
        String prompt = request.prompt() == null ? entity.getPrompt() : metadata(request.prompt(), 6000, image, defaultPrompt(kind));
        String description = request.description() == null ? entity.getDescription() : description(request.description(), image, kind);
        entity.setTitle(title); entity.setPrompt(prompt); entity.setDescription(description); entity.setCategory(category);
        if (request.kind() != null) entity.setKind(request.kind());
        entity.setPreviewImageKey(image); entity.setPublished(published); entity.setUpdatedAt(now());
        audit.record(actor, "AI_TEMPLATE_UPDATE", "AI_TEMPLATE", id, "AI 템플릿 수정", before, auditValue(entity));
        return converter.toResponse(entity);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminAiImageUploadResponse upload(long actor, MultipartFile file) {
        templates.authorizeUpload(actor);
        var image = storage.upload(file);
        templates.registerImage(actor, image);
        return new AdminAiImageUploadResponse(image.key(), image.preview().url(), image.preview().expiresAt());
    }
    private String imageKey(String value) {
        if (value == null || value.isBlank()) return null;
        String key = value.strip(); storage.validateKey(key); templates.requireRegisteredImage(key); return key;
    }
    private void validatePublication(boolean published, String image) {
        if (published && image == null) throw invalid("사용자에게 공개하려면 샘플 이미지를 등록해 주세요.");
        if (image != null) { storage.validateKey(image); templates.requireRegisteredImage(image); }
    }
    /** Saving the uploaded sample must not depend on a successful external analysis call. */
    private String metadata(String value, int max, String image, String fallback) {
        String supplied = optional(value, max);
        if (!supplied.isBlank()) return supplied;
        if (image == null) throw invalid("샘플 이미지를 먼저 업로드해 주세요. 이미지 없는 초안에는 이름과 생성 지침이 필요합니다.");
        return fallback;
    }
    private String description(String value, String image, AiStudioKind kind) {
        String supplied = optional(value, 2000);
        return supplied.isBlank() && image != null
                ? "샘플 이미지의 구도와 색감을 참고해 우리 상품에 맞는 " + kindLabel(kind) + "를 만듭니다." : supplied;
    }
    private String defaultTitle(String category, AiStudioKind kind) {
        return category + " " + kindLabel(kind) + " 샘플";
    }
    private String defaultPrompt(AiStudioKind kind) {
        return "참고 이미지의 구도, 색감, 조명, 여백과 텍스트 배치를 활용해 " + kindLabel(kind) + "를 구성하세요. "
                + "실제 상품과 문구는 고객이 제공한 상품 정보와 추가 요청을 사용하세요. "
                + "참고 이미지의 브랜드, 로고, 가격, 할인, 후기, 인증이나 효능을 복사하거나 사실로 추정하지 마세요.";
    }
    private String kindLabel(AiStudioKind kind) {
        return kind == AiStudioKind.DETAIL_PAGE ? "상품 상세페이지" : "광고 이미지";
    }
    private String optional(String value, int max) {
        if (value != null && value.length() > max) throw invalid("입력값의 최대 길이를 확인해 주세요.");
        return value == null ? "" : value.strip();
    }
    private String auditValue(AiTemplateEntity entity) {
        return "kind=" + entity.getKind() + ";published=" + entity.isPublished() + ";title=" + entity.getTitle()
                + ";categoryId=" + entity.getCategory().getId() + ";category=" + entity.getCategory().getName();
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Seoul")); }
    private AdminException invalid(String message) { return new AdminException(HttpStatus.BAD_REQUEST, message); }
}
