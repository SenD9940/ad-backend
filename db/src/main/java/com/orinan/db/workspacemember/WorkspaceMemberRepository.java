package com.orinan.db.workspacemember;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMemberEntity, WorkspaceMemberId> {

    List<WorkspaceMemberEntity> findAllByIdWorkspaceIdOrderByRegisteredAtAscIdUserIdAsc(Long workspaceId);
}
