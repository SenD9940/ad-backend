package com.orinan.adminapi.domain.audit.business;

import com.orinan.adminapi.annotation.Business;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.adminapi.domain.audit.controller.model.AdminAuditResponse;
import com.orinan.adminapi.domain.audit.converter.AdminAuditConverter;
import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@Business
@RequiredArgsConstructor
public class AdminAuditBusiness {
    private final AdminAuditService auditService;
    private final AdminAuditConverter auditConverter;

    @Transactional(readOnly = true)
    public PageResponse<AdminAuditResponse> list(Long actorId, String action, String targetType, Long targetId, int page, int size) {
        var result = auditService.list(actorId, action, targetType, targetId, page, size);
        var items = result.getContent().stream().map(auditConverter::toResponse).toList();
        return PageResponse.of(items, page, size, result.getTotalElements());
    }
}
