package com.orinan.db.userprofile;

import com.orinan.db.BaseEntity;
import com.orinan.db.crypto.DataCryptConverter;
import com.orinan.db.user.UserEntity;
import com.orinan.db.userprofile.enums.UserProfileStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@Entity
@Table(name = "user_profiles")
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class UserProfileEntity extends BaseEntity {

    @Column(length = 50, nullable = false)
    private String name;

    @JoinColumn(name = "user_id", nullable = false)
    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private UserEntity user;

    @Convert(converter = DataCryptConverter.class)
    @Column(length = 512, nullable = false)
    private String phone;

    @Column(length = 64, nullable = false,  unique = true)
    private String phoneHash;

    @Column(length = 30, nullable = false)
    @Enumerated(EnumType.STRING)
    private UserProfileStatus status;

    @Column(length = 20)
    private String zipCode;

    @Convert(converter = DataCryptConverter.class)
    @Column(length = 1024)
    private String address;

    @Convert(converter = DataCryptConverter.class)
    @Column(length = 512)
    private String addressDetail;

    /** 글 발행 메일 알림 수신 여부 */
    @Column(nullable = false)
    private Boolean mailNotificationEnabled;

}
