package com.orinan.api.domain.aistudio.service;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.aistudio.client.*;
import com.orinan.api.domain.aistudio.controller.model.AiStudioGenerationRequest;
import com.orinan.api.domain.aistudio.controller.model.AiStudioResponse.*;
import com.orinan.api.domain.aistudio.exception.AiStudioErrorCode;
import com.orinan.db.aistudio.enums.AiStudioKind;
import com.orinan.db.aistudio.output.*;
import com.orinan.db.aistudio.template.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiStudioService {
    private final AiStudioAccess access;
    private final AiStudioProperties properties;
    private final AiStudioStorage storage;
    private final AiTemplateRepository templates;
    private final AiStudioOutputRepository outputs;
    private final AiStudioTransactions transactions;
    private final OpenAiStudioClient client;
    private final JsonMapper mapper;

    public Capabilities capabilities(long workspaceId, long userId) {
        access.requireMember(workspaceId, userId);
        boolean enabled = properties.isConfigured() && storage.isConfigured();
        return new Capabilities(enabled, enabled ? null : "AI 생성 기능을 준비 중입니다. 관리자에게 문의해 주세요.");
    }
    public List<Category> categories(long workspaceId, long userId) {
        access.requireMember(workspaceId, userId);
        return templates.findPublishedCategories().stream().map(category -> new Category(category.getId(), category.getName())).toList();
    }
    public Page<Template> templates(long workspaceId, long userId, AiStudioKind kind, Long categoryId, String query, int page, int size) {
        access.requireMember(workspaceId, userId);
        if (categoryId != null && categoryId <= 0) throw invalid();
        if (query != null && query.length() > 200) throw invalid();
        String pattern = query == null || query.isBlank() ? null : "%" + query.strip().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var found = templates.searchPublished(kind, categoryId, pattern, pageable(page, size));
        return Page.from(found, found.map(this::template).getContent());
    }
    public Template template(long workspaceId, long userId, long id) {
        access.requireMember(workspaceId, userId);
        return template(templates.findByIdAndPublishedTrue(id).orElseThrow(AiStudioService::missing));
    }
    public Page<Output> outputs(long workspaceId, long userId, AiStudioKind kind, int page, int size) {
        access.requireMember(workspaceId, userId);
        var found = outputs.search(workspaceId, kind, pageable(page, size));
        return Page.from(found, found.map(this::output).getContent());
    }
    public Output output(long workspaceId, long userId, long id) {
        access.requireMember(workspaceId, userId);
        return output(outputs.findByIdAndWorkspaceId(id, workspaceId).orElseThrow(AiStudioService::missing));
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AiStudioExport exportOutput(long workspaceId, long userId, long outputId) {
        access.requireMember(workspaceId, userId);
        var output = outputs.findByIdAndWorkspaceIdAndStatus(outputId, workspaceId, AiStudioOutputStatus.SUCCEEDED)
                .orElseThrow(AiStudioService::missing);
        var image = storage.read(output.getImageKey());
        access.requireMember(workspaceId, userId);
        return new AiStudioExport(output.getKind(), output.getTitle(), output.getDetailHtml(), image.bytes(), image.contentType());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Output generate(long workspaceId, long userId, AiStudioGenerationRequest input) {
        var request = normalized(input);
        access.requireMember(workspaceId, userId);
        if (request.productImageKey() != null) storage.requireInputKey(workspaceId, userId, request.productImageKey());
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(
                List.of(request.templateId(), request.productName(), request.productDescription(),
                        Objects.toString(request.audience(), ""), Objects.toString(request.instructions(), ""),
                        Objects.toString(request.productImageKey(), ""))).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ignored) { throw invalid(); }
        var prepared = transactions.prepare(workspaceId, userId, request, hash, properties.isConfigured() && storage.isConfigured());
        if (!prepared.generate()) return output(workspaceId, userId, prepared.outputId());
        String key = null;
        try {
            var reference = storage.read(prepared.referenceKey());
            var productReference = request.productImageKey() == null ? null : storage.read(request.productImageKey());
            var generated = client.generate(prepared.kind(), prepared.prompt(), request.productName(), request.productDescription(),
                    request.audience(), request.instructions(), reference, productReference);
            access.requireMember(workspaceId, userId);
            key = storage.store(workspaceId, prepared.outputId(), generated.image());
            transactions.complete(workspaceId, userId, prepared.outputId(), key, generated.image().contentType(), generated.detailHtml());
        } catch (RuntimeException exception) {
            storage.discard(key);
            transactions.fail(prepared.outputId());
            if (exception instanceof ApiException api) throw api;
            throw new ApiException(AiStudioErrorCode.PROVIDER_FAILURE);
        }
        return output(workspaceId, userId, prepared.outputId());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UploadedImage upload(long workspaceId, long userId, MultipartFile file) {
        access.requireMember(workspaceId, userId);
        if (file == null || file.isEmpty() || file.getSize() > 10 * 1024 * 1024) throw invalid();
        AiStudioImage image;
        try { image = AiStudioImage.validate(file.getBytes()); } catch (Exception ignored) { throw invalid(); }
        String key = storage.storeInput(workspaceId, userId, image);
        try {
            access.requireMember(workspaceId, userId);
            return new UploadedImage(key, storage.preview(key));
        } catch (RuntimeException exception) { storage.discard(key); throw exception; }
    }

    private Template template(AiTemplateEntity template) {
        return new Template(template.getId(), template.getKind(), template.getTitle(), template.getDescription(),
                template.getCategory().getId(), template.getCategory().getName(),
                storage.preview(template.getPreviewImageKey()), template.getCreatedAt(), template.getUpdatedAt());
    }
    private Output output(AiStudioOutputEntity output) {
        return new Output(output.getId(), output.getWorkspaceId(), output.getTemplateId(), output.getKind(), output.getTitle(),
                output.getStatus() == AiStudioOutputStatus.SUCCEEDED ? storage.preview(output.getImageKey()) : null,
                output.getDetailHtml(), output.getStatus(), output.getCreatedAt());
    }
    private static PageRequest pageable(int page, int size) {
        if (page < 0 || size < 1 || size > 50) throw invalid();
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    }
    private static AiStudioGenerationRequest normalized(AiStudioGenerationRequest input) {
        if (input == null || input.templateId() == null || input.templateId() <= 0
                || input.idempotencyKey() == null || !input.idempotencyKey().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid();
        return new AiStudioGenerationRequest(input.templateId(), required(input.productName(), 150), required(input.productDescription(), 3000),
                optional(input.audience(), 500), optional(input.instructions(), 1000), input.idempotencyKey().toLowerCase(Locale.ROOT),
                optional(input.productImageKey(), 512));
    }
    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw invalid();
        return value.strip();
    }
    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return required(value, max);
    }
    private static ApiException invalid() { return new ApiException(AiStudioErrorCode.INVALID_REQUEST); }
    private static ApiException missing() { return new ApiException(AiStudioErrorCode.NOT_FOUND); }
    public record UploadedImage(String imageKey, String imageUrl) {}
}
