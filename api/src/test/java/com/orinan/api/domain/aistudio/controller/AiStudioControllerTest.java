package com.orinan.api.domain.aistudio.controller;

import com.orinan.api.domain.aistudio.controller.model.AiStudioGenerationRequest;
import com.orinan.api.domain.aistudio.controller.model.AiStudioResponse.*;
import com.orinan.api.domain.aistudio.service.AiStudioExport;
import com.orinan.api.domain.aistudio.service.AiStudioService;
import com.orinan.api.domain.support.interceptor.SupportAccessInterceptor;
import com.orinan.api.domain.support.model.SupportContext;
import com.orinan.api.domain.support.service.SupportActionService;
import com.orinan.api.domain.support.service.SupportRoutePolicy;
import com.orinan.api.domain.support.service.SupportSessionService;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.user.converter.UserConverter;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.resolver.UserSessionResolver;
import com.orinan.db.aistudio.enums.AiStudioKind;
import com.orinan.db.aistudio.output.AiStudioOutputStatus;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiStudioControllerTest {
    private static final String BASE = "/api/workspaces/10/ai-studio";
    private static final String REQUEST_ID = "9704ea01-eab1-4a84-8024-1b9d269a1234";
    private static final String BODY = """
            {"template_id":3,"product_name":"상품 이름","product_description":"확인된 상품 설명",
             "audience":"성인","instructions":"단정한 디자인","idempotency_key":"9704ea01-eab1-4a84-8024-1b9d269a1234",
             "product_image_key":"test/ai-studio/workspaces/10/inputs/2/product.png"}
            """;
    private final AiStudioService service = mock(AiStudioService.class);
    private final SupportSessionService sessions = mock(SupportSessionService.class);
    private final SupportActionService actions = mock(SupportActionService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        var users = mock(UserService.class); var converter = mock(UserConverter.class);
        var user = UserEntity.builder().id(2L).build();
        when(users.findByIdAndStatusWithThrow(2L, UserStatus.REGISTERED)).thenReturn(user);
        when(converter.toResponse(user)).thenReturn(UserResponse.builder().id(2L).build());
        when(sessions.authenticate("a".repeat(43))).thenReturn(new SupportContext(1, 20, 10, 3, 2,
                "OPERATE", LocalDateTime.of(2030, 1, 1, 0, 0)));
        when(actions.begin(any(), anyString(), anyString())).thenReturn(1L);
        mvc = MockMvcBuilders.standaloneSetup(new AiStudioController(service))
                .setCustomArgumentResolvers(new UserSessionResolver(users, converter))
                .addInterceptors(new SupportAccessInterceptor(sessions, new SupportRoutePolicy(), actions))
                .setControllerAdvice(new AiStudioExceptionHandler())
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build())).build();
    }

    @Test void snakeCaseGenerationUsesAuthenticatedUserAndReturnsOnlyPublicOutputFields() throws Exception {
        var request = new AiStudioGenerationRequest(3L, "상품 이름", "확인된 상품 설명", "성인", "단정한 디자인", REQUEST_ID,
                "test/ai-studio/workspaces/10/inputs/2/product.png");
        when(service.generate(10, 2, request)).thenReturn(output());
        mvc.perform(post(BASE + "/generations").requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body.workspace_id").value(10)).andExpect(jsonPath("$.body.template_id").value(3))
                .andExpect(jsonPath("$.body.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.body.image_url").value("https://example.test/generated.png"))
                .andExpect(jsonPath("$.body.image_key").doesNotExist()).andExpect(jsonPath("$.body.prompt").doesNotExist())
                .andExpect(jsonPath("$.body.api_key").doesNotExist());
        verify(service).generate(10, 2, request);
    }

    @Test void untrustedOwnershipStatusProviderAndUnknownFieldsAreRejectedDespiteLenientGlobalMapper() throws Exception {
        for (String extra : List.of("\"workspace_id\":99", "\"created_by\":99", "\"user_id\":99", "\"status\":\"SUCCEEDED\"",
                "\"image_url\":\"https://untrusted.test/image.png\"", "\"model\":\"arbitrary\"", "\"unknown_field\":true")) {
            String tampered = BODY.strip().substring(0, BODY.strip().length() - 1) + "," + extra + "}";
            mvc.perform(post(BASE + "/generations").requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON).content(tampered))
                    .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        }
        for (String body : List.of("{}", BODY.replace("\"template_id\":3", "\"template_id\":0"),
                BODY.replace(REQUEST_ID, "not-a-request-uuid"), BODY.replace("확인된 상품 설명", "   "))) {
            mvc.perform(post(BASE + "/generations").requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test void listAndDetailRequestsPropagateWorkspaceIdentityAndFilters() throws Exception {
        when(service.capabilities(10, 2)).thenReturn(new Capabilities(false, "설정이 필요합니다."));
        mvc.perform(get(BASE + "/capabilities").requestAttr("userId", 2L)).andExpect(status().isOk())
                .andExpect(jsonPath("$.body.enabled").value(false)).andExpect(jsonPath("$.body.disabled_reason").value("설정이 필요합니다."));
        mvc.perform(get(BASE + "/templates").requestAttr("userId", 2L).param("kind", "AD_IMAGE").param("q", "식품")
                .param("categoryId", "5").param("page", "1").param("size", "10")).andExpect(status().isOk());
        mvc.perform(get(BASE + "/templates/3").requestAttr("userId", 2L)).andExpect(status().isOk());
        mvc.perform(get(BASE + "/outputs").requestAttr("userId", 2L).param("kind", "DETAIL_PAGE"))
                .andExpect(status().isOk());
        when(service.output(10, 2, 7)).thenReturn(output());
        mvc.perform(get(BASE + "/outputs/7").requestAttr("userId", 2L)).andExpect(status().isOk())
                .andExpect(jsonPath("$.body.id").value(7));
        verify(service).templates(10, 2, AiStudioKind.AD_IMAGE, 5L, "식품", 1, 10);
        verify(service).template(10, 2, 3);
        verify(service).outputs(10, 2, AiStudioKind.DETAIL_PAGE, 0, 20);
    }

    @Test void categoryCatalogAndTemplateResponseUseStableIdsAndCurrentNames() throws Exception {
        when(service.categories(10, 2)).thenReturn(List.of(new Category(5, "라이프스타일")));
        var template = new Template(3, AiStudioKind.AD_IMAGE, "샘플", "설명", 5, "라이프스타일",
                "https://example.test/sample.png", LocalDateTime.now(), LocalDateTime.now());
        when(service.template(10, 2, 3)).thenReturn(template);
        mvc.perform(get(BASE + "/categories").requestAttr("userId", 2L)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body[0].id").value(5)).andExpect(jsonPath("$.body[0].name").value("라이프스타일"));
        mvc.perform(get(BASE + "/templates/3").requestAttr("userId", 2L)).andExpect(status().isOk())
                .andExpect(jsonPath("$.body.category_id").value(5)).andExpect(jsonPath("$.body.category_name").value("라이프스타일"))
                .andExpect(jsonPath("$.body.category").doesNotExist());
        verify(service).categories(10, 2);
    }

    @Test void multipartUsesFilePartAndSessionIdentityAndRequiresAnActualPart() throws Exception {
        var file = new MockMultipartFile("file", "product.png", "image/png", new byte[]{1, 2, 3});
        when(service.upload(eq(10L), eq(2L), any())).thenReturn(new AiStudioService.UploadedImage("stored/key.png", "https://example.test/product.png"));
        mvc.perform(multipart(BASE + "/images").file(file).requestAttr("userId", 2L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.image_key").value("stored/key.png"))
                .andExpect(jsonPath("$.body.image_url").value("https://example.test/product.png"));
        verify(service).upload(10, 2, file);
        mvc.perform(multipart(BASE + "/images").requestAttr("userId", 2L)).andExpect(status().isBadRequest());
        verify(service, times(1)).upload(anyLong(), anyLong(), any());
    }

    @Test void authenticatedImageDownloadReturnsAttachmentBytesWithoutRedirectsOrHtml() throws Exception {
        byte[] bytes = {1, 2, 3, 4};
        when(service.exportOutput(10, 2, 7)).thenReturn(new AiStudioExport(AiStudioKind.AD_IMAGE, "상품", null, bytes, "image/png"));
        mvc.perform(get(BASE + "/outputs/7/image").requestAttr("userId", 2L)).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG)).andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"ai-studio-7.png\""))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("Location"));
        verify(service).exportOutput(10, 2, 7);
    }

    @Test void supportOperatorTokensAreDeniedOnEveryStudioRouteBeforeServiceCalls() throws Exception {
        for (String suffix : List.of("/capabilities", "/categories", "/templates", "/templates/3", "/outputs", "/outputs/7", "/outputs/7/image")) {
            mvc.perform(get(BASE + suffix).header("X-Support-Token", "a".repeat(43)).requestAttr("userId", 2L))
                    .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(post(BASE + "/generations").header("X-Support-Token", "a".repeat(43)).requestAttr("userId", 2L)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(multipart(BASE + "/images").file(new MockMultipartFile("file", "x.png", "image/png", new byte[]{1}))
                .header("X-Support-Token", "a".repeat(43)).requestAttr("userId", 2L)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
        verify(actions, times(9)).complete(1, 403);
    }

    private Output output() {
        return new Output(7, 10, 3, AiStudioKind.AD_IMAGE, "상품 이름", "https://example.test/generated.png", null,
                AiStudioOutputStatus.SUCCEEDED, LocalDateTime.of(2026, 9, 28, 10, 0));
    }
}
