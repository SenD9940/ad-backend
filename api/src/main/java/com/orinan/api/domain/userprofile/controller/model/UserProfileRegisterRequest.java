package com.orinan.api.domain.userprofile.controller.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfileRegisterRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String phone;

    private String zipCode;

    private String address;

    private String addressDetail;
}
