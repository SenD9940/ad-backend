package com.orinan.api.domain.platformconnection.meta;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class MetaAdUpdateClientTest {

    private static final String TOKEN = "update-private-token-never-log";
    private static final String SECRET = "update-private-secret-never-log";
    private static final String UNKNOWN_MESSAGE = "수정 결과를 확인하지 못했습니다. Meta 광고 관리자에서 현재 상태와 예산을 확인해 주세요.";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<ClientRequest> requests = new ArrayList<>();
    private final Queue<ClientResponse> responses = new ArrayDeque<>();
    private final ExchangeStrategies strategies = ExchangeStrategies.withDefaults();
    private MetaGraphClient client;
    private MetaProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        properties.setAppId("1234");
        properties.setAppSecret(SECRET);
        properties.setRedirectUri("https://api.example.com/callback");
        properties.setFrontendRedirectUri("https://app.example.com/integrations");
        client = new MetaGraphClient(WebClient.builder().exchangeStrategies(strategies).exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(responses.remove());
        }), properties, mapper);
    }

    @Test
    void readsCampaignIdentityAndBudgetWithoutChangingAnything() {
        response(200, """
                {"id":"111","account_id":"123","objective":"OUTCOME_TRAFFIC",
                 "daily_budget":"15000","lifetime_budget":0}
                """);

        assertThat(client.getCampaignForUpdate("act_123", TOKEN, "111"))
                .isEqualTo(new MetaGraphClient.CampaignForUpdate("111", 15000, 0));

        authenticatedGet("111", "id,account_id,objective,daily_budget,lifetime_budget");
    }

    @Test
    void missingOrNullBudgetFieldsRepresentNoBudgetAtThisLevel() {
        response(200, "{\"id\":\"111\",\"account_id\":\"123\",\"objective\":\"OUTCOME_TRAFFIC\",\"daily_budget\":null}");
        assertThat(client.getCampaignForUpdate("act_123", TOKEN, "111"))
                .isEqualTo(new MetaGraphClient.CampaignForUpdate("111", 0, 0));

        response(200, "{\"id\":\"222\",\"account_id\":\"123\",\"campaign_id\":\"111\",\"lifetime_budget\":null}");
        assertThat(client.getAdSetForUpdate("act_123", TOKEN, "222"))
                .isEqualTo(new MetaGraphClient.AdSetForUpdate("222", "111", 0, 0));
    }

    @Test
    void readsAdSetParentAndLifetimeBudget() {
        response(200, """
                {"id":"222","account_id":"123","campaign_id":"111","daily_budget":0,"lifetime_budget":"100000"}
                """);

        assertThat(client.getAdSetForUpdate("act_123", TOKEN, "222"))
                .isEqualTo(new MetaGraphClient.AdSetForUpdate("222", "111", 0, 100000));

        authenticatedGet("222", "id,account_id,campaign_id,daily_budget,lifetime_budget");
    }

    @Test
    void verifiesAdTypeByBothParentsAndAccount() {
        response(200, "{\"id\":\"333\",\"account_id\":\"123\",\"adset_id\":\"222\",\"campaign_id\":\"111\"}");

        client.verifyAdForUpdate("act_123", TOKEN, "333");

        authenticatedGet("333", "id,account_id,adset_id,campaign_id");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":\"111\",\"account_id\":\"999\",\"objective\":\"OUTCOME_TRAFFIC\"}",
            "{\"id\":\"112\",\"account_id\":\"123\",\"objective\":\"OUTCOME_TRAFFIC\"}",
            "{\"id\":\"111\",\"account_id\":123,\"objective\":\"OUTCOME_TRAFFIC\"}",
            "{\"id\":\"111\",\"account_id\":\"123\"}",
            "{\"id\":\"111\",\"account_id\":\"123\",\"objective\":\" \"}"
    })
    void campaignFromDifferentAccountOrWithInvalidTypeCannotPassVerification(String body) {
        response(200, body);

        assertInvalidResponse(() -> client.getCampaignForUpdate("act_123", TOKEN, "111"));

        assertThat(requests).hasSize(1).allSatisfy(request -> assertThat(request.method()).isEqualTo(HttpMethod.GET));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":\"222\",\"account_id\":\"999\",\"campaign_id\":\"111\"}",
            "{\"id\":\"223\",\"account_id\":\"123\",\"campaign_id\":\"111\"}",
            "{\"id\":\"222\",\"account_id\":\"123\"}",
            "{\"id\":\"222\",\"account_id\":\"123\",\"campaign_id\":\"111/ads\"}"
    })
    void adSetRequiresRequestedIdAccountAndValidParent(String body) {
        response(200, body);

        assertInvalidResponse(() -> client.getAdSetForUpdate("act_123", TOKEN, "222"));

        assertThat(requests).hasSize(1).allSatisfy(request -> assertThat(request.method()).isEqualTo(HttpMethod.GET));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":\"333\",\"account_id\":\"999\",\"adset_id\":\"222\",\"campaign_id\":\"111\"}",
            "{\"id\":\"334\",\"account_id\":\"123\",\"adset_id\":\"222\",\"campaign_id\":\"111\"}",
            "{\"id\":\"333\",\"account_id\":\"123\",\"campaign_id\":\"111\"}",
            "{\"id\":\"333\",\"account_id\":\"123\",\"adset_id\":\"222\"}",
            "{\"id\":\"333\",\"account_id\":\"123\",\"adset_id\":\"222/ads\",\"campaign_id\":\"111\"}",
            "{\"id\":\"333\",\"account_id\":\"123\",\"adset_id\":\"222\",\"campaign_id\":111}"
    })
    void adRequiresRequestedIdAccountAndBothValidParents(String body) {
        response(200, body);

        assertInvalidResponse(() -> client.verifyAdForUpdate("act_123", TOKEN, "333"));

        assertThat(requests).hasSize(1).allSatisfy(request -> assertThat(request.method()).isEqualTo(HttpMethod.GET));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "\"-1\"", "1.5", "\"1.5\"", "true", "{}", "\"\"", "\"9223372036854775808\""})
    void presentBudgetValuesMustBeNonNegativeLongs(String budget) {
        for (String field : List.of("daily_budget", "lifetime_budget")) {
            response(200, "{\"id\":\"111\",\"account_id\":\"123\",\"objective\":\"OUTCOME_TRAFFIC\",\"" + field + "\":" + budget + "}");
            assertInvalidResponse(() -> client.getCampaignForUpdate("act_123", TOKEN, "111"));
            response(200, "{\"id\":\"222\",\"account_id\":\"123\",\"campaign_id\":\"111\",\"" + field + "\":" + budget + "}");
            assertInvalidResponse(() -> client.getAdSetForUpdate("act_123", TOKEN, "222"));
        }
        assertThat(requests).hasSize(4).allSatisfy(request -> assertThat(request.method()).isEqualTo(HttpMethod.GET));
    }

    @Test
    void updatesCampaignOnlyWithProvidedFields() {
        response(200, "{\"success\":true}");

        assertThat(client.updateCampaign("act_123", TOKEN, "111", "캠페인 & 여름", "PAUSED", 15000L))
                .isEqualTo(new MetaGraphClient.UpdatedAdObject("111", true));

        assertThat(authenticatedForm("111")).containsEntry("name", "캠페인 & 여름")
                .containsEntry("status", "PAUSED").containsEntry("daily_budget", "15000").hasSize(4);
    }

    @Test
    void changesAdSetBudgetWithoutChangingDeliveryStatusOrOtherSettings() {
        response(200, "{\"success\":true}");

        assertThat(client.updateAdSet("act_123", TOKEN, "222", null, null, 20000L))
                .isEqualTo(new MetaGraphClient.UpdatedAdObject("222", true));

        assertThat(authenticatedForm("222")).containsEntry("daily_budget", "20000").hasSize(2)
                .doesNotContainKeys("name", "status", "lifetime_budget", "targeting", "campaign_id");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "PAUSED"})
    void changesOnlyAdStatusWithoutUpdatingParents(String status) {
        response(200, "{\"success\":true}");

        assertThat(client.updateAd("act_123", TOKEN, "333", null, status))
                .isEqualTo(new MetaGraphClient.UpdatedAdObject("333", true));

        assertThat(authenticatedForm("333")).containsEntry("status", status).hasSize(2)
                .doesNotContainKeys("name", "daily_budget", "campaign_id", "adset_id");
    }

    @Test
    void renamingLeavesStatusAndBudgetOmitted() {
        response(200, "{\"success\":true}");

        client.updateAd("act_123", TOKEN, "333", "새 광고 이름", null);

        assertThat(authenticatedForm("333")).containsEntry("name", "새 광고 이름").hasSize(2)
                .doesNotContainKeys("status", "daily_budget");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "act_111", "111/ads", "111?access_token=secret", "-1", "12345678901234567890123456789012345"})
    void invalidObjectIdsFailBeforeAnyRequest(String id) {
        assertBadInput(() -> client.getCampaignForUpdate("act_123", TOKEN, id));
        assertBadInput(() -> client.getAdSetForUpdate("act_123", TOKEN, id));
        assertBadInput(() -> client.verifyAdForUpdate("act_123", TOKEN, id));
        assertBadInput(() -> client.updateCampaign("act_123", TOKEN, id, null, "PAUSED", null));
        assertBadInput(() -> client.updateAdSet("act_123", TOKEN, id, null, "PAUSED", null));
        assertBadInput(() -> client.updateAd("act_123", TOKEN, id, null, "PAUSED"));
        assertThat(requests).isEmpty();
    }

    @Test
    void invalidAccountOrTokenCannotTriggerRequests() {
        assertBadInput(() -> client.getCampaignForUpdate("act_123/ads", TOKEN, "111"));
        assertBadInput(() -> client.getAdSetForUpdate("act_123", " ", "222"));
        assertBadInput(() -> client.verifyAdForUpdate("123", TOKEN, "333"));
        assertBadInput(() -> client.updateCampaign("act_123/ads", TOKEN, "111", null, "ACTIVE", null));
        assertBadInput(() -> client.updateAdSet("act_123", null, "222", null, "ACTIVE", null));
        assertBadInput(() -> client.updateAd("123", TOKEN, "333", null, "PAUSED"));
        assertThat(requests).isEmpty();
    }

    @Test
    void rejectsEmptyPatchesAndInvalidNamesStatusesOrBudgetsBeforeAnyWrite() {
        assertBadInput(() -> client.updateCampaign("act_123", TOKEN, "111", null, null, null));
        assertBadInput(() -> client.updateAdSet("act_123", TOKEN, "222", null, null, null));
        assertBadInput(() -> client.updateAd("act_123", TOKEN, "333", null, null));
        for (String name : List.of("", " ", "x".repeat(256))) {
            assertBadInput(() -> client.updateCampaign("act_123", TOKEN, "111", name, null, null));
            assertBadInput(() -> client.updateAdSet("act_123", TOKEN, "222", name, null, null));
            assertBadInput(() -> client.updateAd("act_123", TOKEN, "333", name, null));
        }
        for (String status : List.of("", "active", "DELETED", "ARCHIVED")) {
            assertBadInput(() -> client.updateCampaign("act_123", TOKEN, "111", null, status, null));
            assertBadInput(() -> client.updateAdSet("act_123", TOKEN, "222", null, status, null));
            assertBadInput(() -> client.updateAd("act_123", TOKEN, "333", null, status));
        }
        for (Long budget : List.of(0L, -1L)) {
            assertBadInput(() -> client.updateCampaign("act_123", TOKEN, "111", null, null, budget));
            assertBadInput(() -> client.updateAdSet("act_123", TOKEN, "222", null, null, budget));
        }
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429})
    void explicitHttpRejectionIsSanitizedAndNeverRetried(int status) {
        response(status, "{\"error\":{\"message\":\"" + TOKEN + " " + SECRET + "\"}}");

        assertThatThrownBy(() -> client.updateAd("act_123", TOKEN, "333", null, "ACTIVE"))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.getCodeIfs()).isEqualTo(status == 429 ? ApiCode.SERVER_ERROR : ApiCode.BAD_REQUEST))
                .hasMessageContaining("거절").hasNoCause().hasMessageNotContaining(TOKEN).hasMessageNotContaining(SECRET);
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"success\":false}", "{\"error\":{\"message\":\"rejected\"}}", "{\"success\":true,\"error\":{}}"})
    void explicitRejectionInSuccessfulHttpResponseIsNotAccepted(String body) {
        response(200, body);

        assertThatThrownBy(() -> client.updateAd("act_123", TOKEN, "333", null, "PAUSED"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST))
                .hasMessageContaining("거절").hasNoCause();
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 500, 502, 503})
    void uncertainHttpResultRequiresCheckingCurrentMetaStateWithoutRetry(int status) {
        response(status, "{\"error\":{\"message\":\"" + TOKEN + "\"}}");

        assertUnknownUpdate();

        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "[]", "true", "null", "{\"success\":\"true\"}", "{\"success\":1}", "{\"success\":null}", "not-json"})
    void missingOrMalformedAcknowledgmentIsUnknownAndNeverRetried(String body) {
        response(200, body);

        assertUnknownUpdate();

        assertThat(requests).hasSize(1);
    }

    @Test
    void emptyNoContentResponseCannotBeReportedAsSuccess() {
        response(204, "");

        assertUnknownUpdate();

        assertThat(requests).hasSize(1);
    }

    @Test
    void timeoutDoesNotRetryOrRetainUnsafeCause() {
        client = new MetaGraphClient(WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.error(new TimeoutException(TOKEN + " " + SECRET));
        }), properties, mapper);

        assertUnknownUpdate();

        assertThat(requests).hasSize(1);
    }

    @Test
    void successAndErrorBodyValuesAreNotDecodedIntoDebugLogs(CapturedOutput output) {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.springframework.http.codec");
        var previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            response(200, "{\"success\":true,\"access_token\":\"" + TOKEN + "\"}");
            client.updateAd("act_123", TOKEN, "333", null, "PAUSED");
            authenticatedForm("333");
            response(400, "{\"error\":{\"message\":\"" + TOKEN + " " + SECRET + "\"}}");
            assertThatThrownBy(() -> client.updateAd("act_123", TOKEN, "333", null, "ACTIVE"))
                    .isInstanceOf(ApiException.class);
            assertThat(output.getAll()).doesNotContain(TOKEN, SECRET);
        } finally {
            logger.setLevel(previous);
        }
    }

    private void assertUnknownUpdate() {
        assertThatThrownBy(() -> client.updateAd("act_123", TOKEN, "333", null, "ACTIVE"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCodeIfs()).isEqualTo(ApiCode.SERVER_ERROR))
                .hasMessage(UNKNOWN_MESSAGE).hasNoCause().hasMessageNotContaining(TOKEN).hasMessageNotContaining(SECRET);
    }

    private void assertInvalidResponse(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getCodeIfs()).isEqualTo(ApiCode.SERVER_ERROR));
    }

    private void assertBadInput(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST));
    }

    private void authenticatedGet(String id, String fields) {
        assertThat(requests).hasSize(1);
        var request = requests.get(0);
        assertThat(request.method()).isEqualTo(HttpMethod.GET);
        assertThat(request.url().getScheme()).isEqualTo("https");
        assertThat(request.url().getHost()).isEqualTo("graph.facebook.com");
        assertThat(request.url().getPath()).isEqualTo("/v26.0/" + id);
        assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
        var query = decodeForm(request.url().getRawQuery());
        assertThat(query).containsEntry("fields", fields).hasSize(2);
        assertThat(query.get("appsecret_proof")).matches("[a-f0-9]{64}");
        assertThat(request.url().toString()).doesNotContain(TOKEN, SECRET);
    }

    private Map<String, String> authenticatedForm(String id) {
        assertThat(requests).hasSize(1);
        var request = requests.get(0);
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.url().toString()).isEqualTo("https://graph.facebook.com/v26.0/" + id);
        assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(request.headers().getContentType()).isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);
        var output = new MockClientHttpRequest(request.method(), request.url());
        request.writeTo(output, strategies).block();
        var form = decodeForm(output.getBodyAsString().block());
        assertThat(form).containsKey("appsecret_proof").doesNotContainKeys("access_token", "client_secret");
        assertThat(form.get("appsecret_proof")).matches("[a-f0-9]{64}");
        assertThat(form.values()).doesNotContain(TOKEN, SECRET);
        return form;
    }

    private Map<String, String> decodeForm(String value) {
        return Arrays.stream(value.split("&")).map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    private void response(int status, String body) {
        responses.add(ClientResponse.create(HttpStatusCode.valueOf(status), strategies)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build());
    }
}
