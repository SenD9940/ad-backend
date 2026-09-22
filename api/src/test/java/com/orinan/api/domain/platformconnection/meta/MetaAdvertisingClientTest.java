package com.orinan.api.domain.platformconnection.meta;

import com.orinan.api.common.code.ApiCode;
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
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
class MetaAdvertisingClientTest {

    private static final String TOKEN = "meta-private-token-never-log";
    private static final String SECRET = "meta-private-app-secret-never-log";
    private static final LocalDate SINCE = LocalDate.of(2026, 8, 1);
    private static final LocalDate UNTIL = LocalDate.of(2026, 8, 31);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<ClientRequest> requests = new ArrayList<>();
    private final Queue<ClientResponse> responses = new ArrayDeque<>();
    private MetaGraphClient client;
    private MetaProperties properties;
    private ExchangeStrategies strategies;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        properties.setAppId("1234");
        properties.setAppSecret(SECRET);
        properties.setRedirectUri("https://api.example.com/callback");
        properties.setFrontendRedirectUri("https://app.example.com/integrations");
        strategies = ExchangeStrategies.builder().codecs(codecs -> codecs.defaultCodecs()
                .jacksonJsonDecoder(new JacksonJsonDecoder(mapper))).build();
        client = new MetaGraphClient(WebClient.builder().exchangeStrategies(strategies).exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(responses.remove());
        }), properties, mapper);
    }

    @Test
    void loadsVerifiedAccountCurrencyAndTimezoneWithBearerAuthentication() {
        json("{\"id\":\"act_123\",\"name\":\"스토어 광고\",\"currency\":\"KRW\",\"timezone_name\":\"Asia/Seoul\"}");

        assertThat(client.getAdAccount("act_123", TOKEN))
                .isEqualTo(new MetaGraphClient.AdAccount("act_123", "스토어 광고", "KRW", "Asia/Seoul"));
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).url().getPath()).isEqualTo("/v26.0/act_123");
        assertThat(query(requests.get(0))).containsEntry("fields", "id,name,currency,timezone_name");
        assertAuthenticatedRequests();
    }

    @Test
    void rejectsWrongAccountOrInvalidCurrencyInsteadOfMixingTotals() {
        for (String body : List.of(
                "{\"id\":\"act_456\",\"name\":\"wrong\",\"currency\":\"KRW\",\"timezone_name\":\"Asia/Seoul\"}",
                "{\"id\":\"act_123\",\"name\":\"wrong\",\"currency\":\"usd\",\"timezone_name\":\"Asia/Seoul\"}",
                "{\"id\":\"act_123\",\"name\":\"wrong\",\"currency\":null,\"timezone_name\":\"Asia/Seoul\"}")) {
            json(body);
            assertThatThrownBy(() -> client.getAdAccount("act_123", TOKEN)).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void campaignsFollowEveryCursorOnlyOnFixedHostAndKeepStatuses() {
        json("{\"data\":[" + campaign("111") + "],\"paging\":{\"next\":\"https://evil.example/?access_token=private\","
                + "\"cursors\":{\"after\":\"cursor+/=\"}}}");
        json("{\"data\":[" + campaign("222") + "]}");

        assertThat(client.listCampaigns("act_123", TOKEN)).containsExactly(
                new MetaGraphClient.Campaign("111", "캠페인 111", "PAUSED", "PAUSED", "OUTCOME_SALES"),
                new MetaGraphClient.Campaign("222", "캠페인 222", "PAUSED", "PAUSED", "OUTCOME_SALES"));
        assertThat(requests).hasSize(2);
        assertThat(query(requests.get(1))).containsEntry("after", "cursor+/=")
                .containsEntry("fields", "id,account_id,name,status,effective_status,objective").containsEntry("limit", "100");
        assertThat(requests).allSatisfy(request -> assertThat(request.url().getPath()).isEqualTo("/v26.0/act_123/campaigns"));
        assertAuthenticatedRequests();
    }

    @Test
    void insightsKeepLevelAndDateRangeOnAllPagesAndParseDecimalStringsExactly() {
        json("{\"data\":[" + insight("111", "\"19.99\"", "\"300\"", "\"7\"") + "],"
                + "\"paging\":{\"next\":\"https://evil.example/\",\"cursors\":{\"after\":\"next-page\"}}}");
        json("{\"data\":[" + insight("222", "0", "0", "0") + "]}");

        assertThat(client.getCampaignInsights("act_123", TOKEN, SINCE, UNTIL)).containsExactly(
                new MetaGraphClient.Insights("111", "캠페인 111", new BigDecimal("19.99"), 300, 7, BigDecimal.ZERO),
                new MetaGraphClient.Insights("222", "캠페인 222", BigDecimal.ZERO, 0, 0, BigDecimal.ZERO));
        assertThat(requests).hasSize(2).allSatisfy(request -> {
            Map<String, String> query = query(request);
            assertThat(query).containsEntry("level", "campaign").containsEntry("limit", "100")
                    .containsEntry("fields", "account_id,campaign_id,campaign_name,spend,impressions,clicks,action_values")
                    .doesNotContainKeys("date_preset", "time_increment", "breakdowns", "access_token");
            assertThat(mapper.readTree(query.get("time_range")).path("since").asString()).isEqualTo("2026-08-01");
            assertThat(mapper.readTree(query.get("time_range")).path("until").asString()).isEqualTo("2026-08-31");
        });
        assertThat(query(requests.get(1))).containsEntry("after", "next-page");
        assertAuthenticatedRequests();
    }

    @Test
    void accountInsightsRequestAccountLevelWithoutCampaignDimensions() {
        json("{\"data\":[{\"account_id\":\"123\",\"spend\":\"100.50\",\"impressions\":\"800\",\"clicks\":\"10\"}]}");

        assertThat(client.getAccountInsights("act_123", TOKEN, SINCE, UNTIL)).containsExactly(
                new MetaGraphClient.Insights(null, null, new BigDecimal("100.50"), 800, 10, BigDecimal.ZERO));
        assertThat(query(requests.get(0))).containsEntry("level", "account")
                .containsEntry("fields", "account_id,spend,impressions,clicks,action_values");
    }

    @Test
    void purchaseValueUsesOneAggregateInsteadOfSummingOverlappingPurchaseTypes() {
        String values = """
                [{"action_type":"purchase","value":"300.25"},
                 {"action_type":"offsite_conversion.fb_pixel_purchase","value":"200.25"},
                 {"action_type":"omni_purchase","value":"350.75"},
                 {"action_type":"app_custom_event.fb_mobile_purchase","value":"100"},
                 {"action_type":"add_to_cart","value":"9000"}]
                """;
        purchaseResponse(values);
        assertThat(client.getAccountInsights("act_123", TOKEN, SINCE, UNTIL)).singleElement()
                .satisfies(row -> assertThat(row.purchaseValue()).isEqualByComparingTo("350.75"));
        purchaseResponse(values);
        assertThat(client.getCampaignInsights("act_123", TOKEN, SINCE, UNTIL)).singleElement()
                .satisfies(row -> assertThat(row.purchaseValue()).isEqualByComparingTo("350.75"));
    }

    @Test
    void purchaseAliasIsUsedOnlyWhenOmniPurchaseIsAbsentIncludingExplicitZero() {
        purchaseResponse("[{\"action_type\":\"purchase\",\"value\":123.45}]");
        assertThat(client.getAccountInsights("act_123", TOKEN, SINCE, UNTIL).get(0).purchaseValue())
                .isEqualByComparingTo("123.45");
        purchaseResponse("""
                [{"action_type":"omni_purchase","value":"0"},
                 {"action_type":"purchase","value":"123.45"}]
                """);
        assertThat(client.getAccountInsights("act_123", TOKEN, SINCE, UNTIL).get(0).purchaseValue()).isZero();
    }

    @Test
    void noReportedPurchaseValueDefaultsToZero() {
        for (String values : List.of("null", "[]", "[{\"action_type\":\"add_to_cart\",\"value\":\"999\"}]")) {
            purchaseResponse(values);
            assertThat(client.getAccountInsights("act_123", TOKEN, SINCE, UNTIL).get(0).purchaseValue()).isZero();
        }
    }

    @Test
    void malformedOrDuplicatePurchaseValuesAreRejectedInsteadOfReturningWrongRoas() {
        for (String values : List.of("{}", "0", "[null]", "[{}]",
                "[{\"action_type\":\"omni_purchase\"}]",
                "[{\"action_type\":\"omni_purchase\",\"value\":\"-1\"}]",
                "[{\"action_type\":\"purchase\",\"value\":\"NaN\"}]",
                "[{\"action_type\":\"omni_purchase\",\"value\":\"1e100000\"}]",
                "[{\"action_type\":\"purchase\",\"value\":null}]",
                "[{\"action_type\":\"omni_purchase\",\"value\":\"10\"},{\"action_type\":\"omni_purchase\",\"value\":\"20\"}]",
                "[{\"action_type\":\"purchase\",\"value\":\"10\"},{\"action_type\":\"purchase\",\"value\":\"20\"}]")) {
            purchaseResponse(values);
            assertThatThrownBy(() -> client.getAccountInsights("act_123", TOKEN, SINCE, UNTIL))
                    .isInstanceOf(ApiException.class).hasNoCause();
        }
    }

    private void purchaseResponse(String values) {
        json("{\"data\":[{\"account_id\":\"123\",\"campaign_id\":\"111\",\"campaign_name\":\"구매 캠페인\","
                + "\"spend\":\"100\",\"impressions\":\"800\",\"clicks\":\"10\",\"action_values\":" + values + "}]}");
    }

    @Test
    void emptyCampaignsAndNoDeliveryRemainEmpty() {
        json("{\"data\":[]}");
        json("{\"data\":[]}");
        json("{\"data\":[]}");

        assertThat(client.listCampaigns("act_123", TOKEN)).isEmpty();
        assertThat(client.getCampaignInsights("act_123", TOKEN, SINCE, UNTIL)).isEmpty();
        assertThat(client.getAccountInsights("act_123", TOKEN, SINCE, UNTIL)).isEmpty();
    }

    @Test
    void rejectsInvalidAccountPathsMissingTokenAndInvalidDatesBeforeHttp() {
        for (String id : List.of("123", "act_123/insights", "act_../me", "act_123?fields=access_token", "act_")) {
            assertThatThrownBy(() -> client.listCampaigns(id, TOKEN)).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> client.getAdAccount("act_123", " ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> client.getCampaignInsights("act_123", TOKEN, UNTIL, SINCE)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> client.getAccountInsights("act_123", TOKEN, null, UNTIL)).isInstanceOf(ApiException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void rejectsNegativeMalformedFractionalOrOverflowingMetrics() {
        for (String spend : List.of("\"-1\"", "-1", "\"NaN\"", "\"1e100000\"", "null", "{}")) {
            json("{\"data\":[" + insight("111", spend, "\"10\"", "\"2\"") + "]}");
            assertThatThrownBy(() -> client.getCampaignInsights("act_123", TOKEN, SINCE, UNTIL)).isInstanceOf(ApiException.class);
        }
        for (String count : List.of("\"-1\"", "-1", "\"1.5\"", "1.5", "\"9223372036854775808\"", "9223372036854775808", "null")) {
            json("{\"data\":[" + insight("111", "\"1\"", count, "\"2\"") + "]}");
            assertThatThrownBy(() -> client.getCampaignInsights("act_123", TOKEN, SINCE, UNTIL)).isInstanceOf(ApiException.class);
            json("{\"data\":[" + insight("111", "\"1\"", "\"10\"", count) + "]}");
            assertThatThrownBy(() -> client.getCampaignInsights("act_123", TOKEN, SINCE, UNTIL)).isInstanceOf(ApiException.class);
        }
        json("{\"data\":[{\"account_id\":\"123\",\"spend\":\"1\",\"impressions\":\"1\"}]}");
        assertThatThrownBy(() -> client.getAccountInsights("act_123", TOKEN, SINCE, UNTIL)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsDuplicateCampaignsOrInsightRowsToPreventDoubleCounting() {
        json("{\"data\":[" + campaign("111") + "," + campaign("111") + "]}");
        assertThatThrownBy(() -> client.listCampaigns("act_123", TOKEN)).isInstanceOf(ApiException.class);
        String row = insight("111", "\"1\"", "\"10\"", "\"2\"");
        json("{\"data\":[" + row + "," + row + "]}");
        assertThatThrownBy(() -> client.getCampaignInsights("act_123", TOKEN, SINCE, UNTIL)).isInstanceOf(ApiException.class);
        json("{\"data\":[" + row + "," + row + "]}");
        assertThatThrownBy(() -> client.getAccountInsights("act_123", TOKEN, SINCE, UNTIL)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsCampaignsAndInsightsFromAnotherAccount() {
        json("{\"data\":[" + campaign("111").replace("\"account_id\":\"123\"", "\"account_id\":\"456\"") + "]}");
        assertThatThrownBy(() -> client.listCampaigns("act_123", TOKEN)).isInstanceOf(ApiException.class);
        json("{\"data\":[" + insight("111", "\"1\"", "\"10\"", "\"2\"")
                .replace("\"account_id\":\"123\"", "\"account_id\":\"456\"") + "]}");
        assertThatThrownBy(() -> client.getCampaignInsights("act_123", TOKEN, SINCE, UNTIL)).isInstanceOf(ApiException.class);
    }

    @Test
    void failedLaterPageNeverReturnsPartialReportOrUpstreamSecrets() {
        json("{\"data\":[" + insight("111", "\"1\"", "\"10\"", "\"2\"")
                + "],\"paging\":{\"next\":\"next\",\"cursors\":{\"after\":\"more\"}}}");
        response(HttpStatus.FORBIDDEN, "{\"error\":{\"message\":\"" + TOKEN + "\"}}");

        assertThatThrownBy(() -> client.getCampaignInsights("act_123", TOKEN, SINCE, UNTIL))
                .isInstanceOf(ApiException.class).hasNoCause().hasMessageNotContaining(TOKEN);
        assertThat(requests).hasSize(2);
    }

    @Test
    void refusesMalformedPagingAndBoundedPaginationInsteadOfReturningIncompleteData() {
        json("{\"data\":[],\"paging\":{\"next\":{}}}");
        assertThatThrownBy(() -> client.listCampaigns("act_123", TOKEN)).isInstanceOf(ApiException.class);
        json("{\"data\":[],\"paging\":42}");
        assertThatThrownBy(() -> client.listCampaigns("act_123", TOKEN)).isInstanceOf(ApiException.class);
        for (int page = 0; page < 100; page++) {
            json("{\"data\":[],\"paging\":{\"next\":\"next\",\"cursors\":{\"after\":\"" + page + "\"}}}");
        }
        assertThatThrownBy(() -> client.getCampaignInsights("act_123", TOKEN, SINCE, UNTIL)).isInstanceOf(ApiException.class);
        assertThat(requests).hasSize(102);
    }

    @Test
    void authRateLimitAndServerFailuresAreSanitizedAndNeverRetried() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN,
                HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE)) {
            response(status, "{\"error\":{\"message\":\"" + TOKEN + " " + SECRET + "\"}}");
            assertThatThrownBy(() -> client.getAdAccount("act_123", TOKEN))
                    .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getCodeIfs())
                            .isEqualTo(status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN
                                    ? ApiCode.BAD_REQUEST : ApiCode.SERVER_ERROR))
                    .hasNoCause().hasMessageNotContaining(TOKEN).hasMessageNotContaining(SECRET);
        }
        assertThat(requests).hasSize(4);
    }

    @Test
    void successfulTokenExchangeAndMalformedBodiesNeverExposeTokensAtDebug(CapturedOutput output) {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.springframework");
        var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            json("{\"access_token\":\"" + TOKEN + "-short\"}");
            json("{\"access_token\":\"" + TOKEN + "\",\"expires_in\":3600}");
            json("{\"id\":\"100\",\"name\":\"사용자\"}");
            json("""
                    {"data":[{"permission":"ads_read","status":"granted"},
                    {"permission":"ads_management","status":"granted"},
                    {"permission":"pages_show_list","status":"granted"},
                    {"permission":"pages_read_engagement","status":"granted"},
                    {"permission":"instagram_basic","status":"granted"}]}
                    """);
            assertThat(client.exchangeCode("private-code").accessToken()).isEqualTo(TOKEN);
            for (ClientRequest request : requests.subList(0, 2)) {
                request.writeTo(new MockClientHttpRequest(request.method(), request.url()), strategies).block();
            }
            json("invalid json containing " + TOKEN);
            assertThatThrownBy(() -> client.listCampaigns("act_123", TOKEN)).isInstanceOf(ApiException.class).hasNoCause();
            response(HttpStatus.UNAUTHORIZED, "{\"error\":{\"message\":\"" + TOKEN + "\"}}");
            assertThatThrownBy(() -> client.getAdAccount("act_123", TOKEN)).isInstanceOf(ApiException.class).hasNoCause();
            assertThat(output.getAll()).doesNotContain(TOKEN, SECRET, "private-code");
        } finally {
            logger.setLevel(previousLevel);
        }
    }

    private String campaign(String id) {
        return "{\"id\":\"" + id + "\",\"account_id\":\"123\",\"name\":\"캠페인 " + id
                + "\",\"status\":\"PAUSED\",\"effective_status\":\"PAUSED\",\"objective\":\"OUTCOME_SALES\"}";
    }

    private String insight(String id, String spend, String impressions, String clicks) {
        return "{\"account_id\":\"123\",\"campaign_id\":\"" + id + "\",\"campaign_name\":\"캠페인 " + id
                + "\",\"spend\":" + spend + ",\"impressions\":" + impressions + ",\"clicks\":" + clicks + "}";
    }

    private void assertAuthenticatedRequests() {
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo(HttpMethod.GET);
            assertThat(request.url().getHost()).isEqualTo("graph.facebook.com");
            assertThat(request.url().toString()).doesNotContain(TOKEN, SECRET, "access_token=");
            assertThat(query(request)).containsKey("appsecret_proof");
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
        });
    }

    private Map<String, String> query(ClientRequest request) {
        return Arrays.stream(request.url().getRawQuery().split("&")).map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    private void json(String body) {
        response(HttpStatus.OK, body);
    }

    private void response(HttpStatus status, String body) {
        responses.add(ClientResponse.create(status, strategies)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build());
    }
}
