package com.orinan.api.domain.user.service;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserEntity authenticate(String email, String password) {
        var user = userRepository.findByEmailIgnoreCaseAndStatus(email.strip(), UserStatus.REGISTERED)
                .orElseThrow(() -> new ApiException(UserErrorCode.INVALID_CREDENTIALS));
        if (password.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwordEncoder.matches(password, user.getPassword())) {
            throw new ApiException(UserErrorCode.INVALID_CREDENTIALS);
        }
        return user;
    }

    public void validateRegistration(String email, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(UserErrorCode.PASSWORD_TOO_LONG);
        }
        if (userRepository.existsByEmailIgnoreCase(email.strip())) {
            throw new ApiException(UserErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    public void updateLastLogin(UserEntity user) {
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
    }

    public UserEntity findByIdAndStatusWithThrow(Long id, UserStatus status) {
        return userRepository.findByIdAndStatus(id, status).orElseThrow(() -> new ApiException(UserErrorCode.USER_NOT_FOUND));
    }

    public UserEntity findByEmailAndStatusWithThrow(String email, UserStatus status) {
        return userRepository.findByEmailAndStatus(email, status).orElseThrow(() -> new ApiException(UserErrorCode.USER_NOT_FOUND));
    }

    public List<UserEntity> findAllByStatus(UserStatus status) {
        return userRepository.findAllByStatus(status);
    }

    public UserEntity save(UserEntity userEntity) {
        return userRepository.save(userEntity);
    }
}
