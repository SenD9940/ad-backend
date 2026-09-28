package com.orinan.api.domain.platformconnection.naver.authorization;

import com.orinan.api.domain.platformconnection.naver.solution.NaverAuthorizationProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import org.springframework.util.MultiValueMap;
import java.util.Map;

@RestController @RequiredArgsConstructor @RequestMapping("/open-api/integrations/naver")
public class NaverAuthorizationCallbackController {
    private final NaverAuthorizationService service;
    private final NaverAuthorizationProvider provider;
    @GetMapping("/launch")
    public ResponseEntity<Void> launch(@RequestParam String ticket,HttpServletRequest request,HttpServletResponse response) {
        NaverAuthorizationApiController.secureHeaders(response);
        String id=NaverAuthorizationService.stateId(ticket);
        return redirect(service.launch(ticket,NaverAuthorizationApiController.cookie(request,NaverAuthorizationApiController.attemptCookie(id))));
    }
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam MultiValueMap<String,String> parameters,HttpServletRequest request,HttpServletResponse response) {
        NaverAuthorizationApiController.secureHeaders(response);provider.requireReady();
        try {
            var callback=provider.readCallback(single(parameters));String id=NaverAuthorizationService.stateId(callback.state());
            String result=service.callback(callback.state(),callback.jwe(),NaverAuthorizationApiController.cookie(request,NaverAuthorizationApiController.attemptCookie(id)));
            return redirect(service.resultUrl(result));
        } catch(RuntimeException ignored) {return redirect(service.failureUrl());}
    }
    @GetMapping("/marketplace")
    public ResponseEntity<Void> marketplace(@RequestParam MultiValueMap<String,String> parameters,HttpServletResponse response) {
        NaverAuthorizationApiController.secureHeaders(response);provider.requireReady();
        try {
            var receipt=service.marketplace(provider.marketplaceToken(single(parameters)));
            response.addHeader(HttpHeaders.SET_COOKIE,NaverAuthorizationApiController.bindingCookie(NaverAuthorizationApiController.receiptCookie(receipt.receiptId()),receipt.browserSecret()).toString());
            return redirect(service.marketplaceResultUrl(receipt.receiptId()));
        } catch(RuntimeException ignored) {return redirect(service.failureUrl());}
    }
    private Map<String,String> single(MultiValueMap<String,String> parameters) {
        if(parameters.values().stream().anyMatch(values->values.size()!=1)) throw new IllegalArgumentException("중복된 네이버 인증 매개변수입니다.");
        return parameters.toSingleValueMap();
    }
    private ResponseEntity<Void> redirect(String location) {return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(location)).build();}
}
