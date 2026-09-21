package com.orinan.api.domain.workspacemember.controller.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceMemberInviteRequest {

    @NotNull
    @Positive
    private Long workspaceId;

    @NotEmpty
    @Size(max = 50)
    private List<@NotBlank @Email @Size(max = 254) String> emails;
}
