package com.orinan.db.token;

import com.orinan.db.BaseEntity;
import com.orinan.db.token.enums.TokenStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "tokens")
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class TokenEntity extends BaseEntity {

    @Column(length = 64, nullable = false)
    private String refreshTokenHash;

    @Column(length = 10, nullable = false)
    @Enumerated(EnumType.STRING)
    private TokenStatus status;

    @Column(nullable = false)
    private Long userId;

    private LocalDateTime issuedAt;

    private LocalDateTime revokedAt;

    private LocalDateTime expiredAt;
}
