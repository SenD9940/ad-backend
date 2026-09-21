package com.orinan.db.workspaceinvitation;

import com.orinan.db.workspacemember.WorkspaceMemberId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "workspace_invitations")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkspaceInvitationEntity {

    @EmbeddedId
    private WorkspaceMemberId id;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;
}
