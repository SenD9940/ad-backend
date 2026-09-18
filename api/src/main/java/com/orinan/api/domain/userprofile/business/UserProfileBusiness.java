package com.orinan.api.domain.userprofile.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.domain.userprofile.controller.model.UserProfileMailNotificationUpdateRequest;
import com.orinan.api.domain.userprofile.controller.model.UserProfileRegisterRequest;
import com.orinan.api.domain.userprofile.controller.model.UserProfileResponse;
import com.orinan.api.domain.userprofile.converter.UserProfileConverter;
import com.orinan.api.domain.userprofile.service.UserProfileService;
import com.orinan.db.crypto.SearchHashEncoder;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.userprofile.UserProfileEntity;
import com.orinan.db.userprofile.enums.UserProfileStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@Business
@RequiredArgsConstructor
public class UserProfileBusiness {

    private final UserProfileConverter userProfileConverter;
    private final UserProfileService userProfileService;
    private final UserService userService;
    private final SearchHashEncoder searchHashEncoder;

    public UserProfileResponse register(UserProfileRegisterRequest request, UserEntity userEntity) {
        var newEntity = userProfileConverter.toEntity(request);
        newEntity.setPhoneHash(searchHashEncoder.encode(request.getPhone()));
        newEntity.setUser(userEntity);
        newEntity.setStatus(UserProfileStatus.REGISTERED);
        if (newEntity.getMailNotificationEnabled() == null) {
            newEntity.setMailNotificationEnabled(false);
        }
        var savedEntity = userProfileService.save(newEntity);
        return userProfileConverter.toResponse(savedEntity);
    }

    @Transactional
    public UserProfileResponse getByUser(UserResponse user) {
        return userProfileConverter.toResponse(getOrCreateByUser(user));
    }

    @Transactional
    public UserProfileResponse updateMailNotification(
            UserResponse user,
            UserProfileMailNotificationUpdateRequest request
    ) {
        var entity = getOrCreateByUser(user);
        entity.setMailNotificationEnabled(Boolean.TRUE.equals(request.getMailNotificationEnabled()));
        var saved = userProfileService.save(entity);
        return userProfileConverter.toResponse(saved);
    }

    /**
     * 레거시 사용자 등 프로필이 없는 경우 최소 프로필을 생성합니다.
     */
    private UserProfileEntity getOrCreateByUser(UserResponse user) {
        return userProfileService.findByUserId(user.getId())
                .orElseGet(() -> createMinimalProfile(user));
    }

    private UserProfileEntity createMinimalProfile(UserResponse user) {
        var userEntity = userService.findByIdAndStatusWithThrow(
                user.getId(),
                UserStatus.REGISTERED
        );
        String placeholder = "legacy-" + user.getId();
        var entity = UserProfileEntity.builder()
                .name(resolveDisplayName(user))
                .phone(placeholder)
                .phoneHash(searchHashEncoder.encode(placeholder))
                .status(UserProfileStatus.REGISTERED)
                .mailNotificationEnabled(false)
                .user(userEntity)
                .build();
        return userProfileService.save(entity);
    }

    private String resolveDisplayName(UserResponse user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return "사용자";
        }
        int at = user.getEmail().indexOf('@');
        if (at > 0) {
            return user.getEmail().substring(0, at);
        }
        return user.getEmail();
    }
}
