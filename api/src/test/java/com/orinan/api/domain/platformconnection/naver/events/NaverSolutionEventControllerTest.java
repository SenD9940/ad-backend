package com.orinan.api.domain.platformconnection.naver.events;

import com.orinan.api.domain.platformconnection.naver.solution.NaverSolutionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaverSolutionEventControllerTest {
    private final NaverSolutionProperties properties=mock(NaverSolutionProperties.class);
    private final NaverSolutionEventInbox inbox=mock(NaverSolutionEventInbox.class);
    private final NaverSolutionEventController controller=new NaverSolutionEventController(properties,inbox);
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final MockHttpServletRequest request=new MockHttpServletRequest();
    private static final String KEY="secret-webhook-key-only-for-mocked-tests";
    private static final String VALID="""
            [{"solutionId":"solution","eventId":"event-1","changeType":"END_SUBSCRIPTION",
              "accountUid":"seller","accountMappingId":"mapping"}]
            """;

    @BeforeEach void setUp() {
        when(properties.ready()).thenReturn(true);
        when(properties.getWebhookHeaderName()).thenReturn("X-Naver-Event-Key");
        when(properties.getWebhookKey()).thenReturn(KEY);
        when(properties.getSolutionId()).thenReturn("solution");
        request.addHeader("X-Naver-Event-Key",KEY);
    }

    @Test @SuppressWarnings("unchecked") void authenticatedBatchIsPersistedBeforeSuccessAndOnlyMinimumFieldsAreRetained() {
        var response=controller.receive(request,json(VALID.replace("\"eventId\":", "\"unneededPersonalData\":\"ignore-me\",\"eventId\":")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        var captured=ArgumentCaptor.forClass(List.class); verify(inbox).accept(captured.capture());
        var event=(NaverSolutionEventData)captured.getValue().get(0);
        assertThat(event.solutionId()).isEqualTo("solution"); assertThat(event.eventId()).isEqualTo("event-1");
        assertThat(event.accountUid()).isEqualTo("seller"); assertThat(event.accountMappingId()).isEqualTo("mapping");
        assertThat(event.toString()).doesNotContain("seller", "mapping", "ignore-me");
    }

    @Test void disabledIntegrationDoesNotAcceptEventsEvenWithConfiguredKey() {
        when(properties.ready()).thenReturn(false);
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE,()->controller.receive(request,json(VALID)));
        verifyNoInteractions(inbox);
    }

    @Test void missingWrongOversizedOrDuplicateAuthenticationHeadersAreRejected() {
        request.removeHeader("X-Naver-Event-Key");
        assertStatus(HttpStatus.UNAUTHORIZED,()->controller.receive(request,json(VALID)));
        request.addHeader("X-Naver-Event-Key","wrong");
        assertStatus(HttpStatus.UNAUTHORIZED,()->controller.receive(request,json(VALID)));
        request.removeHeader("X-Naver-Event-Key");request.addHeader("X-Naver-Event-Key","x".repeat(4097));
        assertStatus(HttpStatus.UNAUTHORIZED,()->controller.receive(request,json(VALID)));
        request.removeHeader("X-Naver-Event-Key");request.addHeader("X-Naver-Event-Key",KEY);request.addHeader("X-Naver-Event-Key","other");
        assertStatus(HttpStatus.UNAUTHORIZED,()->controller.receive(request,json(VALID)));
        verifyNoInteractions(inbox);
    }

    @Test void rejectsMalformedPayloadEmptyOrOverlargeBatchAndUnexpectedIdentityWithoutPartialPersistence() {
        for(String invalid:List.of("{}","[]","[1]",VALID.replace("\"solution\"","\"other-solution\""),
                VALID.replace("\"eventId\":\"event-1\",", ""),
                VALID.replace("\"eventId\":\"event-1\"","\"eventId\":123"),
                VALID.replace("\"accountUid\":\"seller\"","\"accountUid\":\" \""),
                VALID.replace("\"accountMappingId\":\"mapping\"","\"accountMappingId\":true"),
                VALID.replace("END_SUBSCRIPTION","X".repeat(65)))) {
            assertStatus(HttpStatus.BAD_REQUEST,()->controller.receive(request,json(invalid)));
        }
        var large=mapper.createArrayNode();for(int i=0;i<101;i++)large.add(json(VALID).get(0));
        assertStatus(HttpStatus.BAD_REQUEST,()->controller.receive(request,large));
        var partiallyInvalid=mapper.createArrayNode().add(json(VALID).get(0)).add(mapper.createObjectNode());
        assertStatus(HttpStatus.BAD_REQUEST,()->controller.receive(request,partiallyInvalid));
        verifyNoInteractions(inbox);
    }

    @Test @SuppressWarnings("unchecked") void unknownChangeTypeAndMissingMappingAreQueuedForAuthoritativeReadBack() {
        String payload=VALID.replace("END_SUBSCRIPTION","FUTURE_EVENT_TYPE").replace(",\"accountMappingId\":\"mapping\"", "");
        assertThat(controller.receive(request,json(payload)).getStatusCode()).isEqualTo(HttpStatus.OK);
        var captured=ArgumentCaptor.forClass(List.class); verify(inbox).accept(captured.capture());
        var event=(NaverSolutionEventData)captured.getValue().get(0);
        assertThat(event.changeType()).isEqualTo("FUTURE_EVENT_TYPE");assertThat(event.accountMappingId()).isNull();
    }

    @Test void persistenceFailureIsNotAcknowledgedAsSuccessfulEventDelivery() {
        doThrow(new IllegalStateException("database unavailable")).when(inbox).accept(anyList());
        assertThatThrownBy(()->controller.receive(request,json(VALID))).isInstanceOf(IllegalStateException.class);
    }

    private JsonNode json(String value) {return mapper.readTree(value);}
    private void assertStatus(HttpStatus status,Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                exception->assertThat(exception.getStatusCode()).isEqualTo(status));
    }
}
