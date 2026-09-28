package com.orinan.api.domain.aistudio.service;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.aistudio.controller.model.AiStudioGenerationRequest;
import com.orinan.api.domain.aistudio.exception.AiStudioErrorCode;
import com.orinan.db.aistudio.enums.AiStudioKind;
import com.orinan.db.aistudio.output.*;
import com.orinan.db.aistudio.template.AiTemplateRepository;
import com.orinan.db.aistudio.template.AiTemplateImageRepository;
import com.orinan.db.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AiStudioTransactions {
    private final AiStudioAccess access;
    private final WorkspaceRepository workspaces;
    private final AiStudioOutputRepository outputs;
    private final AiTemplateRepository templates;
    private final AiTemplateImageRepository images;
    private final EntityManager entityManager;

    /** Commit an idempotency ticket before any billable provider call; the workspace lock also serializes first insert. */
    @Transactional
    public Prepared prepare(long workspaceId, long userId, AiStudioGenerationRequest request, String hash, boolean configured) {
        workspaces.findByIdForUpdate(workspaceId).orElseThrow(() -> new ApiException(AiStudioErrorCode.ACCESS_DENIED));
        access.requireMember(workspaceId, userId);
        var existing = outputs.findByWorkspaceIdAndCreatedByAndIdempotencyKey(workspaceId, userId, request.idempotencyKey());
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) throw new ApiException(AiStudioErrorCode.IDEMPOTENCY_CONFLICT);
            return new Prepared(existing.get().getId(), false, null, null, null);
        }
        if (!configured) throw new ApiException(AiStudioErrorCode.UNAVAILABLE);
        var template = templates.findByIdAndPublishedTrue(request.templateId()).orElseThrow(() -> new ApiException(AiStudioErrorCode.NOT_FOUND));
        entityManager.refresh(template);
        if (!template.isPublished() || template.getPreviewImageKey() == null
                || images.findByImageKey(template.getPreviewImageKey()).isEmpty()) throw new ApiException(AiStudioErrorCode.NOT_FOUND);
        var output = outputs.saveAndFlush(new AiStudioOutputEntity(workspaceId, userId, template.getId(), request.idempotencyKey(),
                hash, template.getKind(), request.productName(), LocalDateTime.now()));
        return new Prepared(output.getId(), true, template.getKind(), template.getPrompt(), template.getPreviewImageKey());
    }

    @Transactional
    public void complete(long workspaceId, long userId, long outputId, String key, String contentType, String html) {
        workspaces.findByIdForUpdate(workspaceId).orElseThrow(() -> new ApiException(AiStudioErrorCode.ACCESS_DENIED));
        access.requireMember(workspaceId, userId);
        var output = outputs.findByIdForUpdate(outputId).orElseThrow(() -> new ApiException(AiStudioErrorCode.NOT_FOUND));
        entityManager.refresh(output, LockModeType.PESSIMISTIC_WRITE);
        if (output.getWorkspaceId() != workspaceId || output.getCreatedBy() != userId) throw new ApiException(AiStudioErrorCode.ACCESS_DENIED);
        output.complete(key, contentType, html, LocalDateTime.now());
    }

    @Transactional
    public void fail(long outputId) {
        outputs.findByIdForUpdate(outputId).ifPresent(output -> {
            entityManager.refresh(output, LockModeType.PESSIMISTIC_WRITE);
            output.fail(LocalDateTime.now());
        });
    }
    public record Prepared(long outputId, boolean generate, AiStudioKind kind, String prompt, String referenceKey) {}
}
