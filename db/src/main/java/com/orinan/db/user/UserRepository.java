package com.orinan.db.user;

import com.orinan.db.user.enums.UserStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByIdAndStatus(Long id, UserStatus status);

    Optional<UserEntity> findByEmailAndStatus(String email, UserStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserEntity> findByEmailIgnoreCaseAndStatus(String email, UserStatus status);

    boolean existsByEmailIgnoreCase(String email);

    List<UserEntity> findAllByStatus(UserStatus status);
}
