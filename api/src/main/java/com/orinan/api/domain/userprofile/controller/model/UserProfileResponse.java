package com.orinan.api.domain.userprofile.controller.model;

import com.orinan.db.userprofile.enums.UserProfileStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfileResponse {

    private Long id;

    private String name;

    private String phone;

    private String zipCode;

    private String address;

    private String addressDetail;

    private UserProfileStatus status;

    private Boolean mailNotificationEnabled;

}
