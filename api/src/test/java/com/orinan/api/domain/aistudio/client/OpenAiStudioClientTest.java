package com.orinan.api.domain.aistudio.client;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.aistudio.service.AiStudioImage;
import com.orinan.db.aistudio.enums.AiStudioKind;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenAiStudioClientTest {
    final JsonMapper mapper = JsonMapper.builder().build();
    AiStudioProperties properties;
    OpenAiStudioClient client;
    MockRestServiceServer server;
    @BeforeEach void setup() {
        properties = new AiStudioProperties(); properties.setApiKey("private-openai-key");
        var builder = RestClient.builder(); server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiStudioClient(properties, mapper, builder.build());
    }
    @AfterEach void verify() { server.verify(); }

    @Test void imageEditUsesFixedHostAndBothStyleAndProductImages() throws Exception {
        var image = image();
        server.expect(requestTo("https://api.openai.com/v1/images/edits")).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer private-openai-key"))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    assertThat(body).contains("name=\"image[]\"", "reference.png", "product.png", "1024x1024", "medium", "gpt-image-2.5-sunburst")
                            .doesNotContain("input_fidelity");
                }).andRespond(withSuccess(imageResponse(image), MediaType.APPLICATION_JSON));
        var output = client.generate(AiStudioKind.AD_IMAGE, "스타일", "상품", "사실", null, null, image, image);
        assertThat(output.image().bytes()).isEqualTo(image.bytes()); assertThat(output.detailHtml()).isNull();
    }
    @Test void detailUsesStrictNonStoredStructuredOutputAndNeverRawModelHtml() throws Exception {
        var image = image(1536); String copy = copy("<script>alert('x')</script>{{STUDIO_IMAGE}}");
        server.expect(requestTo("https://api.openai.com/v1/responses")).andExpect(request -> {
            var root = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(root.path("store").asBoolean()).isFalse();
            assertThat(root.path("text").path("format").path("strict").asBoolean()).isTrue();
            assertThat(root.path("text").path("format").path("type").asString()).isEqualTo("json_schema");
        }).andRespond(withSuccess(response(copy), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.openai.com/v1/images/edits"))
                .andExpect(request -> assertThat(((MockClientHttpRequest) request).getBodyAsString()).contains("1024x1536"))
                .andRespond(withSuccess(imageResponse(image), MediaType.APPLICATION_JSON));
        var output = client.generate(AiStudioKind.DETAIL_PAGE, "스타일", "상품", "사실", null, null, image, null);
        assertThat(output.detailHtml()).contains("&lt;script&gt;", "&#123;&#123;STUDIO_IMAGE&#125;&#125;")
                .doesNotContain("<script>").containsOnlyOnce("{{STUDIO_IMAGE}}");
    }
    @ParameterizedTest @ValueSource(ints = {400,401,429,500,503})
    void providerErrorsNeverRetryOrExposeSecrets(int status) throws Exception {
        server.expect(requestTo("https://api.openai.com/v1/images/edits"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("private-openai-key private-product-details"));
        assertThatThrownBy(() -> client.generate(AiStudioKind.AD_IMAGE, "x", "상품", "사실", null, null, image(), null))
                .isInstanceOf(ApiException.class).hasNoCause().hasMessageNotContaining("private-openai-key").hasMessageNotContaining("private-product-details");
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "[]", "null", "{\"data\":[]}", "{\"data\":[{\"b64_json\":\"not-base64!\"}]}", "{\"data\":[{\"url\":\"https://untrusted.test/\"}]}"})
    void malformedProviderImagesAreRejectedWithoutFollowingUrls(String body) throws Exception {
        server.expect(requestTo("https://api.openai.com/v1/images/edits")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.generate(AiStudioKind.AD_IMAGE, "x", "상품", "사실", null, null, image(), null)).isInstanceOf(ApiException.class);
    }
    @Test void incompleteTextNeverStartsTheImageGeneration() throws Exception {
        server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withSuccess("{\"status\":\"incomplete\",\"output\":[]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.generate(AiStudioKind.DETAIL_PAGE, "x", "상품", "사실", null, null, image(), null)).isInstanceOf(ApiException.class);
    }
    @Test void unexpectedImageDimensionsCannotBeSavedAsAChannelCreative() throws Exception {
        server.expect(requestTo("https://api.openai.com/v1/images/edits"))
                .andRespond(withSuccess(imageResponse(image(512)), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.generate(AiStudioKind.AD_IMAGE, "x", "상품", "사실", null, null, image(), null)).isInstanceOf(ApiException.class);
    }
    @Test void strictLocalRendererRejectsMissingFieldsAndOversizedCopies() {
        assertThatThrownBy(() -> OpenAiStudioClient.renderDetail(mapper.readTree("{}"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> OpenAiStudioClient.renderDetail(mapper.readTree(copy("a".repeat(1001))))).isInstanceOf(ApiException.class);
    }
    @Test void missingKeyNeverCallsProvider() throws Exception {
        properties.setApiKey("");
        assertThatThrownBy(() -> client.generate(AiStudioKind.AD_IMAGE, "x", "상품", "사실", null, null, image(), null)).isInstanceOf(ApiException.class);
    }
    static AiStudioImage image() throws Exception { return image(1024); }
    static AiStudioImage image(int height) throws Exception {
        var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(1024, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return AiStudioImage.validate(out.toByteArray());
    }
    String imageResponse(AiStudioImage image) { return mapper.writeValueAsString(Map.of("data", List.of(Map.of("b64_json", Base64.getEncoder().encodeToString(image.bytes()))))); }
    String response(String copy) { return mapper.writeValueAsString(Map.of("status", "completed", "output", List.of(Map.of("content", List.of(Map.of("type", "output_text", "text", copy)))))); }
    String copy(String headline) { return mapper.writeValueAsString(Map.of("headline", headline, "summary", "소개", "benefits", List.of("특징"), "sections", List.of(Map.of("heading", "구성", "body", "설명")), "closing", "마무리")); }
}
