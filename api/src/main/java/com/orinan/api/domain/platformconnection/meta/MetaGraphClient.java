package com.orinan.api.domain.platformconnection.meta;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class MetaGraphClient {

    private static final String GRAPH_URL = "https://graph.facebook.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_PAGES = 100;
    private static final List<String> SCOPES = List.of(
            "ads_read", "ads_management", "pages_show_list", "pages_read_engagement", "instagram_basic");

    private final WebClient webClient;
    private final MetaProperties properties;
    private final JsonMapper jsonMapper;

    public MetaGraphClient(WebClient.Builder builder, MetaProperties properties, JsonMapper jsonMapper) {
        this.webClient = builder.clone().build();
        this.properties = properties;
        // Preserve raw decimal precision before validating count values with longValueExact().
        this.jsonMapper = jsonMapper.rebuild().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();
    }

    public String authorizationUrl(String state) {
        properties.validate();
        if (!StringUtils.hasText(state)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "Meta 인증 요청이 올바르지 않습니다.");
        }
        return UriComponentsBuilder.fromUriString("https://www.facebook.com")
                .pathSegment(properties.getApiVersion(), "dialog", "oauth")
                .queryParam("client_id", "{appId}")
                .queryParam("redirect_uri", "{redirectUri}")
                .queryParam("response_type", "code")
                .queryParam("state", "{state}")
                .queryParam("scope", "{scope}")
                .queryParam("auth_type", "rerequest")
                .encode().buildAndExpand(Map.of("appId", properties.getAppId(),
                        "redirectUri", properties.getRedirectUri(), "state", state,
                        "scope", String.join(",", SCOPES))).toUriString();
    }

    public AuthorizedAccount exchangeCode(String code) {
        properties.validate();
        if (!StringUtils.hasText(code)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "Meta 인증 코드가 필요합니다.");
        }
        MultiValueMap<String, String> codeForm = credentials();
        codeForm.add("grant_type", "authorization_code");
        codeForm.add("redirect_uri", properties.getRedirectUri());
        codeForm.add("code", code);
        JsonNode shortToken = exchangeToken(codeForm);

        MultiValueMap<String, String> longTokenForm = credentials();
        longTokenForm.add("grant_type", "fb_exchange_token");
        longTokenForm.add("fb_exchange_token", requiredText(shortToken, "access_token"));
        JsonNode longToken = exchangeToken(longTokenForm);
        String accessToken = requiredText(longToken, "access_token");
        JsonNode expiry = longToken.has("expires_in") ? longToken.path("expires_in") : longToken.path("expires");
        long expiresIn = expiry.isIntegralNumber() ? expiry.asLong(-1) : -1;
        if (expiresIn <= 0 || expiresIn > Integer.MAX_VALUE) {
            throw invalidResponse();
        }
        LocalDateTime expiresAt = SeoulDateTimes.now().plusSeconds(expiresIn);
        JsonNode user = get("me", "id,name", accessToken, null);
        String userId = requiredText(user, "id");
        String name = optionalText(user, "name", userId);

        Set<String> granted = new LinkedHashSet<>();
        for (JsonNode permission : readEdge("me/permissions", "permission,status", accessToken)) {
            if ("granted".equals(requiredText(permission, "status"))) {
                granted.add(requiredText(permission, "permission"));
            }
        }
        if (!granted.containsAll(SCOPES)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "광고 계정과 인스타그램 연결에 필요한 Meta 권한을 모두 허용해 주세요.");
        }
        return new AuthorizedAccount(userId, name, accessToken, expiresAt, String.join(",", granted));
    }

    public List<DiscoveredAsset> discoverAssets(String accessToken) {
        properties.validate();
        if (!StringUtils.hasText(accessToken)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "Meta 계정을 다시 연결해 주세요.");
        }
        List<DiscoveredAsset> assets = new ArrayList<>();
        for (JsonNode account : readEdge("me/adaccounts", "id,name", accessToken)) {
            String id = requiredText(account, "id");
            assets.add(new DiscoveredAsset(id, optionalText(account, "name", id),
                    PlatformType.FACEBOOK, AssetType.AD_ACCOUNT, null));
        }
        for (JsonNode page : readEdge("me/accounts", "id,name,instagram_business_account{id,username,name}", accessToken)) {
            String pageId = requiredText(page, "id");
            assets.add(new DiscoveredAsset(pageId, optionalText(page, "name", pageId),
                    PlatformType.FACEBOOK, AssetType.PAGE, pageId));
            JsonNode instagram = page.path("instagram_business_account");
            if (!instagram.isMissingNode() && !instagram.isNull()) {
                String instagramId = requiredText(instagram, "id");
                String name = optionalText(instagram, "username", optionalText(instagram, "name", instagramId));
                assets.add(new DiscoveredAsset(instagramId, name,
                        PlatformType.INSTAGRAM, AssetType.PROFILE, pageId));
            }
        }
        return List.copyOf(assets);
    }

    public AdAccount getAdAccount(String adAccountId, String accessToken) {
        validateAdAccountRequest(adAccountId, accessToken);
        JsonNode account = get(adAccountId, "id,name,currency,timezone_name", accessToken, null);
        String id = requiredText(account, "id");
        String currency = requiredText(account, "currency");
        if (!adAccountId.equals(id) || !currency.matches("[A-Z]{3}")) {
            throw invalidResponse();
        }
        return new AdAccount(id, requiredText(account, "name"), currency, requiredText(account, "timezone_name"));
    }

    public List<DiscoveredAsset> listPromotablePages(String adAccountId, String accessToken) {
        validateAdAccountRequest(adAccountId, accessToken);
        Map<String, DiscoveredAsset> pages = new LinkedHashMap<>();
        for (JsonNode page : readEdge(adAccountId + "/promote_pages", "id,name", accessToken)) {
            String pageId = requiredText(page, "id");
            if (!validNodeId(pageId)) {
                throw invalidResponse();
            }
            pages.putIfAbsent(pageId, new DiscoveredAsset(pageId, optionalText(page, "name", pageId),
                    PlatformType.FACEBOOK, AssetType.PAGE, pageId));
        }
        return List.copyOf(pages.values());
    }

    public List<Campaign> listCampaigns(String adAccountId, String accessToken) {
        validateAdAccountRequest(adAccountId, accessToken);
        List<Campaign> campaigns = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode campaign : readEdge(adAccountId + "/campaigns",
                "id,account_id,name,status,effective_status,objective", accessToken)) {
            requireAccountId(campaign, adAccountId);
            String id = requiredText(campaign, "id");
            if (!ids.add(id)) {
                throw invalidResponse();
            }
            campaigns.add(new Campaign(id, requiredText(campaign, "name"), requiredText(campaign, "status"),
                    requiredText(campaign, "effective_status"), requiredText(campaign, "objective")));
        }
        return List.copyOf(campaigns);
    }

    public CreatedCampaign createCampaign(String adAccountId, String accessToken, String name, String objective,
                                           List<String> specialAdCategories, List<String> specialAdCategoryCountry) {
        validateAdAccountRequest(adAccountId, accessToken);
        if (!StringUtils.hasText(name) || name.length() > 255
                || objective == null || !Set.of("OUTCOME_AWARENESS", "OUTCOME_TRAFFIC", "OUTCOME_ENGAGEMENT",
                "OUTCOME_LEADS", "OUTCOME_APP_PROMOTION", "OUTCOME_SALES").contains(objective)
                || specialAdCategories == null || specialAdCategoryCountry == null) {
            throw new ApiException(ApiCode.BAD_REQUEST, "캠페인 이름과 목표, 특별 광고 카테고리를 확인해 주세요.");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("name", name);
        form.add("objective", objective);
        form.add("special_ad_categories", jsonMapper.writeValueAsString(specialAdCategories));
        if (!specialAdCategoryCountry.isEmpty()) {
            form.add("special_ad_category_country", jsonMapper.writeValueAsString(specialAdCategoryCountry));
        }
        form.add("status", "PAUSED");
        form.add("buying_type", "AUCTION");
        form.add("is_adset_budget_sharing_enabled", "false");
        return new CreatedCampaign(postCreation(adAccountId, accessToken, "campaigns", form,
                "Meta 캠페인 생성 결과를 확인하지 못했습니다. 중복 생성을 피하려면 캠페인 목록을 먼저 확인해 주세요."));
    }

    public CreatedAdSet createAdSet(String adAccountId, String accessToken, String campaignId, AdSetSpec spec) {
        validateAdAccountRequest(adAccountId, accessToken);
        requireNodeId(campaignId);
        if (spec == null || !validName(spec.name()) || spec.objective() == null
                || !Set.of("OUTCOME_TRAFFIC", "OUTCOME_SALES").contains(spec.objective())
                || spec.dailyBudget() <= 0 || spec.countries() == null || spec.countries().isEmpty()
                || spec.countries().size() > 250
                || spec.countries().stream().anyMatch(country -> country == null || !country.matches("[A-Z]{2}"))
                || spec.ageMin() < 18 || spec.ageMax() > 65 || spec.ageMin() > spec.ageMax()
                || ("OUTCOME_SALES".equals(spec.objective()) && !validNodeId(spec.pixelId()))
                || ("OUTCOME_TRAFFIC".equals(spec.objective()) && spec.pixelId() != null)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "광고 세트의 목표, 예산, 타겟 및 픽셀 정보를 확인해 주세요.");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("name", spec.name());
        form.add("campaign_id", campaignId);
        form.add("daily_budget", Long.toString(spec.dailyBudget()));
        form.add("billing_event", "IMPRESSIONS");
        form.add("bid_strategy", "LOWEST_COST_WITHOUT_CAP");
        form.add("optimization_goal", "OUTCOME_SALES".equals(spec.objective()) ? "OFFSITE_CONVERSIONS" : "LINK_CLICKS");
        form.add("destination_type", "WEBSITE");
        form.add("status", "PAUSED");
        Map<String, Object> targeting = new LinkedHashMap<>();
        targeting.put("geo_locations", Map.of("countries", spec.countries().stream().distinct().toList()));
        targeting.put("age_min", spec.ageMin());
        targeting.put("age_max", spec.ageMax());
        targeting.put("targeting_automation", Map.of("advantage_audience", 0));
        targeting.put("publisher_platforms", spec.instagramEnabled() ? List.of("facebook", "instagram") : List.of("facebook"));
        targeting.put("facebook_positions", List.of("feed"));
        if (spec.instagramEnabled()) {
            targeting.put("instagram_positions", List.of("stream"));
        }
        form.add("targeting", jsonMapper.writeValueAsString(targeting));
        if ("OUTCOME_SALES".equals(spec.objective())) {
            form.add("promoted_object", jsonMapper.writeValueAsString(Map.of("pixel_id", spec.pixelId(), "custom_event_type", "PURCHASE")));
        }
        return new CreatedAdSet(postCreation(adAccountId, accessToken, "adsets", form, unknownCreationMessage("광고 세트")));
    }

    public CreatedCreative createImageCreative(String adAccountId, String accessToken, CreativeSpec spec) {
        validateAdAccountRequest(adAccountId, accessToken);
        if (spec == null || !validName(spec.name()) || !validNodeId(spec.pageId())
                || (spec.instagramUserId() != null && !validNodeId(spec.instagramUserId()))
                || !validHttpsUrl(spec.linkUrl()) || !validHttpsUrl(spec.imageUrl())
                || !StringUtils.hasText(spec.message()) || spec.message().length() > 5000
                || !validName(spec.headline()) || (spec.description() != null && spec.description().length() > 500)
                || spec.callToAction() == null
                || !Set.of("LEARN_MORE", "SHOP_NOW", "SIGN_UP", "CONTACT_US", "BOOK_TRAVEL", "DOWNLOAD",
                "GET_QUOTE", "APPLY_NOW", "GET_OFFER").contains(spec.callToAction())) {
            throw new ApiException(ApiCode.BAD_REQUEST, "광고 이미지, 문구, 링크 및 페이지 정보를 확인해 주세요.");
        }
        Map<String, Object> linkData = new LinkedHashMap<>();
        linkData.put("link", spec.linkUrl());
        // Meta fetches this public image URL; the application never fetches user-supplied URLs.
        linkData.put("picture", spec.imageUrl());
        linkData.put("message", spec.message());
        linkData.put("name", spec.headline());
        if (StringUtils.hasText(spec.description())) {
            linkData.put("description", spec.description());
        }
        linkData.put("call_to_action", Map.of("type", spec.callToAction(), "value", Map.of("link", spec.linkUrl())));
        Map<String, Object> story = new LinkedHashMap<>();
        story.put("page_id", spec.pageId());
        story.put("link_data", linkData);
        if (spec.instagramUserId() != null) {
            story.put("instagram_user_id", spec.instagramUserId());
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("name", spec.name());
        form.add("object_story_spec", jsonMapper.writeValueAsString(story));
        return new CreatedCreative(postCreation(adAccountId, accessToken, "adcreatives", form, unknownCreationMessage("광고 소재")));
    }

    public CreatedAd createAd(String adAccountId, String accessToken, String name, String adSetId, String creativeId) {
        validateAdAccountRequest(adAccountId, accessToken);
        requireNodeId(adSetId);
        requireNodeId(creativeId);
        if (!validName(name)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "광고 이름을 확인해 주세요.");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("name", name);
        form.add("adset_id", adSetId);
        form.add("creative", jsonMapper.writeValueAsString(Map.of("creative_id", creativeId)));
        form.add("status", "PAUSED");
        return new CreatedAd(postCreation(adAccountId, accessToken, "ads", form, unknownCreationMessage("광고")));
    }

    public CampaignForUpdate getCampaignForUpdate(String adAccountId, String accessToken, String campaignId) {
        validateAdAccountRequest(adAccountId, accessToken);
        requireNodeId(campaignId);
        JsonNode campaign = get(campaignId, "id,account_id,objective,daily_budget,lifetime_budget", accessToken, null);
        requireUpdateIdentity(campaign, adAccountId, campaignId);
        requiredText(campaign, "objective");
        return new CampaignForUpdate(campaignId, optionalBudget(campaign, "daily_budget"),
                optionalBudget(campaign, "lifetime_budget"));
    }

    public AdSetForUpdate getAdSetForUpdate(String adAccountId, String accessToken, String adSetId) {
        validateAdAccountRequest(adAccountId, accessToken);
        requireNodeId(adSetId);
        JsonNode adSet = get(adSetId, "id,account_id,campaign_id,daily_budget,lifetime_budget", accessToken, null);
        requireUpdateIdentity(adSet, adAccountId, adSetId);
        String campaignId = requiredText(adSet, "campaign_id");
        if (!validNodeId(campaignId)) {
            throw invalidResponse();
        }
        return new AdSetForUpdate(adSetId, campaignId, optionalBudget(adSet, "daily_budget"),
                optionalBudget(adSet, "lifetime_budget"));
    }

    public void verifyAdForUpdate(String adAccountId, String accessToken, String adId) {
        validateAdAccountRequest(adAccountId, accessToken);
        requireNodeId(adId);
        JsonNode ad = get(adId, "id,account_id,adset_id,campaign_id", accessToken, null);
        requireUpdateIdentity(ad, adAccountId, adId);
        if (!validNodeId(requiredText(ad, "adset_id")) || !validNodeId(requiredText(ad, "campaign_id"))) {
            throw invalidResponse();
        }
    }

    public UpdatedAdObject updateCampaign(String adAccountId, String accessToken, String campaignId,
                                           String name, String status, Long dailyBudget) {
        validateAdAccountRequest(adAccountId, accessToken);
        requireNodeId(campaignId);
        return postUpdate(campaignId, accessToken, updateForm(name, status, dailyBudget));
    }

    public UpdatedAdObject updateAdSet(String adAccountId, String accessToken, String adSetId,
                                        String name, String status, Long dailyBudget) {
        validateAdAccountRequest(adAccountId, accessToken);
        requireNodeId(adSetId);
        return postUpdate(adSetId, accessToken, updateForm(name, status, dailyBudget));
    }

    public UpdatedAdObject updateAd(String adAccountId, String accessToken, String adId, String name, String status) {
        validateAdAccountRequest(adAccountId, accessToken);
        requireNodeId(adId);
        return postUpdate(adId, accessToken, updateForm(name, status, null));
    }

    private void requireUpdateIdentity(JsonNode object, String adAccountId, String objectId) {
        requireAccountId(object, adAccountId);
        if (!objectId.equals(requiredText(object, "id"))) {
            throw invalidResponse();
        }
    }

    private long optionalBudget(JsonNode object, String field) {
        return object.path(field).isMissingNode() || object.path(field).isNull() ? 0 : nonNegativeLong(object, field);
    }

    private MultiValueMap<String, String> updateForm(String name, String status, Long dailyBudget) {
        if ((name == null && status == null && dailyBudget == null)
                || (name != null && !validName(name))
                || (status != null && !Set.of("ACTIVE", "PAUSED").contains(status))
                || (dailyBudget != null && dailyBudget <= 0)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "수정할 광고 이름, 상태 또는 일 예산을 확인해 주세요.");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (name != null) {
            form.add("name", name);
        }
        if (status != null) {
            form.add("status", status);
        }
        if (dailyBudget != null) {
            form.add("daily_budget", Long.toString(dailyBudget));
        }
        return form;
    }

    private UpdatedAdObject postUpdate(String objectId, String accessToken, MultiValueMap<String, String> form) {
        String uncertainMessage = "수정 결과를 확인하지 못했습니다. Meta 광고 관리자에서 현재 상태와 예산을 확인해 주세요.";
        String rejectionMessage = "Meta 광고 수정 요청이 거절되었습니다. 계정 권한과 입력 정보를 확인해 주세요.";
        form.add("appsecret_proof", appSecretProof(accessToken));
        URI uri = UriComponentsBuilder.fromUriString(GRAPH_URL)
                .pathSegment(properties.getApiVersion(), objectId).build().toUri();
        try {
            JsonNode response = webClient.post().uri(uri)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .exchangeToMono(result -> {
                        if (!result.statusCode().is2xxSuccessful()) {
                            boolean rejected = result.statusCode().is4xxClientError() && result.statusCode().value() != 408;
                            ApiCode code = rejected && result.statusCode().value() != 429
                                    ? ApiCode.BAD_REQUEST : ApiCode.SERVER_ERROR;
                            return result.releaseBody().then(Mono.error(new ApiException(code,
                                    rejected ? rejectionMessage : uncertainMessage)));
                        }
                        return result.bodyToMono(byte[].class).map(jsonMapper::readTree);
                    }).block(REQUEST_TIMEOUT);
            if (response != null && response.isObject()
                    && (response.has("error") || (response.path("success").isBoolean() && !response.path("success").asBoolean()))) {
                throw new ApiException(ApiCode.BAD_REQUEST, rejectionMessage);
            }
            if (response == null || !response.isObject() || !response.path("success").isBoolean()
                    || !response.path("success").asBoolean()) {
                throw new ApiException(ApiCode.SERVER_ERROR, uncertainMessage);
            }
            return new UpdatedAdObject(objectId, true);
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // A failed transport or malformed acknowledgement can follow a completed write. Never retry it.
            throw new ApiException(ApiCode.SERVER_ERROR, uncertainMessage);
        }
    }

    private String postCreation(String adAccountId, String accessToken, String edge,
                                MultiValueMap<String, String> form, String uncertainMessage) {
        form.add("appsecret_proof", appSecretProof(accessToken));
        URI uri = UriComponentsBuilder.fromUriString(GRAPH_URL)
                .pathSegment(properties.getApiVersion(), adAccountId, edge).build().toUri();
        try {
            JsonNode response = webClient.post().uri(uri)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .exchangeToMono(result -> {
                        if (!result.statusCode().is2xxSuccessful()) {
                            boolean unknown = !result.statusCode().is4xxClientError() || result.statusCode().value() == 408;
                            ApiCode code = !unknown && result.statusCode().is4xxClientError() && result.statusCode().value() != 429
                                    ? ApiCode.BAD_REQUEST : ApiCode.SERVER_ERROR;
                            return result.releaseBody().then(Mono.error(new CreationException(code,
                                    unknown ? uncertainMessage : "Meta 광고 등록 요청이 거절되었습니다. 계정 권한과 입력 정보를 확인해 주세요.", unknown)));
                        }
                        return result.bodyToMono(byte[].class).map(jsonMapper::readTree);
                    }).block(REQUEST_TIMEOUT);
            if (response != null && response.isObject() && response.has("error")) {
                throw new CreationException(ApiCode.BAD_REQUEST,
                        "Meta 광고 등록 요청이 거절되었습니다. 계정 권한과 입력 정보를 확인해 주세요.", false);
            }
            if (response == null || !response.isObject()) {
                throw new CreationException(ApiCode.SERVER_ERROR, uncertainMessage, true);
            }
            String id = requiredText(response, "id");
            if (!id.matches("[0-9]{1,32}")) {
                throw new CreationException(ApiCode.SERVER_ERROR, uncertainMessage, true);
            }
            return id;
        } catch (CreationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // A failed transport or malformed success may follow a completed write. Never retry or expose upstream data.
            throw new CreationException(ApiCode.SERVER_ERROR, uncertainMessage, true);
        }
    }

    private String unknownCreationMessage(String objectName) {
        return "Meta " + objectName + " 생성 결과를 확인하지 못했습니다. 중복 생성을 피하려면 Meta 광고 관리자에서 먼저 확인해 주세요.";
    }

    private boolean validName(String value) {
        return StringUtils.hasText(value) && value.length() <= 255;
    }

    private boolean validNodeId(String value) {
        return value != null && value.matches("[0-9]{1,32}");
    }

    private void requireNodeId(String value) {
        if (!validNodeId(value)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "Meta 광고 객체 ID를 확인해 주세요.");
        }
    }

    private boolean validHttpsUrl(String value) {
        if (!StringUtils.hasText(value) || value.length() > 2048) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public List<Insights> getCampaignInsights(String adAccountId, String accessToken, LocalDate since, LocalDate until) {
        return readInsights(adAccountId, accessToken, since, until, true);
    }

    public List<Insights> getAccountInsights(String adAccountId, String accessToken, LocalDate since, LocalDate until) {
        return readInsights(adAccountId, accessToken, since, until, false);
    }

    private List<Insights> readInsights(String adAccountId, String accessToken, LocalDate since,
                                       LocalDate until, boolean campaignLevel) {
        validateAdAccountRequest(adAccountId, accessToken);
        if (since == null || until == null || since.isAfter(until)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "성과 조회 기간을 확인해 주세요.");
        }
        String fields = campaignLevel ? "account_id,campaign_id,campaign_name,spend,impressions,clicks,action_values"
                : "account_id,spend,impressions,clicks,action_values";
        Map<String, String> parameters = Map.of("level", campaignLevel ? "campaign" : "account",
                "time_range", jsonMapper.writeValueAsString(Map.of("since", since.toString(), "until", until.toString())));
        List<Insights> insights = new ArrayList<>();
        Set<String> campaignIds = new HashSet<>();
        for (JsonNode row : readEdge(adAccountId + "/insights", fields, accessToken, parameters)) {
            requireAccountId(row, adAccountId);
            String campaignId = campaignLevel ? requiredText(row, "campaign_id") : null;
            if (campaignLevel ? !campaignIds.add(campaignId) : !insights.isEmpty()) {
                // No time increment or breakdowns: each entity must have one row for the entire period.
                throw invalidResponse();
            }
            insights.add(new Insights(campaignId, campaignLevel ? requiredText(row, "campaign_name") : null,
                    nonNegativeDecimal(row, "spend"), insightCount(row, "impressions"), insightCount(row, "clicks"),
                    purchaseValue(row)));
        }
        return List.copyOf(insights);
    }

    private BigDecimal purchaseValue(JsonNode row) {
        JsonNode values = row.path("action_values");
        if (values.isMissingNode() || values.isNull()) {
            return BigDecimal.ZERO;
        }
        if (!values.isArray()) {
            throw invalidResponse();
        }
        BigDecimal omniPurchase = null;
        BigDecimal purchase = null;
        for (JsonNode action : values) {
            String type = requiredText(action, "action_type");
            if ("omni_purchase".equals(type)) {
                if (omniPurchase != null) {
                    throw invalidResponse();
                }
                omniPurchase = nonNegativeDecimal(action, "value");
            } else if ("purchase".equals(type)) {
                if (purchase != null) {
                    throw invalidResponse();
                }
                purchase = nonNegativeDecimal(action, "value");
            }
        }
        // Use a single aggregate. Pixel/app purchase entries may overlap with these totals.
        return omniPurchase != null ? omniPurchase : purchase != null ? purchase : BigDecimal.ZERO;
    }

    private void validateAdAccountRequest(String adAccountId, String accessToken) {
        properties.validate();
        if (adAccountId == null || !adAccountId.matches("act_[0-9]{1,32}") || !StringUtils.hasText(accessToken)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "Meta 광고 계정의 연결 상태를 확인해 주세요.");
        }
    }

    private void requireAccountId(JsonNode node, String adAccountId) {
        if (!adAccountId.substring(4).equals(requiredText(node, "account_id"))) {
            throw invalidResponse();
        }
    }

    private BigDecimal nonNegativeDecimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        String text = value.isString() ? value.asString() : value.isNumber() ? value.toString() : "";
        if (text.length() > 64 || !text.matches("[0-9]+(?:\\.[0-9]+)?")) {
            throw invalidResponse();
        }
        return new BigDecimal(text);
    }

    private long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        String text = value.isString() ? value.asString() : value.isIntegralNumber() ? value.toString() : "";
        if (text.length() > 19 || !text.matches("[0-9]+")) {
            throw invalidResponse();
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException exception) {
            throw invalidResponse();
        }
    }

    private Long insightCount(JsonNode row, String field) {
        JsonNode value = row.path(field);
        // Insights can omit a metric. Unknown counts must not become zero or invalidate known spend/ROAS.
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.isString() ? value.asString() : value.isNumber() ? value.toString() : "";
        if (text.length() <= 64 && text.matches("[0-9]+(?:\\.[0-9]+)?")) {
            try {
                // Accept mathematically integral values such as "12.0" without truncating fractional counts.
                return new BigDecimal(text).longValueExact();
            } catch (ArithmeticException exception) {
                // Keep the response and its value out of exceptions and logs.
            }
        }
        throw new ApiException(ApiCode.SERVER_ERROR,
                "Meta 성과의 " + field + " 값을 확인할 수 없습니다. 잠시 후 다시 조회해 주세요.");
    }

    private MultiValueMap<String, String> credentials() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.getAppId());
        form.add("client_secret", properties.getAppSecret());
        return form;
    }

    private JsonNode exchangeToken(MultiValueMap<String, String> form) {
        // Facebook supports client_secret_post; form data keeps credentials out of URL logs.
        return readResponse(webClient.post().uri(graphUri("oauth/access_token", null, null))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form)));
    }

    private List<JsonNode> readEdge(String path, String fields, String accessToken) {
        return readEdge(path, fields, accessToken, Map.of());
    }

    private List<JsonNode> readEdge(String path, String fields, String accessToken, Map<String, String> parameters) {
        List<JsonNode> entries = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String after = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        for (int page = 0; page < MAX_PAGES; page++) {
            if (System.nanoTime() > deadline) {
                throw new ApiException(ApiCode.SERVER_ERROR, "Meta 목록 조회 시간이 초과되었습니다. 다시 시도해 주세요.");
            }
            JsonNode response = get(path, fields, accessToken, after, parameters);
            JsonNode data = response.path("data");
            if (!data.isArray()) {
                throw invalidResponse();
            }
            for (JsonNode item : data) {
                if (!item.isObject()) {
                    throw invalidResponse();
                }
                entries.add(item);
            }
            JsonNode paging = response.path("paging");
            if (!paging.isMissingNode() && !paging.isNull() && !paging.isObject()) {
                throw invalidResponse();
            }
            JsonNode next = paging.path("next");
            if (next.isMissingNode() || next.isNull()) {
                return entries;
            }
            if (!next.isString() || !StringUtils.hasText(next.asString())) {
                throw invalidResponse();
            }
            // Never request paging.next: it can contain a token or a different host.
            after = requiredText(paging.path("cursors"), "after");
            if (after.length() > 4096 || !cursors.add(after)) {
                throw invalidResponse();
            }
        }
        throw new ApiException(ApiCode.BAD_REQUEST, "Meta 목록이 조회 한도를 초과했습니다. 조회 범위를 줄여 주세요.");
    }

    private JsonNode get(String path, String fields, String accessToken, String after) {
        return get(path, fields, accessToken, after, Map.of());
    }

    private JsonNode get(String path, String fields, String accessToken, String after, Map<String, String> parameters) {
        URI uri = UriComponentsBuilder.fromUri(graphUri(path, fields, after, parameters))
                .queryParam("appsecret_proof", appSecretProof(accessToken)).build(true).toUri();
        return readResponse(webClient.get().uri(uri)
                .headers(headers -> headers.setBearerAuth(accessToken)));
    }

    private String appSecretProof(String accessToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getAppSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(accessToken.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new ApiException(ApiCode.SERVER_ERROR, "Meta 요청 인증을 생성하지 못했습니다.");
        }
    }

    private URI graphUri(String path, String fields, String after) {
        return graphUri(path, fields, after, Map.of());
    }

    private URI graphUri(String path, String fields, String after, Map<String, String> parameters) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(GRAPH_URL)
                .pathSegment(properties.getApiVersion()).path("/" + path);
        Map<String, String> variables = new LinkedHashMap<>(parameters);
        if (fields != null) {
            builder.queryParam("fields", "{fields}");
            variables.put("fields", fields);
        }
        if (path.startsWith("me/") || path.endsWith("/campaigns") || path.endsWith("/insights")
                || path.endsWith("/promote_pages")) {
            builder.queryParam("limit", 100);
        }
        if (after != null) {
            builder.queryParam("after", "{after}");
            variables.put("after", after);
        }
        parameters.forEach((key, value) -> builder.queryParam(key, "{" + key + "}"));
        return builder.encode().buildAndExpand(variables).toUri();
    }

    private JsonNode readResponse(WebClient.RequestHeadersSpec<?> request) {
        try {
            JsonNode response = request.exchangeToMono(result -> {
                if (!result.statusCode().is2xxSuccessful()) {
                    ApiCode code = result.statusCode().is4xxClientError() && result.statusCode().value() != 429
                            ? ApiCode.BAD_REQUEST : ApiCode.SERVER_ERROR;
                    return result.releaseBody().then(Mono.error(new ApiException(code,
                            "Meta 요청을 처리하지 못했습니다. 계정 권한과 연결 상태를 확인해 주세요.")));
                }
                // JsonNode decoding logs response values at DEBUG, including OAuth access tokens.
                return result.bodyToMono(byte[].class).map(jsonMapper::readTree);
            }).block(REQUEST_TIMEOUT);
            if (response == null || !response.isObject() || response.has("error")) {
                throw invalidResponse();
            }
            return response;
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Upstream exceptions can contain tokens, URLs or response bodies; do not retain their cause.
            throw new ApiException(ApiCode.SERVER_ERROR, "Meta 서버와 통신하지 못했습니다. 다시 시도해 주세요.");
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field, null);
        if (!StringUtils.hasText(value)) {
            throw invalidResponse();
        }
        return value;
    }

    private String optionalText(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isString() && StringUtils.hasText(value.asString()) ? value.asString() : fallback;
    }

    private ApiException invalidResponse() {
        return new ApiException(ApiCode.SERVER_ERROR, "Meta 응답이 올바르지 않습니다. 다시 연결해 주세요.");
    }

    public record AuthorizedAccount(String userId, String name, String accessToken,
                                    LocalDateTime expiresAt, String grantedScopes) {
        @Override
        public String toString() {
            return "AuthorizedAccount[userId=" + userId + ", accessToken=REDACTED]";
        }
    }

    public record DiscoveredAsset(String externalId, String name, PlatformType platformType,
                                  AssetType assetType, String facebookPageId) {
    }

    public record Campaign(String id, String name, String status, String effectiveStatus, String objective) {
    }

    public record CreatedCampaign(String id) {
    }

    public record AdSetSpec(String name, String objective, long dailyBudget, List<String> countries,
                            int ageMin, int ageMax, String pixelId, boolean instagramEnabled) {
    }

    public record CreativeSpec(String name, String pageId, String instagramUserId, String linkUrl, String imageUrl,
                               String message, String headline, String description, String callToAction) {
    }

    public record CreatedAdSet(String id) {
    }

    public record CreatedCreative(String id) {
    }

    public record CreatedAd(String id) {
    }

    public record CampaignForUpdate(String id, long dailyBudget, long lifetimeBudget) {
    }

    public record AdSetForUpdate(String id, String campaignId, long dailyBudget, long lifetimeBudget) {
    }

    public record UpdatedAdObject(String id, boolean success) {
    }

    public static class CreationException extends ApiException {
        private final boolean outcomeUnknown;

        public CreationException(ApiCode code, String message, boolean outcomeUnknown) {
            super(code, message);
            this.outcomeUnknown = outcomeUnknown;
        }

        public boolean isOutcomeUnknown() {
            return outcomeUnknown;
        }
    }

    public record AdAccount(String id, String name, String currency, String timezoneName) {
    }

    public record Insights(String campaignId, String campaignName, BigDecimal spend, Long impressions, Long clicks,
                           BigDecimal purchaseValue) {
        public Insights(String campaignId, String campaignName, BigDecimal spend, long impressions, long clicks,
                        BigDecimal purchaseValue) {
            this(campaignId, campaignName, spend, Long.valueOf(impressions), Long.valueOf(clicks), purchaseValue);
        }
    }
}
