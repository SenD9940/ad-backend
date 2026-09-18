package com.orinan.api.domain.workspacemember.controller.model;

import com.orinan.db.workspacemember.enums.WorkspaceMemberRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkspaceMemberResponse {

    private Long workspaceId;

    private Long userId;

    private WorkspaceMemberRole role;

    private LocalDateTime registeredAt;

    private LocalDateTime updatedAt;
}
