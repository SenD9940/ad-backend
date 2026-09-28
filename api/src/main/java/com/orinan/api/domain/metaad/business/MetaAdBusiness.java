package com.orinan.api.domain.metaad.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.metaad.controller.model.*;
import com.orinan.api.domain.metaad.exception.MetaAdCreationException;
import com.orinan.api.domain.metaad.service.MetaAdImageService;
import com.orinan.api.domain.metaad.service.MetaAdService;
import com.orinan.api.domain.metaad.service.MetaAdService.SavedAdAccount;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Business
@RequiredArgsConstructor
public class MetaAdBusiness {

    private final MetaAdService service;
    private final MetaGraphClient client;
    private final MetaAdImageService images;

    public MetaGraphClient.UpdatedAdObject updateAd(Long workspaceId, Long assetId, Long userId, String adId,
                                                   MetaAdUpdateRequest request) {
        validateUpdate(adId, request.name(), request.status(), null);
        var account = service.getAdAccountForManagement(workspaceId, assetId, userId);
        client.verifyAdForUpdate(account.externalId(), account.accessToken(), adId);
        service.verifyManagementUnchanged(workspaceId, userId, account);
        return client.updateAd(account.externalId(), account.accessToken(), adId, request.name(),
                request.status() == null ? null : request.status().name());
    }

    public MetaGraphClient.UpdatedAdObject updateAdSet(Long workspaceId, Long assetId, Long userId, String adSetId,
                                                      MetaAdBudgetUpdateRequest request) {
        validateUpdate(adSetId, request.name(), request.status(), request.dailyBudget());
        var account = service.getAdAccountForManagement(workspaceId, assetId, userId);
        var adSet = client.getAdSetForUpdate(account.externalId(), account.accessToken(), adSetId);
        if (request.dailyBudget() != null) {
            var campaign = client.getCampaignForUpdate(account.externalId(), account.accessToken(), adSet.campaignId());
            if (campaign.dailyBudget() > 0 || campaign.lifetimeBudget() > 0) {
                throw new ApiException(ApiCode.BAD_REQUEST, "캠페인에서 예산을 관리하고 있습니다. 캠페인의 예산을 수정해 주세요.");
            }
            requireDailyBudget(adSet.dailyBudget(), adSet.lifetimeBudget());
        }
        service.verifyManagementUnchanged(workspaceId, userId, account);
        return client.updateAdSet(account.externalId(), account.accessToken(), adSetId, request.name(),
                request.status() == null ? null : request.status().name(), request.dailyBudget());
    }

    public MetaGraphClient.UpdatedAdObject updateCampaign(Long workspaceId, Long assetId, Long userId, String campaignId,
                                                         MetaAdBudgetUpdateRequest request) {
        validateUpdate(campaignId, request.name(), request.status(), request.dailyBudget());
        var account = service.getAdAccountForManagement(workspaceId, assetId, userId);
        var campaign = client.getCampaignForUpdate(account.externalId(), account.accessToken(), campaignId);
        if (request.dailyBudget() != null) {
            requireDailyBudget(campaign.dailyBudget(), campaign.lifetimeBudget());
        }
        service.verifyManagementUnchanged(workspaceId, userId, account);
        return client.updateCampaign(account.externalId(), account.accessToken(), campaignId, request.name(),
                request.status() == null ? null : request.status().name(), request.dailyBudget());
    }

    private void validateUpdate(String id, String name, MetaAdUpdateRequest.Status status, Long dailyBudget) {
        if (id == null || !id.matches("[0-9]{1,32}")) {
            throw new ApiException(ApiCode.BAD_REQUEST, "Meta 광고 객체 ID를 확인해 주세요.");
        }
        if ((name == null && status == null && dailyBudget == null)
                || (name != null && (name.isBlank() || name.length() > 255))
                || (dailyBudget != null && dailyBudget <= 0)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "수정할 이름, 상태 또는 양의 정수 일 예산을 입력해 주세요.");
        }
    }

    private void requireDailyBudget(long dailyBudget, long lifetimeBudget) {
        if (dailyBudget <= 0 || lifetimeBudget > 0) {
            throw new ApiException(ApiCode.BAD_REQUEST,
                    "현재 일 예산을 사용하는 항목만 수정할 수 있습니다. 총 예산 또는 예산 관리 단위 전환은 지원하지 않습니다.");
        }
    }

    public MetaAdImageUploadResponse uploadImage(Long workspaceId, Long assetId, Long userId, MultipartFile file) {
        var account = service.getAdAccountForManagement(workspaceId, assetId, userId);
        var uploaded = images.upload(workspaceId, assetId, file);
        service.verifyManagementUnchanged(workspaceId, userId, account);
        return uploaded;
    }

    public MetaAdCreateResponse createAd(Long workspaceId, Long assetId, Long userId, MetaAdCreateRequest request) {
        validateAdRequest(request);
        var campaign = request.campaign();
        var adSet = request.adSet();
        var ad = request.ad();
        var account = service.getAdAccountForManagement(workspaceId, assetId, userId);
        var identity = service.getAdIdentity(workspaceId, userId, account, ad.pageAssetId(), ad.instagramAssetId());
        // Resolve stored images before creating any remote objects, using a fresh URL for each request.
        String imageUrl = ad.imageKey() == null ? ad.imageUrl()
                : images.resolveImageUrl(workspaceId, assetId, ad.imageKey());
        var pages = client.listPromotablePages(account.externalId(), account.accessToken());
        service.verifyManagementUnchanged(workspaceId, userId, account);
        if (pages.stream().noneMatch(page -> identity.pageId().equals(page.externalId()))) {
            throw new ApiException(ApiCode.BAD_REQUEST,
                    "선택한 광고 계정에서 사용할 수 없는 페이지입니다. 페이지를 다시 조회해 선택해 주세요.");
        }
        String campaignId = null;
        String adSetId = null;
        String creativeId = null;
        var step = MetaAdCreateResponse.Step.CAMPAIGN;
        try {
            campaignId = client.createCampaign(account.externalId(), account.accessToken(), campaign.name(), campaign.objective().name(),
                    campaign.specialAdCategories().stream().map(Enum::name).distinct().toList(),
                    campaign.specialAdCategoryCountry() == null ? List.of() : campaign.specialAdCategoryCountry().stream().distinct().toList()).id();

            step = MetaAdCreateResponse.Step.AD_SET;
            verifyIdentity(workspaceId, userId, account, ad, identity);
            adSetId = client.createAdSet(account.externalId(), account.accessToken(), campaignId,
                    new MetaGraphClient.AdSetSpec(adSet.name(), campaign.objective().name(), adSet.dailyBudget(),
                            adSet.countries().stream().distinct().toList(), adSet.ageMin(), adSet.ageMax(), adSet.pixelId(),
                            identity.instagramUserId() != null)).id();

            step = MetaAdCreateResponse.Step.CREATIVE;
            verifyIdentity(workspaceId, userId, account, ad, identity);
            creativeId = client.createImageCreative(account.externalId(), account.accessToken(),
                    new MetaGraphClient.CreativeSpec(ad.name(), identity.pageId(), identity.instagramUserId(),
                            ad.linkUrl(), imageUrl, ad.message(), ad.headline(), ad.description(), ad.callToAction().name())).id();

            step = MetaAdCreateResponse.Step.AD;
            verifyIdentity(workspaceId, userId, account, ad, identity);
            var created = client.createAd(account.externalId(), account.accessToken(), ad.name(), adSetId, creativeId);
            return new MetaAdCreateResponse(assetId, account.externalId(), campaignId, adSetId, creativeId, created.id(),
                    MetaAdCreateResponse.Status.CREATED, null, "캠페인·광고세트·광고를 일시정지 상태로 생성했습니다.");
        } catch (ApiException exception) {
            boolean unknown = exception instanceof MetaGraphClient.CreationException failure && failure.isOutcomeUnknown();
            var result = new MetaAdCreateResponse(assetId, account.externalId(), campaignId, adSetId, creativeId, null,
                    unknown ? MetaAdCreateResponse.Status.UNKNOWN : MetaAdCreateResponse.Status.FAILED, step,
                    exception.getDescription());
            throw new MetaAdCreationException(exception, result);
        } catch (RuntimeException exception) {
            // Preserve completed IDs even if a later local check fails. Never expose raw DB/HTTP exceptions.
            String message = "광고 등록 중 오류가 발생했습니다. 응답의 생성된 항목과 Meta 광고 관리자에서 결과를 확인해 주세요.";
            var result = new MetaAdCreateResponse(assetId, account.externalId(), campaignId, adSetId, creativeId, null,
                    MetaAdCreateResponse.Status.UNKNOWN, step, message);
            throw new MetaAdCreationException(new ApiException(ApiCode.SERVER_ERROR, message), result);
        }
    }

    private void verifyIdentity(Long workspaceId, Long userId, SavedAdAccount account, MetaAdCreateRequest.Ad ad,
                                MetaAdService.AdIdentity expected) {
        var current = service.getAdIdentity(workspaceId, userId, account, ad.pageAssetId(), ad.instagramAssetId());
        if (!expected.equals(current)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "페이지 또는 인스타그램 연결 정보가 변경되었습니다. 생성된 항목을 확인해 주세요.");
        }
    }

    private void validateAdRequest(MetaAdCreateRequest request) {
        var objective = request.campaign().objective();
        if (objective != MetaCampaignCreateRequest.Objective.OUTCOME_TRAFFIC
                && objective != MetaCampaignCreateRequest.Objective.OUTCOME_SALES) {
            throw new ApiException(ApiCode.BAD_REQUEST, "이미지 광고 생성은 트래픽 또는 판매 목표를 선택해 주세요.");
        }
        var adSet = request.adSet();
        if (adSet.ageMin() > adSet.ageMax()) {
            throw new ApiException(ApiCode.BAD_REQUEST, "최소 연령은 최대 연령보다 클 수 없습니다.");
        }
        if (objective == MetaCampaignCreateRequest.Objective.OUTCOME_SALES && adSet.pixelId() == null) {
            throw new ApiException(ApiCode.BAD_REQUEST, "판매 목표의 광고에는 구매 전환을 추적할 pixel_id가 필요합니다.");
        }
        if (objective == MetaCampaignCreateRequest.Objective.OUTCOME_TRAFFIC && adSet.pixelId() != null) {
            throw new ApiException(ApiCode.BAD_REQUEST, "트래픽 목표에는 pixel_id를 지정하지 않습니다.");
        }
        if (!request.ad().isImageSourceValid()) {
            throw new ApiException(ApiCode.BAD_REQUEST, "image_key 또는 image_url 중 하나만 입력해 주세요.");
        }
        if ((request.ad().imageUrl() != null && !isHttpsUrl(request.ad().imageUrl()))
                || !isHttpsUrl(request.ad().linkUrl())) {
            throw new ApiException(ApiCode.BAD_REQUEST, "이미지와 랜딩 페이지는 사용자 정보·프래그먼트가 없는 HTTPS 주소를 입력해 주세요.");
        }
    }

    private boolean isHttpsUrl(String value) {
        if (value == null) {
            return false;
        }
        try {
            var uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme()) && !host.isEmpty()
                    && uri.getUserInfo() == null && uri.getFragment() == null
                    && !host.equals("localhost") && !host.endsWith(".localhost") && !host.endsWith(".local")
                    && !host.equals("[::1]") && !host.equals("[::]")
                    && !host.matches("(?:0|10|127|169\\.254|192\\.168)\\..*|172\\.(?:1[6-9]|2[0-9]|3[01])\\..*");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public MetaGraphClient.CreatedCampaign createCampaign(Long workspaceId, Long assetId, Long userId,
                                                          MetaCampaignCreateRequest request) {
        var account = service.getAdAccountForManagement(workspaceId, assetId, userId);
        // Check permission before the write. A later read failure must not hide an already-created ID.
        return client.createCampaign(account.externalId(), account.accessToken(), request.name(), request.objective().name(),
                request.specialAdCategories().stream().map(Enum::name).distinct().toList(),
                request.specialAdCategoryCountry() == null ? List.of() : request.specialAdCategoryCountry().stream().distinct().toList());
    }

    public List<MetaGraphClient.Campaign> getCampaigns(Long workspaceId, Long assetId, Long userId) {
        var account = service.getAdAccount(workspaceId, assetId, userId);
        var campaigns = client.listCampaigns(account.externalId(), account.accessToken());
        service.verifyUnchanged(workspaceId, userId, List.of(account));
        return campaigns;
    }

    public MetaAdAccountPerformanceResponse getAccountPerformance(Long workspaceId, Long assetId, Long userId,
                                                                   LocalDate since, LocalDate until) {
        long days = validatePeriod(since, until);
        var saved = service.getAdAccount(workspaceId, assetId, userId);
        var summary = accountSummary(saved, since, until, days);
        var campaigns = client.getCampaignInsights(saved.externalId(), saved.accessToken(), since, until).stream()
                .map(row -> {
                    var metrics = metrics(row.spend(), row.impressions(), row.clicks(), row.purchaseValue());
                    return new MetaCampaignPerformance(row.campaignId(), row.campaignName(), metrics, dailyAverage(metrics, days));
                }).toList();
        service.verifyUnchanged(workspaceId, userId, List.of(saved));
        return new MetaAdAccountPerformanceResponse(since, until, days, summary, campaigns);
    }

    public MetaAdWorkspacePerformanceResponse getWorkspacePerformance(Long workspaceId, Long userId,
                                                                      LocalDate since, LocalDate until) {
        long days = validatePeriod(since, until);
        var saved = service.getAdAccounts(workspaceId, userId);
        List<MetaAdAccountSummary> accounts = new ArrayList<>();
        for (var account : saved) {
            accounts.add(accountSummary(account, since, until, days));
        }
        service.verifyUnchanged(workspaceId, userId, saved);

        // A workspace may contain several currencies. Never sum or average unlike monetary units.
        var byCurrency = accounts.stream().collect(Collectors.groupingBy(
                MetaAdAccountSummary::currency, TreeMap::new, Collectors.toList()));
        var totals = byCurrency.entrySet().stream().map(entry -> {
            var metrics = sum(entry.getValue().stream().map(MetaAdAccountSummary::metrics).toList());
            return new MetaAdCurrencyPerformance(entry.getKey(), entry.getValue().size(), metrics, dailyAverage(metrics, days));
        }).toList();
        return new MetaAdWorkspacePerformanceResponse(since, until, days, accounts.size(), List.copyOf(accounts), totals);
    }

    private MetaAdAccountSummary accountSummary(SavedAdAccount saved, LocalDate since, LocalDate until, long days) {
        var account = client.getAdAccount(saved.externalId(), saved.accessToken());
        var rows = client.getAccountInsights(saved.externalId(), saved.accessToken(), since, until);
        // Read account-level insights directly: campaign lists can omit archived or inactive objects.
        var metrics = sum(rows.stream().map(row -> metrics(row.spend(), row.impressions(), row.clicks(), row.purchaseValue())).toList());
        return new MetaAdAccountSummary(saved.assetId(), saved.connectionId(), account.id(), account.name(),
                account.currency(), account.timezoneName(), metrics, dailyAverage(metrics, days));
    }

    private long validatePeriod(LocalDate since, LocalDate until) {
        if (since == null || until == null || until.isBefore(since)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "조회 시작일과 종료일을 올바르게 입력해 주세요.");
        }
        long days = ChronoUnit.DAYS.between(since, until) + 1;
        if (days > 366) {
            throw new ApiException(ApiCode.BAD_REQUEST, "성과는 한 번에 최대 366일까지만 조회할 수 있습니다.");
        }
        return days;
    }

    private MetaAdMetrics sum(List<MetaAdMetrics> values) {
        BigDecimal spend = BigDecimal.ZERO;
        BigDecimal purchaseValue = BigDecimal.ZERO;
        // A missing metric makes only its own total unknown. Do not present a partial sum as a full total.
        Long impressions = values.stream().allMatch(value -> value.impressions() != null) ? 0L : null;
        Long clicks = values.stream().allMatch(value -> value.clicks() != null) ? 0L : null;
        try {
            for (var value : values) {
                spend = spend.add(value.spend());
                purchaseValue = purchaseValue.add(value.purchaseValue());
                if (impressions != null) impressions = Math.addExact(impressions, value.impressions());
                if (clicks != null) clicks = Math.addExact(clicks, value.clicks());
            }
        } catch (ArithmeticException exception) {
            throw new ApiException(ApiCode.SERVER_ERROR, "Meta 성과 수치가 집계 가능한 범위를 초과했습니다.");
        }
        return metrics(spend, impressions, clicks, purchaseValue);
    }

    private MetaAdMetrics metrics(BigDecimal spend, Long impressions, Long clicks, BigDecimal purchaseValue) {
        // Weighted ratios are derived from the totals, never from averages of account ratios.
        return new MetaAdMetrics(spend, impressions, clicks,
                ratio(clicks == null ? null : BigDecimal.valueOf(clicks).multiply(BigDecimal.valueOf(100)), impressions),
                ratio(spend, clicks), ratio(spend.multiply(BigDecimal.valueOf(1000)), impressions),
                purchaseValue, spend.signum() == 0 ? null : purchaseValue.divide(spend, 6, RoundingMode.HALF_UP));
    }

    private BigDecimal ratio(BigDecimal numerator, Long denominator) {
        return numerator == null || denominator == null || denominator == 0
                ? null : numerator.divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private MetaAdDailyAverage dailyAverage(MetaAdMetrics metrics, long days) {
        return new MetaAdDailyAverage(ratio(metrics.spend(), days),
                ratio(metrics.impressions() == null ? null : BigDecimal.valueOf(metrics.impressions()), days),
                ratio(metrics.clicks() == null ? null : BigDecimal.valueOf(metrics.clicks()), days));
    }
}
