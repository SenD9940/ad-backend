package com.orinan.api.domain.navercommerce.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.aistudio.service.AiStudioService;
import com.orinan.api.domain.aistudio.service.AiStudioExport;
import com.orinan.api.domain.navercommerce.client.NaverProductCreationClient;
import com.orinan.api.domain.navercommerce.controller.model.NaverProductCreateRequest;
import com.orinan.api.domain.navercommerce.controller.model.NaverProductCreationResponse.*;
import com.orinan.api.domain.navercommerce.controller.model.NaverStoreResponse.Store;
import com.orinan.api.domain.navercommerce.service.NaverProductImageValidator;
import com.orinan.api.domain.navercommerce.service.NaverProductNoticeSchema;
import com.orinan.api.domain.navercommerce.service.NaverStoreAccessService;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import com.orinan.api.domain.platformconnection.service.NaverConnectionService;
import com.orinan.api.domain.platformconnection.service.NaverConnectionService.Credentials;
import com.orinan.db.aistudio.enums.AiStudioKind;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

@Business
public class NaverProductCreationBusiness {
    private final NaverStoreAccessService access;
    private final NaverConnectionService connections;
    private final NaverCommerceClient commerce;
    private final NaverProductCreationClient client;
    private final NaverProductImageValidator images;
    private final NaverProductNoticeSchema notices;
    private final AiStudioService studio;
    // Image calls for one seller must never overlap. App stripes also bound memory.
    private final ReentrantLock[] writes = new ReentrantLock[64];
    private static final List<DeliveryCompany> COURIERS = List.of(
            new DeliveryCompany("CJGLS", "CJ대한통운"), new DeliveryCompany("HYUNDAI", "롯데택배"),
            new DeliveryCompany("HANJIN", "한진택배"), new DeliveryCompany("KGB", "로젠택배"),
            new DeliveryCompany("EPOST", "우체국택배"), new DeliveryCompany("KDEXP", "경동택배"),
            new DeliveryCompany("DAESIN", "대신택배"), new DeliveryCompany("ILYANG", "일양로지스"),
            new DeliveryCompany("CHUNIL", "천일택배"), new DeliveryCompany("CVSNET", "GSPostbox택배"),
            new DeliveryCompany("CUPARCEL", "CU편의점택배"), new DeliveryCompany("REGISTPOST", "우편등기"),
            new DeliveryCompany("CH1", "기타택배"));

    public NaverProductCreationBusiness(NaverStoreAccessService access, NaverConnectionService connections,
            NaverCommerceClient commerce, NaverProductCreationClient client,
            NaverProductImageValidator images, NaverProductNoticeSchema notices, AiStudioService studio) {
        this.access = access; this.connections = connections; this.commerce = commerce;
        this.client = client; this.images = images; this.notices = notices;
        this.studio = studio;
        for (int i = 0; i < writes.length; i++) writes[i] = new ReentrantLock(true);
    }

    public Options options(Long workspaceId, Long assetId, Long userId) {
        return read(workspaceId, assetId, userId, context -> {
            var credentials = context.credentials();
            return new Options(client.categories(credentials.clientId(), credentials.accessToken(), context.deadline()),
                    client.origins(credentials.clientId(), credentials.accessToken(), context.deadline()),
                    client.addresses(credentials.clientId(), credentials.accessToken(), context.deadline()), COURIERS);
        });
    }

    public Notices notices(Long workspaceId, Long assetId, Long userId, String categoryId) {
        return read(workspaceId, assetId, userId, context -> new Notices(notices.available(client.noticeTypes(
                context.credentials().clientId(), context.credentials().accessToken(), categoryId, context.deadline()))));
    }

    public Created create(Long workspaceId, Long assetId, Long userId, NaverProductCreateRequest request,
                          List<MultipartFile> files) {
        // Establish local authorization before decoding uploads; all validation finishes before the first write.
        access.get(workspaceId, assetId, userId);
        var validatedImages = images.validate(files);
        validateLocal(request);
        Map<String, Object> notice = notices.payload(request.noticeType(), request.noticeFields());
        var studioDetail = studioDetail(workspaceId, userId, request.studioOutputId());
        var context = read(workspaceId, assetId, userId, current -> {
            validateRemote(current, request);
            return current;
        });
        var lock = writes[Math.floorMod(context.credentials().clientId().hashCode(), writes.length)];
        boolean locked = false;
        try {
            locked = lock.tryLock(45, TimeUnit.SECONDS);
            if (!locked) throw bad("다른 상품 등록을 처리하고 있습니다. 잠시 후 다시 등록해 주세요.");
            unchanged(workspaceId, userId, context);
            var uploaded = client.uploadImages(context.credentials().accessToken(), validatedImages);
            unchanged(workspaceId, userId, context);
            String detailHtml = null;
            if (studioDetail != null) {
                var detailImage = new NaverProductImageValidator.ImageData(studioDetail.imageBytes(),
                        studioDetail.imageContentType(), "studio-detail" + ("image/png".equals(studioDetail.imageContentType()) ? ".png" : ".jpg"));
                var detailUrls = client.uploadImages(context.credentials().accessToken(), List.of(detailImage));
                unchanged(workspaceId, userId, context);
                detailHtml = studioDetail.detailHtml().replace("{{STUDIO_IMAGE}}", HtmlUtils.htmlEscape(detailUrls.get(0)));
            }
            var result = client.create(context.credentials().accessToken(), payload(request, uploaded, notice, detailHtml));
            try { unchanged(workspaceId, userId, context); }
            catch (ApiException changed) { return unknown(); }
            return result;
        } catch (NaverProductCreationClient.UnknownWrite uncertain) {
            return unknown();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw bad("상품 등록 대기가 중단되었습니다. 상품 등록은 시작하지 않았습니다.");
        } finally {
            if (locked) lock.unlock();
        }
    }

    private void validateLocal(NaverProductCreateRequest request) {
        if (request.studioOutputId() == null && blank(request.detailContent())) throw bad("상세 설명을 입력해 주세요.");
        if (request.studioOutputId() != null && request.studioOutputId() <= 0) throw bad("AI 상세페이지를 다시 선택해 주세요.");
        if (request.deliveryFeeType() == NaverProductCreateRequest.DeliveryFeeType.FREE && request.deliveryFee() != 0)
            throw bad("무료 배송의 배송비는 0원으로 입력해 주세요.");
        if (request.deliveryFeeType() != NaverProductCreateRequest.DeliveryFeeType.FREE && request.deliveryFee() <= 0)
            throw bad("유료·조건부 무료 배송의 기본 배송비를 입력해 주세요.");
        if (request.deliveryFeeType() == NaverProductCreateRequest.DeliveryFeeType.CONDITIONAL_FREE
                && (request.freeConditionalAmount() == null || request.freeConditionalAmount() <= 0))
            throw bad("무료 배송 조건 금액을 입력해 주세요.");
        if (request.originAreaCode().startsWith("02") && blank(request.importer())) throw bad("수입 상품의 수입사명을 입력해 주세요.");
        if (request.originAreaCode().equals("04") && blank(request.originAreaContent())) throw bad("원산지 내용을 직접 입력해 주세요.");
        if (COURIERS.stream().noneMatch(c -> c.code().equals(request.deliveryCompany()))) throw bad("지원하는 택배사를 선택해 주세요.");
    }

    private void validateRemote(Context context, NaverProductCreateRequest request) {
        var c = context.credentials();
        if (client.categories(c.clientId(), c.accessToken(), context.deadline()).stream().noneMatch(x -> x.id().equals(request.categoryId())))
            throw bad("현재 등록 가능한 최종 상품 카테고리를 선택해 주세요.");
        var origins = client.origins(c.clientId(), c.accessToken(), context.deadline());
        if (origins.stream().noneMatch(x -> x.code().equals(request.originAreaCode()))
                || origins.stream().anyMatch(x -> x.code().startsWith(request.originAreaCode()) && x.code().length() > request.originAreaCode().length()))
            throw bad("원산지의 최종 지역을 선택해 주세요.");
        var addresses = client.addresses(c.clientId(), c.accessToken(), context.deadline());
        requireAddress(addresses, request.shippingAddressId(), "RELEASE");
        requireAddress(addresses, request.returnAddressId(), "REFUND_OR_EXCHANGE");
        if (!client.noticeTypes(c.clientId(), c.accessToken(), request.categoryId(), context.deadline()).contains(request.noticeType()))
            throw bad("선택한 카테고리에 맞는 상품정보제공고시 유형을 선택해 주세요.");
    }

    private void requireAddress(List<Address> addresses, String id, String type) {
        if (addresses.stream().noneMatch(a -> a.id().equals(id) && a.type().equals(type) && !a.overseas()))
            throw bad("현재 판매자 주소록에 저장된 국내 출고지와 반품/교환지를 선택해 주세요.");
    }

    private AiStudioExport studioDetail(Long workspaceId, Long userId, Long outputId) {
        if (outputId == null) return null;
        var detail = studio.exportOutput(workspaceId, userId, outputId);
        if (detail.kind() != AiStudioKind.DETAIL_PAGE || blank(detail.detailHtml())
                || !detail.detailHtml().contains("{{STUDIO_IMAGE}}")) throw bad("완성된 AI 상세페이지를 선택해 주세요.");
        return detail;
    }

    private Map<String, Object> payload(NaverProductCreateRequest r, List<String> urls, Map<String, Object> notice, String studioHtml) {
        var image = new LinkedHashMap<String, Object>();
        image.put("representativeImage", Map.of("url", urls.get(0)));
        if (urls.size() > 1) image.put("optionalImages", urls.subList(1, urls.size()).stream().map(url -> Map.of("url", url)).toList());
        var origin = new LinkedHashMap<String, Object>();
        origin.put("originAreaCode", r.originAreaCode());
        if (!blank(r.importer())) origin.put("importer", r.importer().strip());
        if (!blank(r.originAreaContent())) origin.put("content", r.originAreaContent().strip());
        var fee = new LinkedHashMap<String, Object>();
        fee.put("deliveryFeeType", r.deliveryFeeType().name()); fee.put("baseFee", r.deliveryFee()); fee.put("deliveryFeePayType", "PREPAID");
        if (r.deliveryFeeType() == NaverProductCreateRequest.DeliveryFeeType.CONDITIONAL_FREE) fee.put("freeConditionalAmount", r.freeConditionalAmount());
        var product = new LinkedHashMap<String, Object>();
        product.put("statusType", "SALE"); product.put("saleType", "NEW"); product.put("leafCategoryId", r.categoryId());
        product.put("name", r.name().strip()); product.put("salePrice", r.salePrice()); product.put("stockQuantity", r.stockQuantity());
        // Only saved, server-rendered studio HTML is trusted. Browser text is always escaped.
        String extraDetail = blank(r.detailContent()) ? "" : "<div>" + HtmlUtils.htmlEscape(r.detailContent().strip()).replace("\r\n", "\n").replace("\n", "<br>") + "</div>";
        product.put("detailContent", (studioHtml == null ? "" : studioHtml) + extraDetail);
        product.put("images", image);
        product.put("deliveryInfo", Map.of("deliveryType", "DELIVERY", "deliveryAttributeType", "NORMAL",
                "deliveryCompany", r.deliveryCompany(), "deliveryFee", fee,
                "claimDeliveryInfo", Map.of("returnDeliveryFee", r.returnDeliveryFee(), "exchangeDeliveryFee", r.exchangeDeliveryFee(),
                        "shippingAddressId", Long.parseLong(r.shippingAddressId()), "returnAddressId", Long.parseLong(r.returnAddressId()),
                        "returnDeliveryCompanyPriorityType", "PRIMARY")));
        product.put("detailAttribute", Map.of("originAreaInfo", origin, "taxType", r.taxType().name(),
                "minorPurchasable", r.minorPurchasable(),
                "afterServiceInfo", Map.of("afterServiceTelephoneNumber", r.afterServiceTelephoneNumber().strip(),
                        "afterServiceGuideContent", r.afterServiceGuideContent().strip()), "productInfoProvidedNotice", notice));
        return Map.of("originProduct", product, "smartstoreChannelProduct", Map.of(
                "naverShoppingRegistration", r.naverShoppingRegistration(), "channelProductDisplayStatusType", r.displayStatus().name()));
    }

    private <T> T read(Long workspaceId, Long assetId, Long userId, Function<Context, T> operation) {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        var store = access.get(workspaceId, assetId, userId);
        if (store.requiresReauth()) throw bad("스마트스토어를 다시 연결한 뒤 상품을 등록해 주세요.");
        var credentials = connections.getCredentials(workspaceId, store.connectionId(), userId);
        if (credentials.expiresAt() == null || !credentials.expiresAt().isAfter(SeoulDateTimes.now().plusMinutes(1)))
            credentials = refresh(workspaceId, userId, store, credentials);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                var channels = commerce.getChannels(credentials.accessToken()).stream().filter(x -> "STOREFARM".equals(x.channelType())).toList();
                // The creation API chooses the seller's SmartStore implicitly and takes no SmartStore channel ID.
                if (channels.size() != 1 || !Long.toString(channels.get(0).channelNo()).equals(store.channelNo()))
                    throw bad("저장한 스마트스토어를 판매자의 유일한 스마트스토어 채널로 확인할 수 없습니다. 자산 편집에서 다시 확인해 주세요.");
                var context = new Context(store, credentials, deadline);
                unchanged(workspaceId, userId, context);
                T result = operation.apply(context);
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
        var issued = commerce.issueToken(expected.clientId(), expected.clientSecret(), expected.tokenType(), expected.accountId());
        if (!expected.accountUid().equals(commerce.getSellerAccount(issued.accessToken()).accountUid())) {
            connections.markRequiresReauth(workspaceId, store.connectionId(), userId, expected);
            throw bad("인증된 판매자가 변경되었습니다. 스마트스토어를 다시 연결해 주세요.");
        }
        access.requireUnchanged(workspaceId, userId, store);
        return connections.updateToken(workspaceId, store.connectionId(), userId, expected, issued);
    }

    private void unchanged(Long workspaceId, Long userId, Context context) {
        access.requireUnchanged(workspaceId, userId, context.store());
        connections.requireUnchanged(workspaceId, context.store().connectionId(), userId, context.credentials());
    }
    private boolean blank(String s) { return s == null || s.isBlank(); }
    private ApiException bad(String message) { return new ApiException(ApiCode.BAD_REQUEST, message); }
    private Created unknown() { return new Created(Status.UNKNOWN, null, null,
            "상품 등록 결과를 확정할 수 없습니다. 중복 등록을 피하려면 스마트스토어센터의 상품 목록에서 확인한 뒤 다시 진행해 주세요."); }
    private record Context(Store store, Credentials credentials, long deadline) {
        @Override public String toString() { return "ProductCreationContext[REDACTED]"; }
    }
}
