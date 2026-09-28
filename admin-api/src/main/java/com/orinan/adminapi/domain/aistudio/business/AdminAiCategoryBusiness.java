package com.orinan.adminapi.domain.aistudio.business;

import com.orinan.adminapi.annotation.Business;
import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.domain.aistudio.controller.model.*;
import com.orinan.adminapi.domain.aistudio.converter.AdminAiCategoryConverter;
import com.orinan.adminapi.domain.aistudio.service.AdminAiCategoryService;
import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.domain.user.service.AdminUserMutationGuard;
import com.orinan.db.aistudio.category.AiStudioCategoryEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Business
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAiCategoryBusiness {
    private final AdminAiCategoryService categories;
    private final AdminAiCategoryConverter converter;
    private final AdminUserMutationGuard guard;
    private final AdminAuditService audit;

    public List<AdminAiCategoryResponse> list() { return categories.list().stream().map(converter::toResponse).toList(); }

    @Transactional
    public AdminAiCategoryResponse create(long actor, AdminAiCategoryRequest request) {
        guard.lock(actor, actor);
        String name = name(request);
        categories.requireUnique(name, null);
        var category = categories.save(new AiStudioCategoryEntity(name, now()));
        audit.record(actor, "AI_CATEGORY_CREATE", "AI_CATEGORY", category.getId(), "AI 카테고리 등록", null, category.getName());
        return converter.toResponse(category);
    }

    @Transactional
    public AdminAiCategoryResponse update(long actor, long id, AdminAiCategoryRequest request) {
        guard.lock(actor, actor);
        String name = name(request);
        var category = categories.lock(id);
        if (name.equals(category.getName())) return converter.toResponse(category);
        categories.requireUnique(name, id);
        String previous = category.getName();
        category.rename(name, now());
        categories.save(category);
        audit.record(actor, "AI_CATEGORY_UPDATE", "AI_CATEGORY", id, "AI 카테고리 이름 변경", previous, name);
        return converter.toResponse(category);
    }

    @Transactional
    public AdminAiCategoryDeleteResponse delete(long actor, long id) {
        guard.lock(actor, actor);
        var category = categories.lock(id);
        String name = category.getName();
        categories.delete(category);
        audit.record(actor, "AI_CATEGORY_DELETE", "AI_CATEGORY", id, "AI 카테고리 삭제", name, null);
        return new AdminAiCategoryDeleteResponse(id, true);
    }

    private String name(AdminAiCategoryRequest request) {
        if (request == null || request.name() == null || request.name().isBlank() || request.name().length() > 80)
            throw new AdminException(HttpStatus.BAD_REQUEST, "카테고리 이름을 1~80자로 입력해 주세요.");
        return request.name().strip();
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Seoul")); }
}
