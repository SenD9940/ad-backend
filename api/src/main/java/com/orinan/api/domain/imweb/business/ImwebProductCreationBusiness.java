package com.orinan.api.domain.imweb.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.domain.aistudio.service.AiStudioExport;
import com.orinan.api.domain.aistudio.service.AiStudioService;
import com.orinan.api.domain.imweb.client.ImwebApiClient;
import com.orinan.api.domain.imweb.controller.model.ImwebCommerceResponse.Created;
import com.orinan.api.domain.imweb.controller.model.ImwebProductCreateRequest;
import com.orinan.api.domain.imweb.service.ImwebAccessService;
import com.orinan.api.domain.navercommerce.service.NaverProductImageValidator;
import com.orinan.api.domain.navercommerce.service.NaverProductImageValidator.ImageData;
import com.orinan.db.aistudio.enums.AiStudioKind;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static com.orinan.api.domain.imweb.business.ImwebCommerceBusiness.*;
import static com.orinan.api.domain.imweb.service.ImwebCommerceData.*;

/** Create a hidden product first; publish only after its permanent detail image is in place. */
@Business
@RequiredArgsConstructor
public class ImwebProductCreationBusiness {
    private static final String PLACEHOLDER = "{{STUDIO_IMAGE}}";
    private final ImwebConnectionBusiness connections;
    private final ImwebAccessService access;
    private final ImwebApiClient client;
    private final NaverProductImageValidator images;
    private final AiStudioService studio;

    public Created create(Long workspaceId, Long assetId, Long userId, ImwebProductCreateRequest request,
                          List<MultipartFile> files) {
        var context = connections.context(workspaceId, assetId, userId);
        validate(request);
        AiStudioExport detail = detail(workspaceId, userId, request.studioOutputId());
        List<ImageData> uploads = new ArrayList<>();
        if (detail != null) uploads.add(new ImageData(detail.imageBytes(), detail.imageContentType(),
                "studio-detail" + ("image/png".equals(detail.imageContentType()) ? ".png" : ".jpg")));
        if (files != null && !files.isEmpty()) uploads.addAll(images.validate(files));
        validateImages(uploads);

        // Validate the authenticated site and category before making any changes.
        var reader = new Reader(context, connections, access, client);
        if (!singleUnitSite(reader.site(), context))
            throw bad("여러 언어 스토어가 있는 사이트의 상품 등록은 아임웹 관리자에서 진행해 주세요.");
        if (categories(reader.read("/products/shop-categories", unitQuery(context)))
                .stream().noneMatch(category -> category.code().equals(request.categoryCode())))
            throw bad("현재 아임웹 스토어에 있는 상품 카테고리를 선택해 주세요.");
        context = reader.context();
        access.requireUnchanged(context);
        JsonNode created = client.multipart(context.accessToken(), "/products", HttpMethod.POST,
                payload(context, request, detail == null ? uploads : uploads.subList(0, 1)));
        String id;
        String code;
        try {
            id = Long.toString(integer(created, "prodNo", 1, Long.MAX_VALUE));
            code = text(created, "prodCode", 100);
        } catch (RuntimeException malformed) {
            // The provider may already have created the product. Never replay its POST.
            throw new ImwebApiClient.UnknownWriteException();
        }
        boolean detailApplied = false;
        try {
            var failures = created.path("failUploadImages");
            if (!failures.isMissingNode() && !failures.isNull()
                    && (!failures.isArray() || !failures.isEmpty())) return pending(id, code, false);
            access.requireUnchanged(context);
            if (detail != null) {
                var product = client.read(context.accessToken(), "/products/" + id, unitQuery(context));
                if (!id.equals(Long.toString(integer(product, "prodNo", 1, Long.MAX_VALUE)))
                        || !context.siteCode().equals(text(product, "siteCode", 100))
                        || !context.unitCode().equals(text(product, "unitCode", 100))) throw invalid();
                // Only the AI image was uploaded initially, so its identity does not depend on returned ordering.
                if (!product.path("productImages").isArray() || product.path("productImages").size() != 1) throw invalid();
                String url = permanentImage(product.path("productImages"));
                var fields = new LinkedMultiValueMap<String, Object>();
                fields.add("unitCode", context.unitCode());
                fields.add("description", detail.detailHtml().replace(PLACEHOLDER, HtmlUtils.htmlEscape(url))
                        + plainHtml(request.detailContent()));
                access.requireUnchanged(context);
                var applied = client.multipart(context.accessToken(), "/products/" + id, HttpMethod.PATCH, fields);
                if (!applied.isBoolean() || !applied.asBoolean()) throw invalid();
                detailApplied = true;
                if (uploads.size() > 1) {
                    var additional = new LinkedMultiValueMap<String, Object>();
                    addImages(additional, "images", uploads.subList(1, uploads.size()));
                    access.requireUnchanged(context);
                    var uploaded = client.multipart(context.accessToken(), "/products/" + id + "/images", HttpMethod.POST, additional);
                    if (!uploaded.path("success").isArray() || uploaded.path("success").size() != uploads.size() - 1
                            || !uploaded.path("fail").isArray() || !uploaded.path("fail").isEmpty()) throw invalid();
                    for (var image : uploaded.path("success")) {
                        if (!image.isString() || !safeUrl(image.asString())) throw invalid();
                    }
                }
            }
            access.requireUnchanged(context);
            client.updateProductStatus(context.accessToken(), id, "sale");
            access.requireUnchanged(context);
            return new Created(id, code, "CREATED", detailApplied, "아임웹에 상품을 등록했습니다.");
        } catch (RuntimeException unfinished) {
            // A known product exists even when a later read, permission check or publish fails.
            return pending(id, code, detailApplied);
        }
    }

    private Created pending(String id, String code, boolean detailApplied) {
        return new Created(id, code, "DETAIL_PENDING", detailApplied,
                "상품은 생성되었지만 등록 절차를 모두 확인하지 못했습니다. 다시 등록하지 말고 아임웹 관리자에서 상품 "
                        + id + "의 이미지·상세 설명·판매 상태를 확인해 주세요.");
    }

    private AiStudioExport detail(Long workspaceId, Long userId, Long outputId) {
        if (outputId == null) return null;
        var result = studio.exportOutput(workspaceId, userId, outputId);
        if (result.kind() != AiStudioKind.DETAIL_PAGE || result.detailHtml() == null
                || result.detailHtml().indexOf(PLACEHOLDER) < 0
                || result.detailHtml().indexOf(PLACEHOLDER) != result.detailHtml().lastIndexOf(PLACEHOLDER))
            throw bad("완성된 AI 상세페이지를 선택해 주세요.");
        return result;
    }

    private void validate(ImwebProductCreateRequest r) {
        if (r == null || r.name() == null || r.name().isBlank() || r.name().length() > 100
                || r.categoryCode() == null || !r.categoryCode().matches("[A-Za-z0-9_-]{1,100}")
                || r.stockQuantity() == null || r.stockQuantity() < 1 || r.stockQuantity() > 999999999L
                || r.salePrice() == null || r.originalPrice() == null
                || r.salePrice().signum() <= 0 || r.originalPrice().compareTo(r.salePrice()) < 0
                || r.salePrice().scale() > 2 || r.originalPrice().scale() > 2
                || r.salePrice().precision() - r.salePrice().scale() > 12
                || r.originalPrice().precision() - r.originalPrice().scale() > 12)
            throw bad("상품 이름·카테고리·판매가·정상가·재고를 확인해 주세요. 정상가는 판매가 이상이어야 합니다.");
        if (r.studioOutputId() != null && r.studioOutputId() <= 0) throw bad("AI 상세페이지를 다시 선택해 주세요.");
        if (r.detailContent() != null && r.detailContent().length() > 50000) throw bad("상세 설명은 50,000자 이하로 입력해 주세요.");
        if (r.studioOutputId() == null && (r.detailContent() == null || r.detailContent().isBlank()))
            throw bad("상품 상세 설명을 입력하거나 AI 상세페이지를 선택해 주세요.");
    }

    private void validateImages(List<ImageData> uploads) {
        if (uploads.isEmpty() || uploads.size() > 10) throw bad("AI 상세페이지 이미지를 포함해 상품 이미지는 1~10장 첨부해 주세요.");
        long total = 0;
        for (var image : uploads) {
            if (image.bytes() == null || image.bytes().length == 0 || image.bytes().length > 10 * 1024 * 1024
                    || !List.of("image/png", "image/jpeg").contains(image.contentType()))
                throw bad("상품 이미지는 한 장당 10MB 이하의 PNG 또는 JPEG여야 합니다.");
            total += image.bytes().length;
        }
        if (total > 20 * 1024 * 1024) throw bad("AI 상세페이지 이미지를 포함한 상품 이미지의 합계는 20MB 이하여야 합니다.");
    }

    private MultiValueMap<String, Object> payload(ImwebAccessService.Context c, ImwebProductCreateRequest r, List<ImageData> uploads) {
        var fields = new LinkedMultiValueMap<String, Object>();
        fields.add("productBaseInfo[status]", "nosale");
        fields.add("productBaseInfo[productType]", "normal");
        fields.add("productBaseInfo[unitShopProductInfo][0][unitCode]", c.unitCode());
        fields.add("productBaseInfo[unitShopProductInfo][0][productName]", r.name().strip());
        fields.add("productBaseInfo[unitShopProductInfo][0][description]", plainHtml(r.detailContent()));
        fields.add("productBaseInfo[isUseStock]", "Y");
        fields.add("productBaseInfo[stock]", Long.toString(r.stockQuantity()));
        fields.add("productBaseInfo[isUnlimitedStock]", "N");
        fields.add("productClassificationInfo[categories][0]", r.categoryCode());
        fields.add("productPriceInfo[0][unitCode]", c.unitCode());
        fields.add("productPriceInfo[0][price]", r.salePrice().toPlainString());
        fields.add("productPriceInfo[0][originalPrice]", r.originalPrice().toPlainString());
        fields.add("productDiscountInfo[0][unitCode]", c.unitCode());
        fields.add("productDiscountInfo[0][coupon]", "N");
        fields.add("productDiscountInfo[0][point]", "N");
        fields.add("productDiscountInfo[0][shoppingGroup]", "N");
        fields.add("productDiscountInfo[0][givePointType]", "common");
        fields.add("productDiscountInfo[0][period]", "N");
        fields.add("productShippingSettingInfo[0][unitCode]", c.unitCode());
        fields.add("productShippingSettingInfo[0][useShippingNoticeTemplate]", "Y");
        addImages(fields, "productImages", uploads);
        return fields;
    }

    private void addImages(MultiValueMap<String, Object> fields, String partName, List<ImageData> uploads) {
        for (int index = 0; index < uploads.size(); index++) {
            var image = uploads.get(index);
            String filename = "product-" + index + ("image/png".equals(image.contentType()) ? ".png" : ".jpg");
            var resource = new ByteArrayResource(image.bytes()) { @Override public String getFilename() { return filename; } };
            var headers = new HttpHeaders(); headers.setContentType(MediaType.parseMediaType(image.contentType()));
            fields.add(partName, new HttpEntity<>(resource, headers));
        }
    }

    private String permanentImage(JsonNode images) {
        String url = imageUrl(images);
        if (url == null) throw invalid();
        URI uri = URI.create(url);
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!(host.equals("imweb.me") || host.endsWith(".imweb.me")) || uri.getRawQuery() != null || uri.getFragment() != null)
            throw invalid();
        return url;
    }

    private static String plainHtml(String value) {
        if (value == null || value.isBlank()) return "";
        return "<div>" + HtmlUtils.htmlEscape(value.strip()).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>") + "</div>";
    }
}
