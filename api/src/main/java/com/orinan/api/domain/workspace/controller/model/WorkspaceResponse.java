package com.orinan.api.domain.workspace.controller.model;

import com.orinan.db.user.UserEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkspaceResponse {

    private Long id;

    private String name;

    private Long userId;

    private LocalDateTime registeredAt;

    private LocalDateTime updatedAt;
}
