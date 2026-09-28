package com.orinan.adminapi.domain.aistudio;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.domain.aistudio.business.AdminAiTemplateAnalysisBusiness;
import com.orinan.adminapi.domain.aistudio.client.AdminAiTemplateAnalysisClient;
import com.orinan.adminapi.domain.aistudio.controller.model.*;
import com.orinan.adminapi.domain.aistudio.service.*;
import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.domain.user.service.AdminUserMutationGuard;
import com.orinan.db.aistudio.enums.AiStudioKind;
import com.orinan.db.aistudio.template.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminAiTemplateAnalysisTest {
    private final AdminUserMutationGuard guard = mock(AdminUserMutationGuard.class);
    private final AiTemplateImageRepository images = mock(AiTemplateImageRepository.class);
    private final AdminAiImageStorage storage = mock(AdminAiImageStorage.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final AdminAiTemplateAnalysisClient client = mock(AdminAiTemplateAnalysisClient.class);
    private final AdminAiTemplateAnalysisAccess access = new AdminAiTemplateAnalysisAccess(guard, images, storage, audit);
    private final AdminAiTemplateAnalysisBusiness business = new AdminAiTemplateAnalysisBusiness(access, storage, client);
    private static final String KEY = "test/ai-studio/templates/4c107049-3422-46e8-8102-dd148d96b4be.png";
    private final AdminAiTemplateAnalysisRequest request = new AdminAiTemplateAnalysisRequest(KEY, AiStudioKind.AD_IMAGE);
    private final AdminAiImageStorage.ImageData image = new AdminAiImageStorage.ImageData(new byte[]{1}, "image/png", 1, 1);
    private final AdminAiTemplateAnalysisResponse metadata = new AdminAiTemplateAnalysisResponse("스타일", "설명", "지침");

    @BeforeEach void setup() {
        when(images.findByImageKey(KEY)).thenReturn(Optional.of(new AiTemplateImageEntity(KEY, "image/png", 1, 1, 1, 7, LocalDateTime.now())));
        when(storage.read(KEY)).thenReturn(image); when(client.analyze(AiStudioKind.AD_IMAGE, image)).thenReturn(metadata);
    }

    @Test void registeredImageIsCheckedBeforeStorageBeforeProviderAndBeforeReturningWithAudit() {
        assertThat(business.analyze(7, request)).isEqualTo(metadata);
        var order = inOrder(guard, storage, client, audit);
        order.verify(guard).lock(7, 7); order.verify(storage).validateKey(KEY); order.verify(client).requireConfigured();
        order.verify(storage).read(KEY); order.verify(guard).lock(7, 7); order.verify(storage).validateKey(KEY);
        order.verify(client).analyze(AiStudioKind.AD_IMAGE, image); order.verify(guard).lock(7, 7); order.verify(storage).validateKey(KEY);
        order.verify(audit).record(eq(7L), eq("AI_TEMPLATE_ANALYZE"), eq("AI_TEMPLATE_IMAGE"), isNull(), anyString(), isNull(), eq(KEY));
    }

    @Test void unregisteredAndForeignKeysNeverReadStorageOrCallOpenAi() {
        when(images.findByImageKey(KEY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> business.analyze(7, request)).isInstanceOf(AdminException.class);
        verify(storage, never()).read(anyString()); verifyNoInteractions(client, audit);
        doThrow(new AdminException(HttpStatus.BAD_REQUEST, "invalid key")).when(storage).validateKey("https://untrusted.test/");
        assertThatThrownBy(() -> business.analyze(7, new AdminAiTemplateAnalysisRequest("https://untrusted.test/", AiStudioKind.AD_IMAGE)))
                .isInstanceOf(AdminException.class);
        verify(images, never()).findByImageKey("https://untrusted.test/");
    }

    @Test void revokedAdministratorNeverStartsStorageOrPaidAnalysis() {
        doThrow(new AdminException(HttpStatus.FORBIDDEN, "revoked")).when(guard).lock(7, 7);
        assertThatThrownBy(() -> business.analyze(7, request)).isInstanceOf(AdminException.class);
        verifyNoInteractions(storage, client, audit);
    }

    @Test void revocationDuringStorageStopsProviderAndRevocationDuringProviderStopsResult() {
        when(storage.read(KEY)).thenAnswer(call -> {
            doThrow(new AdminException(HttpStatus.FORBIDDEN, "revoked")).when(guard).lock(7, 7); return image;
        });
        assertThatThrownBy(() -> business.analyze(7, request)).isInstanceOf(AdminException.class);
        verify(client, never()).analyze(any(), any()); verifyNoInteractions(audit);
        reset(guard); when(storage.read(KEY)).thenReturn(image);
        when(client.analyze(AiStudioKind.AD_IMAGE, image)).thenAnswer(call -> {
            doThrow(new AdminException(HttpStatus.FORBIDDEN, "revoked")).when(guard).lock(7, 7); return metadata;
        });
        assertThatThrownBy(() -> business.analyze(7, request)).isInstanceOf(AdminException.class);
        verifyNoInteractions(audit);
    }

    @Test void missingConfigurationDoesNotFetchImageOrMakePaidCalls() {
        doThrow(new AdminException(HttpStatus.SERVICE_UNAVAILABLE, "configure key")).when(client).requireConfigured();
        assertThatThrownBy(() -> business.analyze(7, request)).isInstanceOf(AdminException.class);
        verify(storage, never()).read(anyString()); verify(client, never()).analyze(any(), any()); verifyNoInteractions(audit);
    }
}
