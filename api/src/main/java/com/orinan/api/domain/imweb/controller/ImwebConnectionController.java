package com.orinan.api.domain.imweb.controller;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.domain.imweb.business.ImwebConnectionBusiness;
import com.orinan.api.domain.imweb.client.ImwebProperties;
import com.orinan.api.domain.imweb.service.ImwebAccessService.*;
import com.orinan.api.domain.imweb.service.ImwebOAuthStateService;
import com.orinan.api.domain.user.controller.model.UserResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}")
public class ImwebConnectionController {
    private final ImwebConnectionBusiness business;
    private final ImwebProperties properties;
    @GetMapping("/connections/imweb/capabilities")
    public ResponseEntity<Api<ImwebConnectionBusiness.Capabilities>> capabilities(@PathVariable Long workspaceId, @UserSession UserResponse user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Api.OK(business.capabilities(workspaceId, user.getId())));
    }
    @PostMapping("/connections/imweb/authorize")
    public ResponseEntity<Api<AuthorizeResponse>> authorize(@PathVariable Long workspaceId, @UserSession UserResponse user,
                                                          @RequestBody @Valid AuthorizeRequest request) {
        var result = business.authorize(workspaceId, user.getId(), request.siteCode());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, ImwebOAuthCallbackController.cookie(properties, result.state(), ImwebOAuthStateService.VALIDITY).toString())
                .body(Api.OK(new AuthorizeResponse(result.authorizationUrl())));
    }
    @GetMapping("/connections/{connectionId}/imweb/units")
    public ResponseEntity<Api<List<Unit>>> units(@PathVariable Long workspaceId, @PathVariable Long connectionId, @UserSession UserResponse user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Api.OK(business.units(workspaceId, connectionId, user.getId())));
    }
    @PostMapping("/connections/{connectionId}/imweb/units")
    public ResponseEntity<Api<List<Store>>> select(@PathVariable Long workspaceId, @PathVariable Long connectionId, @UserSession UserResponse user,
                                                  @RequestBody @Valid UnitSelection request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Api.OK(business.selectUnits(workspaceId, connectionId, user.getId(), request.unitCodes())));
    }
    @GetMapping("/imweb/stores")
    public ResponseEntity<Api<List<Store>>> stores(@PathVariable Long workspaceId, @UserSession UserResponse user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Api.OK(business.stores(workspaceId, user.getId())));
    }
    public record AuthorizeRequest(@NotBlank @Pattern(regexp = "S[A-Za-z0-9]{5,99}") String siteCode) {}
    public record AuthorizeResponse(String authorizationUrl) {}
    public record UnitSelection(@NotEmpty @Size(max = 100) List<@NotBlank @Pattern(regexp = "u[A-Za-z0-9]{5,99}") String> unitCodes) {}
}
