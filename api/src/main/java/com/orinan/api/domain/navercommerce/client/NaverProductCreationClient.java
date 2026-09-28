package com.orinan.api.domain.navercommerce.client;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.navercommerce.controller.model.NaverProductCreationResponse.*;
import com.orinan.api.domain.navercommerce.service.NaverProductImageValidator.ImageData;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.AuthenticationException;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/** Metadata GETs may retry; image upload and product creation are each issued exactly once. */
@Component
public class NaverProductCreationClient {
    private static final String BASE = "https://api.commerce.naver.com/external";
    private final WebClient http;
    private final WebClient writeHttp;
    private final JsonMapper json;
    private final NaverReadExecutor reads;

    public NaverProductCreationClient(WebClient.Builder builder, JsonMapper json, NaverReadExecutor reads) {
        this.http = builder.clone().codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build();
        // Reactor Netty retries a TCP-aborted request once by default, even without retryWhen().
        // Product writes must leave ambiguous outcomes for the user to reconcile instead.
        this.writeHttp = builder.clone().clientConnector(new ReactorClientHttpConnector(HttpClient.create().disableRetry(true)))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build();
        this.json = json;
        this.reads = reads;
    }

    public List<Category> categories(String app, String token, long deadline) {
        var node = get(app, token, NaverReadExecutor.Resource.CATEGORIES, "/v1/categories?last=true", deadline);
        var result = new ArrayList<Category>();
        for (var row : array(node, 30000)) {
            if (!row.path("last").isBoolean()) throw invalid();
            if (row.path("last").asBoolean()) result.add(new Category(text(row, "id", 20), text(row, "wholeCategoryName", 1000)));
        }
        return List.copyOf(result);
    }

    public List<Origin> origins(String app, String token, long deadline) {
        var node = get(app, token, NaverReadExecutor.Resource.ORIGINS, "/v1/product-origin-areas", deadline);
        var result = new ArrayList<Origin>();
        for (var row : array(node.path("originAreaCodeNames"), 10000))
            result.add(new Origin(text(row, "code", 12), text(row, "name", 500)));
        return List.copyOf(result);
    }

    public List<Address> addresses(String app, String token, long deadline) {
        var result = new ArrayList<Address>();
        var seen = new HashSet<String>();
        for (int page = 1; page <= 20; page++) {
            var node = get(app, token, NaverReadExecutor.Resource.ADDRESSES,
                    "/v1/seller/addressbooks-for-page?page=" + page, deadline);
            if (!node.path("page").isIntegralNumber() || node.path("page").asInt() != page
                    || !node.path("totalPage").isIntegralNumber() || node.path("totalPage").asInt() < 0) throw invalid();
            for (var row : array(node.path("addressBooks"), 1000)) {
                String type = text(row, "addressType", 60);
                if (!Set.of("RELEASE", "REFUND_OR_EXCHANGE").contains(type)) continue;
                String id = id(row, "addressBookNo");
                if (!seen.add(id) || !row.path("overseasAddress").isBoolean()) throw invalid();
                String address = optionalText(row, "address", 2000);
                if (address == null) address = String.join(" ", Objects.requireNonNullElse(optionalText(row, "baseAddress", 1000), ""),
                        Objects.requireNonNullElse(optionalText(row, "detailAddress", 1000), "")).strip();
                result.add(new Address(id, text(row, "name", 500), address, type, row.path("overseasAddress").asBoolean()));
            }
            if (page >= node.path("totalPage").asInt()) return List.copyOf(result);
        }
        throw new ApiException(ApiCode.BAD_REQUEST, "주소록이 너무 많아 모두 조회하지 못했습니다. 스마트스토어센터에서 출고지와 반품지를 정리해 주세요.");
    }

    public Set<String> noticeTypes(String app, String token, String category, long deadline) {
        if (category == null || !category.matches("[0-9]{1,20}")) throw new ApiException(ApiCode.BAD_REQUEST, "상품 카테고리를 선택해 주세요.");
        String noticeCategory = noticeCategory(app, token, category, deadline);
        var node = get(app, token, NaverReadExecutor.Resource.PRODUCT_NOTICES,
                "/v1/products-for-provided-notice?categoryId=" + noticeCategory, deadline);
        var result = new LinkedHashSet<String>();
        for (var row : array(node, 100)) result.add(text(row, "productInfoProvidedNoticeType", 40));
        return Set.copyOf(result);
    }

    private String noticeCategory(String app, String token, String leafCategory, long deadline) {
        // Naver manages notice types by top-level category, although product creation requires a leaf.
        // https://github.com/commerce-api-naver/commerce-api/discussions/1733
        var node = get(app, token, NaverReadExecutor.Resource.CATEGORIES, "/v1/categories?last=false", deadline);
        var categories = new ArrayList<CategoryPath>();
        for (var row : array(node, 30000)) {
            String id = text(row, "id", 20);
            if (!id.matches("[0-9]{1,20}") || !row.path("last").isBoolean()) throw invalid();
            var segments = Arrays.stream(text(row, "wholeCategoryName", 1000).split(">", -1))
                    .map(String::strip).toList();
            if (segments.stream().anyMatch(String::isEmpty)) throw invalid();
            categories.add(new CategoryPath(id, segments, row.path("last").asBoolean()));
        }
        var selected = categories.stream().filter(row -> row.id().equals(leafCategory)).toList();
        if (selected.isEmpty() || selected.size() == 1 && !selected.get(0).leaf()) {
            throw new ApiException(ApiCode.BAD_REQUEST, "등록할 상품의 최종 카테고리를 다시 선택해 주세요.");
        }
        if (selected.size() != 1) throw invalid();
        String rootName = selected.get(0).segments().get(0);
        var roots = categories.stream().filter(row -> !row.leaf() && row.segments().size() == 1
                && row.segments().get(0).equals(rootName)).toList();
        if (roots.size() != 1) throw invalid();
        return roots.get(0).id();
    }

    private record CategoryPath(String id, List<String> segments, boolean leaf) {}

    public List<String> uploadImages(String token, List<ImageData> images) {
        var parts = new MultipartBodyBuilder();
        for (var image : images) parts.part("imageFiles", image.bytes()).filename(image.filename())
                .contentType(MediaType.parseMediaType(image.contentType()));
        JsonNode node;
        try {
            node = exchange(() -> writeHttp.post().uri(BASE + "/v1/product-images/upload")
                    .headers(h -> h.setBearerAuth(token)).contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(parts.build()), Duration.ofSeconds(30), true, false);
        } catch (UnknownWrite exception) {
            // An upload may have completed, but no product creation was attempted.
            throw new ApiException(ApiCode.BAD_REQUEST, "이미지 업로드 결과를 확인하지 못했습니다. 상품 등록은 시작하지 않았습니다. 다시 시도해 주세요.");
        }
        var result = new ArrayList<String>();
        try {
            for (var image : array(node.path("images"), 10)) {
                String url = text(image, "url", 2048);
                var uri = URI.create(url);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getFragment() != null) throw invalid();
                result.add(url);
            }
            if (result.size() != images.size()) throw invalid();
        } catch (RuntimeException exception) {
            throw new ApiException(ApiCode.BAD_REQUEST, "이미지 업로드 응답을 확인하지 못했습니다. 상품 등록은 시작하지 않았습니다. 이미지를 다시 등록해 주세요.");
        }
        return List.copyOf(result);
    }

    public Created create(String token, Map<String, Object> payload) {
        // Byte-array encoding avoids DEBUG logging of seller-entered content and phone numbers.
        byte[] body = json.writeValueAsBytes(payload);
        var node = exchange(() -> writeHttp.post().uri(BASE + "/v2/products").headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body), Duration.ofSeconds(30), true, true);
        try {
            return new Created(Status.CREATED, id(node, "originProductNo"), id(node, "smartstoreChannelProductNo"),
                    "스마트스토어에 상품을 등록했습니다.");
        } catch (RuntimeException exception) { throw new UnknownWrite(); }
    }

    private JsonNode get(String app, String token, NaverReadExecutor.Resource resource, String path, long deadline) {
        return reads.execute(app, resource, deadline, timeout -> exchange(() -> http.get().uri(BASE + path)
                .headers(h -> h.setBearerAuth(token)), timeout, false, false));
    }

    private JsonNode exchange(Supplier<WebClient.RequestHeadersSpec<?>> request, Duration timeout, boolean write, boolean create) {
        try {
            var node = request.get().exchangeToMono(response -> {
                int status = response.statusCode().value();
                if (response.statusCode().is2xxSuccessful()) return response.bodyToMono(byte[].class).map(json::readTree);
                var retryAfter = reads.retryAfter(response.headers().asHttpHeaders().getFirst("Retry-After"));
                return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(bytes -> {
                    JsonNode error = null;
                    try { error = json.readTree(bytes); } catch (RuntimeException ignored) {}
                    if (!write && status == 401 && error != null && "GW.AUTHN".equals(error.path("code").asString()))
                        return Mono.error(new AuthenticationException());
                    if (!write) {
                        var quota = error != null && "GW.QUOTA_LIMIT".equals(error.path("code").asString())
                                ? NaverReadExecutor.Quota.UNKNOWN : NaverReadExecutor.Quota.NONE;
                        return Mono.error(new NaverReadExecutor.UpstreamFailure(status, quota, retryAfter));
                    }
                    if (status >= 500 || status == 408) return Mono.error(new UnknownWrite());
                    return Mono.error(rejected(status, error, create));
                });
            }).block(timeout);
            if (node == null || node.isNull()) { if (write) throw new UnknownWrite(); throw invalid(); }
            return node;
        } catch (UnknownWrite | ApiException | NaverReadExecutor.UpstreamFailure exception) { throw exception; }
        catch (RuntimeException exception) {
            if (write) throw new UnknownWrite();
            throw new ApiException(ApiCode.SERVER_ERROR, "네이버 상품 등록 정보를 조회하지 못했습니다. 잠시 후 다시 조회해 주세요.");
        }
    }

    private ApiException rejected(int status, JsonNode error, boolean create) {
        if (status == 401 || status == 403) return new ApiException(ApiCode.BAD_REQUEST,
                "네이버 상품 등록 권한 또는 토큰을 확인해 주세요. 애플리케이션의 상품 권한과 판매자 정보 권한이 필요합니다.");
        if (status == 429) return new ApiException(ApiCode.BAD_REQUEST, "네이버 요청량 제한으로 등록 요청이 거절되었습니다. 잠시 후 다시 시도해 주세요.");
        var labels = new LinkedHashSet<String>();
        if (error != null && error.path("invalidInputs").isArray()) for (var field : error.path("invalidInputs")) {
            String name = field.path("name").asString("");
            if (name.contains("productInfoProvidedNotice")) labels.add("상품정보제공고시");
            else if (name.contains("ertification")) labels.add("상품 인증 정보(스마트스토어센터에서 등록 필요)");
            else if (name.contains("delivery")) labels.add("배송·반품 정보");
            else if (name.contains("originArea")) labels.add("원산지");
            else if (name.contains("ategory")) labels.add("카테고리");
            else if (name.contains("salePrice")) labels.add("판매가");
            else if (name.contains("name")) labels.add("상품명");
            else if (name.contains("image")) labels.add("상품 이미지");
        }
        return new ApiException(ApiCode.BAD_REQUEST, (create ? "네이버가 상품 등록 요청을 거절했습니다." : "네이버가 이미지 업로드 요청을 거절했습니다.")
                + (labels.isEmpty() ? " 입력 정보와 카테고리별 필수 인증·옵션 요건을 확인해 주세요." : " 다음 항목을 확인해 주세요: " + String.join(", ", labels)));
    }

    private JsonNode array(JsonNode node, int max) { if (!node.isArray() || node.size() > max) throw invalid(); return node; }
    private String text(JsonNode node, String field, int max) {
        String value = optionalText(node, field, max); if (value == null || value.isBlank()) throw invalid(); return value;
    }
    private String optionalText(JsonNode node, String field, int max) {
        var value = node.path(field); if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString() || value.asString().length() > max) throw invalid(); return value.asString();
    }
    private String id(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) throw invalid();
        return Long.toString(value.asLong());
    }
    private ApiException invalid() { return new ApiException(ApiCode.SERVER_ERROR, "네이버 상품 등록 정보 응답을 확인할 수 없습니다. 다시 조회해 주세요."); }
    public static final class UnknownWrite extends RuntimeException {
        public UnknownWrite() { super("Naver product write outcome unknown", null, false, false); }
    }
}
