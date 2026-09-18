package com.orinan.api.domain.user.controller.model;

import com.orinan.api.domain.userprofile.controller.model.UserProfileResponse;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.user.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserResponse {

    private Long id;

    private String email;

    private UserRole role;

    private UserStatus status;

    private LocalDateTime registeredAt;

    private LocalDateTime updatedAt;

    private LocalDateTime unRegisteredAt;

    private LocalDateTime lastLoginAt;

    private UserProfileResponse userProfileResponse;
}
