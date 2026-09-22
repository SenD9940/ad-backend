package com.orinan.api.domain.platformconnection.meta;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetaGraphClientTest {

    private MetaProperties properties;
    private MetaGraphClient client;
    private final List<ClientRequest> requests = new ArrayList<>();
    private final Queue<ClientResponse> responses = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        properties.setAppId("123456");
        properties.setAppSecret("app-secret-do-not-log");
        properties.setRedirectUri("https://api.example.com/open-api/platform-connections/meta/callback");
        properties.setFrontendRedirectUri("http://localhost:3000/integrations");
        client = new MetaGraphClient(WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(responses.remove());
        }), properties, JsonMapper.builder().build());
    }

    @Test
    void authorizationUrlContainsStateAndRequiredPermissionsWithoutSecret() {
        String url = client.authorizationUrl("browser-specific-state");

        assertThat(url).startsWith("https://www.facebook.com/v26.0/dialog/oauth?")
                .contains("state=browser-specific-state", "response_type=code")
                .doesNotContain(properties.getAppSecret());
        assertThat(URLDecoder.decode(url, StandardCharsets.UTF_8))
                .contains("redirect_uri=" + properties.getRedirectUri())
                .contains("scope=ads_read,ads_management,pages_show_list,pages_read_engagement,instagram_basic");
        assertThat(requests).isEmpty();
    }

    @Test
    void exchangesCodeAndLongLivedTokenInFormsAndChecksActualPermissions() {
        tokenResponses();
        json("{\"id\":\"fb-user\",\"name\":\"연동 계정\"}");
        allPermissions();
        LocalDateTime before = SeoulDateTimes.now();

        MetaGraphClient.AuthorizedAccount account = client.exchangeCode("one-time-code");

        assertThat(account.userId()).isEqualTo("fb-user");
        assertThat(account.name()).isEqualTo("연동 계정");
        assertThat(account.accessToken()).isEqualTo("long-lived-token");
        assertThat(account.expiresAt()).isBetween(before.plusSeconds(3600), SeoulDateTimes.now().plusSeconds(3600));
        assertThat(account.grantedScopes()).contains("ads_management", "instagram_basic");
        assertThat(account.toString()).doesNotContain("long-lived-token");
        assertThat(properties.toString()).doesNotContain(properties.getAppSecret());
        assertThat(requests).hasSize(4);
        assertThat(requests.get(0).method()).isEqualTo(HttpMethod.POST);
        assertThat(requests.get(1).method()).isEqualTo(HttpMethod.POST);
        assertThat(formBody(requests.get(0))).contains("code=one-time-code", "client_secret=app-secret-do-not-log");
        assertThat(formBody(requests.get(1))).contains("grant_type=fb_exchange_token", "fb_exchange_token=short-lived-token");
        assertThat(requests).allSatisfy(request -> assertThat(request.url().toString())
                .doesNotContain("app-secret-do-not-log", "client_secret=", "access_token=", "one-time-code"));
        assertThat(requests.subList(2, 4)).allSatisfy(request ->
                assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer long-lived-token"));
    }

    @Test
    void declinedPermissionPreventsConnectionEvenWithValidToken() {
        tokenResponses();
        json("{\"id\":\"fb-user\"}");
        json("{\"data\":[{\"permission\":\"ads_management\",\"status\":\"declined\"}]}");

        assertThatThrownBy(() -> client.exchangeCode("code"))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST))
                .hasMessageContaining("권한");
    }

    @Test
    void missingTokenExpiryIsRejectedBeforeLookingUpUser() {
        json("{\"access_token\":\"short-lived-token\"}");
        json("{\"access_token\":\"long-lived-token\"}");

        assertThatThrownBy(() -> client.exchangeCode("code"))
                .isInstanceOf(ApiException.class).hasMessageContaining("응답");
        assertThat(requests).hasSize(2);
    }

    @Test
    void discoversAllAssetsUsingCursorOnFixedHostAndKeepsInstagramParentPage() {
        json("""
                {"data":[{"id":"act_11","name":"첫 광고 계정"}],
                 "paging":{"next":"https://untrusted.example/steal?access_token=secret", "cursors":{"after":"cursor+/="}}}
                """);
        json("{\"data\":[{\"id\":\"act_22\",\"name\":\"두번째 광고 계정\"}]}");
        json("""
                {"data":[{"id":"page-1","name":"페이지","instagram_business_account":{"id":"ig-1","username":"mybrand"}},
                         {"id":"page-2","name":"인스타 없는 페이지"}]}
                """);

        List<MetaGraphClient.DiscoveredAsset> assets = client.discoverAssets("access-token");

        assertThat(assets).hasSize(5);
        assertThat(assets).anySatisfy(asset -> {
            assertThat(asset.externalId()).isEqualTo("ig-1");
            assertThat(asset.name()).isEqualTo("mybrand");
            assertThat(asset.platformType()).isEqualTo(PlatformType.INSTAGRAM);
            assertThat(asset.assetType()).isEqualTo(AssetType.PROFILE);
            assertThat(asset.facebookPageId()).isEqualTo("page-1");
        });
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.url().getHost()).isEqualTo("graph.facebook.com");
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer access-token");
            assertThat(request.url().toString()).doesNotContain("access-token");
            assertThat(request.url().getRawQuery()).contains(
                    "appsecret_proof=5e4c60fe14fa98c0853f583a3a3eb3977f041bd9dc6fc5d686d80120a8f60ac1");
        });
        assertThat(requests.get(1).url().getRawQuery()).contains("after=cursor%2B%2F%3D");
    }

    @Test
    void pageFailureNeverReturnsPartialAdAccountsAndDoesNotExposeUpstreamBody() {
        json("{\"data\":[{\"id\":\"act_11\"}]}");
        responses.add(ClientResponse.create(HttpStatus.BAD_REQUEST)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"error\":{\"message\":\"leaked access-token and app-secret\"}}").build());

        assertThatThrownBy(() -> client.discoverAssets("access-token"))
                .isInstanceOf(ApiException.class)
                .hasNoCause()
                .hasMessageNotContaining("access-token")
                .hasMessageNotContaining("app-secret");
    }

    @Test
    void repeatingCursorAndMissingDataFailInsteadOfSilentlyTruncating() {
        String repeatingPage = "{\"data\":[],\"paging\":{\"next\":\"next\",\"cursors\":{\"after\":\"same\"}}}";
        json(repeatingPage);
        json(repeatingPage);
        assertThatThrownBy(() -> client.discoverAssets("access-token")).isInstanceOf(ApiException.class);
        assertThat(requests).hasSize(2);

        json("{\"paging\":{}}");
        assertThatThrownBy(() -> client.discoverAssets("access-token")).isInstanceOf(ApiException.class);
    }

    @Test
    void invalidOrEmptyResponsesAreSanitized() {
        json("not json: access-token");
        assertThatThrownBy(() -> client.discoverAssets("access-token"))
                .isInstanceOf(ApiException.class).hasNoCause().hasMessageNotContaining("access-token");
        json("");
        assertThatThrownBy(() -> client.discoverAssets("access-token")).isInstanceOf(ApiException.class);
    }

    @Test
    void configurationIsCheckedOnlyWhenUsedAndRejectsUnsafeRedirects() {
        MetaProperties empty = new MetaProperties();
        new MetaGraphClient(WebClient.builder(), empty, JsonMapper.builder().build());
        assertThatThrownBy(empty::validate).isInstanceOf(ApiException.class);
        properties.setFrontendRedirectUri("javascript:alert(1)");
        assertThatThrownBy(() -> client.authorizationUrl("state")).isInstanceOf(ApiException.class);
        properties.setFrontendRedirectUri("http://example.com/callback");
        assertThatThrownBy(properties::validate).isInstanceOf(ApiException.class);
        properties.setFrontendRedirectUri("https://user:password@example.com/callback");
        assertThatThrownBy(properties::validate).isInstanceOf(ApiException.class);
        properties.setFrontendRedirectUri("http://localhost:3000/callback");
        properties.validate();
        properties.setApiVersion("v26.0/../../somewhere");
        assertThatThrownBy(properties::validate).isInstanceOf(ApiException.class);
        assertThat(requests).isEmpty();
    }

    private void tokenResponses() {
        json("{\"access_token\":\"short-lived-token\",\"expires_in\":60}");
        json("{\"access_token\":\"long-lived-token\",\"expires_in\":3600}");
    }

    private void allPermissions() {
        json("""
                {"data":[{"permission":"ads_read","status":"granted"},
                         {"permission":"ads_management","status":"granted"},
                         {"permission":"pages_show_list","status":"granted"},
                         {"permission":"pages_read_engagement","status":"granted"},
                         {"permission":"instagram_basic","status":"granted"}]}
                """);
    }

    private void json(String body) {
        responses.add(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build());
    }

    private String formBody(ClientRequest request) {
        MockClientHttpRequest output = new MockClientHttpRequest(request.method(), URI.create("https://example.com"));
        request.writeTo(output, ExchangeStrategies.withDefaults()).block();
        return output.getBodyAsString().block();
    }
}
