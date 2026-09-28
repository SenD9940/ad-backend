package com.orinan.api.domain.navercommerce;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.navercommerce.business.NaverProductCreationBusiness;
import com.orinan.api.domain.navercommerce.controller.NaverProductCreationController;
import com.orinan.api.domain.navercommerce.controller.model.NaverProductCreateRequest;
import com.orinan.api.domain.navercommerce.controller.model.NaverProductCreationResponse.*;
import com.orinan.api.domain.navercommerce.service.NaverProductNoticeSchema;
import com.orinan.api.domain.token.business.TokenBusiness;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.user.converter.UserConverter;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.exceptionhandler.ApiExceptionHandler;
import com.orinan.api.exceptionhandler.GlobalExceptionHandler;
import com.orinan.api.exceptionhandler.ValidExceptionHandler;
import com.orinan.api.interceptor.AuthorizationInterceptor;
import com.orinan.api.resolver.UserSessionResolver;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NaverProductCreationApiTest {
    private static final String BASE="/api/workspaces/10/naver/stores/30";
    private final NaverProductCreationBusiness business=mock(NaverProductCreationBusiness.class);
    private final TokenBusiness tokens=mock(TokenBusiness.class);
    private final UserService users=mock(UserService.class);
    private final UserConverter converter=mock(UserConverter.class);
    @SuppressWarnings("unchecked") private final RedisTemplate<String,String> redis=mock(RedisTemplate.class);
    private final JsonMapper json=JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private MockMvc mvc;

    @BeforeEach void setUp() {
        var user=UserEntity.builder().id(2L).build();
        when(tokens.validateAccessToken("service-token")).thenReturn(2L);
        when(users.findByIdAndStatusWithThrow(2L,UserStatus.REGISTERED)).thenReturn(user);
        when(converter.toResponse(user)).thenReturn(UserResponse.builder().id(2L).build());
        mvc=MockMvcBuilders.standaloneSetup(new NaverProductCreationController(business))
                .addInterceptors(new AuthorizationInterceptor(tokens,users,redis))
                .setCustomArgumentResolvers(new UserSessionResolver(users,converter))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(json))
                .setControllerAdvice(new ValidExceptionHandler(),new ApiExceptionHandler(),new GlobalExceptionHandler()).build();
    }

    @Test @SuppressWarnings("unchecked") void multipartSnakeCaseRequestUsesAuthenticatedUserAndReturnsIdsWithoutCredentials() throws Exception {
        when(business.create(eq(10L),eq(30L),eq(2L),any(),anyList())).thenReturn(new Created(Status.CREATED,"777","888","상품을 등록했습니다."));
        var response=mvc.perform(upload(NaverProductCreationBusinessTest.body()).header("Authorization","Bearer service-token").requestAttr("userId",999L))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.body.status").value("CREATED"))
                .andExpect(jsonPath("$.body.origin_product_no").value("777"))
                .andExpect(jsonPath("$.body.smartstore_channel_product_no").value("888"))
                .andExpect(jsonPath("$.body.originProductNo").doesNotExist()).andReturn().getResponse();
        var request=ArgumentCaptor.forClass(NaverProductCreateRequest.class);var images=ArgumentCaptor.forClass(List.class);
        verify(business).create(eq(10L),eq(30L),eq(2L),request.capture(),images.capture());
        assertThat(request.getValue().categoryId()).isEqualTo("50000000");
        assertThat(request.getValue().minorPurchasable()).isFalse();assertThat(request.getValue().naverShoppingRegistration()).isFalse();
        assertThat(request.getValue().noticeFields()).containsEntry("item_name","실제 품명");
        assertThat(((MultipartFile)images.getValue().get(0)).getContentType()).isEqualTo("image/png");
        assertThat(response.getContentAsString()).doesNotContain("access_token","client_secret","service-token","private-file-name");
    }

    @Test void requiredBooleansAndFieldsAndPositiveStockFailBeforeBusiness() throws Exception {
        for(String required:List.of("minor_purchasable","naver_shopping_registration","name","category_id","sale_price","notice_fields","display_status")) {
            var body=NaverProductCreationBusinessTest.body();body.remove(required);
            mvc.perform(upload(body).header("Authorization","Bearer service-token")).andExpect(status().isBadRequest());
        }
        for(Object stock:new Object[]{null,0,-1,100000000}) {
            var body=NaverProductCreationBusinessTest.body();body.put("stock_quantity",stock);
            mvc.perform(upload(body).header("Authorization","Bearer service-token")).andExpect(status().isBadRequest());
        }
        for(String field:List.of("minor_purchasable","naver_shopping_registration")) {
            var body=NaverProductCreationBusinessTest.body();body.put(field,null);
            mvc.perform(upload(body).header("Authorization","Bearer service-token")).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(business);
    }

    @Test void missingImagesMissingJsonAndMalformedJsonNeverReachCreation() throws Exception {
        mvc.perform(multipart(BASE+"/products").file(requestPart(NaverProductCreationBusinessTest.body()))
                .header("Authorization","Bearer service-token")).andExpect(status().isBadRequest());
        mvc.perform(multipart(BASE+"/products").file(imagePart()).header("Authorization","Bearer service-token"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(BASE+"/products").file(new MockMultipartFile("request","request.json","application/json","{broken".getBytes()))
                .file(imagePart()).header("Authorization","Bearer service-token")).andExpect(status().isBadRequest());
        verifyNoInteractions(business);
    }

    @Test void missingBlacklistedAndNonMemberAuthenticationCannotCreateProduct() throws Exception {
        mvc.perform(upload(NaverProductCreationBusinessTest.body())).andExpect(status().isUnauthorized());
        verifyNoInteractions(business);
        when(redis.hasKey("blacklist:service-token")).thenReturn(true);
        mvc.perform(upload(NaverProductCreationBusinessTest.body()).header("Authorization","Bearer service-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(business);
        when(redis.hasKey("blacklist:service-token")).thenReturn(false);
        when(business.create(eq(10L),eq(30L),eq(2L),any(),anyList())).thenThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY));
        mvc.perform(upload(NaverProductCreationBusinessTest.body()).header("Authorization","Bearer service-token"))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.body").isEmpty());
    }

    @Test void sanitizedServiceRejectionKeepsHttpErrorAndDoesNotEchoProductOrFileData() throws Exception {
        when(business.create(eq(10L),eq(30L),eq(2L),any(),anyList()))
                .thenThrow(new ApiException(ApiCode.BAD_REQUEST,"현재 판매자 주소록의 출고지를 선택해 주세요."));
        var response=mvc.perform(upload(NaverProductCreationBusinessTest.body()).header("Authorization","Bearer service-token"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.body").isEmpty()).andReturn().getResponse();
        assertThat(response.getContentAsString()).contains("출고지").doesNotContain("private-file-name","service-token","02-1234-5678","<script>","client_secret");
    }

    @Test void optionsAndCategoryNoticesUseSameAuthenticatedAssetAndNoStoreHeaders() throws Exception {
        when(business.options(10L,30L,2L)).thenReturn(new Options(List.of(new Category("50000000","카테고리")),
                List.of(new Origin("0100","대한민국")),List.of(new Address("11","출고지","서울","RELEASE",false)),List.of(new DeliveryCompany("CJGLS","CJ대한통운"))));
        when(business.notices(10L,30L,2L,"50000000")).thenReturn(new Notices(List.of(new NaverProductNoticeSchema.NoticeType("ETC","기타 재화",
                List.of(new NaverProductNoticeSchema.NoticeField("item_name","품명","",50,true,"TEXT",List.of()))))));
        mvc.perform(get(BASE+"/product-creation/options").header("Authorization","Bearer service-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.body.delivery_companies[0].code").value("CJGLS"))
                .andExpect(jsonPath("$.body.addresses[0].overseas").value(false));
        mvc.perform(get(BASE+"/product-creation/notices").param("categoryId","50000000").header("Authorization","Bearer service-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.body.types[0].fields[0].max_length").value(50))
                .andExpect(jsonPath("$.body.types[0].fields[0].key").value("item_name"));
        verify(business).options(10L,30L,2L);verify(business).notices(10L,30L,2L,"50000000");
        mvc.perform(get(BASE+"/product-creation/notices").header("Authorization","Bearer service-token"))
                .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(business);
    }

    @Test void unknownCreationOutcomeReturnsExplicitUnknownWithNoCreatedProductIds() throws Exception {
        when(business.create(eq(10L),eq(30L),eq(2L),any(),anyList()))
                .thenReturn(new Created(Status.UNKNOWN,null,null,"스마트스토어센터에서 등록 결과를 확인해 주세요."));
        mvc.perform(upload(NaverProductCreationBusinessTest.body()).header("Authorization","Bearer service-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.body.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.body.origin_product_no").isEmpty())
                .andExpect(jsonPath("$.body.smartstore_channel_product_no").isEmpty());
        verify(business,times(1)).create(eq(10L),eq(30L),eq(2L),any(),anyList());
    }

    private MockMultipartHttpServletRequestBuilder upload(Map<String,Object> body) {return multipart(BASE+"/products").file(requestPart(body)).file(imagePart());}
    private MockMultipartFile requestPart(Map<String,Object> body) {return new MockMultipartFile("request","request.json",MediaType.APPLICATION_JSON_VALUE,json.writeValueAsBytes(body));}
    private MockMultipartFile imagePart() {return new MockMultipartFile("images","private-file-name.png","image/png",new byte[]{1});}
}
