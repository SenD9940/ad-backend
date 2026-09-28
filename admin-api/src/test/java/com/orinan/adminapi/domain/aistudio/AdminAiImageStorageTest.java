package com.orinan.adminapi.domain.aistudio;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.config.aws.AdminAiS3Properties;
import com.orinan.adminapi.domain.aistudio.service.AdminAiImageStorage;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminAiImageStorageTest {
    private final S3Client client = mock(S3Client.class);
    private S3Presigner signer;
    private AdminAiImageStorage storage;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        var clients = (ObjectProvider<S3Client>) mock(ObjectProvider.class);
        var signers = (ObjectProvider<S3Presigner>) mock(ObjectProvider.class);
        signer = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret"))).build();
        when(clients.getObject()).thenReturn(client); when(signers.getObject()).thenReturn(signer);
        var properties = new AdminAiS3Properties(); properties.setBucket("unit-test-bucket"); properties.setKeyPrefix(" /test// ");
        storage = new AdminAiImageStorage(clients, signers, properties);
    }
    @AfterEach void close() { signer.close(); }

    @Test void actualPngIsUploadedWithGeneratedSafeKeyAndPrivateSignedUrl() throws Exception {
        byte[] bytes = png();
        var result = storage.upload(new MockMultipartFile("file", "../../bad.png", "image/png", bytes));
        assertThat(result.key()).matches("test/ai-studio/templates/[0-9a-f-]{36}\\.png");
        assertThat(result.width()).isEqualTo(12); assertThat(result.height()).isEqualTo(8);
        assertThat(result.bytes()).isEqualTo(bytes.length);
        assertThat(result.preview().url()).startsWith("https://").contains("X-Amz-Signature=");
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().contentType()).isEqualTo("image/png");
        assertThat(request.getValue().acl()).isNull();
        assertThat(request.getValue().key()).isEqualTo(result.key());
    }

    @Test void contentTypeSpoofingAndTruncatedImagesNeverReachS3() throws Exception {
        byte[] bytes = png();
        for (MultipartFile file : new MultipartFile[]{
                new MockMultipartFile("file", "fake.png", "image/png", "plain text".getBytes()),
                new MockMultipartFile("file", "wrong.jpg", "image/jpeg", bytes),
                new MockMultipartFile("file", "broken.png", "image/png", Arrays.copyOf(bytes, 25)),
                new MockMultipartFile("file", "vector.svg", "image/svg+xml", "<svg/>".getBytes())}) {
            assertThatThrownBy(() -> storage.upload(file)).isInstanceOf(AdminException.class);
        }
        verifyNoInteractions(client);
    }

    @Test void sizeLimitsApplyToBothMultipartMetadataAndActualBytes() throws Exception {
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.getSize()).thenReturn(10L * 1024 * 1024 + 1);
        assertThatThrownBy(() -> storage.upload(oversized)).isInstanceOf(AdminException.class);
        MultipartFile dishonest = mock(MultipartFile.class);
        when(dishonest.getSize()).thenReturn(1L); when(dishonest.getContentType()).thenReturn("image/png");
        when(dishonest.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[10 * 1024 * 1024 + 1]));
        assertThatThrownBy(() -> storage.upload(dishonest)).isInstanceOf(AdminException.class);
        verifyNoInteractions(client);
    }

    @Test void keysCannotReferenceForeignPathsOrArbitraryUrls() {
        for (String key : new String[]{"https://other.test/file.png", "test/ai-studio/templates/../../secret.png",
                "other/ai-studio/templates/4c107049-3422-46e8-8102-dd148d96b4be.png", "test/ai-studio/templates/file.svg"}) {
            assertThatThrownBy(() -> storage.preview(key)).isInstanceOf(AdminException.class);
        }
    }

    @Test void storageFailuresDoNotExposeRawCredentialsOrProviderMessages() throws Exception {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new IllegalStateException("private-storage-key"));
        assertThatThrownBy(() -> storage.upload(new MockMultipartFile("file", "a.png", "image/png", png())))
                .isInstanceOf(AdminException.class).hasMessageNotContaining("private-storage-key");
    }

    @Test void analysisReadsOwnedPngBytesAndValidatesTheActualImage() throws Exception {
        byte[] bytes = png();
        String key = "test/ai-studio/templates/4c107049-3422-46e8-8102-dd148d96b4be.png";
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(source(bytes, "image/png", bytes.length));
        var image = storage.read(key);
        assertThat(image.bytes()).isEqualTo(bytes); assertThat(image.type()).isEqualTo("image/png");
        assertThat(image.width()).isEqualTo(12); assertThat(image.height()).isEqualTo(8);
        verify(client).getObject(argThat((GetObjectRequest request) -> request.bucket().equals("unit-test-bucket") && request.key().equals(key)));
    }

    @Test void analysisNeverFetchesForeignKeysAndRejectsCorruptOrOversizeStorageObjects() throws Exception {
        assertThatThrownBy(() -> storage.read("https://untrusted.test/image.png")).isInstanceOf(AdminException.class);
        verifyNoInteractions(client);
        String key = "test/ai-studio/templates/4c107049-3422-46e8-8102-dd148d96b4be.png";
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(source(new byte[]{1}, "image/png", 1),
                source(png(), "image/jpeg", 1), source(new byte[10 * 1024 * 1024 + 1], "image/png", 1),
                source(png(), "image/png", 10 * 1024 * 1024 + 1));
        for (int i = 0; i < 4; i++) assertThatThrownBy(() -> storage.read(key)).isInstanceOf(AdminException.class);
    }

    private ResponseInputStream<GetObjectResponse> source(byte[] bytes, String contentType, long length) {
        return new ResponseInputStream<>(GetObjectResponse.builder().contentType(contentType).contentLength(length).build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }

    private byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
