package com.orinan.api.domain.platformconnection.meta;

import com.orinan.api.common.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class MetaAdCreationClientTest {

    private static final String TOKEN = "creation-private-token-never-log";
    private static final String SECRET = "creation-app-secret-never-log";
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
    void createsPausedTrafficAdSetWithExactBudgetAndFacebookFeedTargeting() {
        response(HttpStatus.OK, "{\"id\":\"222\"}");

        assertThat(client.createAdSet("act_123", TOKEN, "111", adSet("OUTCOME_TRAFFIC", null, false)))
                .isEqualTo(new MetaGraphClient.CreatedAdSet("222"));

        var form = authenticatedForm("adsets");
        assertThat(form).containsEntry("campaign_id", "111").containsEntry("name", "광고 세트")
                .containsEntry("daily_budget", "15000").containsEntry("billing_event", "IMPRESSIONS")
                .containsEntry("optimization_goal", "LINK_CLICKS").containsEntry("destination_type", "WEBSITE")
                .containsEntry("bid_strategy", "LOWEST_COST_WITHOUT_CAP").containsEntry("status", "PAUSED")
                .doesNotContainKeys("promoted_object", "lifetime_budget");
        assertThat(mapper.readTree(form.get("targeting"))).isEqualTo(mapper.readTree("""
                {"geo_locations":{"countries":["KR"]},"age_min":18,"age_max":65,
                 "targeting_automation":{"advantage_audience":0},
                 "publisher_platforms":["facebook"],"facebook_positions":["feed"]}
                """));
    }

    @Test
    void salesUsesPurchasePixelOptimizationAndOptionalInstagramFeed() {
        response(HttpStatus.OK, "{\"id\":\"222\"}");

        client.createAdSet("act_123", TOKEN, "111", adSet("OUTCOME_SALES", "987", true));

        var form = authenticatedForm("adsets");
        assertThat(form).containsEntry("optimization_goal", "OFFSITE_CONVERSIONS");
        assertThat(mapper.readTree(form.get("promoted_object")))
                .isEqualTo(mapper.readTree("{\"pixel_id\":\"987\",\"custom_event_type\":\"PURCHASE\"}"));
        var targeting = mapper.readTree(form.get("targeting"));
        assertThat(targeting.path("publisher_platforms")).isEqualTo(mapper.readTree("[\"facebook\",\"instagram\"]"));
        assertThat(targeting.path("instagram_positions")).isEqualTo(mapper.readTree("[\"stream\"]"));
    }

    @Test
    void imageCreativeUsesVerifiedIdentityAndPublicImageUrlWithoutFetchingItLocally() {
        response(HttpStatus.OK, "{\"id\":\"333\"}");

        assertThat(client.createImageCreative("act_123", TOKEN, creative("456")))
                .isEqualTo(new MetaGraphClient.CreatedCreative("333"));

        var form = authenticatedForm("adcreatives");
        var story = mapper.readTree(form.get("object_story_spec"));
        assertThat(story.path("page_id").asString()).isEqualTo("123");
        assertThat(story.path("instagram_user_id").asString()).isEqualTo("456");
        assertThat(story.has("instagram_actor_id")).isFalse();
        assertThat(story.path("link_data")).isEqualTo(mapper.readTree("""
                {"link":"https://shop.example.com/products/1?source=meta&sale=true",
                 "picture":"https://cdn.example.com/creative.jpg","message":"할인 상품 & 무료 배송",
                 "name":"상품 제목","description":"행사 설명",
                 "call_to_action":{"type":"SHOP_NOW","value":{"link":"https://shop.example.com/products/1?source=meta&sale=true"}}}
                """));
    }

    @Test
    void facebookOnlyCreativeDoesNotInventInstagramIdentity() {
        response(HttpStatus.OK, "{\"id\":\"333\"}");

        client.createImageCreative("act_123", TOKEN, creative(null));

        var story = mapper.readTree(authenticatedForm("adcreatives").get("object_story_spec"));
        assertThat(story.has("instagram_user_id")).isFalse();
    }

    @Test
    void adReferencesNewAdSetAndCreativeAndRemainsPaused() {
        response(HttpStatus.OK, "{\"id\":\"444\"}");

        assertThat(client.createAd("act_123", TOKEN, "상품 광고", "222", "333"))
                .isEqualTo(new MetaGraphClient.CreatedAd("444"));

        assertThat(authenticatedForm("ads")).containsEntry("name", "상품 광고")
                .containsEntry("adset_id", "222").containsEntry("status", "PAUSED")
                .containsEntry("creative", "{\"creative_id\":\"333\"}");
    }

    @Test
    void invalidGoalsPixelsBudgetsAndTargetsFailBeforeAnyWrite() {
        for (var spec : List.of(adSet("OUTCOME_SALES", null, false), adSet("OUTCOME_TRAFFIC", "987", false),
                adSet("OUTCOME_LEADS", null, false),
                new MetaGraphClient.AdSetSpec("광고", "OUTCOME_TRAFFIC", 0, List.of("KR"), 18, 65, null, false),
                new MetaGraphClient.AdSetSpec("광고", "OUTCOME_TRAFFIC", 1000, List.of(), 18, 65, null, false),
                new MetaGraphClient.AdSetSpec("광고", "OUTCOME_TRAFFIC", 1000, List.of("kr"), 18, 65, null, false),
                new MetaGraphClient.AdSetSpec("광고", "OUTCOME_TRAFFIC", 1000, List.of("KR"), 65, 18, null, false))) {
            assertThatThrownBy(() -> client.createAdSet("act_123", TOKEN, "111", spec)).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> client.createAdSet("act_123", TOKEN, "111/ads", adSet("OUTCOME_TRAFFIC", null, false)))
                .isInstanceOf(ApiException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void invalidUrlsIdentityOrAdReferencesCannotTriggerGraphRequests() {
        for (String imageUrl : List.of("file:///etc/passwd", "http://cdn.example.com/image.jpg", "https://user:pass@cdn.example.com/image.jpg")) {
            var spec = new MetaGraphClient.CreativeSpec("소재", "123", null, "https://shop.example.com", imageUrl,
                    "내용", "제목", null, "LEARN_MORE");
            assertThatThrownBy(() -> client.createImageCreative("act_123", TOKEN, spec)).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> client.createImageCreative("act_123", TOKEN, creative("456/ads")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> client.createAd("act_123/ads", TOKEN, "광고", "222", "333"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> client.createAd("act_123", TOKEN, "광고", "222", "333?access_token=x"))
                .isInstanceOf(ApiException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void explicitRejectionsAreDistinguishedFromUnknownWritesAndNeverRetried() {
        for (HttpStatus status : List.of(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED,
                HttpStatus.FORBIDDEN, HttpStatus.TOO_MANY_REQUESTS)) {
            response(status, "{\"error\":{\"message\":\"" + TOKEN + " " + SECRET + "\"}}");
            assertThatThrownBy(() -> client.createAd("act_123", TOKEN, "광고", "222", "333"))
                    .isInstanceOfSatisfying(MetaGraphClient.CreationException.class,
                            error -> assertThat(error.isOutcomeUnknown()).isFalse())
                    .hasNoCause().hasMessageNotContaining(TOKEN).hasMessageNotContaining(SECRET);
        }
        response(HttpStatus.OK, "{\"error\":{\"message\":\"rejected\"}}");
        assertThatThrownBy(() -> client.createAd("act_123", TOKEN, "광고", "222", "333"))
                .isInstanceOfSatisfying(MetaGraphClient.CreationException.class,
                        error -> assertThat(error.isOutcomeUnknown()).isFalse());
        assertThat(requests).hasSize(5);
    }

    @Test
    void uncertainHttpAndMalformedSuccessResponsesKeepUnknownOutcome() {
        for (HttpStatus status : List.of(HttpStatus.REQUEST_TIMEOUT, HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE)) {
            response(status, "{\"error\":{\"message\":\"" + TOKEN + "\"}}");
            assertUnknownAdCreation();
        }
        for (String body : List.of("{}", "{\"id\":123}", "{\"id\":\"bad/id\"}", "[]", "invalid " + TOKEN)) {
            response(HttpStatus.OK, body);
            assertUnknownAdCreation();
        }
        assertThat(requests).hasSize(8);
    }

    @Test
    void failedTransportDoesNotRetryOrRetainUnsafeCause() {
        client = new MetaGraphClient(WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.error(new IllegalStateException(TOKEN + " " + SECRET));
        }), properties, mapper);

        assertUnknownAdCreation();

        assertThat(requests).hasSize(1);
    }

    @Test
    void creationResponseAndErrorBodiesDoNotLeakCredentialsAtDebug(CapturedOutput output) {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.springframework.http.codec");
        var previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            response(HttpStatus.OK, "{\"id\":\"444\",\"access_token\":\"" + TOKEN + "\"}");
            client.createAd("act_123", TOKEN, "광고", "222", "333");
            authenticatedForm("ads");
            response(HttpStatus.BAD_REQUEST, "{\"error\":{\"message\":\"" + TOKEN + " " + SECRET + "\"}}");
            assertThatThrownBy(() -> client.createImageCreative("act_123", TOKEN, creative(null)))
                    .isInstanceOf(MetaGraphClient.CreationException.class);
            assertThat(output.getAll()).doesNotContain(TOKEN, SECRET);
        } finally {
            logger.setLevel(previous);
        }
    }

    private void assertUnknownAdCreation() {
        assertThatThrownBy(() -> client.createAd("act_123", TOKEN, "광고", "222", "333"))
                .isInstanceOfSatisfying(MetaGraphClient.CreationException.class,
                        error -> assertThat(error.isOutcomeUnknown()).isTrue())
                .hasNoCause().hasMessageNotContaining(TOKEN).hasMessageNotContaining(SECRET);
    }

    private MetaGraphClient.AdSetSpec adSet(String objective, String pixelId, boolean instagram) {
        return new MetaGraphClient.AdSetSpec("광고 세트", objective, 15000, List.of("KR", "KR"), 18, 65, pixelId, instagram);
    }

    private MetaGraphClient.CreativeSpec creative(String instagramUserId) {
        return new MetaGraphClient.CreativeSpec("상품 소재", "123", instagramUserId,
                "https://shop.example.com/products/1?source=meta&sale=true", "https://cdn.example.com/creative.jpg",
                "할인 상품 & 무료 배송", "상품 제목", "행사 설명", "SHOP_NOW");
    }

    private Map<String, String> authenticatedForm(String edge) {
        assertThat(requests).hasSize(1);
        var request = requests.get(0);
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.url().toString()).isEqualTo("https://graph.facebook.com/v26.0/act_123/" + edge);
        assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(request.headers().getContentType()).isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);
        var output = new MockClientHttpRequest(request.method(), request.url());
        request.writeTo(output, strategies).block();
        var form = Arrays.stream(output.getBodyAsString().block().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
        assertThat(form).containsKey("appsecret_proof").doesNotContainKeys("access_token", "client_secret");
        assertThat(form.get("appsecret_proof")).matches("[a-f0-9]{64}");
        assertThat(form.values()).doesNotContain(TOKEN, SECRET);
        return form;
    }

    private void response(HttpStatus status, String body) {
        responses.add(ClientResponse.create(status, strategies)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build());
    }
}
