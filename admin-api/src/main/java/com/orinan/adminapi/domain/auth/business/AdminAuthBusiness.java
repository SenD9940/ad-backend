package com.orinan.adminapi.domain.auth.business;

import com.orinan.adminapi.annotation.Business;
import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.domain.auth.controller.model.AdminLoginRequest;
import com.orinan.adminapi.domain.auth.controller.model.AdminLoginResponse;
import com.orinan.adminapi.domain.auth.controller.model.AdminMeResponse;
import com.orinan.adminapi.domain.auth.converter.AdminAuthConverter;
import com.orinan.adminapi.domain.auth.model.AdminPrincipal;
import com.orinan.adminapi.domain.auth.service.AdminAuthService;
import com.orinan.adminapi.domain.token.business.AdminTokenBusiness;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@Business
@RequiredArgsConstructor
public class AdminAuthBusiness {
    private final AdminAuthService authService;
    private final AdminAuthConverter authConverter;
    private final AdminTokenBusiness tokenBusiness;
    private final AdminAuditService auditService;

    @Transactional
    public AdminLoginResponse login(AdminLoginRequest request) {
        var user = authService.authenticateCredentials(request.email(), request.password());
        authService.updateLastLogin(user);
        var token = tokenBusiness.issueToken(user.getId());
        auditService.record(user.getId(), "ADMIN_LOGIN", "USER", user.getId(), "관리자 로그인", null, null);
        return authConverter.toLoginResponse(token, authConverter.toResponse(user, authService.displayName(user.getId())));
    }

    @Transactional(readOnly = true)
    public AdminPrincipal authenticate(String token) {
        long userId = tokenBusiness.validateAccessToken(token);
        var user = authService.findActiveAdmin(userId);
        return authConverter.toPrincipal(user);
    }

    @Transactional(readOnly = true)
    public AdminMeResponse me(long userId) {
        var user = authService.findActiveAdmin(userId);
        return authConverter.toResponse(user, authService.displayName(user.getId()));
    }

    @Transactional
    public void logout(long userId) {
        var revocation = tokenBusiness.expireToken(userId);
        auditService.record(userId, "ADMIN_LOGOUT", "USER", userId, "관리자 로그아웃",
                Long.toString(revocation.previousAuthVersion()), Long.toString(revocation.authVersion()));
    }
}
