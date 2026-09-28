package com.orinan.adminapi.domain.aistudio;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.config.security.AdminSecurityConfig;
import com.orinan.adminapi.config.security.AdminSecurityResponses;
import com.orinan.adminapi.domain.aistudio.business.AdminAiTemplateAnalysisBusiness;
import com.orinan.adminapi.domain.aistudio.controller.AdminAiTemplateAnalysisController;
import com.orinan.adminapi.domain.aistudio.controller.model.AdminAiTemplateAnalysisRequest;
import com.orinan.adminapi.domain.aistudio.controller.model.AdminAiTemplateAnalysisResponse;
import com.orinan.adminapi.domain.auth.business.AdminAuthBusiness;
import com.orinan.adminapi.domain.auth.model.AdminPrincipal;
import com.orinan.adminapi.exceptionhandler.AdminExceptionHandler;
import com.orinan.db.aistudio.enums.AiStudioKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminAiTemplateAnalysisControllerTest {
    private static final String PATH = "/admin-api/ai-studio/templates/analyze";
    private static final String KEY = "test/ai-studio/templates/4c107049-3422-46e8-8102-dd148d96b4be.png";
    private static final String VALID_REQUEST = "{\"image_key\":\"" + KEY + "\",\"kind\":\"AD_IMAGE\"}";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private AdminAiTemplateAnalysisBusiness business;

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class, AdminSecurityConfig.class, AdminSecurityResponses.class, AdminExceptionHandler.class);
        context.refresh();
        business = context.getBean(AdminAiTemplateAnalysisBusiness.class);
        var auth = context.getBean(AdminAuthBusiness.class);
        when(auth.authenticate("valid.admin.token")).thenReturn(new AdminPrincipal(7, "admin@example.test"));
        when(auth.authenticate("customer.api.token"))
                .thenThrow(new AdminException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다."));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach void close() { context.close(); }

    @Test void analysisRequiresAuthenticatedAdministratorBeforeCallingBusiness() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("Authorization", "Bearer customer.api.token")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isForbidden());
        verifyNoInteractions(business);
    }

    @Test void snakeCaseImageAndKindUseAuthenticatedActorAndReturnSuggestedMetadata() throws Exception {
        var response = new AdminAiTemplateAnalysisResponse("상품 중심 광고", "여백이 있는 구성", "실제 상품의 외형을 유지해 주세요.");
        when(business.analyze(eq(7L), any())).thenReturn(response);
        for (var kind : AiStudioKind.values()) {
            mvc.perform(post(PATH).header("Authorization", "Bearer valid.admin.token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"image_key\":\"" + KEY + "\",\"kind\":\"" + kind + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.body.title").value(response.title()))
                    .andExpect(jsonPath("$.body.description").value(response.description()))
                    .andExpect(jsonPath("$.body.prompt").value(response.prompt()));
            verify(business).analyze(7, new AdminAiTemplateAnalysisRequest(KEY, kind));
        }
        verifyNoMoreInteractions(business);
    }

    @Test void missingBlankOversizedAndInvalidFieldsAreRejectedBeforeBusiness() throws Exception {
        for (String request : List.of(
                "{}", "{\"kind\":\"AD_IMAGE\"}", "{\"image_key\":null,\"kind\":\"AD_IMAGE\"}",
                "{\"image_key\":\"   \",\"kind\":\"AD_IMAGE\"}",
                "{\"image_key\":\"" + "a".repeat(513) + "\",\"kind\":\"AD_IMAGE\"}",
                "{\"image_key\":\"" + KEY + "\"}",
                "{\"image_key\":\"" + KEY + "\",\"kind\":null}",
                "{\"image_key\":\"" + KEY + "\",\"kind\":\"UNKNOWN\"}")) {
            mvc.perform(post(PATH).header("Authorization", "Bearer valid.admin.token")
                            .contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(business);
    }

    @Test void unknownFieldsAndCamelCaseAliasesCannotBypassStrictRequestContract() throws Exception {
        for (String request : List.of(
                "{\"imageKey\":\"" + KEY + "\",\"kind\":\"AD_IMAGE\"}",
                VALID_REQUEST.substring(0, VALID_REQUEST.length() - 1) + ",\"actor_id\":99}",
                VALID_REQUEST.substring(0, VALID_REQUEST.length() - 1) + ",\"prompt\":\"override\"}")) {
            mvc.perform(post(PATH).header("Authorization", "Bearer valid.admin.token")
                            .contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(business);
    }

    @Test void providerFailuresAndRevocationPreserveSafeStatusWithoutLeakingCauses() throws Exception {
        for (var status : List.of(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.BAD_GATEWAY, HttpStatus.FORBIDDEN)) {
            var failure = new AdminException(status, "분석 요청을 완료할 수 없습니다.");
            failure.initCause(new IllegalStateException("sk-secret-provider-response"));
            reset(business);
            when(business.analyze(eq(7L), any())).thenThrow(failure);
            var result = mvc.perform(post(PATH).header("Authorization", "Bearer valid.admin.token")
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                    .andExpect(status().is(status.value()))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.result.result_code").value(status.value()))
                    .andExpect(jsonPath("$.result.result_description").value(failure.getMessage()))
                    .andExpect(jsonPath("$.body").doesNotExist()).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("sk-secret-provider-response", "IllegalStateException");
            verify(business).analyze(7, new AdminAiTemplateAnalysisRequest(KEY, AiStudioKind.AD_IMAGE));
        }
    }

    @Test void unexpectedInternalFailureDoesNotExposeProviderDetails() throws Exception {
        when(business.analyze(eq(7L), any())).thenThrow(new IllegalStateException("sk-private-key raw-provider-payload"));
        var result = mvc.perform(post(PATH).header("Authorization", "Bearer valid.admin.token")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body").doesNotExist()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("sk-private-key", "raw-provider-payload", "IllegalStateException");
    }

    @Configuration @EnableWebMvc @EnableWebSecurity
    static class Config implements WebMvcConfigurer {
        @Bean AdminAuthBusiness auth() { return mock(AdminAuthBusiness.class); }
        @Bean AdminAiTemplateAnalysisBusiness business() { return mock(AdminAiTemplateAnalysisBusiness.class); }
        @Bean AdminAiTemplateAnalysisController controller(AdminAiTemplateAnalysisBusiness business) {
            return new AdminAiTemplateAnalysisController(business);
        }
        @Bean JsonMapper mapper() {
            return JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        }
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.add(new JacksonJsonHttpMessageConverter(mapper()));
        }
    }
}
