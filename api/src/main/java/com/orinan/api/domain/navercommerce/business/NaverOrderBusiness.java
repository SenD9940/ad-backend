package com.orinan.api.domain.navercommerce.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.navercommerce.client.NaverOrderClient;
import com.orinan.api.domain.navercommerce.controller.model.NaverOrderActionRequest;
import com.orinan.api.domain.navercommerce.controller.model.NaverOrderResponse.*;
import com.orinan.api.domain.navercommerce.controller.model.NaverStoreResponse.Store;
import com.orinan.api.domain.navercommerce.service.*;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import com.orinan.api.domain.platformconnection.service.NaverConnectionService;
import com.orinan.api.domain.platformconnection.service.NaverConnectionService.Credentials;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;

@Business @RequiredArgsConstructor
public class NaverOrderBusiness {
    private final NaverStoreAccessService access;
    private final NaverConnectionService connections;
    private final NaverCommerceClient commerce;
    private final NaverOrderClient client;
    private final NaverOrderProjection projection;
    private final NaverOrderWriteGuard guard;
    private final NaverOrderSessionService session;
    private static final Set<String> RANGES = Set.of("ORDERED_DATETIME", "PAYED_DATETIME", "DISPATCHED_DATETIME", "CLAIM_REQUESTED_DATETIME", "CLAIM_COMPLETED_DATETIME");
    private static final Set<String> STATUSES = Set.of("PAYMENT_WAITING", "PAYED", "DELIVERING", "DELIVERED", "PURCHASE_DECIDED", "EXCHANGED", "CANCELED", "RETURNED", "CANCELED_BY_NOPAYMENT");

    public Options options(Long workspaceId, Long assetId, Long userId) {
        return read(workspaceId, assetId, userId, context -> projection.options(context.channels().size() == 1));
    }
    public Orders orders(Long workspaceId, Long assetId, Long userId, LocalDate date, String rangeType, String status, int page, int size) {
        page(page, size);
        if (date == null || date.isAfter(LocalDate.now(SeoulDateTimes.ZONE)) || !RANGES.contains(Objects.requireNonNullElse(rangeType, ""))
                || status != null && !STATUSES.contains(status)) throw bad("조회일·조회 기준·주문 상태를 확인해 주세요.");
        return read(workspaceId, assetId, userId, context -> {
            var from = date.atStartOfDay(SeoulDateTimes.ZONE).toOffsetDateTime();
            var to = date.plusDays(1).atStartOfDay(SeoulDateTimes.ZONE).toOffsetDateTime().minusNanos(1_000_000);
            var data = client.orders(context.credentials().clientId(), context.credentials().accessToken(), from, to,
                    rangeType, status, page, size, context.deadline()).path("data");
            var rows = array(data.path("contents"), size); var paging = data.path("pagination");
            if (integer(paging, "page") != page || integer(paging, "size") != size || !paging.path("hasNext").isBoolean()
                    || rows.isEmpty() && paging.path("hasNext").asBoolean()) throw invalid();
            List<Order> items = new ArrayList<>(); Set<String> ids = new HashSet<>();
            for (var row : rows) {
                var content = row.path("content"); var product = content.path("productOrder");
                if (!context.store().channelNo().equals(text(product, "merchantChannelId"))) continue;
                var order = projection.summary(content);
                if (!order.productOrderId().equals(text(row, "productOrderId")) || !ids.add(order.productOrderId())) throw invalid();
                items.add(order);
            }
            return new Orders(assetId, context.store().channelNo(), date, rangeType, status, List.copyOf(items), page, size,
                    paging.path("hasNext").asBoolean(), Instant.now(),
                    "한국 시간 기준 상품주문입니다. 상품 결제액은 배송비를 제외하며, 잔여 금액은 완료된 수량 클레임을 반영합니다. 페이지는 판매자 계정 기준으로 조회한 뒤 선택 채널만 표시합니다.");
        });
    }
    public Detail detail(Long workspaceId, Long assetId, Long userId, String productOrderId) {
        id(productOrderId);
        return read(workspaceId, assetId, userId, context -> loadDetail(context, productOrderId));
    }
    public ActionResult act(Long workspaceId, Long assetId, Long userId, String productOrderId, NaverOrderActionRequest request) {
        id(productOrderId); validate(request);
        var initial = read(workspaceId, assetId, userId, Function.identity());
        var lease = guard.acquire(initial.credentials().accountUid(), productOrderId, request.requestId());
        boolean uncertain = false, started = false;
        try {
            var prepared = read(workspaceId, assetId, userId, context -> new Prepared(context, loadDetail(context, productOrderId)));
            var context = prepared.context(); var detail = prepared.detail();
            if (!initial.credentials().accountUid().equals(context.credentials().accountUid())
                    || !initial.store().connectionId().equals(context.store().connectionId())
                    || !initial.store().channelNo().equals(context.store().channelNo())) throw bad("스토어 연결이 변경되었습니다. 주문을 다시 조회해 주세요.");
            if (!detail.version().equals(request.expectedVersion())) throw bad("주문·배송지·클레임 정보가 변경되었습니다. 최신 내용을 다시 확인해 주세요.");
            if (detail.actions().stream().noneMatch(action -> action.code().equals(request.action())))
                throw bad("현재 주문 상태에서는 이 처리를 할 수 없습니다. 주문을 다시 조회하거나 스마트스토어센터에서 확인해 주세요.");
            if ("DISPATCH".equals(request.action()) && detail.order().paymentDate() != null
                    && request.dispatchDate().isBefore(detail.order().paymentDate())) throw bad("발송 일시는 결제 이후로 입력해 주세요.");
            unchanged(workspaceId, userId, context); guard.requireOwned(lease); session.requireCurrent(userId);
            started = true;
            var response = switch (request.action()) {
                case "CONFIRM" -> client.confirm(context.credentials().accessToken(), productOrderId);
                case "DISPATCH" -> client.dispatch(context.credentials().accessToken(), productOrderId, dispatchFields(request));
                case "APPROVE_CANCEL" -> client.approveCancel(context.credentials().accessToken(), productOrderId);
                case "APPROVE_RETURN" -> client.approveReturn(context.credentials().accessToken(), productOrderId);
                default -> throw new IllegalStateException("Unsupported action");
            };
            return result(response, productOrderId, request.action());
        } catch (NaverOrderClient.UnknownWrite failure) { uncertain = true; throw failure; }
        catch (ApiException failure) {
            if (started && failure.getCodeIfs().getHttpStatusCode() >= 500) { uncertain = true; throw new NaverOrderClient.UnknownWrite(); }
            throw failure;
        } catch (RuntimeException failure) {
            if (started) { uncertain = true; throw new NaverOrderClient.UnknownWrite(); }
            throw failure;
        } finally {
            // Redis cleanup must not turn a confirmed provider result into an apparent failed write.
            try { if (uncertain) guard.hold(lease); else guard.release(lease); }
            catch (RuntimeException ignored) { /* Lease expires; the consumed request ID still prevents replay. */ }
        }
    }
    public Settlements settlements(Long workspaceId, Long assetId, Long userId, LocalDate since, LocalDate until, int page, int size) {
        page(page, size);
        if (since == null || until == null || since.isAfter(until) || ChronoUnit.DAYS.between(since, until) >= 28)
            throw bad("정산은 최대 28일의 정산 예정일 기간으로 조회해 주세요.");
        return read(workspaceId, assetId, userId, context -> {
            if (context.channels().size() != 1) throw bad("일별 정산은 판매자 계정 전체 자료입니다. 여러 채널이 연결된 계정은 스마트스토어센터에서 정산을 확인해 주세요.");
            var data = client.settlement(context.credentials().clientId(), context.credentials().accessToken(), since, until, page, size, context.deadline());
            var rows = array(data.path("elements"), size); var pagination = data.path("pagination");
            long total = integer(pagination, "totalElements"), pages = integer(pagination, "totalPages");
            if (integer(pagination, "page") != page || integer(pagination, "size") != size
                    || rows.size() != Math.min(size, Math.max(0, total - (long) (page - 1) * size))
                    || pages != (total + size - 1) / size) throw invalid();
            List<SettlementDay> items = new ArrayList<>();
            for (var row : rows) {
                LocalDate expected = date(row, "settleExpectDate");
                if (expected == null || expected.isBefore(since) || expected.isAfter(until)) throw invalid();
                items.add(new SettlementDay(date(row, "settleBasisStartDate"), date(row, "settleBasisEndDate"), expected,
                        date(row, "settleCompleteDate"), optionalText(row, "settleMethodType"), money(row, "settleAmount"),
                        money(row, "paySettleAmount"), money(row, "commissionSettleAmount"), money(row, "benefitSettleAmount"),
                        money(row, "deductionRestoreSettleAmount")));
            }
            return new Settlements(assetId, context.store().channelNo(), since, until, "SETTLEMENT_EXPECTED", List.copyOf(items), page, size,
                    page < pages, Instant.now(), "정산 예정일 기준 판매자 계정의 일별 정산 자료입니다. 주문 결제액·실제 입금액과 다르며, 정산 완료일이 없는 항목은 완료로 표시하지 않습니다. 금액에는 차감·복원에 따른 음수가 포함될 수 있습니다.");
        });
    }
    private Detail loadDetail(Context context, String id) {
        var rows = array(client.detail(context.credentials().clientId(), context.credentials().accessToken(), id, context.deadline()).path("data"), 1);
        if (rows.size() != 1 || !id.equals(text(rows.get(0).path("productOrder"), "productOrderId")))
            throw bad("상품주문을 찾지 못했습니다. 상품주문번호와 선택한 스토어를 확인해 주세요.");
        return projection.detail(context.store().assetId(), context.store().channelNo(), rows.get(0));
    }
    private ActionResult result(JsonNode response, String id, String action) {
        try {
            var data = response.path("data");
            var successes = optionalArray(data, "CONFIRM".equals(action) ? "successProductOrderInfos" : "successProductOrderIds");
            var failures = optionalArray(data, "failProductOrderInfos");
            if (successes.size() == 1 && failures.isEmpty()) {
                var value = successes.get(0);
                String returned = "CONFIRM".equals(action) ? text(value, "productOrderId") : value.isString() ? value.asString() : null;
                if (!id.equals(returned)) throw new NaverOrderClient.UnknownWrite();
                boolean changed = "CONFIRM".equals(action) && value.path("isReceiverAddressChanged").asBoolean(false);
                return new ActionResult(id, action, "ACCEPTED", changed
                        ? "네이버가 발주 확인을 처리했습니다. 수령인 주소 변경이 있으므로 최신 배송지를 다시 확인해 주세요."
                        : "네이버가 요청을 처리했습니다. 최신 주문 상태를 다시 조회해 주세요. 취소·반품 승인은 구매자의 실제 환불 입금 완료를 의미하지 않습니다.");
            }
            if (successes.isEmpty() && failures.size() == 1 && id.equals(text(failures.get(0), "productOrderId")))
                throw bad("네이버가 주문 처리를 거절했습니다. 최신 주문 상태와 배송·클레임 정보를 확인해 주세요.");
            throw new NaverOrderClient.UnknownWrite();
        } catch (ApiException failure) { throw failure; }
        catch (RuntimeException failure) { throw new NaverOrderClient.UnknownWrite(); }
    }
    private void validate(NaverOrderActionRequest r) {
        if (r == null || r.action() == null || !Set.of("CONFIRM", "DISPATCH", "APPROVE_CANCEL", "APPROVE_RETURN").contains(r.action())
                || r.expectedVersion() == null || !r.expectedVersion().matches("[a-f0-9]{64}")
                || r.requestId() == null || !r.requestId().matches("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))
            throw bad("최신 주문에서 처리 내용을 다시 선택해 주세요.");
        if ("DISPATCH".equals(r.action())) {
            projection.validateDispatch(r);
            if (r.dispatchDate() == null || r.dispatchDate().isAfter(OffsetDateTime.now(SeoulDateTimes.ZONE))) throw bad("실제 발송 일시는 현재 시각 이전으로 입력해 주세요.");
        } else if (r.deliveryMethod() != null || r.deliveryCompanyCode() != null || r.trackingNumber() != null || r.dispatchDate() != null)
            throw bad("선택한 처리에 필요한 항목만 입력해 주세요.");
        if ("APPROVE_RETURN".equals(r.action())) {
            if (!Boolean.TRUE.equals(r.returnReceived())) throw bad("반품 상품 수령과 반품 비용 확인 후 승인해 주세요.");
        } else if (r.returnReceived() != null) throw bad("선택한 처리에 필요한 항목만 입력해 주세요.");
    }
    private Map<String, Object> dispatchFields(NaverOrderActionRequest r) {
        var fields = new LinkedHashMap<String, Object>(); fields.put("deliveryMethod", r.deliveryMethod());
        fields.put("dispatchDate", r.dispatchDate().withOffsetSameInstant(ZoneOffset.ofHours(9)).toString());
        if (r.deliveryCompanyCode() != null) fields.put("deliveryCompanyCode", r.deliveryCompanyCode());
        if (r.trackingNumber() != null) fields.put("trackingNumber", r.trackingNumber());
        return fields;
    }
    private <T> T read(Long ws, Long asset, Long user, Function<Context, T> operation) {
        session.requireCurrent(user);
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        Store store = access.get(ws, asset, user); Credentials credentials = connections.getCredentials(ws, store.connectionId(), user);
        if (credentials.expiresAt() == null || !credentials.expiresAt().isAfter(SeoulDateTimes.now().plusMinutes(1)))
            credentials = refresh(ws, user, store, credentials);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                var channels = commerce.getChannels(credentials.accessToken());
                if (channels.stream().noneMatch(channel -> "STOREFARM".equals(channel.channelType()) && store.channelNo().equals(Long.toString(channel.channelNo()))))
                    throw bad("저장한 스마트스토어 채널에 접근할 수 없습니다. 자산 편집에서 연결을 확인해 주세요.");
                var context = new Context(store, credentials, channels, deadline); unchanged(ws, user, context);
                T result = operation.apply(context); unchanged(ws, user, context); session.requireCurrent(user); return result;
            } catch (NaverCommerceClient.AuthenticationException expired) {
                if (attempt == 1) { connections.markRequiresReauth(ws, store.connectionId(), user, credentials); throw expired; }
                credentials = refresh(ws, user, store, credentials);
            }
        }
        throw new IllegalStateException("Unreachable");
    }
    private Credentials refresh(Long ws, Long user, Store store, Credentials expected) {
        access.requireUnchanged(ws, user, store); connections.requireUnchanged(ws, store.connectionId(), user, expected);
        var token = commerce.issueToken(expected.clientId(), expected.clientSecret(), expected.tokenType(), expected.accountId());
        var seller = commerce.getSellerAccount(token.accessToken());
        if (!expected.accountUid().equals(seller.accountUid())) { connections.markRequiresReauth(ws, store.connectionId(), user, expected); throw bad("인증된 판매자가 변경되었습니다. 스마트스토어를 다시 연결해 주세요."); }
        access.requireUnchanged(ws, user, store); return connections.updateToken(ws, store.connectionId(), user, expected, token);
    }
    private void unchanged(Long ws, Long user, Context context) {
        access.requireUnchanged(ws, user, context.store()); connections.requireUnchanged(ws, context.store().connectionId(), user, context.credentials());
    }
    private void id(String value) { if (value == null || !value.matches("[1-9][0-9]{0,19}")) throw bad("상품주문번호를 확인해 주세요."); }
    private void page(int page, int size) { if (page < 1 || page > 10000 || size < 1 || size > 100) throw bad("페이지와 조회 개수를 확인해 주세요."); }
    private List<JsonNode> array(JsonNode value, int maximum) {
        if (!value.isArray() || value.size() > maximum) throw invalid();
        var list = new ArrayList<JsonNode>(); value.forEach(list::add); return list;
    }
    private List<JsonNode> optionalArray(JsonNode node, String name) { var value = node.path(name); return value.isMissingNode() || value.isNull() ? List.of() : array(value, 1); }
    private String optionalText(JsonNode node, String key) {
        var value = node.path(key); if (value.isNull() || value.isMissingNode()) return null;
        if (!value.isString() || value.asString().length() > 4096) throw invalid(); return value.asString();
    }
    private String text(JsonNode node, String key) { String value = optionalText(node, key); if (value == null || value.isBlank()) throw invalid(); return value; }
    private long integer(JsonNode node, String key) { var value = node.path(key); if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) throw invalid(); return value.asLong(); }
    private LocalDate date(JsonNode node, String key) { String value = optionalText(node, key); if (value == null) return null; try { return LocalDate.parse(value); } catch (RuntimeException failure) { throw invalid(); } }
    private BigDecimal money(JsonNode node, String key) {
        var value = node.path(key); if (value.isNull() || value.isMissingNode()) return null;
        if (!value.isNumber()) throw invalid(); var amount = value.decimalValue();
        if (amount.precision() > 24 || amount.precision() - amount.scale() > 24 || amount.scale() > 8) throw invalid(); return amount;
    }
    private ApiException bad(String text) { return new ApiException(ApiCode.BAD_REQUEST, text); }
    private ApiException invalid() { return new ApiException(ApiCode.SERVER_ERROR, "네이버 주문·정산 응답을 확인하지 못했습니다. 다시 조회해 주세요."); }
    private record Context(Store store, Credentials credentials, List<NaverCommerceClient.Channel> channels, long deadline) {
        @Override public String toString() { return "OrderReadContext[REDACTED]"; }
    }
    private record Prepared(Context context, Detail detail) { @Override public String toString() { return "OrderPrepared[REDACTED]"; } }
}
