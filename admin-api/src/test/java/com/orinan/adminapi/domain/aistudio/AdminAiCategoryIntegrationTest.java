package com.orinan.adminapi.domain.aistudio;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.domain.aistudio.business.AdminAiCategoryBusiness;
import com.orinan.adminapi.domain.aistudio.business.AdminAiTemplateBusiness;
import com.orinan.adminapi.domain.aistudio.controller.model.*;
import com.orinan.adminapi.domain.aistudio.converter.AdminAiCategoryConverter;
import com.orinan.adminapi.domain.aistudio.converter.AdminAiTemplateConverter;
import com.orinan.adminapi.domain.aistudio.service.*;
import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.domain.user.service.AdminUserMutationGuard;
import com.orinan.db.aistudio.category.*;
import com.orinan.db.aistudio.enums.AiStudioKind;
import com.orinan.db.aistudio.template.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@ActiveProfiles("test")
@ContextConfiguration(classes = AdminAiCategoryIntegrationTest.Config.class)
class AdminAiCategoryIntegrationTest {
    private static final String KEY = "test/ai-studio/templates/4c107049-3422-46e8-8102-dd148d96b4be.png";
    @Autowired AdminAiCategoryBusiness categories;
    @Autowired AdminAiTemplateBusiness templates;
    @Autowired AiStudioCategoryRepository categoryRepository;
    @Autowired AiTemplateRepository templateRepository;
    @Autowired AiTemplateImageRepository images;
    @Autowired AdminUserMutationGuard guard;
    @Autowired AdminAuditService audit;
    @Autowired AdminAiImageStorage storage;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void setup() {
        reset(guard, audit, storage);
        when(storage.preview(KEY)).thenReturn(new AdminAiImageStorage.Preview("https://example.test/sample.png", Instant.now().plusSeconds(3600)));
        images.save(new AiTemplateImageEntity(KEY, "image/png", 100, 10, 10, 1, LocalDateTime.now()));
    }

    @Test void administratorCanCreateRenameListAndDeleteAnUnusedCategory() {
        var created = categories.create(1, new AdminAiCategoryRequest("  식품  "));
        assertThat(created.name()).isEqualTo("식품");
        assertThat(categories.list()).extracting(AdminAiCategoryResponse::id).containsExactly(created.id());
        var renamed = categories.update(1, created.id(), new AdminAiCategoryRequest("  건강식품 "));
        assertThat(renamed.id()).isEqualTo(created.id());
        assertThat(renamed.name()).isEqualTo("건강식품");
        em.flush(); em.clear();
        assertThat(categoryRepository.findById(created.id()).orElseThrow().getName()).isEqualTo("건강식품");
        var deleted = categories.delete(1, created.id());
        assertThat(deleted.id()).isEqualTo(created.id()); assertThat(deleted.success()).isTrue();
        assertThat(categories.list()).isEmpty();
        verify(guard, times(3)).lock(1, 1);
        verify(audit, times(3)).record(eq(1L), anyString(), anyString(), eq(created.id()), anyString(), any(), any());
    }

    @Test void categoryNamesAreTrimmedAndUniqueRegardlessOfCase() {
        var first = categories.create(1, new AdminAiCategoryRequest("Beauty"));
        assertStatus(() -> categories.create(1, new AdminAiCategoryRequest(" beauty ")), HttpStatus.CONFLICT);
        var second = categories.create(1, new AdminAiCategoryRequest("Food"));
        assertStatus(() -> categories.update(1, second.id(), new AdminAiCategoryRequest(" BEAUTY ")), HttpStatus.CONFLICT);
        assertThat(categories.update(1, first.id(), new AdminAiCategoryRequest("Beauty")).name()).isEqualTo("Beauty");
        assertThat(categoryRepository.findById(second.id()).orElseThrow().getName()).isEqualTo("Food");
        assertThat(categoryRepository.count()).isEqualTo(2);
    }

    @Test void blankAndOverlongNamesAndMissingIdsAreRejectedWithoutWritingCategories() {
        for (String name : new String[]{null, "", "   ", "a".repeat(81)})
            assertStatus(() -> categories.create(1, new AdminAiCategoryRequest(name)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> categories.update(1, Long.MAX_VALUE, new AdminAiCategoryRequest("식품")), HttpStatus.NOT_FOUND);
        assertStatus(() -> categories.delete(1, Long.MAX_VALUE), HttpStatus.NOT_FOUND);
        assertThat(categoryRepository.count()).isZero();
        verifyNoInteractions(audit);
    }

    @Test void categoryRenameChangesExistingTemplateDisplayWithoutReplacingTheForeignKey() {
        var category = categories.create(1, new AdminAiCategoryRequest("기존 분류"));
        var template = templates.create(1, template(category.id(), "참조 샘플", true));
        em.flush(); em.clear();
        assertThat(jdbc.queryForObject("select category_id from ai_templates where id = ?", Long.class, template.id())).isEqualTo(category.id());
        categories.update(1, category.id(), new AdminAiCategoryRequest("새 분류"));
        em.flush(); em.clear();
        var detail = templates.detail(template.id());
        assertThat(detail.categoryId()).isEqualTo(category.id()); assertThat(detail.categoryName()).isEqualTo("새 분류");
        var customerTemplate = templateRepository.findByIdAndPublishedTrue(template.id()).orElseThrow();
        assertThat(customerTemplate.getCategory().getId()).isEqualTo(category.id());
        assertThat(customerTemplate.getCategory().getName()).isEqualTo("새 분류");
        assertThat(templates.list(null, category.id(), "새 분류", 0, 20).items()).hasSize(1);
    }

    @Test void referencedCategoriesCannotBeDeletedUntilTemplatesMoveToAnotherCategory() {
        var first = categories.create(1, new AdminAiCategoryRequest("식품"));
        var second = categories.create(1, new AdminAiCategoryRequest("생활"));
        var template = templates.create(1, template(first.id(), "샘플", false));
        assertStatus(() -> categories.delete(1, first.id()), HttpStatus.CONFLICT);
        assertThat(categoryRepository.existsById(first.id())).isTrue();
        var moved = templates.update(1, template.id(), updateCategory(second.id()));
        assertThat(moved.categoryId()).isEqualTo(second.id()); assertThat(moved.categoryName()).isEqualTo("생활");
        assertThat(categories.delete(1, first.id()).success()).isTrue();
        assertThat(categoryRepository.existsById(first.id())).isFalse();
        assertThat(templates.detail(template.id()).categoryId()).isEqualTo(second.id());
    }

    @Test void templatesRequireRegisteredCategoryAndPatchWithoutCategoryPreservesAssociation() {
        var category = categories.create(1, new AdminAiCategoryRequest("식품"));
        assertThatThrownBy(() -> templates.create(1, template(null, "누락", false))).isInstanceOf(AdminException.class);
        assertThatThrownBy(() -> templates.create(1, template(Long.MAX_VALUE, "존재하지 않음", false))).isInstanceOf(AdminException.class);
        assertThat(templateRepository.count()).isZero();
        var created = templates.create(1, template(category.id(), "원본", false));
        assertThatThrownBy(() -> templates.update(1, created.id(), updateCategory(Long.MAX_VALUE))).isInstanceOf(AdminException.class);
        var updated = templates.update(1, created.id(), new AdminAiTemplateUpdateRequest(null, "제목만 변경", null, null, null, null, null));
        assertThat(updated.title()).isEqualTo("제목만 변경"); assertThat(updated.categoryId()).isEqualTo(category.id());
    }

    @Test void categoryFilterAppliesToAdministratorAndCustomerTemplateQueries() {
        var food = categories.create(1, new AdminAiCategoryRequest("식품"));
        var beauty = categories.create(1, new AdminAiCategoryRequest("뷰티"));
        var publishedFood = templates.create(1, template(food.id(), "식품 공개", true));
        templates.create(1, template(food.id(), "식품 초안", false));
        templates.create(1, template(beauty.id(), "뷰티 공개", true));
        assertThat(templates.list(null, food.id(), null, 0, 20).items()).hasSize(2)
                .allSatisfy(item -> assertThat(item.categoryId()).isEqualTo(food.id()));
        assertThat(templateRepository.searchPublished(null, food.id(), null, PageRequest.of(0, 20)).getContent())
                .extracting(AiTemplateEntity::getId).containsExactly(publishedFood.id());
        assertThat(templates.list(AiStudioKind.DETAIL_PAGE, food.id(), null, 0, 20).items()).isEmpty();
    }

    @Test void databaseForeignKeyRejectsAnUnknownCategoryEvenWithoutBusinessValidation() {
        var category = categories.create(1, new AdminAiCategoryRequest("식품"));
        var template = templates.create(1, template(category.id(), "샘플", false));
        em.flush();
        assertThatThrownBy(() -> jdbc.update("update ai_templates set category_id = ? where id = ?", Long.MAX_VALUE, template.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void administratorRevocationPreventsCategoryWritesAndTheirAudits() {
        var category = categoryRepository.save(new AiStudioCategoryEntity("식품", LocalDateTime.now()));
        doThrow(new AdminException(HttpStatus.FORBIDDEN, "현재 관리자 권한이 필요합니다.")).when(guard).lock(1, 1);
        assertStatus(() -> categories.create(1, new AdminAiCategoryRequest("뷰티")), HttpStatus.FORBIDDEN);
        assertStatus(() -> categories.update(1, category.getId(), new AdminAiCategoryRequest("생활")), HttpStatus.FORBIDDEN);
        assertStatus(() -> categories.delete(1, category.getId()), HttpStatus.FORBIDDEN);
        assertThat(categoryRepository.findById(category.getId()).orElseThrow().getName()).isEqualTo("식품");
        verifyNoInteractions(audit);
    }

    private AdminAiTemplateCreateRequest template(Long categoryId, String title, boolean published) {
        return new AdminAiTemplateCreateRequest(AiStudioKind.AD_IMAGE, title, "샘플 설명", categoryId, "상품 정보를 사용해 디자인", published ? KEY : null, published);
    }
    private AdminAiTemplateUpdateRequest updateCategory(long categoryId) {
        return new AdminAiTemplateUpdateRequest(null, null, null, categoryId, null, null, null);
    }
    private void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable task, HttpStatus status) {
        assertThatThrownBy(task).isInstanceOfSatisfying(AdminException.class, error -> assertThat(error.getStatus()).isEqualTo(status));
    }
    @Configuration
    @EntityScan(basePackages = "com.orinan.db.aistudio")
    @EnableJpaRepositories(basePackages = "com.orinan.db.aistudio")
    @Import({AdminAiCategoryBusiness.class, AdminAiCategoryService.class, AdminAiCategoryConverter.class,
            AdminAiTemplateBusiness.class, AdminAiTemplateService.class, AdminAiTemplateConverter.class})
    static class Config {
        @Bean AdminAiImageStorage storage() { return mock(AdminAiImageStorage.class); }
        @Bean AdminUserMutationGuard guard() { return mock(AdminUserMutationGuard.class); }
        @Bean AdminAuditService audit() { return mock(AdminAuditService.class); }
    }
}
