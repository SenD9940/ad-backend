package com.orinan.api.domain.platformconnection.naver.events;

import com.orinan.api.domain.platformconnection.naver.solution.NaverSolutionProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

@RestController @RequiredArgsConstructor
public class NaverSolutionEventController {
    private final NaverSolutionProperties properties;
    private final NaverSolutionEventInbox inbox;

    @PostMapping("/open-api/integrations/naver/events")
    public ResponseEntity<Void> receive(HttpServletRequest request,@RequestBody JsonNode payload) {
        if(!properties.ready()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"네이버 솔루션 연동 준비 중입니다.");
        String header=request.getHeader(properties.getWebhookHeaderName());
        var values=request.getHeaders(properties.getWebhookHeaderName());
        int count=0;
        while(values!=null && values.hasMoreElements()) {values.nextElement(); if(++count>1) break;}
        if(count!=1 || header==null || header.length()>4096 || !MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),
                properties.getWebhookKey().getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"이벤트 인증에 실패했습니다.");
        }
        if(payload==null || !payload.isArray() || payload.isEmpty() || payload.size()>100) throw invalid();
        List<NaverSolutionEventData> batch=new ArrayList<>();
        for(var node:payload) {
            if(!node.isObject()) throw invalid();
            String solution=text(node,"solutionId",128,true);
            if(!properties.getSolutionId().equals(solution)) throw invalid();
            batch.add(new NaverSolutionEventData(solution,text(node,"eventId",255,true),text(node,"changeType",64,true),
                    text(node,"accountUid",255,true),text(node,"accountMappingId",128,false)));
        }
        inbox.accept(List.copyOf(batch));
        return ResponseEntity.ok().header("Cache-Control","no-store").build();
    }

    private String text(JsonNode node,String name,int max,boolean required) {
        var value=node.path(name);
        if(value.isMissingNode() || value.isNull()) { if(required) throw invalid(); return null; }
        if(!value.isString() || value.asString().isBlank() || value.asString().length()>max) throw invalid();
        return value.asString();
    }
    private ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"이벤트 형식이 올바르지 않습니다."); }
}
