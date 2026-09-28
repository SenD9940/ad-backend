package com.orinan.api.domain.aistudio.service;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.config.aws.AwsS3Properties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiStudioStorageTest {
    @Test void productReferenceKeysAreBoundToWorkspaceAndUploaderAndCannotTraverse() {
        var properties = new AwsS3Properties(); properties.setBucket("test-bucket"); properties.setKeyPrefix("/private/");
        var storage = new AiStudioStorage(mock(S3Client.class), mock(S3Presigner.class), properties);
        String prefix = "private/ai-studio/workspaces/1/inputs/2/";
        String id = "12345678-1234-1234-1234-123456789abc.png";
        assertThatCode(() -> storage.requireInputKey(1, 2, prefix + id)).doesNotThrowAnyException();
        for (String key : List.of(prefix.replace("workspaces/1/", "workspaces/9/") + id,
                prefix.replace("inputs/2/", "inputs/9/") + id, prefix + "../" + id,
                prefix + "%2e%2e/" + id, "https://external.test/" + id, "private/ai-studio/templates/" + id,
                prefix + id + "/more", prefix + id + "?query", prefix + id.replace(".png", ".svg"))) {
            assertThatThrownBy(() -> storage.requireInputKey(1, 2, key)).isInstanceOf(ApiException.class);
        }
    }
    @Test void imageDecodingRejectsSvgInvalidAndOversizedData() {
        for (byte[] bytes : List.of(new byte[0], "<svg onload='alert(1)'/>".getBytes(), new byte[]{1,2,3}, new byte[AiStudioImage.MAX_BYTES + 1]))
            assertThatThrownBy(() -> AiStudioImage.validate(bytes)).isInstanceOf(ApiException.class);
    }
}
