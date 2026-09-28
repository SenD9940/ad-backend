package com.orinan.api.domain.platformconnection.naver.selftest;

import com.orinan.api.annotation.Business;
import com.orinan.api.domain.platformconnection.controller.model.PlatformConnectionResponse;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import lombok.RequiredArgsConstructor;

@Business
@RequiredArgsConstructor
public class NaverSelfTestBusiness {
    private final NaverSelfTestPolicy policy;
    private final NaverCommerceClient client;
    private final NaverSelfTestService service;

    public NaverSelfTestPolicy.Availability availability(Long workspaceId, Long userId) {
        return policy.availability(workspaceId, userId);
    }

    public PlatformConnectionResponse connect(Long workspaceId, Long userId) {
        var credentials = policy.requireConfiguredOwner(workspaceId, userId);
        var token = client.issueToken(credentials.appId(), credentials.appSecret(), NaverTokenType.SELF, null);
        var account = client.getSellerAccount(token.accessToken());
        return service.save(workspaceId, userId, credentials, token, account);
    }
}
