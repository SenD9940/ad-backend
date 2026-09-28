package com.orinan.api.domain.platformconnection.naver.authorization;

import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;

/** Saves only a verified seller grant; implementations recheck owner and subscription under a database lock. */
public interface NaverAuthorizationConnectionBridge {
    String reconnectAccountUid(Long workspaceId, Long userId, Long connectionId);
    Long save(Long workspaceId, Long userId, Long reconnectConnectionId, String applicationRef,
              Long subscriptionId, long generation, NaverCommerceClient.IssuedToken token,
              NaverCommerceClient.SellerAccount account);
}
