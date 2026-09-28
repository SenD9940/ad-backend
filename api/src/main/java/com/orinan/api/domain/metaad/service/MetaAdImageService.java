package com.orinan.api.domain.metaad.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.config.aws.AwsS3Properties;
import com.orinan.api.domain.metaad.controller.model.MetaAdImageUploadResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MetaAdImageService {

    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 25_000_000L;
    private static final int MAX_IMAGE_DIMENSION = 10_000;
    private static final Duration URL_VALIDITY = Duration.ofHours(1);
    private static final String IMAGE_FILE_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)";

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final AwsS3Properties properties;

    public MetaAdImageUploadResponse upload(Long workspaceId, Long assetId, MultipartFile file) {
        var bucket = bucket();
        var prefix = imagePrefix(workspaceId, assetId);
        var image = readImage(file);
        var key = prefix + UUID.randomUUID() + (image.contentType().equals("image/png") ? ".png" : ".jpg");
        if (key.getBytes(StandardCharsets.UTF_8).length > 1024) {
            throw new ApiException(ApiCode.SERVER_ERROR, "광고 이미지 저장 경로 설정을 확인해 주세요.");
        }
        var signed = presign(bucket, key);
        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(image.contentType())
                            .contentLength((long) image.bytes().length)
                            .build(),
                    RequestBody.fromBytes(image.bytes()));
        } catch (SdkException | IllegalArgumentException exception) {
            throw storageFailure();
        }
        return new MetaAdImageUploadResponse(key, signed.url().toExternalForm(), signed.expiration(),
                image.contentType(), image.bytes().length);
    }

    public String resolveImageUrl(Long workspaceId, Long assetId, String imageKey) {
        var prefix = imagePrefix(workspaceId, assetId);
        if (imageKey == null || imageKey.getBytes(StandardCharsets.UTF_8).length > 1024
                || !imageKey.matches(Pattern.quote(prefix) + IMAGE_FILE_PATTERN)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "이 광고 계정에 업로드한 이미지 키를 입력해 주세요.");
        }
        var bucket = bucket();
        try {
            var head = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(imageKey).build());
            var expectedType = imageKey.endsWith(".png") ? "image/png" : "image/jpeg";
            if (!expectedType.equals(head.contentType()) || head.contentLength() == null
                    || head.contentLength() <= 0 || head.contentLength() > MAX_IMAGE_BYTES) {
                throw new ApiException(ApiCode.BAD_REQUEST, "광고 소재로 사용할 수 없는 이미지입니다. 다시 업로드해 주세요.");
            }
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new ApiException(ApiCode.BAD_REQUEST, "업로드한 이미지를 찾을 수 없습니다. 다시 업로드해 주세요.");
            }
            throw storageFailure();
        } catch (SdkException | IllegalArgumentException exception) {
            throw storageFailure();
        }
        return presign(bucket, imageKey).url().toExternalForm();
    }

    private ImageData readImage(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0 || file.getSize() > MAX_IMAGE_BYTES) {
            throw new ApiException(ApiCode.BAD_REQUEST, "10MB 이하의 PNG 또는 JPEG 이미지를 업로드해 주세요.");
        }
        var contentType = file.getContentType();
        if (!"image/png".equals(contentType) && !"image/jpeg".equals(contentType)) {
            throw invalidImage();
        }
        try (var source = file.getInputStream()) {
            var bytes = source.readNBytes(MAX_IMAGE_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                throw new ApiException(ApiCode.BAD_REQUEST, "10MB 이하의 PNG 또는 JPEG 이미지를 업로드해 주세요.");
            }
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) {
                    throw invalidImage();
                }
                var reader = readers.next();
                try {
                    var format = reader.getFormatName();
                    var actualType = "PNG".equalsIgnoreCase(format) ? "image/png"
                            : "JPEG".equalsIgnoreCase(format) ? "image/jpeg" : null;
                    if (!contentType.equals(actualType)) {
                        throw invalidImage();
                    }
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION
                            || (long) width * height > MAX_IMAGE_PIXELS) {
                        throw new ApiException(ApiCode.BAD_REQUEST, "이미지는 가로·세로 10,000픽셀, 총 2,500만 픽셀 이하여야 합니다.");
                    }
                    boolean[] warning = {false};
                    reader.addIIOReadWarningListener((ignored, message) -> warning[0] = true);
                    var decoded = reader.read(0);
                    if (decoded == null || warning[0]) {
                        throw invalidImage();
                    }
                    decoded.flush();
                    return new ImageData(bytes, actualType);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw invalidImage();
        }
    }

    private PresignedGetObjectRequest presign(String bucket, String key) {
        try {
            var signed = s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(URL_VALIDITY)
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .build());
            if (!"https".equalsIgnoreCase(signed.url().getProtocol())
                    || signed.url().toExternalForm().length() > 2048) {
                throw new ApiException(ApiCode.SERVER_ERROR, "광고 이미지 URL 설정을 확인해 주세요.");
            }
            return signed;
        } catch (SdkException | IllegalArgumentException exception) {
            throw storageFailure();
        }
    }

    private String imagePrefix(Long workspaceId, Long assetId) {
        if (workspaceId == null || workspaceId <= 0 || assetId == null || assetId <= 0) {
            throw new ApiException(ApiCode.BAD_REQUEST, "올바른 워크스페이스와 광고 계정을 선택해 주세요.");
        }
        var prefix = properties.getKeyPrefix();
        prefix = prefix == null ? "" : prefix.strip().replaceAll("^/+|/+$", "").replaceAll("/{2,}", "/");
        return (prefix.isEmpty() ? "" : prefix + "/") + "workspaces/" + workspaceId
                + "/meta/ad-accounts/" + assetId + "/images/";
    }

    private String bucket() {
        if (properties.getBucket() == null || properties.getBucket().isBlank()) {
            throw new ApiException(ApiCode.SERVER_ERROR, "광고 이미지 저장소가 설정되지 않았습니다.");
        }
        return properties.getBucket().strip();
    }

    private ApiException invalidImage() {
        return new ApiException(ApiCode.BAD_REQUEST, "파일 형식과 내용이 일치하는 PNG 또는 JPEG 이미지를 업로드해 주세요.");
    }

    private ApiException storageFailure() {
        return new ApiException(ApiCode.SERVER_ERROR, "광고 이미지 저장소에 접근할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }

    private record ImageData(byte[] bytes, String contentType) {}
}
