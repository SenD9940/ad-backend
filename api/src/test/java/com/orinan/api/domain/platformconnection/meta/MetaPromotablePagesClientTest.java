package com.orinan.api.domain.platformconnection.meta;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetaPromotablePagesClientTest {

    private MetaGraphClient client;
    private final List<ClientRequest> requests = new ArrayList<>();
    private final Queue<ClientResponse> responses = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        MetaProperties properties = new MetaProperties();
        properties.setAppId("123456");
        properties.setAppSecret("app-secret-do-not-log");
        properties.setRedirectUri("https://api.example.com/open-api/platform-connections/meta/callback");
        properties.setFrontendRedirectUri("http://localhost:3400/integrations");
        client = new MetaGraphClient(WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(responses.remove());
        }), properties, JsonMapper.builder().build());
    }

    @Test
    void requestsOnlySelectedAccountsPromotablePagesWithProofAndBearerAuthentication() {
        json("""
                {"data":[{"id":"101","name":"광고용 페이지"},{"id":"102"}]}
                """);

        assertThat(client.listPromotablePages("act_123", "access-token")).containsExactly(
                new MetaGraphClient.DiscoveredAsset("101", "광고용 페이지", PlatformType.FACEBOOK, AssetType.PAGE, "101"),
                new MetaGraphClient.DiscoveredAsset("102", "102", PlatformType.FACEBOOK, AssetType.PAGE, "102"));
        assertThat(requests).hasSize(1);
        assertPromotablePagesRequest(requests.get(0));
        assertThat(URLDecoder.decode(requests.get(0).url().getRawQuery(), StandardCharsets.UTF_8))
                .isEqualTo("fields=id,name&limit=100&appsecret_proof=5e4c60fe14fa98c0853f583a3a3eb3977f041bd9dc6fc5d686d80120a8f60ac1");
    }

    @Test
    void followsEncodedCursorsOnFixedHostAndDeduplicatesPagesWithoutDiscoveringOtherAssets() {
        json("""
                {"data":[{"id":"101","name":"첫 페이지"}],
                 "paging":{"next":"https://untrusted.example/steal?access_token=secret","cursors":{"after":"cursor+/="}}}
                """);
        json("""
                {"data":[{"id":"101","name":"중복 페이지"},
                         {"id":"202","name":"다음 페이지","instagram_business_account":{"id":"303","username":"brand"}}]}
                """);

        List<MetaGraphClient.DiscoveredAsset> pages = client.listPromotablePages("act_123", "access-token");

        assertThat(pages).containsExactly(
                new MetaGraphClient.DiscoveredAsset("101", "첫 페이지", PlatformType.FACEBOOK, AssetType.PAGE, "101"),
                new MetaGraphClient.DiscoveredAsset("202", "다음 페이지", PlatformType.FACEBOOK, AssetType.PAGE, "202"));
        assertThat(requests).hasSize(2).allSatisfy(this::assertPromotablePagesRequest);
        assertThat(requests.get(1).url().getRawQuery()).contains("after=cursor%2B%2F%3D");
    }

    @Test
    void emptyPromotablePagesDoNotFallBackToUsersPages() {
        json("{\"data\":[]}");

        assertThat(client.listPromotablePages("act_123", "access-token")).isEmpty();
        assertThat(requests).hasSize(1).allSatisfy(this::assertPromotablePagesRequest);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 502})
    void secondPageFailureDoesNotReturnPartialResultsOrExposeUpstreamCredentials(int status) {
        json("""
                {"data":[{"id":"101","name":"첫 페이지"}],
                 "paging":{"next":"next","cursors":{"after":"page-two"}}}
                """);
        responses.add(ClientResponse.create(HttpStatus.valueOf(status))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"error\":{\"message\":\"access-token app-secret-do-not-log\"}}")
                .build());

        assertThatThrownBy(() -> client.listPromotablePages("act_123", "access-token"))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(status < 500 && status != 429
                                ? ApiCode.BAD_REQUEST : ApiCode.SERVER_ERROR))
                .hasNoCause().hasMessageNotContaining("access-token").hasMessageNotContaining("app-secret");
        assertThat(requests).hasSize(2).allSatisfy(this::assertPromotablePagesRequest);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "page-101", "act_101", "1/../me", "123456789012345678901234567890123"})
    void rejectsMalformedPageIds(String pageId) {
        json("{\"data\":[{\"id\":\"" + pageId + "\"}]}");

        assertThatThrownBy(() -> client.listPromotablePages("act_123", "access-token"))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.SERVER_ERROR));
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"data\":{}}", "{\"data\":[{}]}", "{\"data\":[{\"id\":101}]}",
            "{\"data\":[null]}", "{\"error\":{\"message\":\"access-token\"}}", "not json: access-token"})
    void rejectsMalformedOrErrorResponsesWithoutLeakingPayload(String response) {
        json(response);

        assertThatThrownBy(() -> client.listPromotablePages("act_123", "access-token"))
                .isInstanceOf(ApiException.class).hasNoCause().hasMessageNotContaining("access-token");
        assertThat(requests).hasSize(1);
    }

    @Test
    void repeatedCursorFailsRatherThanReturningTruncatedPages() {
        String repeated = "{\"data\":[],\"paging\":{\"next\":\"next\",\"cursors\":{\"after\":\"same\"}}}";
        json(repeated);
        json(repeated);

        assertThatThrownBy(() -> client.listPromotablePages("act_123", "access-token"))
                .isInstanceOf(ApiException.class);
        assertThat(requests).hasSize(2).allSatisfy(this::assertPromotablePagesRequest);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "123", "act_", "act_123/../me", "act_123?fields=access_token",
            "act_123456789012345678901234567890123"})
    void rejectsInvalidAdAccountBeforeSendingRequests(String accountId) {
        assertThatThrownBy(() -> client.listPromotablePages(accountId, "access-token"))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST));
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingAccessTokenBeforeSendingRequests(String token) {
        assertThatThrownBy(() -> client.listPromotablePages("act_123", token))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST));
        assertThat(requests).isEmpty();
    }

    private void assertPromotablePagesRequest(ClientRequest request) {
        assertThat(request.method()).isEqualTo(HttpMethod.GET);
        assertThat(request.url().getScheme()).isEqualTo("https");
        assertThat(request.url().getHost()).isEqualTo("graph.facebook.com");
        assertThat(request.url().getPath()).isEqualTo("/v26.0/act_123/promote_pages");
        assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer access-token");
        assertThat(request.url().toString()).doesNotContain("access-token", "app-secret", "access_token=");
        assertThat(request.url().getRawQuery()).contains("limit=100",
                "appsecret_proof=5e4c60fe14fa98c0853f583a3a3eb3977f041bd9dc6fc5d686d80120a8f60ac1");
    }

    private void json(String body) {
        responses.add(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build());
    }
}
