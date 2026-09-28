package com.orinan.adminapi.domain.aistudio.service;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.domain.user.service.AdminUserMutationGuard;
import com.orinan.db.aistudio.enums.AiStudioKind;
import com.orinan.db.aistudio.template.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class AdminAiTemplateService {
    private final AiTemplateRepository templates;
    private final AiTemplateImageRepository images;
    private final AdminUserMutationGuard guard;
    private final AdminAuditService audit;

    public Page<AiTemplateEntity> search(AiStudioKind kind, Long categoryId, String pattern, Pageable pageable) {
        return templates.search(kind, categoryId, pattern, pageable);
    }
    public AiTemplateEntity find(long id) { positive(id); return templates.findById(id).orElseThrow(this::missing); }
    public AiTemplateEntity lock(long id) { positive(id); return templates.findByIdForUpdate(id).orElseThrow(this::missing); }
    public AiTemplateEntity save(AiTemplateEntity entity) { return templates.save(entity); }

    public void requireRegisteredImage(String key) {
        if (key != null && images.findByImageKey(key).isEmpty())
            throw new AdminException(HttpStatus.BAD_REQUEST, "등록된 샘플 이미지가 아닙니다. AI 스튜디오에서 이미지를 먼저 업로드해 주세요.");
    }

    @Transactional
    public void authorizeUpload(long actor) { guard.lock(actor, actor); }

    @Transactional
    public void registerImage(long actor, AdminAiImageStorage.StoredImage image) {
        // Re-check live authority after S3 I/O before making an image eligible for templates.
        guard.lock(actor, actor);
        var registered = images.save(new AiTemplateImageEntity(image.key(), image.contentType(), image.bytes(),
                image.width(), image.height(), actor, LocalDateTime.now(ZoneId.of("Asia/Seoul"))));
        audit.record(actor, "AI_TEMPLATE_IMAGE_UPLOAD", "AI_TEMPLATE_IMAGE", registered.getId(), "AI 샘플 이미지 등록", null, image.key());
    }
    private void positive(long id) {
        if (id < 1) throw new AdminException(HttpStatus.BAD_REQUEST, "올바른 템플릿 ID가 필요합니다.");
    }
    private AdminException missing() { return new AdminException(HttpStatus.NOT_FOUND, "AI 템플릿을 찾을 수 없습니다."); }
}
