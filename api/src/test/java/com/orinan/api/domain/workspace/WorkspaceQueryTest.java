package com.orinan.api.domain.workspace;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.domain.workspace.business.WorkspaceBusiness;
import com.orinan.api.domain.workspace.controller.model.WorkspaceResponse;
import com.orinan.api.domain.workspace.converter.WorkspaceConverter;
import com.orinan.api.domain.workspace.service.WorkspaceService;
import com.orinan.api.domain.workspacemember.service.WorkspaceMemberService;
import com.orinan.db.user.UserEntity;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspace.WorkspaceRepository;
import com.orinan.db.workspacemember.WorkspaceMemberId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WorkspaceQueryTest {

    private final WorkspaceRepository repository = mock(WorkspaceRepository.class);
    private final WorkspaceMemberService memberService = mock(WorkspaceMemberService.class);
    private final WorkspaceBusiness business = new WorkspaceBusiness(
            new WorkspaceService(repository), new WorkspaceConverter(),
            mock(UserService.class), memberService);

    @Test
    void returnsAllWorkspacesFilteredByLoggedInOwner() {
        when(repository.findAllByUserIdOrderByIdDesc(1L))
                .thenReturn(List.of(workspace(20L, 1L), workspace(10L, 1L)));

        var responses = business.getMyWorkspaces(1L);

        assertThat(responses).extracting(WorkspaceResponse::getId).containsExactly(20L, 10L);
        assertThat(responses).extracting(WorkspaceResponse::getUserId).containsOnly(1L);
        verify(repository).findAllByUserIdOrderByIdDesc(1L);
    }

    @Test
    void returnsEmptyListWhenOwnerHasNoWorkspaces() {
        when(repository.findAllByUserIdOrderByIdDesc(1L)).thenReturn(List.of());
        assertThat(business.getMyWorkspaces(1L)).isEmpty();
    }

    @Test
    void ownerCanReadOneWorkspace() {
        when(repository.findById(10L)).thenReturn(Optional.of(workspace(10L, 1L)));

        var response = business.getMyWorkspace(10L, 1L);

        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getUserId()).isEqualTo(1L);
    }

    @Test
    void anotherUserCannotReadWorkspaceByGuessingItsId() {
        when(repository.findById(10L)).thenReturn(Optional.of(workspace(10L, 1L)));

        assertThatThrownBy(() -> business.getMyWorkspace(10L, 2L))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));
    }

    @Test
    void currentMemberCanReadJoinedWorkspace() {
        when(repository.findById(10L)).thenReturn(Optional.of(workspace(10L, 1L)));
        when(memberService.exists(new WorkspaceMemberId(10L, 2L))).thenReturn(true);

        assertThat(business.getMyWorkspace(10L, 2L).getId()).isEqualTo(10L);
    }

    @Test
    void kickedMemberLosesReadAccess() {
        when(repository.findById(10L)).thenReturn(Optional.of(workspace(10L, 1L)));
        when(memberService.exists(new WorkspaceMemberId(10L, 2L))).thenReturn(true, false);

        assertThat(business.getMyWorkspace(10L, 2L).getId()).isEqualTo(10L);
        assertThatThrownBy(() -> business.getMyWorkspace(10L, 2L))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));
    }

    @Test
    void missingWorkspaceReturnsError() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> business.getMyWorkspace(99L, 1L))
                .isInstanceOf(ApiException.class).hasMessage("존재하지 않는 워크스페이스입니다");
    }

    private WorkspaceEntity workspace(Long id, Long ownerId) {
        return WorkspaceEntity.builder().id(id).name("워크스페이스 " + id)
                .user(UserEntity.builder().id(ownerId).build()).build();
    }
}
