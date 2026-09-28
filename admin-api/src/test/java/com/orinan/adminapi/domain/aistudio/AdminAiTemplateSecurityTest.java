package com.orinan.adminapi.domain.aistudio;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.config.security.AdminSecurityConfig;
import com.orinan.adminapi.config.security.AdminSecurityResponses;
import com.orinan.adminapi.domain.aistudio.business.AdminAiTemplateBusiness;
import com.orinan.adminapi.domain.aistudio.business.AdminAiCategoryBusiness;
import com.orinan.adminapi.domain.aistudio.controller.AdminAiCategoryApiController;
import com.orinan.adminapi.domain.aistudio.controller.AdminAiTemplateApiController;
import com.orinan.adminapi.domain.aistudio.controller.model.AdminAiCategoryDeleteResponse;
import com.orinan.adminapi.domain.aistudio.controller.model.AdminAiCategoryRequest;
import com.orinan.adminapi.domain.aistudio.controller.model.AdminAiCategoryResponse;
import com.orinan.adminapi.domain.auth.business.AdminAuthBusiness;
import com.orinan.adminapi.domain.auth.model.AdminPrincipal;
import com.orinan.adminapi.exceptionhandler.AdminExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
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

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminAiTemplateSecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private AdminAiTemplateBusiness business;
    private AdminAiCategoryBusiness categories;
    private static final String BASE = "/admin-api/ai-studio/templates";
    private static final String CATEGORY_BASE = "/admin-api/ai-studio/categories";

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Config.class, AdminSecurityConfig.class, AdminSecurityResponses.class, AdminExceptionHandler.class);
        context.refresh(); business = context.getBean(AdminAiTemplateBusiness.class);
        categories = context.getBean(AdminAiCategoryBusiness.class);
        var auth = context.getBean(AdminAuthBusiness.class);
        when(auth.authenticate("valid.admin.token")).thenReturn(new AdminPrincipal(7, "admin@example.test"));
        when(auth.authenticate("customer.api.token")).thenThrow(new AdminException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다."));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void close() { context.close(); }

    @Test void anonymousAndNonAdminCannotReadTemplatesOrUploadImages() throws Exception {
        for (String path : new String[]{BASE, BASE + "/1"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer customer.api.token")).andExpect(status().isForbidden());
        }
        mvc.perform(multipart(BASE + "/images").file(image())).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch(BASE + "/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(business);
    }

    @Test void validAdminCanListAndUploadUsingAuthenticatedActor() throws Exception {
        mvc.perform(get(BASE).header("Authorization", "Bearer valid.admin.token")).andExpect(status().isOk());
        verify(business).list(null, null, null, 0, 20);
        mvc.perform(get(BASE).param("categoryId", "5").header("Authorization", "Bearer valid.admin.token"))
                .andExpect(status().isOk());
        verify(business).list(null, 5L, null, 0, 20);
        mvc.perform(multipart(BASE + "/images").file(image()).header("Authorization", "Bearer valid.admin.token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(business).upload(eq(7L), any());
    }

    @Test void requiredFieldsAndMultipartPartAreValidatedBeforeBusiness() throws Exception {
        mvc.perform(post(BASE).header("Authorization", "Bearer valid.admin.token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"샘플\",\"kind\":\"AD_IMAGE\",\"published\":false}"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(BASE + "/images").header("Authorization", "Bearer valid.admin.token"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("kind", "BAD").header("Authorization", "Bearer valid.admin.token"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(business);
    }

    @Test void categoryCatalogAndMutationsRequireAdministratorAuthentication() throws Exception {
        for (String token : new String[]{null, "customer.api.token"}) {
            var requests = List.of(get(CATEGORY_BASE), post(CATEGORY_BASE).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"식품\"}"),
                    patch(CATEGORY_BASE + "/5").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"뷰티\"}"), delete(CATEGORY_BASE + "/5"));
            for (var request : requests) {
                if (token != null) request.header("Authorization", "Bearer " + token);
                mvc.perform(request).andExpect(status().is(token == null ? 401 : 403));
            }
        }
        verifyNoInteractions(categories);
    }

    @Test void administratorCanManageCategoriesUsingAuthenticatedIdentity() throws Exception {
        var category = new AdminAiCategoryResponse(5, "식품");
        when(categories.list()).thenReturn(List.of(category));
        when(categories.create(eq(7L), any())).thenReturn(category);
        when(categories.update(eq(7L), eq(5L), any())).thenReturn(category);
        when(categories.delete(7, 5)).thenReturn(new AdminAiCategoryDeleteResponse(5, true));
        mvc.perform(get(CATEGORY_BASE).header("Authorization", "Bearer valid.admin.token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body[0].id").value(5));
        mvc.perform(post(CATEGORY_BASE).header("Authorization", "Bearer valid.admin.token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"식품\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.name").value("식품"));
        mvc.perform(patch(CATEGORY_BASE + "/5").header("Authorization", "Bearer valid.admin.token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"식품\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete(CATEGORY_BASE + "/5").header("Authorization", "Bearer valid.admin.token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.id").value(5)).andExpect(jsonPath("$.body.success").value(true));
        verify(categories).create(7, new AdminAiCategoryRequest("식품"));
        verify(categories).update(7, 5, new AdminAiCategoryRequest("식품"));
        verify(categories).delete(7, 5);
    }

    @Test void templateCreateRequiresCategoryIdAndRejectsTheRemovedFreeTextCategory() throws Exception {
        String prefix = "{\"title\":\"샘플\",\"kind\":\"AD_IMAGE\",\"prompt\":\"생성 지침\",\"published\":false";
        for (String suffix : new String[]{"}", ",\"category_id\":0}", ",\"category_id\":-1}", ",\"category_id\":5,\"category\":\"식품\"}"})
            mvc.perform(post(BASE).header("Authorization", "Bearer valid.admin.token")
                            .contentType(MediaType.APPLICATION_JSON).content(prefix + suffix))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(business);
        mvc.perform(post(BASE).header("Authorization", "Bearer valid.admin.token")
                        .contentType(MediaType.APPLICATION_JSON).content(prefix + ",\"category_id\":5}"))
                .andExpect(status().isOk());
        verify(business).create(eq(7L), argThat(request -> request.categoryId().equals(5L)));
    }

    @Test void invalidCategoryNamesAreRejectedBeforeBusiness() throws Exception {
        for (String name : new String[]{"", "   ", "a".repeat(81)})
            mvc.perform(post(CATEGORY_BASE).header("Authorization", "Bearer valid.admin.token")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(categories);
    }

    @Test void imageOnlyTemplateRequestDoesNotRequireManualNameDescriptionOrPrompt() throws Exception {
        mvc.perform(post(BASE).header("Authorization", "Bearer valid.admin.token")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"kind":"AD_IMAGE","category_id":5,"preview_image_key":"stored/sample.png","published":true}
                                """))
                .andExpect(status().isOk());
        verify(business).create(eq(7L), argThat(request -> request.title() == null && request.description() == null
                && request.prompt() == null && request.categoryId().equals(5L)
                && request.previewImageKey().equals("stored/sample.png")));
    }

    private MockMultipartFile image() { return new MockMultipartFile("file", "sample.png", "image/png", new byte[]{1}); }
    @Configuration @EnableWebMvc @EnableWebSecurity
    static class Config implements WebMvcConfigurer {
        @Bean AdminAuthBusiness auth() { return mock(AdminAuthBusiness.class); }
        @Bean AdminAiTemplateBusiness business() { return mock(AdminAiTemplateBusiness.class); }
        @Bean AdminAiTemplateApiController controller(AdminAiTemplateBusiness business) { return new AdminAiTemplateApiController(business); }
        @Bean AdminAiCategoryBusiness categoryBusiness() { return mock(AdminAiCategoryBusiness.class); }
        @Bean AdminAiCategoryApiController categoryController(AdminAiCategoryBusiness business) { return new AdminAiCategoryApiController(business); }
        @Bean JsonMapper mapper() { return JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build(); }
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.add(new JacksonJsonHttpMessageConverter(mapper()));
        }
    }
}
