package com.orinan.api.domain.userprofile.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.db.userprofile.UserProfileEntity;
import com.orinan.db.userprofile.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserProfileRepository userProfileRepository;

    public UserProfileEntity save(UserProfileEntity entity) {
        return userProfileRepository.save(entity);
    }

    public Optional<UserProfileEntity> findByUserId(Long userId) {
        return userProfileRepository.findByUserId(userId);
    }

    public UserProfileEntity findByUserIdWithThrow(Long userId) {
        return findByUserId(userId)
                .orElseThrow(() -> new ApiException(ApiCode.NULL_POINT));
    }

    public List<UserProfileEntity> findAllMailNotificationEnabled() {
        return userProfileRepository.findAllMailNotificationEnabled();
    }
}
