package com.orinan.adminapi.domain.aistudio.service;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.config.aws.AdminAiS3Properties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AdminAiImageStorage {
    static final int MAX_BYTES = 10 * 1024 * 1024;
    private final ObjectProvider<S3Client> clients;
    private final ObjectProvider<S3Presigner> signers;
    private final AdminAiS3Properties properties;

    public AdminAiImageStorage(ObjectProvider<S3Client> clients, ObjectProvider<S3Presigner> signers,
                               AdminAiS3Properties properties) {
        this.clients = clients; this.signers = signers; this.properties = properties;
    }

    public StoredImage upload(MultipartFile file) {
        var image = read(file);
        String key = prefix() + UUID.randomUUID() + (image.type().equals("image/png") ? ".png" : ".jpg");
        if (key.length() > 512) throw unavailable();
        var preview = preview(key);
        try {
            clients.getObject().putObject(PutObjectRequest.builder().bucket(bucket()).key(key)
                    .contentType(image.type()).contentLength((long) image.bytes().length).build(),
                    RequestBody.fromBytes(image.bytes()));
        } catch (RuntimeException exception) {
            throw unavailable();
        }
        return new StoredImage(key, image.type(), image.bytes().length, image.width(), image.height(), preview);
    }

    public Preview preview(String key) {
        if (key == null) return null;
        validateKey(key);
        try {
            var signed = signers.getObject().presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofHours(1))
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket()).key(key).build()).build());
            String url = signed.url().toExternalForm();
            if (!"https".equalsIgnoreCase(signed.url().getProtocol()) || url.length() > 4096) throw unavailable();
            return new Preview(url, signed.expiration());
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    public void validateKey(String key) {
        if (key == null || key.length() > 512 || !key.matches(Pattern.quote(prefix())
                + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpg)")) {
            throw new AdminException(HttpStatus.BAD_REQUEST, "AI 스튜디오에 업로드한 샘플 이미지를 선택해 주세요.");
        }
    }

    /** Read only an application-owned sample key; callers must also verify its upload registration. */
    public ImageData read(String key) {
        validateKey(key);
        String expected = key.endsWith(".png") ? "image/png" : "image/jpeg";
        try (var source = clients.getObject().getObject(GetObjectRequest.builder().bucket(bucket()).key(key).build())) {
            if (source.response().contentLength() != null && source.response().contentLength() > MAX_BYTES) throw invalid();
            if (!expected.equals(source.response().contentType())) throw invalid();
            return decode(source.readNBytes(MAX_BYTES + 1), expected);
        } catch (AdminException exception) { throw exception; }
        catch (IOException | RuntimeException exception) { throw unavailable(); }
    }

    private ImageData read(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() < 1 || file.getSize() > MAX_BYTES) throw invalid();
        String declared = file.getContentType();
        if (!"image/png".equals(declared) && !"image/jpeg".equals(declared)) throw invalid();
        try (var source = file.getInputStream()) {
            return decode(source.readNBytes(MAX_BYTES + 1), declared);
        } catch (IOException | IllegalArgumentException exception) { throw invalid(); }
    }

    private ImageData decode(byte[] bytes, String declared) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
        try {
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalid();
                var reader = readers.next();
                try {
                    String format = reader.getFormatName();
                    String actual = "png".equalsIgnoreCase(format) ? "image/png"
                            : "jpeg".equalsIgnoreCase(format) ? "image/jpeg" : "";
                    if (!declared.equals(actual)) throw invalid();
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 10000 || height > 10000 || (long) width * height > 25_000_000L)
                        throw new AdminException(HttpStatus.BAD_REQUEST, "샘플 이미지는 각 변 10,000픽셀, 총 2,500만 픽셀 이하여야 합니다.");
                    boolean[] warning = {false};
                    reader.addIIOReadWarningListener((ignored, message) -> warning[0] = true);
                    var decoded = reader.read(0);
                    if (decoded == null || warning[0]) throw invalid();
                    decoded.flush();
                    return new ImageData(bytes, actual, width, height);
                } finally { reader.dispose(); }
            }
        } catch (IOException | IllegalArgumentException exception) { throw invalid(); }
    }

    private String prefix() {
        String prefix = properties.getKeyPrefix();
        prefix = prefix == null ? "" : prefix.strip().replaceAll("^/+|/+$", "").replaceAll("/{2,}", "/");
        return (prefix.isEmpty() ? "" : prefix + "/") + "ai-studio/templates/";
    }
    private String bucket() {
        if (properties.getBucket() == null || properties.getBucket().isBlank()) throw unavailable();
        return properties.getBucket().strip();
    }
    private AdminException invalid() {
        return new AdminException(HttpStatus.BAD_REQUEST, "파일 형식과 내용이 일치하는 10MB 이하 PNG 또는 JPEG 이미지를 업로드해 주세요.");
    }
    private AdminException unavailable() {
        return new AdminException(HttpStatus.SERVICE_UNAVAILABLE, "샘플 이미지 저장소에 접근할 수 없습니다. 저장소 설정을 확인해 주세요.");
    }
    public record Preview(String url, Instant expiresAt) { }
    public record StoredImage(String key, String contentType, long bytes, int width, int height, Preview preview) { }
    public record ImageData(byte[] bytes, String type, int width, int height) { }
}
