package com.orinan.api.domain.navercommerce.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.navercommerce.client.NaverStoreClient;
import com.orinan.api.domain.navercommerce.controller.model.NaverStoreResponse.*;
import com.orinan.api.domain.navercommerce.service.NaverSalesAggregator;
import com.orinan.api.domain.navercommerce.service.NaverStoreAccessService;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import com.orinan.api.domain.platformconnection.service.NaverConnectionService;
import com.orinan.api.domain.platformconnection.service.NaverConnectionService.Credentials;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Business
@RequiredArgsConstructor
public class NaverStoreBusiness {
    private final NaverStoreAccessService access;
    private final NaverConnectionService connections;
    private final NaverCommerceClient commerce;
    private final NaverStoreClient client;
    private final NaverSalesAggregator aggregator;
    private static final int MAX_ORDER_PAGES = 62;
    private static final long MAX_QUERY_NANOS = 45_000_000_000L;

    public List<Store> stores(Long workspaceId, Long userId) { return access.list(workspaceId, userId); }

    public Products products(Long workspaceId, Long assetId, Long userId, int page, int size) {
        if (page < 1 || page > 10000 || size < 1 || size > 100) {
            throw new ApiException(ApiCode.BAD_REQUEST, "상품 페이지는 1 이상, 한 페이지의 상품 수는 1~100개로 지정해 주세요.");
        }
        return read(workspaceId, assetId, userId, context -> {
            // Product search is seller-wide and only exposes channelServiceType.
            // Verify that this seller has exactly one accessible STOREFARM before attributing results.
            if (context.channels().stream().filter(channel -> "STOREFARM".equals(channel.channelType())).count() != 1) {
                throw new ApiException(ApiCode.BAD_REQUEST, "판매자의 스마트스토어 채널을 하나로 확인할 수 없어 상품을 구분할 수 없습니다.");
            }
            var result = client.searchProducts(context.credentials().clientId(), context.credentials().accessToken(),
                    "STOREFARM", page, size, context.deadlineNanos());
            var items = result.products().stream().map(product -> new Product(product.productId(), product.name(),
                    product.status(), product.imageUrl(), product.salePrice(), product.discountedPrice(), product.stockQuantity())).toList();
            return new Products(assetId, context.store().channelNo(), items, page, size, result.hasNext(), null, Instant.now());
        });
    }

    public Sales sales(Long workspaceId, Long assetId, Long userId, LocalDate since, LocalDate until) {
        validatePeriod(since, until);
        return read(workspaceId, assetId, userId, context -> {
            var lines = new ArrayList<NaverStoreClient.OrderLine>();
            int calls = 0;
            for (LocalDate day = since; !day.isAfter(until); day = day.plusDays(1)) {
                var from = day.atStartOfDay(SeoulDateTimes.ZONE).toOffsetDateTime();
                var to = day.plusDays(1).atStartOfDay(SeoulDateTimes.ZONE).toOffsetDateTime().minusNanos(1_000_000);
                int page = 1;
                while (true) {
                    if (++calls > MAX_ORDER_PAGES || System.nanoTime() >= context.deadlineNanos()) {
                        throw new ApiException(ApiCode.BAD_REQUEST, "조회할 주문이 많아 전체 성과를 집계하지 못했습니다. 기간을 줄여 다시 조회해 주세요.");
                    }
                    unchanged(workspaceId, userId, context);
                    var result = client.getOrders(context.credentials().clientId(), context.credentials().accessToken(),
                            Long.parseLong(context.store().channelNo()), from, to, page, 300, context.deadlineNanos());
                    lines.addAll(result.orders());
                    if (!result.hasNext()) break;
                    page++;
                }
            }
            return aggregator.aggregate(context.store(), since, until, lines);
        });
    }

    private <T> T read(Long workspaceId, Long assetId, Long userId, Function<ReadContext, T> request) {
        // One deadline includes queueing, page retries, and authentication refreshes for this report.
        long deadlineNanos = System.nanoTime() + MAX_QUERY_NANOS;
        var store = access.get(workspaceId, assetId, userId);
        var credentials = connections.getCredentials(workspaceId, store.connectionId(), userId);
        if (credentials.expiresAt() == null || !credentials.expiresAt().isAfter(SeoulDateTimes.now().plusMinutes(1))) {
            credentials = refresh(workspaceId, userId, store, credentials);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                var channels = commerce.getChannels(credentials.accessToken());
                if (channels.stream().noneMatch(channel -> "STOREFARM".equals(channel.channelType())
                        && Long.toString(channel.channelNo()).equals(store.channelNo()))) {
                    throw new ApiException(ApiCode.BAD_REQUEST, "저장한 스마트스토어 채널에 접근할 수 없습니다. 자산 편집에서 다시 확인해 주세요.");
                }
                var context = new ReadContext(store, credentials, channels, deadlineNanos);
                unchanged(workspaceId, userId, context);
                T result = request.apply(context);
                unchanged(workspaceId, userId, context);
                return result;
            } catch (NaverCommerceClient.AuthenticationException expired) {
                if (attempt == 1) {
                    connections.markRequiresReauth(workspaceId, store.connectionId(), userId, credentials);
                    throw expired;
                }
                credentials = refresh(workspaceId, userId, store, credentials);
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    private Credentials refresh(Long workspaceId, Long userId, Store store, Credentials expected) {
        access.requireUnchanged(workspaceId, userId, store);
        connections.requireUnchanged(workspaceId, store.connectionId(), userId, expected);
        var token = commerce.issueToken(expected.clientId(), expected.clientSecret(), expected.tokenType(), expected.accountId());
        var account = commerce.getSellerAccount(token.accessToken());
        if (!expected.accountUid().equals(account.accountUid())) {
            connections.markRequiresReauth(workspaceId, store.connectionId(), userId, expected);
            throw new ApiException(ApiCode.BAD_REQUEST, "인증된 판매자가 변경되었습니다. 네이버 스마트스토어를 다시 연결해 주세요.");
        }
        access.requireUnchanged(workspaceId, userId, store);
        return connections.updateToken(workspaceId, store.connectionId(), userId, expected, token);
    }

    private void unchanged(Long workspaceId, Long userId, ReadContext context) {
        access.requireUnchanged(workspaceId, userId, context.store());
        connections.requireUnchanged(workspaceId, context.store().connectionId(), userId, context.credentials());
    }

    private void validatePeriod(LocalDate since, LocalDate until) {
        if (since == null || until == null || since.isAfter(until)
                || ChronoUnit.DAYS.between(since, until) >= 31 || until.isAfter(LocalDate.now(SeoulDateTimes.ZONE))) {
            throw new ApiException(ApiCode.BAD_REQUEST, "판매 성과는 오늘까지 최대 31일의 기간으로 조회해 주세요.");
        }
    }

    private record ReadContext(Store store, Credentials credentials, List<NaverCommerceClient.Channel> channels,
                               long deadlineNanos) {
        @Override public String toString() { return "StoreReadContext[REDACTED]"; }
    }
}
