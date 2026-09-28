package com.orinan.api.domain.aistudio.service;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.config.aws.AwsS3Properties;
import com.orinan.api.domain.aistudio.exception.AiStudioErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiStudioStorage {
    private final S3Client s3;
    private final S3Presigner signer;
    private final AwsS3Properties properties;
    public boolean isConfigured() { return properties.getBucket() != null && !properties.getBucket().isBlank(); }
    public AiStudioImage read(String key) {
        if (!isConfigured() || key == null || key.isBlank()) throw failure();
        try (var object = s3.getObject(GetObjectRequest.builder().bucket(properties.getBucket()).key(key).build())) {
            if (object.response().contentLength() != null && object.response().contentLength() > AiStudioImage.MAX_BYTES) throw failure();
            return AiStudioImage.validate(object.readNBytes(AiStudioImage.MAX_BYTES + 1));
        } catch (Exception exception) { throw failure(); }
    }
    public String store(long workspaceId, long outputId, AiStudioImage image) {
        if (!isConfigured()) throw failure();
        String prefix = properties.getKeyPrefix() == null ? "auto-threads" : properties.getKeyPrefix().replaceAll("^/+|/+$", "");
        String key = (prefix.isBlank() ? "" : prefix + "/") + "ai-studio/workspaces/" + workspaceId + "/outputs/" + outputId + "/" + UUID.randomUUID()
                + (image.contentType().equals("image/png") ? ".png" : ".jpg");
        try {
            s3.putObject(PutObjectRequest.builder().bucket(properties.getBucket()).key(key)
                    .contentType(image.contentType()).cacheControl("private, max-age=3600").build(), RequestBody.fromBytes(image.bytes()));
            return key;
        } catch (RuntimeException exception) { throw failure(); }
    }
    public String storeInput(long workspaceId, long userId, AiStudioImage image) {
        if (!isConfigured()) throw failure();
        String key = inputPrefix(workspaceId, userId) + UUID.randomUUID()
                + (image.contentType().equals("image/png") ? ".png" : ".jpg");
        try {
            s3.putObject(PutObjectRequest.builder().bucket(properties.getBucket()).key(key).contentType(image.contentType())
                    .cacheControl("private, max-age=3600").build(), RequestBody.fromBytes(image.bytes()));
            return key;
        } catch (RuntimeException exception) { throw failure(); }
    }
    public void requireInputKey(long workspaceId, long userId, String key) {
        String prefix = inputPrefix(workspaceId, userId);
        if (key == null || !key.startsWith(prefix) || !key.substring(prefix.length())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpg)"))
            throw new ApiException(AiStudioErrorCode.ACCESS_DENIED);
    }
    private String inputPrefix(long workspaceId, long userId) {
        String prefix = properties.getKeyPrefix() == null ? "auto-threads" : properties.getKeyPrefix().replaceAll("^/+|/+$", "");
        return (prefix.isBlank() ? "" : prefix + "/") + "ai-studio/workspaces/" + workspaceId + "/inputs/" + userId + "/";
    }
    public String preview(String key) {
        if (key == null || key.isBlank()) return null;
        if (!isConfigured()) throw failure();
        try {
            return signer.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(Duration.ofHours(1))
                    .getObjectRequest(GetObjectRequest.builder().bucket(properties.getBucket()).key(key).build()).build()).url().toExternalForm();
        } catch (RuntimeException exception) { throw failure(); }
    }
    public void discard(String key) {
        if (key == null) return;
        try { s3.deleteObject(request -> request.bucket(properties.getBucket()).key(key)); }
        catch (RuntimeException ignored) { /* Best effort orphan cleanup; never override generation failure. */ }
    }
    private static ApiException failure() { return new ApiException(AiStudioErrorCode.STORAGE_FAILURE); }
}
