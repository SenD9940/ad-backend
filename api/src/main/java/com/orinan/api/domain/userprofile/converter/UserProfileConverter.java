package com.orinan.api.domain.userprofile.converter;

import com.orinan.api.annotation.Converter;
import com.orinan.api.domain.userprofile.controller.model.UserProfileRegisterRequest;
import com.orinan.api.domain.userprofile.controller.model.UserProfileResponse;
import com.orinan.db.userprofile.UserProfileEntity;

@Converter
public class UserProfileConverter {

    public UserProfileEntity toEntity(UserProfileRegisterRequest request){
        return UserProfileEntity.builder()
                .name(request.getName())
                .phone(request.getPhone())
                .zipCode(request.getZipCode())
                .address(request.getAddress())
                .addressDetail(request.getAddressDetail())
                .mailNotificationEnabled(false)
                .build();
    }

    public UserProfileResponse toResponse(UserProfileEntity entity){
        return UserProfileResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .phone(entity.getPhone())
                .zipCode(entity.getZipCode())
                .address(entity.getAddress())
                .addressDetail(entity.getAddressDetail())
                .status(entity.getStatus())
                .mailNotificationEnabled(Boolean.TRUE.equals(entity.getMailNotificationEnabled()))
                .build();
    }
}
