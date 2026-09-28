package com.orinan.adminapi.domain.aistudio;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.domain.aistudio.business.AdminAiTemplateBusiness;
import com.orinan.adminapi.domain.aistudio.controller.model.*;
import com.orinan.adminapi.domain.aistudio.converter.AdminAiTemplateConverter;
import com.orinan.adminapi.domain.aistudio.service.*;
import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.domain.user.service.AdminUserMutationGuard;
import com.orinan.db.aistudio.enums.AiStudioKind;
import com.orinan.db.aistudio.category.*;
import com.orinan.db.aistudio.template.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@ActiveProfiles("test")
@ContextConfiguration(classes = AdminAiTemplateIntegrationTest.Config.class)
class AdminAiTemplateIntegrationTest {
    private static final String KEY = "test/ai-studio/templates/4c107049-3422-46e8-8102-dd148d96b4be.png";
    @Autowired AdminAiTemplateBusiness business;
    @Autowired AiTemplateRepository templates;
    @Autowired AiTemplateImageRepository images;
    @Autowired AiStudioCategoryRepository categories;
    @Autowired AdminUserMutationGuard guard;
    @Autowired AdminAuditService audit;
    @Autowired AdminAiImageStorage storage;
    long categoryId;

    @BeforeEach void setup() {
        reset(guard, audit, storage);
        when(storage.preview(KEY)).thenReturn(new AdminAiImageStorage.Preview("https://example.test/sample.png", Instant.now().plusSeconds(3600)));
        images.save(new AiTemplateImageEntity(KEY, "image/png", 100, 10, 10, 1, LocalDateTime.now()));
        categoryId = categories.save(new AiStudioCategoryEntity("식품", LocalDateTime.now())).getId();
    }

    @Test void draftCanBeCreatedWithoutImageAndRemainsInvisibleToCustomers() {
        var result = business.create(1, create("초안", null, false));
        assertThat(result.published()).isFalse();
        assertThat(result.previewImageUrl()).isNull();
        assertThat(templates.findByIdAndPublishedTrue(result.id())).isEmpty();
        assertThat(templates.searchPublished(null, null, null, PageRequest.of(0, 20))).isEmpty();
        verify(guard).lock(1, 1);
        verify(audit).record(eq(1L), eq("AI_TEMPLATE_CREATE"), eq("AI_TEMPLATE"), eq(result.id()), anyString(), isNull(), anyString());
    }

    @Test void publishRequiresImageFromUploadRegistry() {
        assertThatThrownBy(() -> business.create(1, create("이미지 없음", null, true))).isInstanceOf(AdminException.class);
        assertThatThrownBy(() -> business.create(1, create("조작된 이미지", "other/image.png", true))).isInstanceOf(AdminException.class);
        assertThat(templates.count()).isZero();
        verifyNoInteractions(audit);
    }

    @Test void registeredImageAndCategoryAreEnoughToPublishWithoutManualMetadataOrAiAnalysis() {
        var created = business.create(1, new AdminAiTemplateCreateRequest(AiStudioKind.AD_IMAGE, null, null,
                categoryId, null, KEY, true));
        assertThat(created.title()).isEqualTo("식품 광고 이미지 샘플");
        assertThat(created.description()).contains("샘플 이미지", "광고 이미지");
        assertThat(created.prompt()).contains("참고 이미지", "고객이 제공한 상품 정보");
        assertThat(templates.findByIdAndPublishedTrue(created.id())).isPresent();
        assertThat(created.previewImageKey()).isEqualTo(KEY);
        var detail = business.create(1, new AdminAiTemplateCreateRequest(AiStudioKind.DETAIL_PAGE, "  ", "",
                categoryId, "  ", KEY, false));
        assertThat(detail.title()).isEqualTo("식품 상품 상세페이지 샘플");
        assertThat(detail.prompt()).contains("상품 상세페이지");
        assertThatThrownBy(() -> business.create(1, new AdminAiTemplateCreateRequest(AiStudioKind.AD_IMAGE,
                null, null, categoryId, null, null, false))).isInstanceOf(AdminException.class);
    }

    @Test void providedAnalysisOrManualMetadataIsKeptAndBlankFieldsCanUseImageDefaults() {
        var created = business.create(1, new AdminAiTemplateCreateRequest(AiStudioKind.AD_IMAGE,
                "  자연광 스타일  ", "  밝은 여백  ", categoryId, "  부드러운 빛과 여백을 활용  ", KEY, true));
        assertThat(created.title()).isEqualTo("자연광 스타일");
        assertThat(created.description()).isEqualTo("밝은 여백");
        assertThat(created.prompt()).isEqualTo("부드러운 빛과 여백을 활용");
        var preserved = business.update(1, created.id(), new AdminAiTemplateUpdateRequest(null, null, null,
                null, null, null, false));
        assertThat(preserved.title()).isEqualTo(created.title());
        assertThat(preserved.prompt()).isEqualTo(created.prompt());
        var reset = business.update(1, created.id(), new AdminAiTemplateUpdateRequest(null, " ", "",
                null, "", null, null));
        assertThat(reset.title()).isEqualTo("식품 광고 이미지 샘플");
        assertThat(reset.description()).isNotBlank();
        assertThat(reset.prompt()).contains("참고 이미지");
    }

    @Test void partialUpdatePreservesFieldsAndCanPublishThenUnpublish() {
        var created = business.create(1, create("초안 제목", KEY, false));
        var published = business.update(1, created.id(), update(null, null, true));
        assertThat(published.title()).isEqualTo("초안 제목");
        assertThat(published.prompt()).isEqualTo("상품 특징을 사용해 광고를 구성");
        assertThat(published.previewImageKey()).isEqualTo(KEY);
        assertThat(published.categoryId()).isEqualTo(categoryId);
        assertThat(published.categoryName()).isEqualTo("식품");
        assertThat(templates.findByIdAndPublishedTrue(created.id())).isPresent();
        assertThatThrownBy(() -> business.update(1, created.id(), update(null, "", null))).isInstanceOf(AdminException.class);
        var draft = business.update(1, created.id(), update("수정된 초안", "", false));
        assertThat(draft.previewImageKey()).isNull();
        assertThat(draft.published()).isFalse();
        assertThat(templates.findByIdAndPublishedTrue(created.id())).isEmpty();
    }

    @Test void emptyOrBlankChangesAreRejectedWithoutMutatingExistingTitle() {
        var created = business.create(1, create("원본", null, false));
        assertThatThrownBy(() -> business.update(1, created.id(), update(null, null, null))).isInstanceOf(AdminException.class);
        assertThatThrownBy(() -> business.update(1, created.id(), update("  ", null, null))).isInstanceOf(AdminException.class);
        assertThat(templates.findById(created.id()).orElseThrow().getTitle()).isEqualTo("원본");
    }

    @Test void searchEscapesSqlWildcardsAndKindAndPageLimitsAreEnforced() {
        business.create(1, create("상품 %_! 샘플", KEY, true));
        business.create(1, create("상품 일반 샘플", null, false));
        assertThat(business.list(null, null, "%_!", 0, 20).items()).hasSize(1);
        assertThat(business.list(AiStudioKind.DETAIL_PAGE, null, null, 0, 20).items()).isEmpty();
        assertThatThrownBy(() -> business.list(null, null, null, -1, 20)).isInstanceOf(AdminException.class);
        assertThatThrownBy(() -> business.list(null, null, null, 0, 101)).isInstanceOf(AdminException.class);
        assertThatThrownBy(() -> business.list(null, null, "a".repeat(201), 0, 20)).isInstanceOf(AdminException.class);
    }

    @Test void revokedAdministratorCannotWriteOrUploadAfterAuthentication() {
        doThrow(new AdminException(HttpStatus.FORBIDDEN, "현재 관리자 권한이 필요합니다.")).when(guard).lock(1, 1);
        assertThatThrownBy(() -> business.create(1, create("샘플", KEY, true))).isInstanceOf(AdminException.class);
        assertThatThrownBy(() -> business.upload(1, null)).isInstanceOf(AdminException.class);
        verifyNoInteractions(storage, audit);
        assertThat(templates.count()).isZero();
    }

    private AdminAiTemplateCreateRequest create(String title, String key, boolean published) {
        return new AdminAiTemplateCreateRequest(AiStudioKind.AD_IMAGE, title, "설명", categoryId, "상품 특징을 사용해 광고를 구성", key, published);
    }
    private AdminAiTemplateUpdateRequest update(String title, String key, Boolean published) {
        return new AdminAiTemplateUpdateRequest(null, title, null, null, null, key, published);
    }
    @Configuration
    @EntityScan(basePackages = "com.orinan.db.aistudio")
    @EnableJpaRepositories(basePackages = "com.orinan.db.aistudio")
    @Import({AdminAiTemplateBusiness.class, AdminAiTemplateService.class, AdminAiTemplateConverter.class, AdminAiCategoryService.class})
    static class Config {
        @Bean AdminAiImageStorage storage() { return mock(AdminAiImageStorage.class); }
        @Bean AdminUserMutationGuard guard() { return mock(AdminUserMutationGuard.class); }
        @Bean AdminAuditService audit() { return mock(AdminAuditService.class); }
    }
}
