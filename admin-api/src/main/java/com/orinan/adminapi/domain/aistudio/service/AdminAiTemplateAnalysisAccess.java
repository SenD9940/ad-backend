package com.orinan.adminapi.domain.aistudio.service;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.domain.user.service.AdminUserMutationGuard;
import com.orinan.db.aistudio.template.AiTemplateImageEntity;
import com.orinan.db.aistudio.template.AiTemplateImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAiTemplateAnalysisAccess {
    private final AdminUserMutationGuard guard;
    private final AiTemplateImageRepository images;
    private final AdminAiImageStorage storage;
    private final AdminAuditService audit;

    @Transactional
    public void authorizeImage(long actor, String key) { requireImage(actor, key); }

    @Transactional
    public void complete(long actor, String key) {
        var image = requireImage(actor, key);
        audit.record(actor, "AI_TEMPLATE_ANALYZE", "AI_TEMPLATE_IMAGE", image.getId(), "AI 샘플 스타일 분석", null, key);
    }

    private AiTemplateImageEntity requireImage(long actor, String key) {
        guard.lock(actor, actor);
        storage.validateKey(key);
        return images.findByImageKey(key).orElseThrow(() -> new AdminException(HttpStatus.BAD_REQUEST,
                "등록된 샘플 이미지가 아닙니다. AI 스튜디오에서 이미지를 먼저 업로드해 주세요."));
    }
}
