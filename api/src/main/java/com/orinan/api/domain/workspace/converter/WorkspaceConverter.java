package com.orinan.api.domain.workspace.converter;

import com.orinan.api.annotation.Converter;
import com.orinan.api.domain.workspace.controller.model.WorkspaceRegisterRequest;
import com.orinan.api.domain.workspace.controller.model.WorkspaceResponse;
import com.orinan.db.workspace.WorkspaceEntity;

@Converter
public class WorkspaceConverter {

    public WorkspaceResponse toResponse(WorkspaceEntity entity){
        return WorkspaceResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .userId(entity.getUser().getId())
                .registeredAt(entity.getRegisteredAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public WorkspaceEntity toEntity(WorkspaceRegisterRequest request){
        return WorkspaceEntity.builder()
                .name(request.getName())
                .build();
    }
}
