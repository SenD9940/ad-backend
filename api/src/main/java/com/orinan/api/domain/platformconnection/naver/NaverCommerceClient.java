package com.orinan.api.domain.platformconnection.naver;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class NaverCommerceClient {

    private static final String BASE_URL = "https://api.commerce.naver.com/external";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern BCRYPT_SALT = Pattern.compile("\\$2[aby]\\$(\\d{2})\\$[./A-Za-z0-9]{22}");

    private final WebClient webClient;
    private final JsonMapper jsonMapper;

    public NaverCommerceClient(WebClient.Builder builder, JsonMapper jsonMapper) {
        this.webClient = builder.clone().build();
        this.jsonMapper = jsonMapper;
    }

    public IssuedToken issueToken(String clientId, String clientSecret, NaverTokenType type, String accountId) {
        long timestamp = System.currentTimeMillis();
        String password = clientId + "_" + timestamp;
        Matcher salt = BCRYPT_SALT.matcher(clientSecret == null ? "" : clientSecret);
        if (!StringUtils.hasText(clientId) || type == null
                || type == NaverTokenType.SELLER && !StringUtils.hasText(accountId)
                || type == NaverTokenType.SELF && StringUtils.hasText(accountId)
                || password.getBytes(StandardCharsets.UTF_8).length > 72
                || !salt.matches()) {
            throw invalidCredentials();
        }
        // The salt comes from the caller; bound its work factor before invoking BCrypt.
        int cost = Integer.parseInt(salt.group(1));
        if (cost < 4 || cost > 14) {
            throw invalidCredentials();
        }

        String signature;
        try {
            signature = Base64.getEncoder().encodeToString(
                    BCrypt.hashpw(password, clientSecret).getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            throw invalidCredentials();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("timestamp", Long.toString(timestamp));
        form.add("grant_type", "client_credentials");
        form.add("client_secret_sign", signature);
        form.add("type", type.name());
        if (type == NaverTokenType.SELLER) {
            form.add("account_id", accountId);
        }
        LocalDateTime requestedAt = SeoulDateTimes.now();
        JsonNode response = readResponse(webClient.post().uri(BASE_URL + "/v1/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form)), true);
        String accessToken = requiredText(response, "access_token", 16384);
        if (!"Bearer".equalsIgnoreCase(requiredText(response, "token_type", 30))) {
            throw invalidResponse();
        }
        JsonNode expiry = response.path("expires_in");
        if (!expiry.isIntegralNumber() || !expiry.canConvertToLong()
                || expiry.asLong() <= 0 || expiry.asLong() > Integer.MAX_VALUE) {
            throw invalidResponse();
        }
        return new IssuedToken(accessToken, requestedAt.plusSeconds(expiry.asLong()));
    }

    public SellerAccount getSellerAccount(String accessToken) {
        JsonNode account = get("/v1/seller/account", accessToken);
        return new SellerAccount(requiredText(account, "accountId", 255),
                requiredText(account, "accountUid", 255));
    }

    public List<Channel> getChannels(String accessToken) {
        JsonNode response = get("/v1/seller/channels", accessToken);
        // The published schema is a channel object; normalize list responses as well.
        if (response.isObject()) {
            return List.of(channel(response));
        }
        if (!response.isArray()) {
            throw invalidResponse();
        }
        List<Channel> channels = new ArrayList<>();
        Set<Long> channelNumbers = new HashSet<>();
        for (JsonNode item : response) {
            Channel channel = channel(item);
            if (!channelNumbers.add(channel.channelNo())) {
                throw invalidResponse();
            }
            channels.add(channel);
        }
        return List.copyOf(channels);
    }

    private Channel channel(JsonNode node) {
        JsonNode number = node.path("channelNo");
        if (!number.isIntegralNumber() || !number.canConvertToLong() || number.asLong() <= 0) {
            throw invalidResponse();
        }
        String type = requiredText(node, "channelType", 30);
        if (!Set.of("STOREFARM", "WINDOW").contains(type)) {
            throw invalidResponse();
        }
        return new Channel(number.asLong(), type, requiredText(node, "name", 255),
                optionalText(node, "url", 2048));
    }

    private JsonNode get(String path, String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "네이버 스마트스토어를 다시 연결해 주세요.");
        }
        return readResponse(webClient.get().uri(BASE_URL + path)
                .headers(headers -> headers.setBearerAuth(accessToken)), false);
    }

    private JsonNode readResponse(WebClient.RequestHeadersSpec<?> request, boolean tokenRequest) {
        try {
            JsonNode response = request.exchangeToMono(result -> {
                if (result.statusCode().is2xxSuccessful()) {
                    // The JSON decoder logs decoded values at DEBUG, including access tokens.
                    return result.bodyToMono(byte[].class).map(jsonMapper::readTree);
                }
                int status = result.statusCode().value();
                if (status == 401 && !tokenRequest) {
                    return result.bodyToMono(byte[].class).map(jsonMapper::readTree)
                            .flatMap(body -> Mono.<JsonNode>error("GW.AUTHN".equals(body.path("code").asString())
                                    ? new AuthenticationException() : failedRequest(status)))
                            .switchIfEmpty(Mono.error(failedRequest(status)));
                }
                return result.releaseBody().then(Mono.error(failedRequest(status)));
            }).block(REQUEST_TIMEOUT);
            if (response == null || response.isNull()) {
                throw invalidResponse();
            }
            return response;
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Transport and decoding exceptions can include credentials or upstream bodies.
            throw new ApiException(ApiCode.SERVER_ERROR, "네이버 서버와 통신하지 못했습니다. 다시 시도해 주세요.");
        }
    }

    private ApiException failedRequest(int status) {
        if (status == 429 || status >= 500) {
            return new ApiException(ApiCode.SERVER_ERROR, "네이버 요청이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.");
        }
        return new ApiException(ApiCode.BAD_REQUEST, "네이버 요청을 처리하지 못했습니다. 애플리케이션 정보와 판매자 권한을 확인해 주세요.");
    }

    private String requiredText(JsonNode node, String field, int maxLength) {
        String value = optionalText(node, field, maxLength);
        if (!StringUtils.hasText(value)) {
            throw invalidResponse();
        }
        return value;
    }

    private String optionalText(JsonNode node, String field, int maxLength) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isString() || value.asString().length() > maxLength) {
            throw invalidResponse();
        }
        return value.asString();
    }

    private ApiException invalidCredentials() {
        return new ApiException(ApiCode.BAD_REQUEST, "네이버 애플리케이션 ID, 시크릿 및 인증 유형을 확인해 주세요.");
    }

    private ApiException invalidResponse() {
        return new ApiException(ApiCode.SERVER_ERROR, "네이버 응답이 올바르지 않습니다. 잠시 후 다시 시도해 주세요.");
    }

    public static final class AuthenticationException extends ApiException {
        public AuthenticationException() {
            super(ApiCode.BAD_REQUEST, "네이버 인증 토큰을 갱신해야 합니다.");
        }
    }

    public record IssuedToken(String accessToken, LocalDateTime expiresAt) {
        @Override
        public String toString() {
            return "IssuedToken[accessToken=REDACTED, expiresAt=" + expiresAt + "]";
        }
    }

    public record SellerAccount(String accountId, String accountUid) {
    }

    public record Channel(long channelNo, String channelType, String name, String url) {
    }
}
