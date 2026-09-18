package com.orinan.db.user;

import com.orinan.db.BaseEntity;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.userprofile.UserProfileEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "users")
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class UserEntity extends BaseEntity {

    @Column(length = 512, nullable = false, unique = true)
    private String email;

    @Column(length = 512, nullable = false)
    private String password;

    @Column(length = 30, nullable = false)
    @Enumerated(EnumType.STRING)
    private UserStatus status;

    @Column(length = 10, nullable = false)
    @Enumerated(EnumType.STRING)
    private UserRole role;

    private LocalDateTime lastLoginAt;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private UserProfileEntity userProfile;

    @CreationTimestamp
    private LocalDateTime registeredAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    private LocalDateTime unRegisteredAt;
}
