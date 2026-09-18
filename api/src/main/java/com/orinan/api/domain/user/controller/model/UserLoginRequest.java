package com.orinan.api.domain.user.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserLoginRequest {

    @NotBlank
    @Email(message = "올바른 이메일을 입력하세요.")
    @Size(max = 254)
    private String email;

    @NotBlank
    private String password;
}
