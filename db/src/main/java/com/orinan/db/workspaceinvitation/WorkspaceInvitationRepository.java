package com.orinan.db.workspaceinvitation;

import com.orinan.db.workspacemember.WorkspaceMemberId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface WorkspaceInvitationRepository extends JpaRepository<WorkspaceInvitationEntity, WorkspaceMemberId> {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<WorkspaceInvitationEntity> findById(WorkspaceMemberId id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<WorkspaceInvitationEntity> findByTokenHash(String tokenHash);
}
