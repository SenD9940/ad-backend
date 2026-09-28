package com.orinan.adminapi.domain.support;

import com.orinan.adminapi.domain.auth.model.AdminPrincipal;
import com.orinan.adminapi.domain.support.business.AdminSupportBusiness;
import com.orinan.adminapi.domain.support.controller.AdminSupportApiController;
import com.orinan.adminapi.domain.support.controller.model.*;
import com.orinan.adminapi.exceptionhandler.AdminExceptionHandler;
import com.orinan.db.support.enums.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminSupportApiControllerTest {
    private final AdminSupportBusiness business = mock(AdminSupportBusiness.class);
    private MockMvc mvc;
    private static final String TICKET = """
            {"workspace_id":12,"customer_user_id":3,"title":"상품 등록 지원","description":"등록 지원",
             "access_mode":"OPERATE","amount_krw":30000,"reason":"고객 요청"}
            """;

    @BeforeEach
    void setup() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AdminPrincipal(1, "admin@example.test"), null, List.of()));
        mvc = MockMvcBuilders.standaloneSetup(new AdminSupportApiController(business))
                .setControllerAdvice(new AdminExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build())).build();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void createUsesAuthenticatedActorAndSnakeCaseRequest() throws Exception {
        mvc.perform(post("/admin-api/support/tickets").contentType(MediaType.APPLICATION_JSON).content(TICKET))
                .andExpect(status().isOk());
        verify(business).create(1, new AdminSupportTicketRequest(12L, 3L, "상품 등록 지원", "등록 지원",
                SupportAccessMode.OPERATE, 30000L, "고객 요청"));
    }

    @Test
    void rejectsSpoofedActorMutableApprovalAndInvalidAmountsBeforeBusiness() throws Exception {
        for (String body : List.of("{}", TICKET.replace("30000", "-1"), TICKET.replace("30000", "1000000001"),
                TICKET.replace("OPERATE", "ADMIN"), TICKET.replace("고객 요청", "x".repeat(501)),
                TICKET.replace("\"reason\":", "\"assigned_admin_id\":2,\"reason\":"),
                TICKET.replace("\"reason\":", "\"request_source\":\"CUSTOMER\",\"reason\":"),
                TICKET.replace("\"reason\":", "\"status\":\"APPROVED\",\"reason\":"))) {
            mvc.perform(post("/admin-api/support/tickets").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(business);
    }

    @Test
    void sessionTokenResponseUsesExpectedEnvelopeWithoutExposingHash() throws Exception {
        when(business.start(1, 7, new AdminSupportReasonRequest("지원 시작"))).thenReturn(
                new AdminSupportSessionTokenResponse(8, 7, 12, 3, SupportAccessMode.READ_ONLY,
                        "one-time-opaque-token", LocalDateTime.of(2026, 9, 28, 15, 30)));
        mvc.perform(post("/admin-api/support/tickets/7/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"지원 시작\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.result_code").value(200))
                .andExpect(jsonPath("$.body.session_id").value(8))
                .andExpect(jsonPath("$.body.access_mode").value("READ_ONLY"))
                .andExpect(jsonPath("$.body.access_token").value("one-time-opaque-token"))
                .andExpect(jsonPath("$.body.token_hash").doesNotExist());
        verify(business).start(1, 7, new AdminSupportReasonRequest("지원 시작"));
        verifyNoMoreInteractions(business);
    }

    @Test
    void customerTicketResponsePreservesUnassignedAdministratorAndConsentSnapshot() throws Exception {
        LocalDateTime consent = LocalDateTime.of(2026, 9, 28, 15, 30);
        when(business.ticket(7)).thenReturn(new AdminSupportTicketResponse(7, 12, 3, null,
                "고객 기술 지원 신청", "상품 등록 지원", SupportAccessMode.OPERATE, 9900,
                SupportPaymentStatus.UNPAID, null, null, null, SupportTicketStatus.APPROVED,
                consent, consent.plusDays(7), consent, consent, SupportRequestSource.CUSTOMER,
                "support-terms-v1", "지원료 9,900원 및 지원 범위에 동의합니다."));

        mvc.perform(get("/admin-api/support/tickets/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.assigned_admin_id").isEmpty())
                .andExpect(jsonPath("$.body.request_source").value("CUSTOMER"))
                .andExpect(jsonPath("$.body.amount_krw").value(9900))
                .andExpect(jsonPath("$.body.payment_status").value("UNPAID"))
                .andExpect(jsonPath("$.body.terms_version").value("support-terms-v1"))
                .andExpect(jsonPath("$.body.terms_snapshot").value("지원료 9,900원 및 지원 범위에 동의합니다."));
    }

    @Test
    void omittedNullAndBlankNotesAreAcceptedForEverySupportAction() throws Exception {
        for (String field : List.of("", ",\"reason\":null", ",\"reason\":\"  \"")) {
            String createBody = TICKET.replace(",\"reason\":\"고객 요청\"", field);
            mvc.perform(post("/admin-api/support/tickets").contentType(MediaType.APPLICATION_JSON).content(createBody))
                    .andExpect(status().isOk());
            mvc.perform(post("/admin-api/support/tickets/7/payment").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"payment_status\":\"WAIVED\"" + field + "}"))
                    .andExpect(status().isOk());
            String reasonBody = field.isEmpty() ? "{}" : "{" + field.substring(1) + "}";
            for (String endpoint : List.of("/tickets/7/sessions", "/tickets/7/complete", "/tickets/7/cancel", "/sessions/8/end")) {
                mvc.perform(post("/admin-api/support" + endpoint).contentType(MediaType.APPLICATION_JSON).content(reasonBody))
                        .andExpect(status().isOk());
            }
        }
        for (String note : new String[]{null, "  "}) {
            int count = note == null ? 2 : 1;
            verify(business, times(count)).create(1, new AdminSupportTicketRequest(12L, 3L, "상품 등록 지원", "등록 지원",
                    SupportAccessMode.OPERATE, 30000L, note));
            verify(business, times(count)).payment(1, 7, new AdminSupportPaymentRequest(SupportPaymentStatus.WAIVED, null, note));
            verify(business, times(count)).start(1, 7, new AdminSupportReasonRequest(note));
            verify(business, times(count)).complete(1, 7, new AdminSupportReasonRequest(note));
            verify(business, times(count)).cancel(1, 7, new AdminSupportReasonRequest(note));
            verify(business, times(count)).end(1, 8, new AdminSupportReasonRequest(note));
        }
        verifyNoMoreInteractions(business);
    }

    @Test
    void optionalNotesStillRejectOverlongValuesAndRequireOtherMandatoryFields() throws Exception {
        String note = "x".repeat(501);
        for (String endpoint : List.of("/tickets/7/sessions", "/tickets/7/complete", "/tickets/7/cancel", "/sessions/8/end")) {
            mvc.perform(post("/admin-api/support" + endpoint).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"" + note + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/admin-api/support/tickets/7/payment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_status\":\"WAIVED\",\"reason\":\"" + note + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin-api/support/tickets/7/payment").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin-api/support/tickets").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(business);
    }

    @Test
    void routesReadFiltersAndManualReceiptWithoutPermittingAmountChanges() throws Exception {
        mvc.perform(get("/admin-api/support/tickets").param("status", "APPROVED")
                        .param("customerUserId", "3").param("workspaceId", "12").param("size", "10"))
                .andExpect(status().isOk());
        verify(business).tickets(SupportTicketStatus.APPROVED, 3L, 12L, 0, 10);
        mvc.perform(post("/admin-api/support/tickets/7/payment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_status\":\"PAID\",\"payment_reference\":\"receipt-4\",\"reason\":\"수납 확인\"}"))
                .andExpect(status().isOk());
        verify(business).payment(1, 7, new AdminSupportPaymentRequest(SupportPaymentStatus.PAID, "receipt-4", "수납 확인"));
        mvc.perform(post("/admin-api/support/tickets/7/payment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_status\":\"PAID\",\"amount_krw\":1,\"reason\":\"변경\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin-api/support/tickets").param("status", "INVALID")).andExpect(status().isBadRequest());
    }
}
