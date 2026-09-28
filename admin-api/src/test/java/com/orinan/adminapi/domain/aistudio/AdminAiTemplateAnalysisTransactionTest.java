package com.orinan.adminapi.domain.aistudio;

import com.orinan.adminapi.domain.aistudio.business.AdminAiTemplateAnalysisBusiness;
import com.orinan.adminapi.domain.aistudio.client.AdminAiTemplateAnalysisClient;
import com.orinan.adminapi.domain.aistudio.controller.model.*;
import com.orinan.adminapi.domain.aistudio.service.*;
import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.domain.user.service.AdminUserMutationGuard;
import com.orinan.db.aistudio.enums.AiStudioKind;
import com.orinan.db.aistudio.template.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@ActiveProfiles("test")
@ContextConfiguration(classes = AdminAiTemplateAnalysisTransactionTest.Config.class)
class AdminAiTemplateAnalysisTransactionTest {
    @Autowired AdminAiTemplateAnalysisBusiness business;
    @Autowired AiTemplateImageRepository images;
    @Autowired AdminUserMutationGuard guard;
    @Autowired AdminAiImageStorage storage;
    @Autowired AdminAiTemplateAnalysisClient client;
    @Autowired PlatformTransactionManager manager;

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void authorityChecksHaveTransactionsButRemoteImageAndAiRequestsNeverHoldDatabaseLocks() {
        String key = "test/ai-studio/templates/4c107049-3422-46e8-8102-dd148d96b4be.png";
        var transaction = new TransactionTemplate(manager);
        transaction.execute(status -> images.saveAndFlush(new AiTemplateImageEntity(key, "image/png", 1, 1, 1, 7, LocalDateTime.now())));
        var image = new AdminAiImageStorage.ImageData(new byte[]{1}, "image/png", 1, 1);
        var expected = new AdminAiTemplateAnalysisResponse("제목", "설명", "지침");
        // Stub the Mockito target without invoking the real MANDATORY transaction interceptor during setup.
        AdminUserMutationGuard guardMock = AopTestUtils.getTargetObject(guard);
        when(guardMock.lock(7, 7)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); return null;
        });
        when(storage.read(key)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return image;
        });
        when(client.analyze(AiStudioKind.AD_IMAGE, image)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return expected;
        });
        assertThat(business.analyze(7, new AdminAiTemplateAnalysisRequest(key, AiStudioKind.AD_IMAGE))).isEqualTo(expected);
        verify(guardMock, times(3)).lock(7, 7);
    }

    @Configuration
    @EntityScan(basePackages = "com.orinan.db.aistudio")
    @EnableJpaRepositories(basePackages = "com.orinan.db.aistudio")
    @Import({AdminAiTemplateAnalysisBusiness.class, AdminAiTemplateAnalysisAccess.class})
    static class Config {
        @Bean AdminUserMutationGuard guard() { return mock(AdminUserMutationGuard.class); }
        @Bean AdminAiImageStorage storage() { return mock(AdminAiImageStorage.class); }
        @Bean AdminAiTemplateAnalysisClient client() { return mock(AdminAiTemplateAnalysisClient.class); }
        @Bean AdminAuditService audit() { return mock(AdminAuditService.class); }
    }
}
