package com.orinan.api.domain.token;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.token.business.TokenBusiness;
import com.orinan.api.domain.token.converter.TokenConverter;
import com.orinan.api.domain.token.exception.TokenErrorCode;
import com.orinan.api.domain.token.helper.TokenHelper;
import com.orinan.api.domain.token.model.TokenDto;
import com.orinan.api.domain.token.service.TokenService;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.db.user.UserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TokenBusinessTest {
    private final TokenService tokens = mock(TokenService.class);
    private final UserService users = mock(UserService.class);
    private final TokenBusiness business = new TokenBusiness(mock(RedisTemplate.class), tokens,
            new TokenConverter(), mock(TokenHelper.class), users);

    @Test
    void loginHoldsTheUserLockBeforeIssuingEitherToken() {
        when(tokens.issueAccessToken(7L)).thenReturn(TokenDto.builder().token("access").build());
        when(tokens.issueRefreshToken(7L)).thenReturn(TokenDto.builder().token("refresh").build());

        var response = business.issueToken(UserEntity.builder().id(7L).build());

        assertThat(response.getAccessToken()).isEqualTo("access");
        assertThat(response.getRefreshToken()).isEqualTo("refresh");
        var order = inOrder(tokens);
        order.verify(tokens).lockRegisteredUser(7L);
        order.verify(tokens).issueAccessToken(7L);
        order.verify(tokens).issueRefreshToken(7L);
    }

    @Test
    void concurrentRevocationFailureDoesNotRotateOrIssueReplacementTokens() {
        when(tokens.validateRefreshToken("revoked-refresh"))
                .thenThrow(new ApiException(TokenErrorCode.INVALID_TOKEN));

        assertThatThrownBy(() -> business.refreshTokenAndIssueNewTokenWithRefreshToken("revoked-refresh"))
                .isInstanceOf(ApiException.class);

        verify(tokens).validateRefreshToken("revoked-refresh");
        verifyNoMoreInteractions(tokens);
        verifyNoInteractions(users);
    }
}
