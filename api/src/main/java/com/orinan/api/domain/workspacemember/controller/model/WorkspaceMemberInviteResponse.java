package com.orinan.api.domain.workspacemember.controller.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class WorkspaceMemberInviteResponse {

    private String email;

    private boolean success;

    private String message;
}
