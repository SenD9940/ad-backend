package com.orinan.api.domain.platformconnection.naver;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.ClientResponse;
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

    private static final Logger log = LoggerFactory.getLogger(NaverCommerceClient.class);
    private static final String BASE_URL = "https://api.commerce.naver.com/external";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern BCRYPT_SALT = Pattern.compile("\\$2[aby]\\$(\\d{2})\\$[./A-Za-z0-9]{22}");
    private static final int MAX_ERROR_BODY_BYTES = 16 * 1024;
    private static final Set<String> ERROR_CODES = Set.of("BAD_REQUEST", "GW.AUTHN", "GW.AUTHZ", "GW.IP_NOT_ALLOWED");
    private static final List<String> TOKEN_FIELDS = List.of("client_id", "client_secret_sign", "timestamp", "type", "account_id");
    private static final ErrorDetails UNKNOWN_ERROR = new ErrorDetails("UNKNOWN", List.of());

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
                return errorDetails(result).flatMap(details -> Mono.error(failedRequest(status, tokenRequest, details)));
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

    private Mono<ErrorDetails> errorDetails(ClientResponse response) {
        // Bound untrusted error bodies before parsing, and never pass their contents to a JSON logging decoder.
        // Even if decoding or reading the body fails, retain the HTTP status already received from Naver.
        return DataBufferUtils.join(response.body(BodyExtractors.toDataBuffers()), MAX_ERROR_BODY_BYTES)
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return parseErrorDetails(bytes);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .onErrorReturn(UNKNOWN_ERROR)
                .defaultIfEmpty(UNKNOWN_ERROR);
    }

    private ErrorDetails parseErrorDetails(byte[] bytes) {
        try {
            JsonNode body = jsonMapper.readTree(bytes);
            if (body == null || !body.isObject()) return UNKNOWN_ERROR;
            JsonNode codeNode = body.path("code");
            String code = codeNode.isString() && ERROR_CODES.contains(codeNode.asString())
                    ? codeNode.asString() : "UNKNOWN";
            Set<String> fields = new HashSet<>();
            JsonNode inputs = body.path("invalidInputs");
            if (inputs.isArray()) {
                for (JsonNode input : inputs) {
                    JsonNode name = input.path("name");
                    if (name.isString() && TOKEN_FIELDS.contains(name.asString())) fields.add(name.asString());
                }
            }
            return new ErrorDetails(code, TOKEN_FIELDS.stream().filter(fields::contains).toList());
        } catch (RuntimeException exception) {
            return UNKNOWN_ERROR;
        }
    }

    private ApiException failedRequest(int status, boolean tokenRequest, ErrorDetails details) {
        // All text values here come from fixed allowlists; do not log provider messages, request IDs, or credentials.
        log.warn("Naver commerce request rejected: operation={} http_status={} code={} invalid_fields={}",
                tokenRequest ? "TOKEN" : "RESOURCE", status, details.code(), details.fields());
        if (status == 401 && !tokenRequest && "GW.AUTHN".equals(details.code())) {
            return new AuthenticationException();
        }
        if (status == 429 || status >= 500) {
            return new ApiException(ApiCode.SERVER_ERROR, "네이버 요청이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.");
        }
        if ("GW.IP_NOT_ALLOWED".equals(details.code())) {
            return new ApiException(ApiCode.BAD_REQUEST,
                    "네이버가 API 호출 IP를 허용하지 않았습니다. 네이버 커머스 API센터의 애플리케이션 설정에 현재 서버의 공인 IP를 등록해 주세요.");
        }
        if (tokenRequest) {
            if (details.fields().contains("client_id")) {
                return new ApiException(ApiCode.BAD_REQUEST,
                        "네이버 커머스 API 애플리케이션 ID를 확인해 주세요. 앱 정보를 변경했다면 자산 편집에서 내 스토어를 다시 연결해 주세요.");
            }
            if (details.fields().contains("client_secret_sign")) {
                return new ApiException(ApiCode.BAD_REQUEST,
                        "네이버 앱 인증 서명이 거절되었습니다. 커머스 API센터의 앱 ID·시크릿을 확인하고, 앱 정보를 변경했다면 자산 편집에서 내 스토어를 다시 연결해 주세요.");
            }
            if (details.fields().contains("timestamp")) {
                return new ApiException(ApiCode.BAD_REQUEST,
                        "네이버 토큰 발급 시간이 유효하지 않습니다. 서버의 날짜·시간 자동 동기화를 확인해 주세요.");
            }
            if (details.fields().contains("type")) {
                return new ApiException(ApiCode.BAD_REQUEST,
                        "네이버 앱의 인증 유형을 확인해 주세요. 내 스토어 앱은 SELF, 판매자 연동 앱은 SELLER를 사용합니다.");
            }
            if (details.fields().contains("account_id")) {
                return new ApiException(ApiCode.BAD_REQUEST,
                        "네이버 판매자 ID와 해당 판매자에 대한 애플리케이션의 연동 권한을 확인해 주세요.");
            }
            return new ApiException(ApiCode.BAD_REQUEST,
                    "네이버 인증 토큰 발급이 거절되었습니다. 커머스 API센터의 앱 ID·시크릿, API 호출 허용 IP와 판매자 권한을 확인해 주세요. 앱 정보를 변경했다면 자산 편집에서 다시 연결해 주세요.");
        }
        return new ApiException(ApiCode.BAD_REQUEST,
                "네이버 조회 요청이 거절되었습니다. 애플리케이션의 조회 권한과 판매자 연결 상태를 확인해 주세요.");
    }

    private record ErrorDetails(String code, List<String> fields) {}

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
