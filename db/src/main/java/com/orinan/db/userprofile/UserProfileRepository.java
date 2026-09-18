package com.orinan.db.userprofile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfileEntity, Long> {

    Optional<UserProfileEntity> findByUserId(Long userId);

    @Query("""
            select p from UserProfileEntity p
            join fetch p.user u
            where p.mailNotificationEnabled = true
              and p.status = com.orinan.db.userprofile.enums.UserProfileStatus.REGISTERED
              and u.status = com.orinan.db.user.enums.UserStatus.REGISTERED
            """)
    List<UserProfileEntity> findAllMailNotificationEnabled();
}
