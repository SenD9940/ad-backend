package com.orinan.adminapi.domain.audit.controller;

import com.orinan.adminapi.common.api.Api;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.adminapi.domain.audit.business.AdminAuditBusiness;
import com.orinan.adminapi.domain.audit.controller.model.AdminAuditResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin-api/audit-logs")
public class AdminAuditApiController {
    private final AdminAuditBusiness auditBusiness;

    @GetMapping
    public Api<PageResponse<AdminAuditResponse>> list(@RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action, @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Api.OK(auditBusiness.list(actorId, action, targetType, targetId, page, size));
    }
}
