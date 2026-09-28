package com.orinan.api.domain.metaad;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.config.aws.AwsS3Properties;
import com.orinan.api.domain.metaad.service.MetaAdImageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MetaAdImageServiceTest {

    private static final int TEN_MIB = 10 * 1024 * 1024;
    private static final String KEY = "ads/workspaces/10/meta/ad-accounts/30/images/"
            + "12345678-1234-4234-8234-123456789abc.png";
    private final S3Client s3 = mock(S3Client.class);
    private final AwsS3Properties properties = new AwsS3Properties();
    private final S3Presigner presigner = S3Presigner.builder()
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test-access-key", "test-secret-key")))
            .build();
    private MetaAdImageService service;

    @BeforeEach
    void setUp() {
        properties.setBucket("test-ad-images");
        properties.setKeyPrefix(" /ads// ");
        service = new MetaAdImageService(s3, presigner, properties);
    }

    @AfterEach
    void close() {
        presigner.close();
    }

    @Test
    void uploadsActualPngPrivatelyWithScopedRandomKeyAndOneHourGetUrl() throws Exception {
        byte[] bytes = image("png");
        var before = Instant.now();

        var response = service.upload(10L, 30L,
                new MockMultipartFile("file", "../../my-secret-file.exe", "image/png", bytes));

        var put = ArgumentCaptor.forClass(PutObjectRequest.class);
        var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(put.capture(), body.capture());
        assertThat(put.getValue().bucket()).isEqualTo("test-ad-images");
        assertThat(put.getValue().key()).matches("ads/workspaces/10/meta/ad-accounts/30/images/"
                + "[0-9a-f-]{36}\\.png").isEqualTo(response.imageKey());
        assertThat(put.getValue().contentType()).isEqualTo("image/png");
        assertThat(put.getValue().contentLength()).isEqualTo(bytes.length);
        assertThat(put.getValue().acl()).isNull();
        assertThat(body.getValue().contentStreamProvider().newStream().readAllBytes()).isEqualTo(bytes);
        assertThat(response.contentType()).isEqualTo("image/png");
        assertThat(response.size()).isEqualTo(bytes.length);
        assertThat(response.imageUrl()).startsWith("https://test-ad-images.s3.ap-northeast-2.amazonaws.com/")
                .contains(response.imageKey(), "X-Amz-Expires=3600", "X-Amz-Signature=")
                .doesNotContain("my-secret-file", "test-secret-key");
        assertThat(response.expiresAt()).isBetween(before.plusSeconds(3599), Instant.now().plusSeconds(3601));
        verifyNoMoreInteractions(s3);
    }

    @Test
    void uploadsActualJpegWithCanonicalExtensionRegardlessOfFilename() throws Exception {
        var response = service.upload(10L, 30L,
                new MockMultipartFile("file", "image.png", "image/jpeg", image("jpeg")));

        assertThat(response.imageKey()).endsWith(".jpg");
        assertThat(response.contentType()).isEqualTo("image/jpeg");
        verify(s3).putObject(argThat((PutObjectRequest request) -> request.contentType().equals("image/jpeg")),
                any(RequestBody.class));
    }

    @Test
    void generatesDifferentKeysForRepeatedUploadsAndSupportsEmptyPrefix() throws Exception {
        properties.setKeyPrefix("///");
        var file = new MockMultipartFile("file", "image.png", "image/png", image("png"));

        var first = service.upload(10L, 30L, file);
        var second = service.upload(10L, 30L, file);

        assertThat(first.imageKey()).startsWith("workspaces/10/meta/ad-accounts/30/images/")
                .isNotEqualTo(second.imageKey());
    }

    @Test
    void rejectsMissingEmptyAndOversizedFilesBeforeStorage() {
        for (var file : new MultipartFile[]{null,
                new MockMultipartFile("file", "image.png", "image/png", new byte[0]),
                new MockMultipartFile("file", "image.png", "image/png", new byte[TEN_MIB + 1])}) {
            assertBadRequest(() -> service.upload(10L, 30L, file));
        }
        verifyNoInteractions(s3);
    }

    @Test
    void boundsBytesReadEvenWhenMultipartLengthIsIncorrect() throws Exception {
        var file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1L);
        when(file.getContentType()).thenReturn("image/png");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[TEN_MIB + 1]));

        assertBadRequest(() -> service.upload(10L, 30L, file));

        verifyNoInteractions(s3);
    }

    @Test
    void rejectsUnsupportedMimeMimeMismatchAndNonImageContent() throws Exception {
        byte[] png = image("png");
        for (var file : new MultipartFile[]{
                new MockMultipartFile("file", "image.svg", "image/svg+xml", "<svg/>".getBytes()),
                new MockMultipartFile("file", "image.jpg", "image/jpeg", png),
                new MockMultipartFile("file", "image.png", "image/png", "not an image".getBytes()),
                new MockMultipartFile("file", "image.png", null, png)}) {
            assertBadRequest(() -> service.upload(10L, 30L, file));
        }
        verifyNoInteractions(s3);
    }

    @Test
    void rejectsTruncatedImagesAfterReadingValidHeaders() throws Exception {
        var png = image("png");
        var jpeg = image("jpeg");
        assertBadRequest(() -> service.upload(10L, 30L,
                new MockMultipartFile("file", "image.png", "image/png", Arrays.copyOf(png, 33))));
        assertBadRequest(() -> service.upload(10L, 30L,
                new MockMultipartFile("file", "image.jpg", "image/jpeg", Arrays.copyOf(jpeg, jpeg.length - 2))));
        verifyNoInteractions(s3);
    }

    @Test
    void rejectsTooManyPixelsAndExtremeDimensionsBeforeDecoding() throws Exception {
        for (int[] size : new int[][]{{6000, 5000}, {10001, 1}}) {
            byte[] bytes = image("png");
            ByteBuffer.wrap(bytes).putInt(16, size[0]).putInt(20, size[1]);
            var crc = new CRC32();
            crc.update(bytes, 12, 17);
            ByteBuffer.wrap(bytes).putInt(29, (int) crc.getValue());

            assertBadRequest(() -> service.upload(10L, 30L,
                    new MockMultipartFile("file", "image.png", "image/png", bytes)));
        }
        verifyNoInteractions(s3);
    }

    @Test
    void resolvesOnlyExistingImageInMatchingAccountAndWorkspace() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(validHead());

        var url = service.resolveImageUrl(10L, 30L, KEY);

        assertThat(url).contains(KEY, "X-Amz-Expires=3600", "X-Amz-Signature=");
        verify(s3).headObject(HeadObjectRequest.builder().bucket("test-ad-images").key(KEY).build());
        verifyNoMoreInteractions(s3);
    }

    @Test
    void rejectsKeysOutsideAccountNamespaceAndNonCanonicalKeysWithoutStorageCalls() {
        for (String key : new String[]{null, "", KEY.replace("/10/", "/11/"), KEY.replace("/30/", "/31/"),
                KEY.replace("images/", "images/../"), KEY + "?suffix=value", KEY.replace(".png", ".svg"),
                KEY.replace("12345678-1234-4234-8234-123456789abc", "not-a-uuid"),
                "https://test-ad-images.s3.ap-northeast-2.amazonaws.com/" + KEY}) {
            assertBadRequest(() -> service.resolveImageUrl(10L, 30L, key));
        }
        verifyNoInteractions(s3);
    }

    @Test
    void rejectsStoredObjectsWithWrongTypeOrInvalidSize() {
        for (var head : new HeadObjectResponse[]{
                HeadObjectResponse.builder().contentType("image/jpeg").contentLength(100L).build(),
                HeadObjectResponse.builder().contentLength(100L).build(),
                HeadObjectResponse.builder().contentType("image/png").contentLength(0L).build(),
                HeadObjectResponse.builder().contentType("image/png").contentLength((long) TEN_MIB + 1).build(),
                HeadObjectResponse.builder().contentType("image/png").build()}) {
            when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(head);
            assertBadRequest(() -> service.resolveImageUrl(10L, 30L, KEY));
        }
    }

    @Test
    void missingObjectIsBadRequestAndDoesNotExposeStorageError() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("private-bucket-and-key").build());

        assertThatThrownBy(() -> service.resolveImageUrl(10L, 30L, KEY))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST);
                    assertThat(exception.getCause()).isNull();
                }).hasMessageContaining("찾을 수 없습니다").hasMessageNotContaining("private-bucket-and-key");
    }

    @Test
    void sanitizesUploadStorageFailures() throws Exception {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("credentials-and-presigned-url"));
        var file = new MockMultipartFile("file", "image.png", "image/png", image("png"));

        assertStorageFailure(() -> service.upload(10L, 30L, file));
    }

    @Test
    void sanitizesLookupStorageFailures() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(403).message("credentials-and-presigned-url").build());

        assertStorageFailure(() -> service.resolveImageUrl(10L, 30L, KEY));
    }

    @Test
    void sanitizesSigningFailures() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(validHead());
        var failingPresigner = mock(S3Presigner.class);
        when(failingPresigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenThrow(SdkClientException.create("credentials-and-presigned-url"));
        var failingService = new MetaAdImageService(s3, failingPresigner, properties);

        assertStorageFailure(() -> failingService.resolveImageUrl(10L, 30L, KEY));
    }

    @Test
    void missingBucketIsReportedBeforeStorageAccess() throws Exception {
        properties.setBucket(" ");
        var file = new MockMultipartFile("file", "image.png", "image/png", image("png"));

        assertThatThrownBy(() -> service.upload(10L, 30L, file))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.SERVER_ERROR));
        verifyNoInteractions(s3);
    }

    @Test
    void rejectsOversizedUtf8KeysAndLongEncodedUrlsBeforeUploading() throws Exception {
        var file = new MockMultipartFile("file", "image.png", "image/png", image("png"));
        for (String prefix : new String[]{"가".repeat(400), "가".repeat(300)}) {
            properties.setKeyPrefix(prefix);
            assertThatThrownBy(() -> service.upload(10L, 30L, file))
                    .isInstanceOfSatisfying(ApiException.class, exception ->
                            assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.SERVER_ERROR));
        }
        verifyNoInteractions(s3);
    }

    @Test
    void rejectsInsecurePresignedUrlBeforeUploading() throws Exception {
        try (var insecurePresigner = S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .endpointOverride(URI.create("http://s3.example.com"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                .build()) {
            var insecureService = new MetaAdImageService(s3, insecurePresigner, properties);
            var file = new MockMultipartFile("file", "image.png", "image/png", image("png"));

            assertThatThrownBy(() -> insecureService.upload(10L, 30L, file))
                    .isInstanceOfSatisfying(ApiException.class, exception ->
                            assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.SERVER_ERROR));
            verifyNoInteractions(s3);
        }
    }

    @Test
    void invalidMultipartStreamIsReportedWithoutRetainingRawCause() throws Exception {
        var file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(100L);
        when(file.getContentType()).thenReturn("image/png");
        when(file.getInputStream()).thenThrow(new IOException("internal-file-path"));

        assertThatThrownBy(() -> service.upload(10L, 30L, file))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST);
                    assertThat(exception.getCause()).isNull();
                }).hasMessageNotContaining("internal-file-path");
        verifyNoInteractions(s3);
    }

    private byte[] image(String format) throws IOException {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x00ff00);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        image.flush();
        return output.toByteArray();
    }

    private HeadObjectResponse validHead() {
        return HeadObjectResponse.builder().contentType("image/png").contentLength(100L).build();
    }

    private void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST));
    }

    private void assertStorageFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.SERVER_ERROR);
                    assertThat(exception.getCause()).isNull();
                }).hasMessageNotContaining("credentials-and-presigned-url");
    }
}
