package com.orinan.api.domain.userprofile.controller.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfileMailNotificationUpdateRequest {
    private Boolean mailNotificationEnabled;
}
