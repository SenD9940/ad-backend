package com.orinan.db.user;

import com.orinan.db.user.enums.UserStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByIdAndStatus(Long id, UserStatus status);

    @Query("select u from UserEntity u where lower(u.email) = lower(:email) and u.status = :status")
    Optional<UserEntity> findByEmailAndStatus(@Param("email") String email, @Param("status") UserStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserEntity> findByEmailIgnoreCaseAndStatus(String email, UserStatus status);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndStatus(String email, UserStatus status);

    List<UserEntity> findAllByStatus(UserStatus status);
}
