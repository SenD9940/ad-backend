package com.orinan.api.domain.navercommerce.client;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.navercommerce.controller.model.NaverProductCreationResponse.Category;
import com.orinan.api.domain.navercommerce.controller.model.NaverProductCreationResponse.Origin;
import com.orinan.api.domain.navercommerce.controller.model.NaverProductCreationResponse.Status;
import com.orinan.api.domain.navercommerce.service.NaverProductImageValidator.ImageData;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.AuthenticationException;
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
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class NaverProductCreationClientTest {
    private static final String TOKEN = "NAVER-TOKEN-MUST-STAY-PRIVATE";
    private static final String BASE = "https://api.commerce.naver.com/external";
    private static final String SECRET_NAME = "SELLER-PRIVATE-NAME";
    private static final String SECRET_PHONE = "SELLER-PRIVATE-PHONE";
    private static final String SECRET_ADDRESS = "SELLER-PRIVATE-ADDRESS";
    private final Queue<Mono<ClientResponse>> responses = new ArrayDeque<>();
    private final List<ClientRequest> requests = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1);
    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private NaverProductCreationClient client;

    @BeforeEach void setUp() {
        client = new NaverProductCreationClient(WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return responses.remove();
        }), mapper, new NaverReadExecutor(now::get, () -> Instant.parse("2026-09-28T00:00:00Z"),
                nanos -> now.addAndGet(nanos)));
    }

    @Test void categoriesRequestLeavesAndReturnTheirFullPathNames() {
        json("""
                [{"id":"50000000","name":"패션","wholeCategoryName":"패션","last":false},
                 {"id":"50000100","name":"상의","wholeCategoryName":"패션 > 의류 > 상의","last":true}]
                """);
        assertThat(client.categories("app", TOKEN, deadline()))
                .containsExactly(new Category("50000100", "패션 > 의류 > 상의"));
        assertRequest(0, HttpMethod.GET, "/v1/categories?last=true");
    }

    @Test void malformedMetadataCannotBecomeAnEmptySuccessAndIsNotRetried() {
        for (var body : List.of("{}", "[{\"id\":\"1\",\"last\":\"true\"}]",
                "[{\"id\":\"1\",\"last\":true,\"wholeCategoryName\":null}]")) {
            int before = requests.size();
            json(body);
            assertThatThrownBy(() -> client.categories("app", TOKEN, deadline()))
                    .isInstanceOf(ApiException.class).hasNoCause();
            assertThat(requests).hasSize(before + 1);
        }
    }

    @Test void originsUseTheAllCodesRouteAndPreserveDetailedCodesWithLeadingZeroes() {
        json("""
                {"originAreaCodeNames":[{"code":"00","name":"국산"},
                  {"code":"0200037","name":"수입산 > 아시아 > 일본"},
                  {"code":"04","name":"기타 > 직접 입력"}]}
                """);
        assertThat(client.origins("app", TOKEN, deadline())).containsExactly(
                new Origin("00", "국산"), new Origin("0200037", "수입산 > 아시아 > 일본"),
                new Origin("04", "기타 > 직접 입력"));
        assertRequest(0, HttpMethod.GET, "/v1/product-origin-areas");
    }

    @Test void addressesReadAllPagesRetainExactIdsAndExcludeUnneededPrivateFieldsFromProjectionAndLogs(CapturedOutput output) {
        var logger = springLogger();
        var previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            json(addressPage(1, 2, """
                    {"addressBookNo":9007199254740993,"name":"SELLER-PRIVATE-NAME","addressType":"RELEASE",
                     "address":"SELLER-PRIVATE-ADDRESS","overseasAddress":false,
                     "phoneNumber1":"SELLER-PRIVATE-PHONE","phoneNumber2":"EXTRA-PRIVATE-PHONE"},
                    {"addressBookNo":3,"name":"사업장","addressType":"BUSINESS"}
                    """));
            json(addressPage(2, 2, """
                    {"addressBookNo":9007199254740995,"name":"반품지","addressType":"REFUND_OR_EXCHANGE",
                     "baseAddress":"기본 주소","detailAddress":"상세 주소","overseasAddress":true}
                    """));
            var addresses = client.addresses("app", TOKEN, deadline());
            assertThat(addresses).hasSize(2);
            assertThat(addresses.get(0).id()).isEqualTo("9007199254740993");
            assertThat(addresses.get(0).address()).isEqualTo(SECRET_ADDRESS);
            assertThat(addresses.get(1).address()).isEqualTo("기본 주소 상세 주소");
            assertThat(addresses.get(1).overseas()).isTrue();
            assertThat(mapper.writeValueAsString(addresses)).doesNotContain(SECRET_PHONE, "phone_number", "EXTRA-PRIVATE-PHONE");
            assertThat(addresses.toString()).doesNotContain(SECRET_NAME, SECRET_ADDRESS);
            assertThat(output.getAll()).doesNotContain(TOKEN, SECRET_NAME, SECRET_PHONE, SECRET_ADDRESS, "EXTRA-PRIVATE-PHONE");
            assertRequest(0, HttpMethod.GET, "/v1/seller/addressbooks-for-page?page=1");
            assertRequest(1, HttpMethod.GET, "/v1/seller/addressbooks-for-page?page=2");
        } finally { logger.setLevel(previous); }
    }

    @Test void inconsistentAddressPaginationAndDuplicateAddressesFailInsteadOfReturningPartialResults() {
        json(addressPage(2, 2, ""));
        assertThatThrownBy(() -> client.addresses("app", TOKEN, deadline())).isInstanceOf(ApiException.class);
        String address = "{\"addressBookNo\":123,\"name\":\"출고지\",\"addressType\":\"RELEASE\",\"overseasAddress\":false}";
        json(addressPage(1, 2, address));
        json(addressPage(2, 2, address));
        assertThatThrownBy(() -> client.addresses("app", TOKEN, deadline())).isInstanceOf(ApiException.class);
        assertThat(requests).hasSize(3);
    }

    @Test void addressPaginationIsBoundedAndNeverReturnsATruncatedList() {
        for (int page = 1; page <= 20; page++) json(addressPage(page, 21, ""));
        assertThatThrownBy(() -> client.addresses("app", TOKEN, deadline())).isInstanceOf(ApiException.class)
                .hasMessageContaining("모두 조회하지 못했습니다");
        assertThat(requests).hasSize(20);
        assertRequest(19, HttpMethod.GET, "/v1/seller/addressbooks-for-page?page=20");
    }

    @Test void noticeTypesResolveTheExactLeafToItsTopLevelCategoryAndTrimPathSegments() {
        json("""
                [{"id":"50000000","wholeCategoryName":" 패션의류 ","last":false},
                 {"id":"50000111","wholeCategoryName":"패션의류 > 여성의류","last":false},
                 {"id":"50000001","wholeCategoryName":" 패션의류 > 여성의류 > 티셔츠 ","last":true},
                 {"id":"50000002","wholeCategoryName":"패션의류소품","last":false}]
                """);
        json("""
                [{"productInfoProvidedNoticeType":"WEAR"},{"productInfoProvidedNoticeType":"ETC"},
                 {"productInfoProvidedNoticeType":"WEAR"}]
                """);
        assertThat(client.noticeTypes("app", TOKEN, "50000001", deadline())).containsExactlyInAnyOrder("WEAR", "ETC");
        assertRequest(0, HttpMethod.GET, "/v1/categories?last=false");
        assertRequest(1, HttpMethod.GET, "/v1/products-for-provided-notice?categoryId=50000000");
        for (var invalid : List.of("", "1&categoryId=2", "../products", "50000001?x=1")) {
            assertThatThrownBy(() -> client.noticeTypes("app", TOKEN, invalid, deadline())).isInstanceOf(ApiException.class);
        }
        assertThat(requests).hasSize(2);
    }

    @Test void missingOrNonLeafSelectionIsRejectedBeforeLookingUpNotices() {
        for (var selected : List.of("99999999", "50000000")) {
            json("""
                    [{"id":"50000000","wholeCategoryName":"패션의류","last":false},
                     {"id":"50000001","wholeCategoryName":"패션의류>여성의류>티셔츠","last":true}]
                    """);
            assertThatThrownBy(() -> client.noticeTypes("app", TOKEN, selected, deadline()))
                    .isInstanceOf(ApiException.class).hasMessageContaining("최종 카테고리");
        }
        assertThat(requests).hasSize(2).allSatisfy(request ->
                assertThat(request.url().getPath()).endsWith("/v1/categories"));
    }

    @Test void missingAmbiguousOrUnsafeRootMappingIsRejectedWithoutGuessingANoticeCategory() {
        String leaf = "{\"id\":\"50000001\",\"wholeCategoryName\":\"패션의류>여성의류>티셔츠\",\"last\":true}";
        for (var otherRows : List.of("",
                ",{\"id\":\"50000000\",\"wholeCategoryName\":\"패션의류\",\"last\":false},"
                        + "{\"id\":\"50000002\",\"wholeCategoryName\":\" 패션의류 \",\"last\":false}",
                ",{\"id\":\"50000000&evil=1\",\"wholeCategoryName\":\"패션의류\",\"last\":false}",
                "," + leaf,
                ",{\"id\":\"50000000\",\"wholeCategoryName\":\"패션의류>\",\"last\":false}")) {
            int before = requests.size();
            json("[" + leaf + otherRows + "]");
            assertThatThrownBy(() -> client.noticeTypes("app", TOKEN, "50000001", deadline()))
                    .isInstanceOf(ApiException.class).hasNoCause();
            assertThat(requests).hasSize(before + 1);
        }
        assertThat(requests).allSatisfy(request -> assertThat(request.url().getPath()).endsWith("/v1/categories"));
    }

    @Test void metadataCanRetryTransientFailuresButAuthenticationIsReturnedForTheBusinessRefresh() {
        respond(HttpStatus.SERVICE_UNAVAILABLE, "{\"message\":\"private upstream payload\"}");
        json("[]");
        assertThat(client.categories("app", TOKEN, deadline())).isEmpty();
        assertThat(requests).hasSize(2);
        assertThat(now.get()).isGreaterThanOrEqualTo(Duration.ofSeconds(1).toNanos());
        respond(HttpStatus.UNAUTHORIZED, "{\"code\":\"GW.AUTHN\"}");
        assertThatThrownBy(() -> client.categories("app", TOKEN, deadline())).isInstanceOf(AuthenticationException.class);
        assertThat(requests).hasSize(3);
    }

    @Test void imageUploadSendsNamedMultipartFilesInOrderAndKeepsReturnedUrls() {
        json("{\"images\":[{\"url\":\"https://shop-phinf.pstatic.net/first.png\"},{\"url\":\"https://shop-phinf.pstatic.net/second.jpg\"}]}");
        var images = List.of(new ImageData("PNG-BYTES".getBytes(StandardCharsets.UTF_8), "image/png", "image-1.png"),
                new ImageData("JPEG-BYTES".getBytes(StandardCharsets.UTF_8), "image/jpeg", "image-2.jpg"));
        assertThat(client.uploadImages(TOKEN, images)).containsExactly(
                "https://shop-phinf.pstatic.net/first.png", "https://shop-phinf.pstatic.net/second.jpg");
        assertRequest(0, HttpMethod.POST, "/v1/product-images/upload");
        var output = written(requests.get(0));
        assertThat(output.getHeaders().getContentType()).isNotNull();
        assertThat(output.getHeaders().getContentType().isCompatibleWith(MediaType.MULTIPART_FORM_DATA)).isTrue();
        String multipart = output.getBodyAsString().block();
        assertThat(multipart).contains("name=\"imageFiles\"; filename=\"image-1.png\"", "Content-Type: image/png", "PNG-BYTES",
                "name=\"imageFiles\"; filename=\"image-2.jpg\"", "Content-Type: image/jpeg", "JPEG-BYTES");
        assertThat(multipart.indexOf("image-1.png")).isLessThan(multipart.indexOf("image-2.jpg"));
        assertThat(multipart).doesNotContain(TOKEN);
    }

    @Test void imageCountMismatchUnsafeUrlsAndAmbiguousUploadResponsesNeverProceedOrRetry() {
        for (var body : List.of("{\"images\":[]}", "{\"images\":[{\"url\":\"http://shop-phinf.pstatic.net/a.png\"}]}",
                "{\"images\":[{\"url\":\"https://user:pass@shop-phinf.pstatic.net/a.png\"}]}",
                "{\"images\":[{\"url\":\"https://shop-phinf.pstatic.net/a.png#fragment\"}]}",
                "MALFORMED-JSON " + TOKEN)) {
            int before = requests.size();
            json(body);
            assertThatThrownBy(() -> client.uploadImages(TOKEN, oneImage())).isInstanceOf(ApiException.class)
                    .hasMessageContaining("상품 등록은 시작하지 않았습니다").hasMessageNotContaining(TOKEN).hasNoCause();
            assertThat(requests).hasSize(before + 1);
        }
        respond(HttpStatus.INTERNAL_SERVER_ERROR, "{\"message\":\"" + TOKEN + "\"}");
        assertThatThrownBy(() -> client.uploadImages(TOKEN, oneImage())).isInstanceOf(ApiException.class)
                .hasMessageContaining("상품 등록은 시작하지 않았습니다");
        assertThat(requests).hasSize(6);
        assertThat(requests).allSatisfy(request -> assertThat(request.url().getPath()).endsWith("/product-images/upload"));
    }

    @Test void productCreationUsesOneCamelCasePostAndReturnsLargeNumericIdsAsExactStrings(CapturedOutput output) {
        var logger = springLogger();
        var previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            json("{\"originProductNo\":9007199254740993,\"smartstoreChannelProductNo\":9007199254740995}");
            var payload = product();
            var result = client.create(TOKEN, payload);
            assertThat(result.status()).isEqualTo(Status.CREATED);
            assertThat(result.originProductNo()).isEqualTo("9007199254740993");
            assertThat(result.smartstoreChannelProductNo()).isEqualTo("9007199254740995");
            assertRequest(0, HttpMethod.POST, "/v2/products");
            assertThat(requests.get(0).headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            var sent = mapper.readTree(written(requests.get(0)).getBodyAsString().block());
            assertThat(sent).isEqualTo(mapper.valueToTree(payload));
            assertThat(sent.path("originProduct").path("salePrice").asLong()).isEqualTo(12340);
            assertThat(sent.path("originProduct").path("leafCategoryId").asString()).isEqualTo("50000001");
            assertThat(sent.has("origin_product")).isFalse();
            assertThat(sent.path("originProduct").path("detailAttribute").path("afterServiceInfo")
                    .path("afterServiceTelephoneNumber").asString()).isEqualTo(SECRET_PHONE);
            assertThat(requests).hasSize(1);
            assertThat(output.getAll()).doesNotContain(TOKEN, SECRET_PHONE, SECRET_NAME);
        } finally { logger.setLevel(previous); }
    }

    @Test void providerValidationDetailsBecomeSafeFixedFieldLabelsWithoutRawMessagesOrTokens(CapturedOutput output) {
        respond(HttpStatus.BAD_REQUEST, """
                {"code":"BAD_REQUEST","message":"PRIVATE-PROVIDER-ERROR",
                 "invalidInputs":[
                 {"name":"originProduct.detailAttribute.productInfoProvidedNotice","message":"NAVER-TOKEN-MUST-STAY-PRIVATE"},
                 {"name":"originProduct.detailAttribute.productCertificationInfos","message":"SELLER-PRIVATE-PHONE"},
                 {"name":"originProduct.deliveryInfo","message":"SELLER-PRIVATE-ADDRESS"},
                 {"name":"originProduct.detailAttribute.originAreaInfo"},{"name":"originProduct.leafCategoryId"},
                 {"name":"originProduct.salePrice"},{"name":"originProduct.name"},{"name":"originProduct.images"}]}
                """);
        assertThatThrownBy(() -> client.create(TOKEN, product())).isInstanceOf(ApiException.class).hasNoCause()
                .hasMessageContaining("상품정보제공고시").hasMessageContaining("상품 인증 정보")
                .hasMessageContaining("배송·반품 정보").hasMessageContaining("원산지")
                .hasMessageContaining("카테고리").hasMessageContaining("판매가")
                .hasMessageContaining("상품명").hasMessageContaining("상품 이미지")
                .hasMessageNotContaining(TOKEN).hasMessageNotContaining("PRIVATE-PROVIDER-ERROR");
        assertThat(requests).hasSize(1);
        assertThat(output.getAll()).doesNotContain(TOKEN, SECRET_PHONE, SECRET_ADDRESS, "PRIVATE-PROVIDER-ERROR");
    }

    @Test void permissionAndRateLimitRejectionsNeverAutomaticallyRetryEitherWrite() {
        for (var status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.TOO_MANY_REQUESTS)) {
            int before = requests.size();
            respond(status, "{\"code\":\"GW.AUTHN\",\"message\":\"" + TOKEN + "\"}");
            assertThatThrownBy(() -> client.create(TOKEN, product())).isInstanceOf(ApiException.class)
                    .isNotInstanceOf(AuthenticationException.class).hasNoCause().hasMessageNotContaining(TOKEN);
            assertThat(requests).hasSize(before + 1);
            respond(status, "{\"code\":\"GW.AUTHN\",\"message\":\"" + TOKEN + "\"}");
            assertThatThrownBy(() -> client.uploadImages(TOKEN, oneImage())).isInstanceOf(ApiException.class)
                    .isNotInstanceOf(AuthenticationException.class).hasNoCause().hasMessageNotContaining(TOKEN);
            assertThat(requests).hasSize(before + 2);
        }
    }

    @Test void serverFailuresAndTransportTimeoutsAreUnknownAndNeverRetried(CapturedOutput output) {
        for (var status : List.of(HttpStatus.REQUEST_TIMEOUT, HttpStatus.INTERNAL_SERVER_ERROR,
                HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT)) {
            int before = requests.size();
            respond(status, "{\"message\":\"" + TOKEN + " PRIVATE-PROVIDER-ERROR\"}");
            assertUnknownCreation();
            assertThat(requests).hasSize(before + 1);
        }
        responses.add(Mono.error(new TimeoutException("transport timeout " + TOKEN)));
        assertUnknownCreation();
        responses.add(Mono.error(new IllegalStateException("connection reset " + TOKEN)));
        assertUnknownCreation();
        assertThat(requests).hasSize(7);
        assertThat(output.getAll()).doesNotContain(TOKEN, "PRIVATE-PROVIDER-ERROR");
    }

    @Test void malformedOrIncompleteSuccessCannotBeReportedAsCreatedOrAutomaticallyRetried(CapturedOutput output) {
        for (var body : List.of("not-json " + TOKEN, "", "null", "{}",
                "{\"originProductNo\":1,\"smartstoreChannelProductNo\":0}",
                "{\"originProductNo\":\"123\",\"smartstoreChannelProductNo\":2}",
                "{\"originProductNo\":1.5,\"smartstoreChannelProductNo\":2}",
                "{\"originProductNo\":9223372036854775808,\"smartstoreChannelProductNo\":2}")) {
            int before = requests.size();
            json(body);
            assertUnknownCreation();
            assertThat(requests).hasSize(before + 1);
        }
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    private void assertUnknownCreation() {
        assertThatThrownBy(() -> client.create(TOKEN, product())).isInstanceOf(NaverProductCreationClient.UnknownWrite.class)
                .hasNoCause().hasMessageNotContaining(TOKEN).satisfies(error -> assertThat(error.getStackTrace()).isEmpty());
    }

    private Map<String, Object> product() {
        return Map.of("originProduct", Map.of("name", SECRET_NAME, "statusType", "SALE", "salePrice", 12340,
                        "leafCategoryId", "50000001",
                        "detailAttribute", Map.of("afterServiceInfo", Map.of("afterServiceTelephoneNumber", SECRET_PHONE))),
                "smartstoreChannelProduct", Map.of("channelProductDisplayStatusType", "SUSPENSION", "naverShoppingRegistration", false));
    }

    private List<ImageData> oneImage() {
        return List.of(new ImageData("PNG-BYTES".getBytes(StandardCharsets.UTF_8), "image/png", "image-1.png"));
    }

    private String addressPage(int page, int totalPage, String rows) {
        return "{\"page\":" + page + ",\"totalPage\":" + totalPage + ",\"addressBooks\":[" + rows + "]}";
    }

    private void assertRequest(int index, HttpMethod method, String path) {
        assertThat(requests.get(index).method()).isEqualTo(method);
        assertThat(requests.get(index).url().toString()).isEqualTo(BASE + path).doesNotContain(TOKEN);
        assertThat(requests.get(index).headers().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
    }

    private MockClientHttpRequest written(ClientRequest request) {
        var output = new MockClientHttpRequest(request.method(), request.url());
        request.writeTo(output, ExchangeStrategies.withDefaults()).block();
        return output;
    }

    private ch.qos.logback.classic.Logger springLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.springframework");
    }

    private long deadline() { return now.get() + Duration.ofSeconds(45).toNanos(); }
    private void json(String body) { respond(HttpStatus.OK, body); }
    private void respond(HttpStatus status, String body) {
        responses.add(Mono.just(ClientResponse.create(status).header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build()));
    }
}
