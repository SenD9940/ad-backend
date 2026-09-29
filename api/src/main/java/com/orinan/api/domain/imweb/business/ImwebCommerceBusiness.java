package com.orinan.api.domain.imweb.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.imweb.client.ImwebApiClient;
import com.orinan.api.domain.imweb.controller.model.ImwebCommerceResponse.*;
import com.orinan.api.domain.imweb.service.ImwebAccessService;
import com.orinan.api.domain.imweb.service.ImwebCommerceData;
import com.orinan.api.domain.imweb.service.ImwebSalesAggregator;
import lombok.RequiredArgsConstructor;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.orinan.api.domain.imweb.service.ImwebCommerceData.*;

@Business
@RequiredArgsConstructor
public class ImwebCommerceBusiness {
    private static final int ORDER_PAGE_SIZE = 100;
    private static final int MAX_ORDER_PAGES = 20;
    private static final long MAX_QUERY_NANOS = Duration.ofSeconds(45).toNanos();
    private final ImwebConnectionBusiness connections;
    private final ImwebAccessService access;
    private final ImwebApiClient client;
    private final ImwebSalesAggregator aggregator;

    public Products products(Long workspaceId, Long assetId, Long userId, int page, int size) {
        if (page < 1 || page > 10000 || size < 1 || size > 100) throw bad("상품 페이지는 1 이상, 한 페이지 상품 수는 1~100개로 지정해 주세요.");
        var context = connections.context(workspaceId, assetId, userId);
        var reader = new Reader(context, connections, access, client);
        var query = unitQuery(context);
        query.add("page", Integer.toString(page)); query.add("limit", Integer.toString(size)); query.add("prodStatus", "sale");
        access.requireUnchanged(context);
        var result = ImwebCommerceData.page(reader.read("/products", query), page, size);
        Set<Long> ids = new HashSet<>();
        List<Product> products = result.items().stream().map(item -> {
            if (!context.siteCode().equals(text(item, "siteCode", 100)) || !context.unitCode().equals(text(item, "unitCode", 100))
                    || !"sale".equals(text(item, "prodStatus", 30))) throw invalid();
            long id = integer(item, "prodNo", 1, Long.MAX_VALUE);
            if (!ids.add(id)) throw invalid();
            Long stock = null;
            if ("Y".equals(item.path("stockUse").asString()) && "N".equals(item.path("stockUnlimit").asString())) {
                stock = integer(item, "stockNoOption", 0, Long.MAX_VALUE);
            }
            return new Product(Long.toString(id), text(item, "name", 500), "sale", imageUrl(item.path("productImages")),
                    money(item, "price", true), money(item, "priceOrg", true), stock);
        }).toList();
        reader.check();
        return new Products(assetId, context.unitCode(), products, page, size, result.hasNext(), result.totalCount(), Instant.now());
    }

    public Sales sales(Long workspaceId, Long assetId, Long userId, LocalDate since, LocalDate until) {
        if (since == null || until == null || since.isAfter(until) || ChronoUnit.DAYS.between(since, until) >= 31
                || until.isAfter(LocalDate.now(SeoulDateTimes.ZONE))) throw bad("판매 성과는 오늘까지 최대 31일의 기간으로 조회해 주세요.");
        long deadline = System.nanoTime() + MAX_QUERY_NANOS;
        var context = connections.context(workspaceId, assetId, userId);
        var reader = new Reader(context, connections, access, client);
        var query = unitQuery(context);
        query.add("limit", Integer.toString(ORDER_PAGE_SIZE)); query.add("includeOrderPending", "Y");
        query.add("startWtime", since.atStartOfDay(SeoulDateTimes.ZONE).toInstant().toString());
        query.add("endWtime", until.plusDays(1).atStartOfDay(SeoulDateTimes.ZONE).toInstant().minusMillis(1).toString());
        List<JsonNode> orders = new ArrayList<>();
        Long expectedCount = null;
        for (int page = 1; ; page++) {
            if (page > MAX_ORDER_PAGES || System.nanoTime() >= deadline) throw tooManyOrders();
            reader.check();
            query.set("page", Integer.toString(page));
            var result = ImwebCommerceData.page(reader.read("/orders", query), page, ORDER_PAGE_SIZE);
            if (System.nanoTime() >= deadline || result.totalPages() > MAX_ORDER_PAGES) throw tooManyOrders();
            if (expectedCount == null) expectedCount = result.totalCount();
            if (expectedCount != result.totalCount()) throw bad("조회 중 주문 정보가 변경되었습니다. 전체 성과를 다시 조회해 주세요.");
            orders.addAll(result.items());
            if (!result.hasNext()) break;
        }
        if (orders.size() != expectedCount) throw invalid();
        var sales = aggregator.aggregate(context, since, until, orders);
        reader.check();
        return sales;
    }

    public Options options(Long workspaceId, Long assetId, Long userId) {
        var context = connections.context(workspaceId, assetId, userId);
        var reader = new Reader(context, connections, access, client);
        var site = reader.site();
        boolean singleUnit = singleUnitSite(site, context);
        var categories = categories(reader.read("/products/shop-categories", unitQuery(context)));
        reader.check();
        return new Options(categories, context.currency(), context.unitCode(), singleUnit,
                singleUnit ? null : "여러 언어 스토어가 있는 사이트의 상품 등록은 아임웹 관리자에서 진행해 주세요. 상품과 판매 성과는 여기에서 조회할 수 있습니다.");
    }

    static boolean singleUnitSite(JsonNode site, ImwebAccessService.Context context) {
        if (!context.siteCode().equals(text(site, "siteCode", 100)) || !site.path("unitList").isArray()) throw invalid();
        boolean found = false;
        for (var unit : site.path("unitList")) {
            if (context.unitCode().equals(text(unit, "unitCode", 100))) {
                if (!context.currency().equals(text(unit, "currency", 8))) throw bad("스토어 통화가 변경되었습니다. 자산 편집에서 스토어를 다시 저장해 주세요.");
                found = true;
            }
        }
        if (!found) throw bad("저장한 아임웹 스토어에 접근할 수 없습니다. 자산 편집에서 다시 확인해 주세요.");
        return site.path("unitList").size() == 1;
    }

    static MultiValueMap<String, String> unitQuery(ImwebAccessService.Context context) {
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>(); query.add("unitCode", context.unitCode()); return query;
    }

    /** One token refresh per read operation. Writes deliberately do not use this retry wrapper. */
    static final class Reader {
        private ImwebAccessService.Context context;
        private final ImwebConnectionBusiness connections;
        private final ImwebAccessService access;
        private final ImwebApiClient client;
        private boolean refreshed;
        Reader(ImwebAccessService.Context context, ImwebConnectionBusiness connections, ImwebAccessService access, ImwebApiClient client) {
            this.context = context; this.connections = connections; this.access = access; this.client = client;
        }
        ImwebAccessService.Context context() { return context; }
        void check() { access.requireUnchanged(context); }
        JsonNode read(String path, MultiValueMap<String, String> query) { return execute(token -> client.read(token, path, query)); }
        JsonNode site() { return execute(client::site); }
        private JsonNode execute(java.util.function.Function<String, JsonNode> operation) {
            check();
            try { return operation.apply(context.accessToken()); }
            catch (ImwebApiClient.AuthenticationException expired) {
                if (refreshed) throw expired;
                context = connections.refresh(context); refreshed = true; check();
                return operation.apply(context.accessToken());
            }
        }
    }

    private static RuntimeException tooManyOrders() {
        return bad("조회할 주문이 많거나 응답이 지연되어 전체 성과를 집계하지 못했습니다. 기간을 줄여 다시 조회해 주세요.");
    }
}
