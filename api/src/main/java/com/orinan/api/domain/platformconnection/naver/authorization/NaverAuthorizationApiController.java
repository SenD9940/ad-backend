package com.orinan.api.domain.platformconnection.naver.authorization;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.user.controller.model.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Arrays;

@RestController @RequiredArgsConstructor
public class NaverAuthorizationApiController {
    private final NaverAuthorizationService service;
    private static final String BASE="/api/workspaces/{workspaceId}/connections/naver";

    @GetMapping(BASE+"/capabilities")
    public Api<NaverAuthorizationResponse.Capabilities> capabilities(@UserSession UserResponse user,@PathVariable Long workspaceId) {
        return Api.OK(service.capabilities(workspaceId,user.getId()));
    }
    @PostMapping(BASE+"/authorizations")
    public Api<NaverAuthorizationResponse> start(@UserSession UserResponse user,@PathVariable Long workspaceId,
            @RequestBody(required=false) @Valid StartRequest body,HttpServletRequest request,HttpServletResponse response) {
        secureHeaders(response);origin(request);
        var result=service.start(workspaceId,user.getId(),body==null?null:body.reconnectConnectionId(),body==null?null:body.marketplaceReceipt(),
                cookie(request,receiptCookie(body==null?null:body.marketplaceReceipt())));
        response.addHeader(HttpHeaders.SET_COOKIE,bindingCookie(attemptCookie(result.response().attemptId()),result.browserSecret()).toString());
        return Api.OK(result.response());
    }
    @GetMapping(BASE+"/authorizations/{attemptId}")
    public Api<NaverAuthorizationResponse> get(@UserSession UserResponse user,@PathVariable Long workspaceId,@PathVariable String attemptId,HttpServletResponse response) {
        secureHeaders(response);return Api.OK(service.get(workspaceId,user.getId(),attemptId));
    }
    @GetMapping("/api/integrations/naver/authorizations/{attemptId}")
    public Api<NaverAuthorizationResponse> resolve(@UserSession UserResponse user,@PathVariable String attemptId,HttpServletResponse response) {
        secureHeaders(response);return Api.OK(service.get(null,user.getId(),attemptId));
    }
    @PostMapping(BASE+"/authorizations/{attemptId}/launch-ticket")
    public Api<NaverAuthorizationResponse> reissue(@UserSession UserResponse user,@PathVariable Long workspaceId,@PathVariable String attemptId,HttpServletRequest request,HttpServletResponse response) {
        secureHeaders(response);origin(request);return Api.OK(service.reissue(workspaceId,user.getId(),attemptId,cookie(request,attemptCookie(attemptId))));
    }
    @PostMapping(BASE+"/authorizations/{attemptId}/cancel")
    public Api<NaverAuthorizationResponse> cancel(@UserSession UserResponse user,@PathVariable Long workspaceId,@PathVariable String attemptId,HttpServletRequest request,HttpServletResponse response) {
        secureHeaders(response);origin(request);return Api.OK(service.cancel(workspaceId,user.getId(),attemptId,cookie(request,attemptCookie(attemptId))));
    }
    @PostMapping(BASE+"/authorizations/{attemptId}/complete")
    public ResponseEntity<Api<NaverAuthorizationResponse>> complete(@UserSession UserResponse user,@PathVariable Long workspaceId,@PathVariable String attemptId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody @Valid CompleteRequest body,HttpServletRequest request,HttpServletResponse response) {
        secureHeaders(response);origin(request);var result=service.complete(workspaceId,user.getId(),attemptId,cookie(request,attemptCookie(attemptId)),body.reviewRevision(),key);
        boolean pending=java.util.Set.of("APPROVING","RECONCILING","VERIFYING_CONNECTION").contains(result.status());
        return ResponseEntity.status(pending?HttpStatus.ACCEPTED:HttpStatus.OK).body(Api.OK(result));
    }
    private void origin(HttpServletRequest request) {
        if(service.publicOrigin()==null||!service.publicOrigin().equals(request.getHeader(HttpHeaders.ORIGIN))) throw new ApiException(NaverAuthorizationCode.FORBIDDEN);
    }
    static String cookie(HttpServletRequest request,String name) {
        if(name==null||request.getCookies()==null) return "";
        return Arrays.stream(request.getCookies()).filter(c->name.equals(c.getName())).map(jakarta.servlet.http.Cookie::getValue).findFirst().orElse("");
    }
    static String attemptCookie(String id) {return "naver_attempt_"+id;}
    static String receiptCookie(String id) {return id==null?null:"naver_receipt_"+id;}
    static ResponseCookie bindingCookie(String name,String value) {return ResponseCookie.from(name,value).httpOnly(true).secure(true).path("/").sameSite("Lax").maxAge(86400).build();}
    static void secureHeaders(HttpServletResponse response) {response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");response.setHeader("Referrer-Policy","no-referrer");response.setHeader("X-Content-Type-Options","nosniff");}
    public record StartRequest(@Positive Long reconnectConnectionId,@Pattern(regexp="[A-Za-z0-9_-]{32,64}") String marketplaceReceipt) {}
    public record CompleteRequest(@Positive long reviewRevision) {}
}
