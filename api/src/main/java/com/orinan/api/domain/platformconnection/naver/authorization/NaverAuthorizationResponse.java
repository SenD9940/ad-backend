package com.orinan.api.domain.platformconnection.naver.authorization;
import java.time.Instant;
public record NaverAuthorizationResponse(String attemptId,Long workspaceId,String status,long reviewRevision,
        Seller seller,Subscription subscription,Long connectionId,String nextAction,Instant expiresAt,
        String launchUrl,String errorMessage) {
    public record Seller(String name,String storeUrl) {}
    public record Subscription(boolean requiresApproval,String planName,String billingDescription) {}
    public record Capabilities(String mode,boolean ready,String reason,boolean manualConnectionAllowed) {}
}
