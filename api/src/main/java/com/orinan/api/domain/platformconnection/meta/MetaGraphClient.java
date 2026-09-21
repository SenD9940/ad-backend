package com.orinan.api.domain.platformconnection.meta;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class MetaGraphClient {

    private static final String GRAPH_URL = "https://graph.facebook.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_PAGES = 100;
    private static final List<String> SCOPES = List.of(
            "ads_read", "ads_management", "pages_show_list", "pages_read_engagement", "instagram_basic");

    private final WebClient webClient;
    private final MetaProperties properties;

    public MetaGraphClient(WebClient.Builder builder, MetaProperties properties) {
        this.webClient = builder.clone().build();
        this.properties = properties;
    }

    public String authorizationUrl(String state) {
        properties.validate();
        if (!StringUtils.hasText(state)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "Meta 인증 요청이 올바르지 않습니다.");
        }
        return UriComponentsBuilder.fromUriString("https://www.facebook.com")
                .pathSegment(properties.getApiVersion(), "dialog", "oauth")
                .queryParam("client_id", "{appId}")
                .queryParam("redirect_uri", "{redirectUri}")
                .queryParam("response_type", "code")
                .queryParam("state", "{state}")
                .queryParam("scope", "{scope}")
                .queryParam("auth_type", "rerequest")
                .encode().buildAndExpand(Map.of("appId", properties.getAppId(),
                        "redirectUri", properties.getRedirectUri(), "state", state,
                        "scope", String.join(",", SCOPES))).toUriString();
    }

    public AuthorizedAccount exchangeCode(String code) {
        properties.validate();
        if (!StringUtils.hasText(code)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "Meta 인증 코드가 필요합니다.");
        }
        MultiValueMap<String, String> codeForm = credentials();
        codeForm.add("grant_type", "authorization_code");
        codeForm.add("redirect_uri", properties.getRedirectUri());
        codeForm.add("code", code);
        JsonNode shortToken = exchangeToken(codeForm);

        MultiValueMap<String, String> longTokenForm = credentials();
        longTokenForm.add("grant_type", "fb_exchange_token");
        longTokenForm.add("fb_exchange_token", requiredText(shortToken, "access_token"));
        JsonNode longToken = exchangeToken(longTokenForm);
        String accessToken = requiredText(longToken, "access_token");
        JsonNode expiry = longToken.has("expires_in") ? longToken.path("expires_in") : longToken.path("expires");
        long expiresIn = expiry.isIntegralNumber() ? expiry.asLong(-1) : -1;
        if (expiresIn <= 0 || expiresIn > Integer.MAX_VALUE) {
            throw invalidResponse();
        }
        LocalDateTime expiresAt = SeoulDateTimes.now().plusSeconds(expiresIn);
        JsonNode user = get("me", "id,name", accessToken, null);
        String userId = requiredText(user, "id");
        String name = optionalText(user, "name", userId);

        Set<String> granted = new LinkedHashSet<>();
        for (JsonNode permission : readEdge("me/permissions", "permission,status", accessToken)) {
            if ("granted".equals(requiredText(permission, "status"))) {
                granted.add(requiredText(permission, "permission"));
            }
        }
        if (!granted.containsAll(SCOPES)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "광고 계정과 인스타그램 연결에 필요한 Meta 권한을 모두 허용해 주세요.");
        }
        return new AuthorizedAccount(userId, name, accessToken, expiresAt, String.join(",", granted));
    }

    public List<DiscoveredAsset> discoverAssets(String accessToken) {
        properties.validate();
        if (!StringUtils.hasText(accessToken)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "Meta 계정을 다시 연결해 주세요.");
        }
        List<DiscoveredAsset> assets = new ArrayList<>();
        for (JsonNode account : readEdge("me/adaccounts", "id,name", accessToken)) {
            String id = requiredText(account, "id");
            assets.add(new DiscoveredAsset(id, optionalText(account, "name", id),
                    PlatformType.FACEBOOK, AssetType.AD_ACCOUNT, null));
        }
        for (JsonNode page : readEdge("me/accounts", "id,name,instagram_business_account{id,username,name}", accessToken)) {
            String pageId = requiredText(page, "id");
            assets.add(new DiscoveredAsset(pageId, optionalText(page, "name", pageId),
                    PlatformType.FACEBOOK, AssetType.PAGE, pageId));
            JsonNode instagram = page.path("instagram_business_account");
            if (!instagram.isMissingNode() && !instagram.isNull()) {
                String instagramId = requiredText(instagram, "id");
                String name = optionalText(instagram, "username", optionalText(instagram, "name", instagramId));
                assets.add(new DiscoveredAsset(instagramId, name,
                        PlatformType.INSTAGRAM, AssetType.PROFILE, pageId));
            }
        }
        return List.copyOf(assets);
    }

    private MultiValueMap<String, String> credentials() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.getAppId());
        form.add("client_secret", properties.getAppSecret());
        return form;
    }

    private JsonNode exchangeToken(MultiValueMap<String, String> form) {
        // Facebook supports client_secret_post; form data keeps credentials out of URL logs.
        return readResponse(webClient.post().uri(graphUri("oauth/access_token", null, null))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form)));
    }

    private List<JsonNode> readEdge(String path, String fields, String accessToken) {
        List<JsonNode> entries = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String after = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        for (int page = 0; page < MAX_PAGES; page++) {
            if (System.nanoTime() > deadline) {
                throw new ApiException(ApiCode.SERVER_ERROR, "Meta 목록 조회 시간이 초과되었습니다. 다시 시도해 주세요.");
            }
            JsonNode response = get(path, fields, accessToken, after);
            JsonNode data = response.path("data");
            if (!data.isArray()) {
                throw invalidResponse();
            }
            for (JsonNode item : data) {
                if (!item.isObject()) {
                    throw invalidResponse();
                }
                entries.add(item);
            }
            JsonNode paging = response.path("paging");
            if (!StringUtils.hasText(optionalText(paging, "next", null))) {
                return entries;
            }
            // Never request paging.next: it can contain a token or a different host.
            after = requiredText(paging.path("cursors"), "after");
            if (after.length() > 4096 || !cursors.add(after)) {
                throw invalidResponse();
            }
        }
        throw new ApiException(ApiCode.BAD_REQUEST, "Meta 자산 목록이 조회 한도를 초과했습니다.");
    }

    private JsonNode get(String path, String fields, String accessToken, String after) {
        URI uri = UriComponentsBuilder.fromUri(graphUri(path, fields, after))
                .queryParam("appsecret_proof", appSecretProof(accessToken)).build(true).toUri();
        return readResponse(webClient.get().uri(uri)
                .headers(headers -> headers.setBearerAuth(accessToken)));
    }

    private String appSecretProof(String accessToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getAppSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(accessToken.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new ApiException(ApiCode.SERVER_ERROR, "Meta 요청 인증을 생성하지 못했습니다.");
        }
    }

    private URI graphUri(String path, String fields, String after) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(GRAPH_URL)
                .pathSegment(properties.getApiVersion()).path("/" + path);
        if (fields != null) {
            builder.queryParam("fields", "{fields}");
        }
        if (path.startsWith("me/")) {
            builder.queryParam("limit", 100);
        }
        if (after != null) {
            builder.queryParam("after", "{after}");
        }
        return builder.encode().buildAndExpand(Map.of("fields", fields == null ? "" : fields,
                "after", after == null ? "" : after)).toUri();
    }

    private JsonNode readResponse(WebClient.RequestHeadersSpec<?> request) {
        try {
            JsonNode response = request.exchangeToMono(result -> {
                if (!result.statusCode().is2xxSuccessful()) {
                    ApiCode code = result.statusCode().is4xxClientError() ? ApiCode.BAD_REQUEST : ApiCode.SERVER_ERROR;
                    return result.releaseBody().then(Mono.error(new ApiException(code,
                            "Meta 요청을 처리하지 못했습니다. 계정 권한과 연결 상태를 확인해 주세요.")));
                }
                return result.bodyToMono(JsonNode.class);
            }).block(REQUEST_TIMEOUT);
            if (response == null || !response.isObject() || response.has("error")) {
                throw invalidResponse();
            }
            return response;
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Upstream exceptions can contain tokens, URLs or response bodies; do not retain their cause.
            throw new ApiException(ApiCode.SERVER_ERROR, "Meta 서버와 통신하지 못했습니다. 다시 시도해 주세요.");
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field, null);
        if (!StringUtils.hasText(value)) {
            throw invalidResponse();
        }
        return value;
    }

    private String optionalText(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isString() && StringUtils.hasText(value.asString()) ? value.asString() : fallback;
    }

    private ApiException invalidResponse() {
        return new ApiException(ApiCode.SERVER_ERROR, "Meta 응답이 올바르지 않습니다. 다시 연결해 주세요.");
    }

    public record AuthorizedAccount(String userId, String name, String accessToken,
                                    LocalDateTime expiresAt, String grantedScopes) {
        @Override
        public String toString() {
            return "AuthorizedAccount[userId=" + userId + ", accessToken=REDACTED]";
        }
    }

    public record DiscoveredAsset(String externalId, String name, PlatformType platformType,
                                  AssetType assetType, String facebookPageId) {
    }
}
