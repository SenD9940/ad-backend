package com.orinan.api.domain.navercommerce;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.aistudio.service.AiStudioService;
import com.orinan.api.domain.aistudio.service.AiStudioExport;
import com.orinan.db.aistudio.enums.AiStudioKind;
import com.orinan.api.domain.navercommerce.business.NaverProductCreationBusiness;
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
import com.orinan.db.naverconnection.enums.NaverTokenType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaverProductCreationBusinessTest {
    private final NaverStoreAccessService access=mock(NaverStoreAccessService.class);
    private final NaverConnectionService connections=mock(NaverConnectionService.class);
    private final NaverCommerceClient commerce=mock(NaverCommerceClient.class);
    private final NaverProductCreationClient client=mock(NaverProductCreationClient.class);
    private final NaverProductImageValidator images=mock(NaverProductImageValidator.class);
    private final AiStudioService studio=mock(AiStudioService.class);
    private final JsonMapper json=JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private final NaverProductCreationBusiness business=new NaverProductCreationBusiness(access,connections,commerce,client,images,new NaverProductNoticeSchema(json),studio);
    private final Store store=new Store(30L,20L,"123456","스토어","https://smartstore.naver.com/test","판매자",false);
    private final Credentials credentials=credentials("seller-token-private",false);
    private final List<MultipartFile> files=List.of(new MockMultipartFile("images","private-name.png","image/png",new byte[]{1}));
    private final List<NaverProductImageValidator.ImageData> validated=List.of(new NaverProductImageValidator.ImageData(new byte[]{1},"image/png","image-0.png"));
    private final Created created=new Created(Status.CREATED,"777","888","상품을 등록했습니다.");

    @BeforeEach void setUp() {
        when(access.get(10L,30L,2L)).thenReturn(store);
        when(connections.getCredentials(10L,20L,2L)).thenReturn(credentials);
        when(images.validate(files)).thenReturn(validated);
        when(commerce.getChannels(anyString())).thenReturn(List.of(new NaverCommerceClient.Channel(123456,"STOREFARM","스토어",null)));
        when(client.categories(anyString(),anyString(),anyLong())).thenReturn(List.of(new Category("50000000","최종 카테고리")));
        when(client.origins(anyString(),anyString(),anyLong())).thenReturn(List.of(new Origin("0100","대한민국")));
        when(client.addresses(anyString(),anyString(),anyLong())).thenReturn(addresses());
        when(client.noticeTypes(anyString(),anyString(),anyString(),anyLong())).thenReturn(Set.of("ETC"));
        when(client.uploadImages(anyString(),anyList())).thenReturn(List.of("https://shop-phinf.pstatic.net/representative.png","https://shop-phinf.pstatic.net/optional.png"));
        when(client.create(anyString(),anyMap())).thenReturn(created);
    }

    @Test @SuppressWarnings("unchecked") void createsOnceWithExactCamelCasePayloadAndEscapedTextAfterAuthorizationAndValidation() {
        assertThat(business.create(10L,30L,2L,request(),files)).isEqualTo(created);
        var payload=ArgumentCaptor.forClass(Map.class); verify(client).create(eq("seller-token-private"),payload.capture());
        var root=json.valueToTree(payload.getValue());
        assertThat(root.path("originProduct").path("leafCategoryId").asString()).isEqualTo("50000000");
        assertThat(root.path("originProduct").path("statusType").asString()).isEqualTo("SALE");
        assertThat(root.path("originProduct").path("name").asString()).isEqualTo("상품명");
        assertThat(root.path("originProduct").path("salePrice").asLong()).isEqualTo(20000);
        assertThat(root.path("originProduct").path("stockQuantity").asInt()).isEqualTo(3);
        assertThat(root.path("originProduct").path("detailContent").asString()).isEqualTo("<div>&lt;script&gt;alert(1)&lt;/script&gt;<br>상품 설명</div>");
        assertThat(root.path("originProduct").path("images").path("representativeImage").path("url").asString()).endsWith("/representative.png");
        assertThat(root.path("originProduct").path("images").path("optionalImages").get(0).path("url").asString()).endsWith("/optional.png");
        var delivery=root.path("originProduct").path("deliveryInfo");
        assertThat(delivery.path("claimDeliveryInfo").path("shippingAddressId").isIntegralNumber()).isTrue();
        assertThat(delivery.path("claimDeliveryInfo").path("shippingAddressId").asLong()).isEqualTo(11);
        assertThat(delivery.path("claimDeliveryInfo").path("returnAddressId").asLong()).isEqualTo(12);
        assertThat(delivery.path("deliveryFee").path("freeConditionalAmount").asInt()).isEqualTo(50000);
        assertThat(delivery.path("deliveryFee").path("deliveryFeePayType").asString()).isEqualTo("PREPAID");
        var detail=root.path("originProduct").path("detailAttribute");
        assertThat(detail.path("minorPurchasable").asBoolean()).isFalse();
        assertThat(detail.path("productInfoProvidedNotice").path("productInfoProvidedNoticeType").asString()).isEqualTo("ETC");
        assertThat(detail.path("productInfoProvidedNotice").path("etc").path("itemName").asString()).isEqualTo("실제 품명");
        assertThat(detail.path("productInfoProvidedNotice").path("etc").path("returnCostReason").asString()).isEqualTo("0");
        assertThat(root.path("smartstoreChannelProduct").path("channelProductDisplayStatusType").asString()).isEqualTo("SUSPENSION");
        assertThat(json.writeValueAsString(payload.getValue())).doesNotContain("origin_product","sale_price","item_name","seller-token-private","client-secret-private","private-name.png");
        var ordered=inOrder(access,images,client);
        ordered.verify(access).get(10L,30L,2L); ordered.verify(images).validate(files);
        ordered.verify(client).categories(eq("client"),eq("seller-token-private"),anyLong());
        ordered.verify(client).uploadImages("seller-token-private",validated); ordered.verify(client).create(eq("seller-token-private"),anyMap());
    }

    @Test void unauthorizedAssetInvalidImageOrMissingNoticePreventsAllRemoteWrites() {
        doThrow(bad()).when(access).get(10L,30L,2L);
        assertThatThrownBy(()->business.create(10L,30L,2L,request(),files)).isInstanceOf(ApiException.class);
        verifyNoInteractions(images,client,commerce);
        doReturn(store).when(access).get(10L,30L,2L);
        when(images.validate(files)).thenThrow(bad());
        assertThatThrownBy(()->business.create(10L,30L,2L,request(),files)).isInstanceOf(ApiException.class);
        verifyNoInteractions(client,commerce);
        doReturn(validated).when(images).validate(files);
        assertThatThrownBy(()->business.create(10L,30L,2L,changed(x->x.put("notice_fields",Map.of())),files)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->business.create(10L,30L,2L,changed(x->x.put("delivery_fee_type","FREE")),files)).isInstanceOf(ApiException.class);
        verifyNoInteractions(client,commerce);
    }

    @Test @SuppressWarnings("unchecked") void studioDetailUsesStoredHtmlAndPermanentNaverImageAndEscapesAdditionalText() {
        when(studio.exportOutput(10L, 2L, 7L)).thenReturn(new AiStudioExport(AiStudioKind.DETAIL_PAGE,
                "샘플 상품", "<section><h2>저장된 상세</h2><img src=\"{{STUDIO_IMAGE}}\"></section>", new byte[]{2}, "image/png"));
        when(client.uploadImages(anyString(), anyList())).thenReturn(
                List.of("https://shop-phinf.pstatic.net/product.png"), List.of("https://shop-phinf.pstatic.net/detail.png"));
        business.create(10L, 30L, 2L, changed(value -> value.put("studio_output_id", 7)), files);
        var payload = ArgumentCaptor.forClass(Map.class);
        verify(client).create(eq("seller-token-private"), payload.capture());
        var html = json.valueToTree(payload.getValue()).path("originProduct").path("detailContent").asString();
        assertThat(html).contains("<h2>저장된 상세</h2>", "src=\"https://shop-phinf.pstatic.net/detail.png\"", "&lt;script&gt;")
                .doesNotContain("{{STUDIO_IMAGE}}", "<script>", "X-Amz-");
        verify(client, times(2)).uploadImages(anyString(), anyList());
        var uploads = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).uploadImages(eq("seller-token-private"), uploads.capture());
        var detailImage = (NaverProductImageValidator.ImageData) uploads.getAllValues().get(1).get(0);
        assertThat(detailImage.bytes()).containsExactly((byte) 2);
        assertThat(detailImage.contentType()).isEqualTo("image/png");
    }

    @Test void studioDetailAllowsEmptyAdditionalTextButRejectsUnownedOrWrongKindBeforeNaverWrites() {
        when(studio.exportOutput(10L, 2L, 7L)).thenThrow(bad());
        var request = changed(value -> { value.put("studio_output_id", 7); value.put("detail_content", ""); });
        assertThatThrownBy(() -> business.create(10L,30L,2L,request,files)).isInstanceOf(ApiException.class);
        verifyNoInteractions(client, commerce);
        doReturn(new AiStudioExport(AiStudioKind.AD_IMAGE, "image", null, new byte[]{2}, "image/png")).when(studio).exportOutput(10L, 2L, 7L);
        assertThatThrownBy(() -> business.create(10L,30L,2L,request,files)).isInstanceOf(ApiException.class);
        verifyNoInteractions(client, commerce);
        when(studio.exportOutput(10L, 2L, 7L)).thenReturn(new AiStudioExport(AiStudioKind.DETAIL_PAGE, "detail", "<img src=\"{{STUDIO_IMAGE}}\">", new byte[]{2}, "image/png"));
        assertThat(business.create(10L,30L,2L,request,files)).isEqualTo(created);
    }

    @Test void missingDetailWithoutStudioAndRevocationBetweenStudioUploadAndProductWriteAreRejected() {
        assertThatThrownBy(() -> business.create(10L,30L,2L,changed(value -> value.put("detail_content", " ")),files)).isInstanceOf(ApiException.class);
        verifyNoInteractions(client, commerce, studio);
        when(studio.exportOutput(10L, 2L, 7L)).thenReturn(new AiStudioExport(AiStudioKind.DETAIL_PAGE, "detail", "<img src=\"{{STUDIO_IMAGE}}\">", new byte[]{2}, "image/png"));
        var revoked = new AtomicBoolean();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> { if (revoked.get()) throw bad(); return null; }).when(access).requireUnchanged(10L, 2L, store);
        when(client.uploadImages(anyString(), anyList())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) revoked.set(true);
            return List.of("https://shop-phinf.pstatic.net/image.png");
        });
        assertThatThrownBy(() -> business.create(10L,30L,2L,changed(value -> value.put("studio_output_id",7)),files)).isInstanceOf(ApiException.class);
        verify(client, never()).create(anyString(), anyMap());
    }

    @Test void ambiguousOrDifferentSmartStoreChannelBlocksEveryWrite() {
        for(var channels:List.of(
                List.of(new NaverCommerceClient.Channel(654321,"STOREFARM","다른 스토어",null)),
                List.of(new NaverCommerceClient.Channel(123456,"STOREFARM","스토어",null),new NaverCommerceClient.Channel(654321,"STOREFARM","다른 스토어",null)),
                List.of(new NaverCommerceClient.Channel(123456,"WINDOW","윈도",null)))) {
            when(commerce.getChannels(anyString())).thenReturn(channels);
            assertThatThrownBy(()->business.create(10L,30L,2L,request(),files)).isInstanceOf(ApiException.class);
        }
        verify(client,never()).uploadImages(anyString(),anyList());verify(client,never()).create(anyString(),anyMap());
    }

    @Test void onlyCurrentSellerDomesticAddressWithMatchingReleaseAndReturnTypesIsAllowed() {
        for(var wrong:List.of(
                List.of(new Address("99","다른 주소","주소","RELEASE",false),addresses().get(1)),
                List.of(new Address("11","출고지","주소","REFUND_OR_EXCHANGE",false),addresses().get(1)),
                List.of(new Address("11","해외 출고지","주소","RELEASE",true),addresses().get(1)),
                List.of(addresses().get(0),new Address("12","반품지","주소","RELEASE",false)))) {
            when(client.addresses(anyString(),anyString(),anyLong())).thenReturn(wrong);
            assertThatThrownBy(()->business.create(10L,30L,2L,request(),files)).isInstanceOf(ApiException.class);
        }
        verify(client,never()).uploadImages(anyString(),anyList());verify(client,never()).create(anyString(),anyMap());
    }

    @Test void categoryOriginLeafAndCategorySpecificNoticeTypeAreRecheckedBeforeUpload() {
        when(client.categories(anyString(),anyString(),anyLong())).thenReturn(List.of(new Category("50000001","다른 카테고리")));
        assertThatThrownBy(()->business.create(10L,30L,2L,request(),files)).isInstanceOf(ApiException.class);
        when(client.categories(anyString(),anyString(),anyLong())).thenReturn(List.of(new Category("50000000","최종 카테고리")));
        when(client.origins(anyString(),anyString(),anyLong())).thenReturn(List.of(new Origin("0100","상위 지역"),new Origin("010001","하위 지역")));
        assertThatThrownBy(()->business.create(10L,30L,2L,request(),files)).isInstanceOf(ApiException.class);
        when(client.origins(anyString(),anyString(),anyLong())).thenReturn(List.of(new Origin("0100","대한민국")));
        when(client.noticeTypes(anyString(),anyString(),anyString(),anyLong())).thenReturn(Set.of("WEAR"));
        assertThatThrownBy(()->business.create(10L,30L,2L,request(),files)).isInstanceOf(ApiException.class);
        verify(client,never()).uploadImages(anyString(),anyList());verify(client,never()).create(anyString(),anyMap());
    }

    @Test void expiredTokenIsRefreshedAndSellerIdentityVerifiedBeforeMetadataAndWrites() {
        var expired=credentials("expired-token",true);var refreshed=credentials("fresh-token",false);
        var issued=new NaverCommerceClient.IssuedToken("fresh-token",refreshed.expiresAt());
        when(connections.getCredentials(10L,20L,2L)).thenReturn(expired);
        when(commerce.issueToken("client","client-secret-private",NaverTokenType.SELLER,"seller-uid")).thenReturn(issued);
        when(commerce.getSellerAccount("fresh-token")).thenReturn(new NaverCommerceClient.SellerAccount("seller","seller-uid"));
        when(connections.updateToken(10L,20L,2L,expired,issued)).thenReturn(refreshed);
        assertThat(business.create(10L,30L,2L,request(),files).status()).isEqualTo(Status.CREATED);
        var ordered=inOrder(commerce,connections,client);
        ordered.verify(commerce).issueToken("client","client-secret-private",NaverTokenType.SELLER,"seller-uid");
        ordered.verify(commerce).getSellerAccount("fresh-token");ordered.verify(connections).updateToken(10L,20L,2L,expired,issued);
        ordered.verify(client).uploadImages("fresh-token",validated);ordered.verify(client).create(eq("fresh-token"),anyMap());
        verify(client,never()).uploadImages(eq("expired-token"),anyList());
    }

    @Test void refreshReturningDifferentSellerNeverPersistsTokenOrUploads() {
        var expired=credentials("expired-token",true);when(connections.getCredentials(10L,20L,2L)).thenReturn(expired);
        when(commerce.issueToken(anyString(),anyString(),any(),anyString())).thenReturn(new NaverCommerceClient.IssuedToken("wrong-token",SeoulDateTimes.now().plusHours(1)));
        when(commerce.getSellerAccount("wrong-token")).thenReturn(new NaverCommerceClient.SellerAccount("other","different-uid"));
        assertThatThrownBy(()->business.create(10L,30L,2L,request(),files)).isInstanceOf(ApiException.class);
        verify(connections).markRequiresReauth(10L,20L,2L,expired);
        verify(connections,never()).updateToken(anyLong(),anyLong(),anyLong(),any(),any());verifyNoInteractions(client);
    }

    @Test void revocationAfterMetadataAndAfterUploadBlocksNextWrite() {
        var revoked=new AtomicBoolean();
        doAnswer(invocation->{if(revoked.get())throw bad();return null;}).when(access).requireUnchanged(10L,2L,store);
        when(client.noticeTypes(anyString(),anyString(),anyString(),anyLong())).thenAnswer(invocation->{revoked.set(true);return Set.of("ETC");});
        assertThatThrownBy(()->business.create(10L,30L,2L,request(),files)).isInstanceOf(ApiException.class);
        verify(client,never()).uploadImages(anyString(),anyList());
        revoked.set(false);doReturn(Set.of("ETC")).when(client).noticeTypes(anyString(),anyString(),anyString(),anyLong());
        when(client.uploadImages(anyString(),anyList())).thenAnswer(invocation->{revoked.set(true);return List.of("https://shop-phinf.pstatic.net/image.png");});
        assertThatThrownBy(()->business.create(10L,30L,2L,request(),files)).isInstanceOf(ApiException.class);
        verify(client,times(1)).uploadImages(anyString(),anyList());verify(client,never()).create(anyString(),anyMap());
        verify(commerce,never()).issueToken(anyString(),anyString(),any(),any());
    }

    @Test void unknownWriteNeverRetriesOrRefreshesAndDoesNotClaimCreatedIds() {
        when(client.create(anyString(),anyMap())).thenThrow(new NaverProductCreationClient.UnknownWrite());
        var result=business.create(10L,30L,2L,request(),files);
        assertThat(result.status()).isEqualTo(Status.UNKNOWN);assertThat(result.originProductNo()).isNull();assertThat(result.smartstoreChannelProductNo()).isNull();
        assertThat(result.message()).contains("스마트스토어센터").doesNotContain("seller-token-private","client-secret-private");
        verify(client,times(1)).uploadImages(anyString(),anyList());verify(client,times(1)).create(anyString(),anyMap());
        verify(commerce,never()).issueToken(anyString(),anyString(),any(),any());
    }

    @Test void revocationAfterSuccessfulCreateReportsUnknownAndWriteAuthenticationFailureIsNotRetried() {
        var revoked=new AtomicBoolean();
        doAnswer(invocation->{if(revoked.get())throw bad();return null;}).when(connections).requireUnchanged(10L,20L,2L,credentials);
        when(client.create(anyString(),anyMap())).thenAnswer(invocation->{revoked.set(true);return created;});
        assertThat(business.create(10L,30L,2L,request(),files).status()).isEqualTo(Status.UNKNOWN);
        verify(client,times(1)).create(anyString(),anyMap());
        revoked.set(false);clearInvocations(client);
        when(client.uploadImages(anyString(),anyList())).thenThrow(new NaverCommerceClient.AuthenticationException());
        assertThatThrownBy(()->business.create(10L,30L,2L,request(),files)).isInstanceOf(ApiException.class);
        verify(client,times(1)).uploadImages(anyString(),anyList());verify(client,never()).create(anyString(),anyMap());
        verify(commerce,never()).issueToken(anyString(),anyString(),any(),any());
    }

    private static Credentials credentials(String token,boolean expired) {return new Credentials("seller-uid","client","client-secret-private",NaverTokenType.SELLER,"seller-uid",token,SeoulDateTimes.now().plusHours(expired?-1:1));}
    private static ApiException bad() {return new ApiException(ApiCode.BAD_REQUEST,"연결을 확인해 주세요.");}
    private static List<Address> addresses() {return List.of(new Address("11","출고지","서울","RELEASE",false),new Address("12","반품지","서울","REFUND_OR_EXCHANGE",false));}
    private NaverProductCreateRequest request() {return changed(x->{});}
    private NaverProductCreateRequest changed(Consumer<Map<String,Object>> edit) {var value=body();edit.accept(value);return json.convertValue(value,NaverProductCreateRequest.class);}
    static Map<String,Object> body() {
        var values=new LinkedHashMap<String,Object>();values.put("name"," 상품명 ");values.put("category_id","50000000");values.put("sale_price",20000L);values.put("stock_quantity",3);
        values.put("detail_content","<script>alert(1)</script>\n상품 설명");values.put("origin_area_code","0100");values.put("tax_type","TAX");values.put("minor_purchasable",false);
        values.put("after_service_telephone_number","02-1234-5678");values.put("after_service_guide_content","고객센터 안내");values.put("delivery_company","CJGLS");
        values.put("delivery_fee_type","CONDITIONAL_FREE");values.put("delivery_fee",3000);values.put("free_conditional_amount",50000);values.put("shipping_address_id","11");values.put("return_address_id","12");
        values.put("return_delivery_fee",3000);values.put("exchange_delivery_fee",6000);values.put("notice_type","ETC");values.put("notice_fields",Map.of(
                "return_cost_reason","0","no_refund_reason","0","quality_assurance_standard","0","compensation_procedure","0","trouble_shooting_contents","0",
                "item_name","실제 품명","model_name","모델 A","manufacturer","제조사","after_service_director","고객센터"));
        values.put("display_status","SUSPENSION");values.put("naver_shopping_registration",false);return values;
    }
}
