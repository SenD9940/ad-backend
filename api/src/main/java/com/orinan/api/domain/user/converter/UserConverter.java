package com.orinan.api.domain.user.converter;

import com.orinan.api.annotation.Converter;
import com.orinan.api.domain.user.controller.model.UserRegisterRequest;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.userprofile.controller.model.UserProfileResponse;
import com.orinan.api.domain.userprofile.controller.model.UserProfileRegisterRequest;
import com.orinan.db.user.UserEntity;
import java.util.Locale;

@Converter
public class UserConverter {

    public UserResponse toResponse(UserEntity entity){
        return UserResponse.builder()
                .id(entity.getId())
                .email(entity.getEmail())
                .role(entity.getRole())
                .status(entity.getStatus())
                .registeredAt(entity.getRegisteredAt())
                .updatedAt(entity.getUpdatedAt())
                .unRegisteredAt(entity.getUnRegisteredAt())
                .lastLoginAt(entity.getLastLoginAt())
                .build();
    }

    public UserResponse toResponse(UserEntity entity, UserProfileResponse userProfileResponse) {
        return UserResponse.builder()
                .id(entity.getId())
                .email(entity.getEmail())
                .role(entity.getRole())
                .status(entity.getStatus())
                .registeredAt(entity.getRegisteredAt())
                .updatedAt(entity.getUpdatedAt())
                .unRegisteredAt(entity.getUnRegisteredAt())
                .lastLoginAt(entity.getLastLoginAt())
                .userProfileResponse(userProfileResponse)
                .build();
    }

    public UserEntity toEntity(UserRegisterRequest request) {
        return UserEntity.builder()
                .email(request.getEmail().strip().toLowerCase(Locale.ROOT))
                .build();
    }

    public UserProfileRegisterRequest toProfileRequest(UserRegisterRequest request) {
        return UserProfileRegisterRequest.builder()
                .name(request.getName().strip())
                .phone(request.getPhone())
                .zipCode(request.getZipCode())
                .address(request.getAddress())
                .addressDetail(request.getAddressDetail())
                .build();
    }
}
