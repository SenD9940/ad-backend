package com.orinan.api.domain.workspacemember.controller.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceMemberKickRequest {

    @NotNull
    @Positive
    private Long workspaceId;

    @NotNull
    @Positive
    private Long userId;
}
