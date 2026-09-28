package com.orinan.api.domain.metaad.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.metaad.controller.model.MetaAdPageSaveRequest;
import com.orinan.api.domain.metaad.service.MetaAdService;
import com.orinan.api.domain.platformconnection.controller.model.PlatformAssetResponse;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Business
@RequiredArgsConstructor
public class MetaAdPageBusiness {

    private final MetaAdService accounts;
    private final MetaGraphClient client;
    private final PlatformConnectionService connections;

    public List<MetaGraphClient.DiscoveredAsset> getPages(Long workspaceId, Long assetId, Long userId) {
        var account = accounts.getAdAccountForManagement(workspaceId, assetId, userId);
        var pages = client.listPromotablePages(account.externalId(), account.accessToken());
        accounts.verifyManagementUnchanged(workspaceId, userId, account);
        return pages;
    }

    public List<PlatformAssetResponse> savePage(Long workspaceId, Long assetId, Long userId,
                                                MetaAdPageSaveRequest request) {
        var account = accounts.getAdAccountForManagement(workspaceId, assetId, userId);
        // Discover again at save time; the browser never supplies trusted page metadata.
        var pages = client.listPromotablePages(account.externalId(), account.accessToken());
        accounts.verifyManagementUnchanged(workspaceId, userId, account);
        var page = pages.stream().filter(item -> item.externalId().equals(request.externalId())).findFirst()
                .orElseThrow(() -> new ApiException(ApiCode.BAD_REQUEST,
                        "선택한 광고 계정에서 사용할 수 없는 페이지입니다. 페이지를 다시 조회해 선택해 주세요."));
        return connections.saveMetaAssets(workspaceId, account.connectionId(), userId,
                account.accessToken(), List.of(page));
    }
}
