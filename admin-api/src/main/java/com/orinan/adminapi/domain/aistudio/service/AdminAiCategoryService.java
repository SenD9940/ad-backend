package com.orinan.adminapi.domain.aistudio.service;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.db.aistudio.category.*;
import com.orinan.db.aistudio.template.AiTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminAiCategoryService {
    private final AiStudioCategoryRepository categories;
    private final AiTemplateRepository templates;

    public List<AiStudioCategoryEntity> list() { return categories.findAllByOrderByNameAscIdAsc(); }
    /** Both assignment and deletion hold this lock until commit; the database FK is the final guard. */
    public AiStudioCategoryEntity lock(Long id) {
        if (id == null || id < 1) throw new AdminException(HttpStatus.BAD_REQUEST, "생성된 카테고리를 선택해 주세요.");
        return categories.findByIdForUpdate(id)
                .orElseThrow(() -> new AdminException(HttpStatus.NOT_FOUND, "카테고리를 찾을 수 없습니다. 목록을 새로 조회해 주세요."));
    }
    public void requireUnique(String name, Long currentId) {
        boolean duplicate = currentId == null ? categories.existsByNameIgnoreCase(name)
                : categories.existsByNameIgnoreCaseAndIdNot(name, currentId);
        if (duplicate) throw new AdminException(HttpStatus.CONFLICT, "같은 이름의 카테고리가 이미 있습니다.");
    }
    public AiStudioCategoryEntity save(AiStudioCategoryEntity category) { return categories.saveAndFlush(category); }
    public void delete(AiStudioCategoryEntity category) {
        if (templates.existsByCategoryId(category.getId()))
            throw new AdminException(HttpStatus.CONFLICT, "샘플에서 사용 중인 카테고리입니다. 샘플의 카테고리를 먼저 변경해 주세요.");
        categories.delete(category);
        categories.flush();
    }
}
