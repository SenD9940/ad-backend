package com.orinan.api.domain.workspacemember.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceMemberAcceptRequest {

    @NotBlank
    // 이미 발송된 기존 토큰도 만료 전까지 수락할 수 있습니다.
    @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}|[A-Za-z0-9_-]{43}")
    @ToString.Exclude
    private String token;
}
