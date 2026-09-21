package com.orinan.api.domain.workspacemember.converter;

import com.orinan.api.annotation.Converter;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberResponse;
import com.orinan.db.workspacemember.WorkspaceMemberEntity;
import com.orinan.db.workspacemember.WorkspaceMemberId;
import com.orinan.db.workspacemember.enums.WorkspaceMemberRole;

@Converter
public class WorkspaceMemberConverter {

    public WorkspaceMemberEntity toEntity(WorkspaceMemberId id){
        return WorkspaceMemberEntity.builder()
                .id(id)
                .role(WorkspaceMemberRole.MEMBER)
                .build();
    }

    public WorkspaceMemberResponse toResponse(WorkspaceMemberEntity entity){
        return WorkspaceMemberResponse.builder()
                .workspaceId(entity.getId().getWorkspaceId())
                .userId(entity.getId().getUserId())
                .role(entity.getRole())
                .registeredAt(entity.getRegisteredAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
