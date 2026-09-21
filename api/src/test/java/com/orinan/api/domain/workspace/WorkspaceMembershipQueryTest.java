package com.orinan.api.domain.workspace;

import com.orinan.db.crypto.AesGcmStringEncryptor;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.UserRepository;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.userprofile.UserProfileEntity;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspace.WorkspaceRepository;
import com.orinan.db.workspacemember.WorkspaceMemberEntity;
import com.orinan.db.workspacemember.WorkspaceMemberId;
import com.orinan.db.workspacemember.WorkspaceMemberRepository;
import com.orinan.db.workspacemember.enums.WorkspaceMemberRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@ActiveProfiles("test")
@ContextConfiguration(classes = WorkspaceMembershipQueryTest.Config.class)
class WorkspaceMembershipQueryTest {

    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository members;

    @Test
    void separatesOwnedAndJoinedWorkspacesAndExcludesNonMembers() {
        var user = user("member@example.com");
        var other = user("owner@example.com");
        var owned = workspace("내 워크스페이스", user);
        var first = workspace("참여 1", other);
        var second = workspace("참여 2", other);
        workspace("아직 참여하지 않음", other);
        join(owned, user);
        join(first, user);
        join(second, user);

        assertThat(workspaces.findAllByUserIdOrderByIdDesc(user.getId()))
                .extracting(WorkspaceEntity::getId).containsExactly(owned.getId());
        assertThat(workspaces.findAllJoinedByUserId(user.getId()))
                .extracting(WorkspaceEntity::getId).containsExactly(second.getId(), first.getId());
        assertThat(workspaces.findAllJoinedByUserId(other.getId())).isEmpty();
    }

    @Test
    void membershipControlsVisibilityBeforeJoiningAndAfterKick() {
        var user = user("member@example.com");
        var other = user("owner@example.com");
        var workspace = workspace("초대된 워크스페이스", other);

        assertThat(workspaces.findAllJoinedByUserId(user.getId())).isEmpty();
        join(workspace, user);
        assertThat(workspaces.findAllJoinedByUserId(user.getId()))
                .extracting(WorkspaceEntity::getId).containsExactly(workspace.getId());
        members.deleteById(new WorkspaceMemberId(workspace.getId(), user.getId()));
        members.flush();
        assertThat(workspaces.findAllJoinedByUserId(user.getId())).isEmpty();
    }

    private UserEntity user(String email) {
        return users.saveAndFlush(UserEntity.builder().email(email).password("test-password")
                .status(UserStatus.REGISTERED).role(UserRole.CUSTOMER).build());
    }

    private WorkspaceEntity workspace(String name, UserEntity owner) {
        return workspaces.saveAndFlush(WorkspaceEntity.builder().name(name).user(owner).build());
    }

    private void join(WorkspaceEntity workspace, UserEntity user) {
        members.saveAndFlush(WorkspaceMemberEntity.builder()
                .id(new WorkspaceMemberId(workspace.getId(), user.getId()))
                .role(WorkspaceMemberRole.MEMBER).build());
    }

    @Configuration
    @EntityScan(basePackageClasses = {WorkspaceEntity.class, WorkspaceMemberEntity.class, UserEntity.class, UserProfileEntity.class})
    @EnableJpaRepositories(basePackageClasses = {WorkspaceRepository.class, WorkspaceMemberRepository.class, UserRepository.class})
    static class Config {
        @Bean
        AesGcmStringEncryptor encryptor() {
            return mock(AesGcmStringEncryptor.class);
        }
    }
}
