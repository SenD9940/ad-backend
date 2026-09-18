package com.orinan.api.domain.user.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserRegisterRequest {

    @NotBlank
    @Email(message = "올바른 이메일을 입력하세요.")
    @Size(max = 254)
    private String email;

    @NotBlank
    @Size(min = 8, max = 72, message = "비밀번호는 8자 이상, 72자 이하로 입력하세요.")
    private String password;

    @NotBlank
    @Size(max = 50)
    private String name;

    @NotBlank
    @Pattern(regexp = "01[016789]-[0-9]{3,4}-[0-9]{4}", message = "휴대폰 번호 형식(010-0000-0000)으로 입력하세요.")
    private String phone;

    private String zipCode;

    private String address;

    private String addressDetail;
}
