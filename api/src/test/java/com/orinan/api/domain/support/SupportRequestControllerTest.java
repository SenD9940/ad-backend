package com.orinan.api.domain.support;

import com.orinan.api.domain.support.business.SupportBusiness;
import com.orinan.api.domain.support.controller.SupportController;
import com.orinan.api.domain.support.controller.model.SupportCreateRequest;
import com.orinan.api.domain.support.controller.model.SupportResponse.Offer;
import com.orinan.api.domain.support.service.SupportTerms;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.user.converter.UserConverter;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.exceptionhandler.ApiExceptionHandler;
import com.orinan.api.resolver.UserSessionResolver;
import com.orinan.db.support.enums.SupportAccessMode;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.enums.UserStatus;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SupportRequestControllerTest {
    private final SupportBusiness business = mock(SupportBusiness.class);
    private MockMvc mvc;
    private static final String REQUEST = """
            {"title":"상품 등록 지원","description":"스토어 상품 등록 지원","access_mode":"OPERATE",
             "terms_version":"support-2026-09-28-v1","accepted_terms":true}
            """;

    @BeforeEach void setup() {
        var users = mock(UserService.class);
        var converter = mock(UserConverter.class);
        var customer = UserEntity.builder().id(2L).build();
        when(users.findByIdAndStatusWithThrow(2L, UserStatus.REGISTERED)).thenReturn(customer);
        when(converter.toResponse(customer)).thenReturn(UserResponse.builder().id(2L).build());
        mvc = MockMvcBuilders.standaloneSetup(new SupportController(business))
                .setCustomArgumentResolvers(new UserSessionResolver(users, converter))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build())).build();
    }

    @Test void offerUsesAuthenticatedOwnerAndReturnsOnlyPublicCheckoutReadinessAndTerms() throws Exception {
        when(business.offer(1, 2)).thenReturn(new Offer(true, 9900, SupportTerms.VERSION, SupportTerms.TEXT));
        mvc.perform(get("/api/workspaces/1/support/offer").requestAttr("userId", 2L))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body.enabled").value(true)).andExpect(jsonPath("$.body.amount_krw").value(9900))
                .andExpect(jsonPath("$.body.terms_version").value(SupportTerms.VERSION))
                .andExpect(jsonPath("$.body.secret_key").doesNotExist());
        verify(business).offer(1, 2);
    }

    @Test void createBindsAuthenticatedCustomerAndAcceptsOnlyThePublishedRequestFields() throws Exception {
        mvc.perform(post("/api/workspaces/1/support/tickets").requestAttr("userId", 2L)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(business).create(1, 2, new SupportCreateRequest("상품 등록 지원", "스토어 상품 등록 지원",
                SupportAccessMode.OPERATE, SupportTerms.VERSION, true));
    }

    @Test void rejectsClientPriceIdentityAssignmentPaymentAndScopeOverridesEvenWithLenientGlobalMapper() throws Exception {
        for (String field : List.of("\"amount_krw\":1", "\"customer_user_id\":99", "\"assigned_admin_id\":99",
                "\"workspace_id\":99", "\"payment_status\":\"PAID\"", "\"status\":\"APPROVED\"",
                "\"request_source\":\"ADMIN\"", "\"terms_snapshot\":\"changed\"", "\"approved_at\":\"2026-09-28\"")) {
            String body = REQUEST.replace("\"accepted_terms\":true", "\"accepted_terms\":true," + field);
            mvc.perform(post("/api/workspaces/1/support/tickets").requestAttr("userId", 2L)
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(business);
    }

    @Test void explicitConsentAndBoundedTitleDescriptionAndTermsAreRequired() throws Exception {
        for (String body : List.of("{}", REQUEST.replace("true", "false"), REQUEST.replace("true", "null"),
                REQUEST.replace(",\"accepted_terms\":true", ""), REQUEST.replace("상품 등록 지원", "x".repeat(151)),
                REQUEST.replace("스토어 상품 등록 지원", "x".repeat(1001)), REQUEST.replace(SupportTerms.VERSION, ""),
                REQUEST.replace("OPERATE", "UNKNOWN"))) {
            mvc.perform(post("/api/workspaces/1/support/tickets").requestAttr("userId", 2L)
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(business);
    }
}
