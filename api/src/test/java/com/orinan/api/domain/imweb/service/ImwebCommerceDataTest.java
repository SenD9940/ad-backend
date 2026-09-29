package com.orinan.api.domain.imweb.service;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.imweb.controller.model.ImwebCommerceResponse.Category;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.orinan.api.domain.imweb.service.ImwebCommerceData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImwebCommerceDataTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void acceptsDocumentedProductWrapperAndOrderObjectWithIdenticalPagination() {
        var object = node(pageBody(1, 2, 2, 3, "{\"prodNo\":1},{\"prodNo\":2}"));
        var fromObject = page(object, 1, 2);
        var fromArray = page(node("[" + object + "]"), 1, 2);
        assertThat(fromObject).isEqualTo(fromArray);
        assertThat(fromObject.hasNext()).isTrue();
        assertThat(fromObject.items()).hasSize(2);
        var last = page(node(pageBody(2, 2, 2, 3, "{\"prodNo\":3}")), 2, 2);
        assertThat(last.hasNext()).isFalse();
        assertThat(last.totalCount()).isEqualTo(3);
    }

    @Test void acceptsEmptyFirstPageWithoutInventingProductsOrAdditionalPages() {
        for (int totalPages : List.of(0, 1)) {
            var page = page(node(pageBody(1, 20, totalPages, 0, "")), 1, 20);
            assertThat(page.items()).isEmpty();
            assertThat(page.hasNext()).isFalse();
            assertThat(page.totalCount()).isZero();
        }
    }

    @Test void incompletePagesAndInconsistentTotalsCannotBeReportedAsComplete() {
        for (var body : List.of(pageBody(2, 2, 2, 3, "{}"), pageBody(1, 3, 1, 3, "{},{},{}"),
                pageBody(1, 2, 2, 3, "{}"), pageBody(1, 2, 1, 3, "{},{}"),
                pageBody(1, 2, 0, 0, "{}"), pageBody(1, 2, 2, 0, ""),
                pageBody(1, 2, 1, 1, "{},{}"), pageBody(1, 2, 1, 2, "{},3"))) {
            assertThatThrownBy(() -> page(node(body), 1, 2)).isInstanceOf(ApiException.class).hasNoCause();
        }
        assertThatThrownBy(() -> page(node(pageBody(2, 2, 2, 3, "")), 2, 2)).isInstanceOf(ApiException.class);
    }

    @Test void malformedOrAmbiguousPaginationWrappersAreRejected() {
        for (var body : List.of("null", "[]", "[{},{}]", "{}", "{\"list\":{}}",
                "{\"list\":[],\"currentPage\":\"1\",\"pageSize\":20,\"totalPage\":0,\"totalCount\":0}")) {
            assertThatThrownBy(() -> page(node(body), 1, 20)).isInstanceOf(ApiException.class).hasNoCause();
        }
    }

    @Test void moneyPreservesDecimalValuesAndDistinguishesMissingValuesFromZero() {
        assertThat(money(node("{\"price\":12.34}"), "price", false)).isEqualByComparingTo("12.34");
        assertThat(money(node("{\"price\":0}"), "price", false)).isZero();
        assertThat(money(node("{}"), "price", true)).isNull();
        assertThat(money(node("{\"price\":null}"), "price", true)).isNull();
        assertThat(money(node("{\"price\":999999999999999999999999}"), "price", false))
                .isEqualByComparingTo("999999999999999999999999");
    }

    @Test void malformedNegativeAndUnboundedMoneyCannotCorruptTotals() {
        for (var raw : List.of("null", "\"1000\"", "true", "-1", "0.123456789", "1000000000000000000000000", "1e100")) {
            assertThatThrownBy(() -> money(node("{\"price\":" + raw + "}"), "price", false))
                    .isInstanceOf(ApiException.class).hasNoCause();
        }
        assertThatThrownBy(() -> money(node("{}"), "price", false)).isInstanceOf(ApiException.class);
    }

    @Test void numericIdsRemainExactBeyondJavascriptSafeIntegerAndRejectFractions() {
        assertThat(integer(node("{\"id\":9007199254740993}"), "id", 1, Long.MAX_VALUE)).isEqualTo(9007199254740993L);
        for (var value : List.of("\"123\"", "1.5", "true", "0", "9223372036854775808")) {
            assertThatThrownBy(() -> integer(node("{\"id\":" + value + "}"), "id", 1, Long.MAX_VALUE)).isInstanceOf(ApiException.class);
        }
    }

    @Test void categoryHierarchyUsesFullNamesAndRetainsParentCategories() {
        var categories = categories(node("""
                [{"categoryCode":"c-parent","name":"패션","children":[
                  {"categoryCode":"c_child","name":"상의","children":[]}]},
                 {"categoryCode":"c-other","name":"생활","children":[]}]
                """));
        assertThat(categories).containsExactly(new Category("c-parent", "패션"), new Category("c_child", "패션 > 상의"), new Category("c-other", "생활"));
    }

    @Test void duplicateUnsafeDeepAndOversizedCategoriesAreRejectedWithoutPartialResults() {
        for (var body : List.of("[{\"categoryCode\":\"c1\",\"name\":\"상품\"}]",
                "[{\"categoryCode\":\"../../other\",\"name\":\"상품\",\"children\":[]}]",
                "[{\"categoryCode\":\"c1\",\"name\":\"상품\",\"children\":[{\"categoryCode\":\"c1\",\"name\":\"중복\",\"children\":[]}]}]")) {
            assertThatThrownBy(() -> categories(node(body))).isInstanceOf(ApiException.class).hasNoCause();
        }
        String deep = "[]";
        for (int i = 0; i < 12; i++) deep = "[{\"categoryCode\":\"c" + i + "\",\"name\":\"상품\",\"children\":" + deep + "}]";
        JsonNode deepCategories = node(deep);
        assertThatThrownBy(() -> categories(deepCategories)).isInstanceOf(ApiException.class);
        String large = IntStream.range(0, 2001).mapToObj(i -> "{\"categoryCode\":\"c" + i + "\",\"name\":\"상품\",\"children\":[]}")
                .collect(Collectors.joining(",", "[", "]"));
        assertThatThrownBy(() -> categories(node(large))).isInstanceOf(ApiException.class);
    }

    @Test void unsafeImageUrlsAreOmittedAndAbsentImagesRemainUnknown() {
        assertThat(imageUrl(node("[\"https://cdn.imweb.me/upload/site/photo.png\"]"))).isEqualTo("https://cdn.imweb.me/upload/site/photo.png");
        for (var body : List.of("null", "[]", "[\"javascript:alert(1)\"]", "[\"http://cdn.imweb.me/image.png\"]", "[\"https://user:pass@evil.example/image.png\"]")) {
            assertThat(imageUrl(node(body))).isNull();
        }
        assertThatThrownBy(() -> imageUrl(node("[12]"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> imageUrl(node("{}"))).isInstanceOf(ApiException.class);
    }

    private String pageBody(int page, int size, int pages, int count, String rows) {
        return "{\"currentPage\":" + page + ",\"pageSize\":" + size + ",\"totalPage\":" + pages + ",\"totalCount\":" + count + ",\"list\":[" + rows + "]}";
    }
    private JsonNode node(String value) { return json.readTree(value); }
}
