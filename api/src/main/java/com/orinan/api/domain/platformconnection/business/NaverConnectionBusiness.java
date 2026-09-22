package com.orinan.api.domain.platformconnection.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.platformconnection.controller.model.NaverChannelResponse;
import com.orinan.api.domain.platformconnection.controller.model.NaverChannelSelectRequest;
import com.orinan.api.domain.platformconnection.controller.model.NaverConnectRequest;
import com.orinan.api.domain.platformconnection.controller.model.PlatformConnectionResponse;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.Channel;
import com.orinan.api.domain.platformconnection.service.NaverConnectionService;
import com.orinan.api.domain.platformconnection.service.NaverConnectionService.Credentials;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Business
@RequiredArgsConstructor
public class NaverConnectionBusiness {

    private final PlatformConnectionService platformConnections;
    private final NaverConnectionService service;
    private final NaverCommerceClient client;

    public PlatformConnectionResponse connect(Long workspaceId, Long userId, NaverConnectRequest request) {
        platformConnections.requireOwner(workspaceId, userId);
        var token = client.issueToken(request.getClientId(), request.getClientSecret(), request.getTokenType(), request.getAccountId());
        var account = client.getSellerAccount(token.accessToken());
        if (request.getTokenType() == NaverTokenType.SELLER
                && !account.accountId().equals(request.getAccountId()) && !account.accountUid().equals(request.getAccountId())) {
            throw new ApiException(ApiCode.BAD_REQUEST, "요청한 판매자와 인증된 네이버 판매자가 일치하지 않습니다.");
        }
        return service.saveConnection(workspaceId, userId, request, token, account);
    }

    public List<Channel> getChannels(Long workspaceId, Long connectionId, Long userId) {
        return fetchChannels(workspaceId, connectionId, userId).channels();
    }

    public List<NaverChannelResponse> selectChannels(Long workspaceId, Long connectionId, Long userId,
                                                     NaverChannelSelectRequest request) {
        var fetched = fetchChannels(workspaceId, connectionId, userId);
        var selected = request.channelNos().stream().distinct().map(channelNo -> fetched.channels().stream()
                .filter(channel -> channel.channelNo() == channelNo)
                .findFirst().orElseThrow(() -> new ApiException(ApiCode.BAD_REQUEST,
                        "선택한 스마트스토어 채널에 접근할 수 없습니다. 채널을 다시 조회해 주세요."))).toList();
        return service.saveChannels(workspaceId, connectionId, userId, fetched.credentials(), selected);
    }

    private FetchedChannels fetchChannels(Long workspaceId, Long connectionId, Long userId) {
        var credentials = service.getCredentials(workspaceId, connectionId, userId);
        if (credentials.expiresAt() == null || !credentials.expiresAt().isAfter(SeoulDateTimes.now().plusMinutes(1))) {
            credentials = refresh(workspaceId, connectionId, userId, credentials);
        }
        List<Channel> channels;
        try {
            channels = client.getChannels(credentials.accessToken());
        } catch (NaverCommerceClient.AuthenticationException exception) {
            credentials = refresh(workspaceId, connectionId, userId, credentials);
            try {
                channels = client.getChannels(credentials.accessToken());
            } catch (NaverCommerceClient.AuthenticationException retryFailure) {
                service.markRequiresReauth(workspaceId, connectionId, userId, credentials);
                throw retryFailure;
            }
        }
        platformConnections.requireMember(workspaceId, userId);
        return new FetchedChannels(credentials, channels.stream().filter(channel -> "STOREFARM".equals(channel.channelType())).toList());
    }

    private Credentials refresh(Long workspaceId, Long connectionId, Long userId, Credentials credentials) {
        platformConnections.requireMember(workspaceId, userId);
        var token = client.issueToken(credentials.clientId(), credentials.clientSecret(), credentials.tokenType(), credentials.accountId());
        NaverCommerceClient.SellerAccount account;
        try {
            account = client.getSellerAccount(token.accessToken());
        } catch (NaverCommerceClient.AuthenticationException exception) {
            service.markRequiresReauth(workspaceId, connectionId, userId, credentials);
            throw exception;
        }
        if (!account.accountUid().equals(credentials.accountUid())) {
            service.markRequiresReauth(workspaceId, connectionId, userId, credentials);
            throw new ApiException(ApiCode.BAD_REQUEST, "연결된 판매자가 변경되었습니다. 네이버 스마트스토어를 다시 연결해 주세요.");
        }
        return service.updateToken(workspaceId, connectionId, userId, credentials, token);
    }

    private record FetchedChannels(Credentials credentials, List<Channel> channels) {
    }
}
