package com.orinan.adminapi.domain.audit.converter;

import com.orinan.adminapi.annotation.Converter;
import com.orinan.adminapi.domain.audit.controller.model.AdminAuditResponse;
import com.orinan.db.adminaudit.AdminAuditEntity;

@Converter
public class AdminAuditConverter {
    public AdminAuditResponse toResponse(AdminAuditEntity entity) {
        return new AdminAuditResponse(entity.getId(), entity.getActorUserId(), entity.getAction(), entity.getTargetType(),
                entity.getTargetId(), entity.getReason(), entity.getBeforeValue(), entity.getAfterValue(), entity.getCreatedAt());
    }
}
