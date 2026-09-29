package com.orinan.api.domain.imweb;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.aistudio.service.*;
import com.orinan.api.domain.imweb.business.*;
import com.orinan.api.domain.imweb.client.ImwebApiClient;
import com.orinan.api.domain.imweb.controller.model.ImwebProductCreateRequest;
import com.orinan.api.domain.imweb.service.ImwebAccessService;
import com.orinan.api.domain.navercommerce.service.NaverProductImageValidator;
import com.orinan.db.aistudio.enums.AiStudioKind;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImwebProductCreationBusinessTest {
    private final ImwebConnectionBusiness connections = mock(ImwebConnectionBusiness.class);
    private final ImwebAccessService access = mock(ImwebAccessService.class);
    private final ImwebApiClient client = mock(ImwebApiClient.class);
    private final NaverProductImageValidator images = mock(NaverProductImageValidator.class);
    private final AiStudioService studio = mock(AiStudioService.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final ImwebProductCreationBusiness business = new ImwebProductCreationBusiness(connections, access, client, images, studio);
    private final ImwebAccessService.Context context = new ImwebAccessService.Context(1, 2, 3, 4, "S123456", "u123456", "KRW", "private-token", 1);
    private final List<MultipartFile> files = List.of(new MockMultipartFile("files", "private.png", "image/png", new byte[]{1}));

    @BeforeEach void setup() {
        when(connections.context(1L, 4L, 2L)).thenReturn(context);
        when(images.validate(files)).thenReturn(List.of(new NaverProductImageValidator.ImageData(new byte[]{1}, "image/png", "safe.png")));
        when(client.site(anyString())).thenReturn(json.readTree("""
                {"siteCode":"S123456","unitList":[{"unitCode":"u123456","currency":"KRW"}]}
                """));
        when(client.read(anyString(), eq("/products/shop-categories"), any())).thenReturn(json.readTree("""
                [{"categoryCode":"c123","name":"카테고리","children":[]}]
                """));
        when(client.multipart(anyString(), eq("/products"), eq(HttpMethod.POST), any()))
                .thenReturn(json.readTree("{\"prodNo\":100,\"prodCode\":\"p100\"}"));
        when(client.multipart(anyString(), eq("/products/100"), eq(HttpMethod.PATCH), any())).thenReturn(json.readTree("true"));
        when(client.read(anyString(), eq("/products/100"), any())).thenReturn(json.readTree("""
                {"prodNo":100,"siteCode":"S123456","unitCode":"u123456","productImages":["https://cdn.imweb.me/upload/test/detail.png"]}
                """));
        when(studio.exportOutput(1, 2, 9)).thenReturn(new AiStudioExport(AiStudioKind.DETAIL_PAGE, "상세페이지",
                "<section><img src=\"{{STUDIO_IMAGE}}\"></section>", new byte[]{1}, "image/png"));
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"}) void createsHiddenWithEscapedTextThenPublishesOnce() {
        var result = business.create(1L, 4L, 2L, request(null), files);
        assertThat(result.status()).isEqualTo("CREATED"); assertThat(result.detailApplied()).isFalse();
        var payload = ArgumentCaptor.forClass(MultiValueMap.class);
        var order = inOrder(client);
        order.verify(client).site("private-token");
        order.verify(client).read(eq("private-token"), eq("/products/shop-categories"), any());
        order.verify(client).multipart(eq("private-token"), eq("/products"), eq(HttpMethod.POST), payload.capture());
        order.verify(client).updateProductStatus("private-token", "100", "sale");
        assertThat(payload.getValue().getFirst("productBaseInfo[status]")).isEqualTo("nosale");
        assertThat(payload.getValue().getFirst("productBaseInfo[unitShopProductInfo][0][description]"))
                .isEqualTo("<div>&lt;script&gt;x&lt;/script&gt;<br>설명</div>");
        assertThat(payload.getValue().getFirst("productPriceInfo[0][unitCode]")).isEqualTo("u123456");
        verify(client, never()).multipart(anyString(), eq("/products/100"), any(), any());
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"}) void serverSavedDetailUsesPermanentProviderImageBeforePublishing() {
        var result = business.create(1L, 4L, 2L, request(9L), List.of());
        assertThat(result.detailApplied()).isTrue(); assertThat(result.status()).isEqualTo("CREATED");
        var fields = ArgumentCaptor.forClass(MultiValueMap.class);
        var order = inOrder(client);
        order.verify(client).multipart(anyString(), eq("/products"), eq(HttpMethod.POST), any());
        order.verify(client).read(eq("private-token"), eq("/products/100"), any());
        order.verify(client).multipart(eq("private-token"), eq("/products/100"), eq(HttpMethod.PATCH), fields.capture());
        order.verify(client).updateProductStatus("private-token", "100", "sale");
        assertThat(fields.getValue().getFirst("description").toString()).contains("https://cdn.imweb.me/upload/test/detail.png", "&lt;script&gt;")
                .doesNotContain("{{STUDIO_IMAGE}}", "<script>", "amazonaws");
        verifyNoInteractions(images);
    }

    @Test void rejectsAdImageStudioOutputBeforeAnyProductWrite() {
        when(studio.exportOutput(1, 2, 9)).thenReturn(new AiStudioExport(AiStudioKind.AD_IMAGE, "광고", null, new byte[]{1}, "image/png"));
        assertThatThrownBy(() -> business.create(1L, 4L, 2L, request(9L), files)).isInstanceOf(ApiException.class);
        verifyNoInteractions(client);
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"}) void uploadsAdditionalPhotosAfterIdentifyingSoleAiImage() {
        when(client.multipart(anyString(), eq("/products/100/images"), eq(HttpMethod.POST), any()))
                .thenReturn(json.readTree("{\"success\":[\"https://cdn.imweb.me/extra.png\"],\"fail\":[]}"));
        assertThat(business.create(1L, 4L, 2L, request(9L), files).status()).isEqualTo("CREATED");
        var initial = ArgumentCaptor.forClass(MultiValueMap.class);
        var extra = ArgumentCaptor.forClass(MultiValueMap.class);
        var order = inOrder(client);
        order.verify(client).multipart(anyString(), eq("/products"), eq(HttpMethod.POST), initial.capture());
        order.verify(client).read(anyString(), eq("/products/100"), any());
        order.verify(client).multipart(anyString(), eq("/products/100"), eq(HttpMethod.PATCH), any());
        order.verify(client).multipart(anyString(), eq("/products/100/images"), eq(HttpMethod.POST), extra.capture());
        order.verify(client).updateProductStatus("private-token", "100", "sale");
        assertThat((List<?>) initial.getValue().get("productImages")).hasSize(1);
        assertThat((List<?>) extra.getValue().get("images")).hasSize(1);
        assertThat(extra.getValue()).doesNotContainKey("productImages");
    }

    @Test void partialExtraImageUploadPreservesKnownIdWithoutPublishing() {
        when(client.multipart(anyString(), eq("/products/100/images"), eq(HttpMethod.POST), any()))
                .thenReturn(json.readTree("{\"success\":[],\"fail\":[\"failed.png\"]}"));
        var result = business.create(1L, 4L, 2L, request(9L), files);
        assertThat(result.status()).isEqualTo("DETAIL_PENDING"); assertThat(result.productId()).isEqualTo("100");
        assertThat(result.detailApplied()).isTrue();
        verify(client, never()).updateProductStatus(anyString(), anyString(), anyString());
    }

    @Test void revokedPermissionBeforeWritePreventsCreate() {
        doThrow(new IllegalStateException("revoked")).when(access).requireUnchanged(context);
        assertThatThrownBy(() -> business.create(1L, 4L, 2L, request(null), files)).isInstanceOf(IllegalStateException.class);
        verify(client, never()).multipart(anyString(), anyString(), any(), any());
    }

    @Test void unsupportedMultipleUnitsDoNotCreateAnything() {
        when(client.site(anyString())).thenReturn(json.readTree("""
                {"siteCode":"S123456","unitList":[{"unitCode":"u123456","currency":"KRW"},{"unitCode":"u654321","currency":"USD"}]}
                """));
        assertThatThrownBy(() -> business.create(1L, 4L, 2L, request(null), files)).isInstanceOf(ApiException.class);
        verify(client, never()).multipart(anyString(), anyString(), any(), any());
    }

    @Test void unknownInitialWriteIsNotRetriedOrReportedAsFailureWithoutCreation() {
        when(client.multipart(anyString(), eq("/products"), eq(HttpMethod.POST), any())).thenThrow(new ImwebApiClient.UnknownWriteException());
        assertThatThrownBy(() -> business.create(1L, 4L, 2L, request(null), files)).isInstanceOf(ImwebApiClient.UnknownWriteException.class);
        verify(client, times(1)).multipart(anyString(), eq("/products"), eq(HttpMethod.POST), any());
        verify(client, never()).updateProductStatus(anyString(), anyString(), anyString());
    }

    @Test void knownCreatedProductSurvivesDetailFailureAndIsNeverRepublishedOrRecreated() {
        when(client.multipart(anyString(), eq("/products/100"), eq(HttpMethod.PATCH), any())).thenThrow(new ImwebApiClient.UnknownWriteException());
        var result = business.create(1L, 4L, 2L, request(9L), List.of());
        assertThat(result.productId()).isEqualTo("100"); assertThat(result.status()).isEqualTo("DETAIL_PENDING");
        verify(client, times(1)).multipart(anyString(), eq("/products"), eq(HttpMethod.POST), any());
        verify(client, never()).updateProductStatus(anyString(), anyString(), anyString());
    }

    @Test void imageFailureOrNonPermanentUrlLeavesKnownProductForManualCompletion() {
        when(client.read(anyString(), eq("/products/100"), any())).thenReturn(json.readTree("""
                {"prodNo":100,"siteCode":"S123456","unitCode":"u123456","productImages":["https://cdn.imweb.me/x?temporary=1"]}
                """));
        assertThat(business.create(1L, 4L, 2L, request(9L), List.of()).status()).isEqualTo("DETAIL_PENDING");
        verify(client, never()).multipart(anyString(), eq("/products/100"), any(), any());
        verify(client, never()).updateProductStatus(anyString(), anyString(), anyString());
    }

    @Test void revocationAfterCreationStopsRemainingWritesAndReturnsKnownId() {
        var created = new AtomicBoolean();
        when(client.multipart(anyString(), eq("/products"), eq(HttpMethod.POST), any())).thenAnswer(invocation -> {
            created.set(true); return json.readTree("{\"prodNo\":100,\"prodCode\":\"p100\"}");
        });
        doAnswer(invocation -> { if (created.get()) throw new IllegalStateException("revoked"); return null; }).when(access).requireUnchanged(context);
        var result = business.create(1L, 4L, 2L, request(null), files);
        assertThat(result.status()).isEqualTo("DETAIL_PENDING"); assertThat(result.productId()).isEqualTo("100");
        verify(client, never()).updateProductStatus(anyString(), anyString(), anyString());
    }

    @Test void malformedSuccessfulCreateDoesNotTriggerAnotherWrite() {
        when(client.multipart(anyString(), eq("/products"), eq(HttpMethod.POST), any())).thenReturn(json.readTree("true"));
        assertThatThrownBy(() -> business.create(1L, 4L, 2L, request(null), files)).isInstanceOf(ImwebApiClient.UnknownWriteException.class);
        verify(client, never()).updateProductStatus(anyString(), anyString(), anyString());
    }

    private ImwebProductCreateRequest request(Long studioId) {
        return new ImwebProductCreateRequest("상품", "c123", new BigDecimal("9900"), new BigDecimal("12000"), 5L,
                "<script>x</script>\n설명", studioId);
    }
}
