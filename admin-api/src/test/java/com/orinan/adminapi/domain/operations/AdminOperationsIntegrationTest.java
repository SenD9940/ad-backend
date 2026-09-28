package com.orinan.adminapi.domain.operations;

import com.orinan.db.adminaudit.AdminAuditEntity;
import com.orinan.db.adminaudit.AdminAuditRepository;
import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.domain.overview.business.AdminOverviewBusiness;
import com.orinan.adminapi.domain.overview.service.AdminOverviewService;
import com.orinan.adminapi.domain.overview.converter.AdminOverviewConverter;
import com.orinan.adminapi.domain.workspace.business.AdminWorkspaceBusiness;
import com.orinan.adminapi.domain.workspace.service.AdminWorkspaceService;
import com.orinan.adminapi.domain.workspace.converter.AdminWorkspaceConverter;
import com.orinan.adminapi.domain.workspace.controller.model.*;
import com.orinan.adminapi.domain.platformconnection.business.AdminPlatformConnectionBusiness;
import com.orinan.adminapi.domain.platformconnection.service.AdminPlatformConnectionService;
import com.orinan.adminapi.domain.platformconnection.converter.AdminPlatformConnectionConverter;
import com.orinan.adminapi.domain.platformconnection.controller.model.*;
import com.orinan.db.adminoverview.AdminOverviewRepository;
import com.orinan.db.workspace.AdminWorkspaceRepository;
import com.orinan.db.platformconnection.AdminPlatformConnectionRepository;
import com.orinan.adminapi.domain.user.service.AdminUserMutationGuard;
import com.orinan.adminapi.domain.user.service.AdminUserService;
import com.orinan.db.user.AdminUserRepository;
import com.orinan.db.crypto.AesGcmStringEncryptor;
import com.orinan.db.crypto.CryptoProperties;
import com.orinan.db.metaconnection.MetaConnectionEntity;
import com.orinan.db.naverconnection.NaverConnectionEntity;
import com.orinan.db.platformasset.PlatformAssetEntity;
import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.UserRepository;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.userprofile.UserProfileEntity;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspaceinvitation.WorkspaceInvitationEntity;
import com.orinan.db.workspacemember.WorkspaceMemberEntity;
import com.orinan.db.workspacemember.WorkspaceMemberId;
import com.orinan.db.workspacemember.enums.WorkspaceMemberRole;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpStatus.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@ActiveProfiles("test")
@ContextConfiguration(classes = AdminOperationsIntegrationTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminOperationsIntegrationTest {
    @Autowired private AdminOverviewBusiness overview;
    @Autowired private AdminWorkspaceBusiness workspaces;
    @Autowired private AdminPlatformConnectionBusiness connections;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private long adminId, ownerId, memberId, suspendedId, deletedId;
    private long workspaceId, otherWorkspaceId, metaId, naverId;
    private final LocalDateTime expires = LocalDateTime.of(2027, 3, 1, 10, 0);

    @BeforeEach
    void seed() {
        tx(() -> {
            for (String entity : List.of("AdminAuditEntity", "WorkspaceInvitationEntity", "WorkspaceMemberEntity",
                    "MetaConnectionEntity", "NaverConnectionEntity", "PlatformAssetEntity", "PlatformConnectionEntity",
                    "WorkspaceEntity", "UserProfileEntity", "UserEntity"))
                em.createQuery("delete from " + entity).executeUpdate();
            var admin = user("admin@example.test", UserStatus.REGISTERED, UserRole.ADMIN);
            var owner = user("owner@example.test", UserStatus.REGISTERED, UserRole.CUSTOMER);
            var member = user("member@example.test", UserStatus.REGISTERED, UserRole.CUSTOMER);
            var suspended = user("suspended@example.test", UserStatus.SUSPENDED, UserRole.CUSTOMER);
            var deleted = user("deleted@example.test", UserStatus.UNREGISTERED, UserRole.CUSTOMER);
            adminId = admin.getId(); ownerId = owner.getId(); memberId = member.getId();
            suspendedId = suspended.getId(); deletedId = deleted.getId();
            var workspace = workspace("워크 %_! 상점", owner);
            var otherWorkspace = workspace("일반 상점", admin);
            workspaceId = workspace.getId(); otherWorkspaceId = otherWorkspace.getId();
            member(workspaceId, memberId); member(workspaceId, suspendedId);
            var meta = connection(workspace, ProviderType.META, "meta-account", false);
            var naver = connection(otherWorkspace, ProviderType.NAVER, "naver-account", true);
            metaId = meta.getId(); naverId = naver.getId();
            asset(workspaceId, metaId, PlatformType.FACEBOOK, AssetType.AD_ACCOUNT, "act_1");
            asset(workspaceId, metaId, PlatformType.FACEBOOK, AssetType.PAGE, "page_1");
            asset(otherWorkspaceId, naverId, PlatformType.NAVER_SMART_STORE, AssetType.STORE, "store_1");
            invitation(workspaceId, adminId, "private-pending-invitation-hash", now().plusDays(1));
            invitation(workspaceId, deletedId, "private-expired-invitation-hash", now().minusDays(1));
            em.flush();
            em.createQuery("update UserEntity u set u.registeredAt=:old where u.id=:id")
                    .setParameter("old", now().minusDays(30)).setParameter("id", deletedId).executeUpdate();
            // These deliberately invalid ciphertext values prove metadata queries never
            // load/decrypt credentials or depend on the corresponding access tokens.
            em.createNativeQuery("insert into meta_connections (connection_id,access_token,expires_at) values (:id,:token,:expires)")
                    .setParameter("id", metaId).setParameter("token", "private-meta-token-invalid-ciphertext")
                    .setParameter("expires", expires).executeUpdate();
            em.createNativeQuery("""
                    insert into naver_connections (connection_id,client_id,client_secret,token_type,access_token,
                        expires_at,credential_source,credential_version)
                    values (:id,'private-client-id','private-client-secret-invalid-ciphertext','SELF',
                        'private-naver-token-invalid-ciphertext',:expires,'MANUAL',0)
                    """).setParameter("id", naverId).setParameter("expires", expires.plusDays(1)).executeUpdate();
        });
    }

    @Test
    void overviewCountsAccountStatesConnectionsAssetsAndOnlyPendingInvitations() {
        var result = overview.overview();
        assertThat(result.users()).isEqualTo(5);
        assertThat(result.registeredUsers()).isEqualTo(3);
        assertThat(result.suspendedUsers()).isEqualTo(1);
        assertThat(result.unregisteredUsers()).isEqualTo(1);
        assertThat(result.activeAdministrators()).isEqualTo(1);
        assertThat(result.newUsersLast7Days()).isEqualTo(4);
        assertThat(result.workspaces()).isEqualTo(2);
        assertThat(result.connections()).isEqualTo(2);
        assertThat(result.connectionsRequiringReauth()).isEqualTo(1);
        assertThat(result.assets()).isEqualTo(3);
        assertThat(result.pendingInvitations()).isEqualTo(1);
        assertThat(result.fetchedAt()).isBetween(now().minusMinutes(1), now().plusMinutes(1));
    }

    @Test
    void workspaceSearchEscapesWildcardsFiltersOwnersAndCountsLegacyOwnerOnce() {
        var first = workspaces.workspaces(null, null, 0, 1);
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.items()).extracting(AdminWorkspaceResponse::id).containsExactly(otherWorkspaceId);
        assertThat(workspaces.workspaces(null, null, 1, 1).items()).extracting(AdminWorkspaceResponse::id).containsExactly(workspaceId);
        assertThat(workspaces.workspaces(" %_! ", ownerId, 0, 20).items()).extracting(AdminWorkspaceResponse::id).containsExactly(workspaceId);
        assertThat(workspaces.workspaces("OWNER@EXAMPLE.TEST", null, 0, 20).items())
                .extracting(AdminWorkspaceResponse::id).containsExactly(workspaceId);
        assertThat(workspaces.workspaces("%_!", adminId, 0, 20).items()).isEmpty();
        assertThat(workspaces.workspaces("' OR 1=1 --", null, 0, 20).items()).isEmpty();
        var detail = workspaces.workspace(workspaceId);
        assertThat(detail.ownerId()).isEqualTo(ownerId);
        assertThat(detail.memberCount()).isEqualTo(3);
        assertThat(detail.connectionCount()).isEqualTo(1);
    }

    @Test
    void membersIncludeLegacyOwnerAndSuspendedMembersWithoutDuplicatingOwnerMembership() {
        var legacy = workspaces.members(workspaceId, 0, 20);
        assertThat(legacy.totalElements()).isEqualTo(3);
        assertThat(legacy.items()).extracting(AdminWorkspaceMemberResponse::userId).containsExactly(ownerId, memberId, suspendedId);
        assertThat(legacy.items().get(0).owner()).isTrue();
        assertThat(legacy.items().get(0).registeredAt()).isNull();
        assertThat(legacy.items().get(2).status()).isEqualTo(UserStatus.SUSPENDED);
        tx(() -> member(workspaceId, ownerId));
        assertThat(workspaces.members(workspaceId, 0, 20).items()).filteredOn(AdminWorkspaceMemberResponse::owner).hasSize(1);
        assertThat(workspaces.members(workspaceId, 0, 20).totalElements()).isEqualTo(3);
        assertThat(workspaces.workspace(workspaceId).memberCount()).isEqualTo(3);
    }

    @Test
    void connectionQueriesReturnSafeMetadataAndExpiryWithoutDecryptingCredentials() {
        var meta = connections.connection(metaId);
        assertThat(meta.assetCount()).isEqualTo(2);
        assertThat(meta.expiresAt()).isEqualTo(expires);
        assertThat(meta.workspaceId()).isEqualTo(workspaceId);
        var naver = connections.connection(naverId);
        assertThat(naver.expiresAt()).isEqualTo(expires.plusDays(1));
        assertThat(naver.assetCount()).isEqualTo(1);
        assertThat(connections.connections(workspaceId, ProviderType.META, false, 0, 20).items())
                .extracting(AdminPlatformConnectionResponse::id).containsExactly(metaId);
        assertThat(connections.connections(null, null, true, 0, 20).items())
                .extracting(AdminPlatformConnectionResponse::id).containsExactly(naverId);
        assertThat(connections.connections(workspaceId, ProviderType.NAVER, null, 0, 20).items()).isEmpty();
        String json = JsonMapper.builder().build().writeValueAsString(List.of(meta, naver));
        assertThat(json).doesNotContain("private-", "accessToken", "clientSecret", "clientId", "tokenHash");
    }

    @Test
    void renameRecordsBeforeAndAfterValuesAndNoopDoesNotCreateAnotherAudit() {
        assertThat(workspaces.rename(adminId, workspaceId, new AdminWorkspaceRenameRequest("  새 상점 이름  ", "운영 요청")).changed()).isTrue();
        assertThat(workspaces.workspace(workspaceId).name()).isEqualTo("새 상점 이름");
        var audit = audit("WORKSPACE_RENAME");
        assertThat(audit.getActorUserId()).isEqualTo(adminId);
        assertThat(audit.getTargetId()).isEqualTo(workspaceId);
        assertThat(audit.getBeforeValue()).isEqualTo("워크 %_! 상점");
        assertThat(audit.getAfterValue()).isEqualTo("새 상점 이름");
        assertThat(workspaces.rename(adminId, workspaceId, new AdminWorkspaceRenameRequest("새 상점 이름", "재확인")).changed()).isFalse();
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test
    void transferRequiresAnActiveExistingMemberAndCurrentOwnerAndRetainsPreviousOwnerAsMember() {
        assertCode(() -> workspaces.transfer(adminId, workspaceId, new AdminWorkspaceTransferRequest(ownerId, suspendedId, "변경")), CONFLICT);
        assertCode(() -> workspaces.transfer(adminId, workspaceId, new AdminWorkspaceTransferRequest(ownerId, adminId, "변경")), CONFLICT);
        assertCode(() -> workspaces.transfer(adminId, workspaceId, new AdminWorkspaceTransferRequest(adminId, memberId, "변경")), CONFLICT);
        assertThat(workspaces.transfer(adminId, workspaceId, new AdminWorkspaceTransferRequest(ownerId, ownerId, "재확인")).changed()).isFalse();
        assertThat(auditCount()).isZero();

        assertThat(workspaces.transfer(adminId, workspaceId, new AdminWorkspaceTransferRequest(ownerId, memberId, "담당자 변경")).changed()).isTrue();

        assertThat(workspaces.workspace(workspaceId).ownerId()).isEqualTo(memberId);
        assertThat(workspaces.members(workspaceId, 0, 20).items()).filteredOn(AdminWorkspaceMemberResponse::owner)
                .extracting(AdminWorkspaceMemberResponse::userId).containsExactly(memberId);
        assertThat(workspaces.members(workspaceId, 0, 20).items()).filteredOn(member -> member.userId() == ownerId)
                .singleElement().satisfies(member -> {
                    assertThat(member.owner()).isFalse();
                    assertThat(member.registeredAt()).isNotNull();
                });
        assertThat(audit("WORKSPACE_TRANSFER_OWNER").getBeforeValue()).isEqualTo(Long.toString(ownerId));
        assertThat(audit("WORKSPACE_TRANSFER_OWNER").getAfterValue()).isEqualTo(Long.toString(memberId));
    }

    @Test
    void removingMembersPreservesOwnerAndAuditsOnlyTheRemovedMemberIdentifier() {
        assertCode(() -> workspaces.removeMember(adminId, workspaceId, ownerId, new AdminWorkspaceReasonRequest("정리")), CONFLICT);
        assertThat(workspaces.removeMember(adminId, workspaceId, memberId, new AdminWorkspaceReasonRequest("멤버 요청")).changed()).isTrue();
        assertThat(workspaces.members(workspaceId, 0, 20).items()).extracting(AdminWorkspaceMemberResponse::userId).containsExactly(ownerId, suspendedId);
        assertThat(audit("WORKSPACE_REMOVE_MEMBER").getBeforeValue()).isEqualTo("member:" + memberId);
        assertCode(() -> workspaces.removeMember(adminId, workspaceId, memberId, new AdminWorkspaceReasonRequest("재정리")), NOT_FOUND);
    }

    @Test
    void invitationListsAndRevocationNeverExposeInvitationTokenHashes() {
        var invitations = workspaces.invitations(workspaceId, 0, 20);
        assertThat(invitations.totalElements()).isEqualTo(2);
        assertThat(invitations.items()).filteredOn(AdminWorkspaceInvitationResponse::expired).extracting(AdminWorkspaceInvitationResponse::userId).containsExactly(deletedId);
        assertThat(JsonMapper.builder().build().writeValueAsString(invitations))
                .doesNotContain("private-", "tokenHash", "token_hash");
        assertThat(workspaces.revokeInvitation(adminId, workspaceId, adminId, new AdminWorkspaceReasonRequest("초대 취소 요청")).changed()).isTrue();
        assertThat(workspaces.invitations(workspaceId, 0, 20).items()).extracting(AdminWorkspaceInvitationResponse::userId).containsExactly(deletedId);
        assertThat(audit("WORKSPACE_REVOKE_INVITATION").getBeforeValue()).isEqualTo("invited_user:" + adminId);
        assertCode(() -> workspaces.revokeInvitation(adminId, workspaceId, adminId, new AdminWorkspaceReasonRequest("중복 취소")), NOT_FOUND);
    }

    @Test
    void requiringReauthenticationOnlyChangesTheFlagAndRecordsOneAudit() {
        assertThat(connections.requireReauth(adminId, metaId, new AdminConnectionReauthRequest("연결 점검")).changed()).isTrue();
        assertThat(connections.connection(metaId).requiresReauth()).isTrue();
        assertThat(connections.requireReauth(adminId, metaId, new AdminConnectionReauthRequest("재확인")).changed()).isFalse();
        assertThat(auditCount()).isEqualTo(1);
        assertThat(audit("CONNECTION_REQUIRE_REAUTH").getBeforeValue()).isEqualTo("false");
        assertThat(audit("CONNECTION_REQUIRE_REAUTH").getAfterValue()).isEqualTo("true");
        assertThat(jdbc.queryForObject("select access_token from meta_connections where connection_id=?", String.class, metaId))
                .isEqualTo("private-meta-token-invalid-ciphertext");
        assertThat(connections.connection(metaId).assetCount()).isEqualTo(2);
    }

    @Test
    void omittedMemosRecordEachWorkspaceAndConnectionAction() {
        workspaces.rename(adminId, workspaceId, new AdminWorkspaceRenameRequest("이름 변경", null));
        workspaces.transfer(adminId, workspaceId, new AdminWorkspaceTransferRequest(ownerId, memberId, "  "));
        workspaces.removeMember(adminId, workspaceId, suspendedId, new AdminWorkspaceReasonRequest(""));
        workspaces.revokeInvitation(adminId, workspaceId, adminId, new AdminWorkspaceReasonRequest(null));
        connections.requireReauth(adminId, metaId, new AdminConnectionReauthRequest("\t "));

        assertThat(audit("WORKSPACE_RENAME").getReason()).isEqualTo("워크스페이스 이름 변경");
        assertThat(audit("WORKSPACE_TRANSFER_OWNER").getReason()).isEqualTo("워크스페이스 소유권 이전");
        assertThat(audit("WORKSPACE_REMOVE_MEMBER").getReason()).isEqualTo("워크스페이스 멤버 제거");
        assertThat(audit("WORKSPACE_REVOKE_INVITATION").getReason()).isEqualTo("워크스페이스 초대 취소");
        assertThat(audit("CONNECTION_REQUIRE_REAUTH").getReason()).isEqualTo("연결 재인증 요청");
        assertThat(auditCount()).isEqualTo(5);
        assertThat(workspaces.workspace(workspaceId).ownerId()).isEqualTo(memberId);
        assertThat(connections.connection(metaId).requiresReauth()).isTrue();
    }

    @Test
    void invalidQueriesAndRevokedActorAuthorityAreRejectedBeforeMutation() {
        assertCode(() -> workspaces.workspaces(null, null, -1, 20), BAD_REQUEST);
        assertCode(() -> workspaces.workspaces("x".repeat(201), null, 0, 20), BAD_REQUEST);
        assertCode(() -> connections.connections(null, null, null, 0, 101), BAD_REQUEST);
        assertCode(() -> workspaces.workspace(0), BAD_REQUEST);
        assertCode(() -> workspaces.workspace(Long.MAX_VALUE), NOT_FOUND);
        assertCode(() -> connections.connection(Long.MAX_VALUE), NOT_FOUND);
        assertCode(() -> workspaces.rename(memberId, workspaceId, new AdminWorkspaceRenameRequest("변경", "요청")), FORBIDDEN);
        jdbc.update("update users set status='SUSPENDED' where id=?", adminId);
        assertCode(() -> connections.requireReauth(adminId, metaId, new AdminConnectionReauthRequest("요청")), FORBIDDEN);
        assertThat(connections.connection(metaId).requiresReauth()).isFalse();
        assertThat(workspaces.workspace(workspaceId).name()).isEqualTo("워크 %_! 상점");
        assertThat(auditCount()).isZero();
    }

    @Test
    void auditInsertFailureRollsBackConnectionAndOwnershipChanges() {
        jdbc.execute("alter table admin_audit_logs add constraint reject_operations_audit check (action = 'NEVER_ALLOWED')");
        try {
            assertThatThrownBy(() -> connections.requireReauth(adminId, metaId, new AdminConnectionReauthRequest("기록 실패")))
                    .isInstanceOf(RuntimeException.class);
            assertThat(connections.connection(metaId).requiresReauth()).isFalse();
            assertThatThrownBy(() -> workspaces.transfer(adminId, workspaceId, new AdminWorkspaceTransferRequest(ownerId, memberId, "기록 실패")))
                    .isInstanceOf(RuntimeException.class);
            assertThat(workspaces.workspace(workspaceId).ownerId()).isEqualTo(ownerId);
            assertThat(jdbc.queryForObject("select count(*) from workspace_members where workspace_id=? and user_id=?",
                    Long.class, workspaceId, ownerId)).isZero();
            assertThat(auditCount()).isZero();
        } finally {
            jdbc.execute("alter table admin_audit_logs drop constraint reject_operations_audit");
        }
    }

    private UserEntity user(String email, UserStatus status, UserRole role) {
        var user = UserEntity.builder().email(email).password("private-password").status(status).role(role).build();
        em.persist(user); return user;
    }
    private WorkspaceEntity workspace(String name, UserEntity owner) {
        var workspace = WorkspaceEntity.builder().name(name).user(owner).build(); em.persist(workspace); return workspace;
    }
    private void member(long workspace, long user) {
        em.persist(WorkspaceMemberEntity.builder().id(new WorkspaceMemberId(workspace, user)).role(WorkspaceMemberRole.MEMBER).build());
    }
    private PlatformConnectionEntity connection(WorkspaceEntity workspace, ProviderType provider, String account, boolean reauth) {
        var connection = PlatformConnectionEntity.builder().workspace(workspace).providerType(provider)
                .externalAccountId(account).accountName(account).requiresReauth(reauth).build();
        em.persist(connection); return connection;
    }
    private void asset(long workspace, long connection, PlatformType platform, AssetType type, String external) {
        em.persist(PlatformAssetEntity.builder().workspaceId(workspace).connectionId(connection).platformType(platform)
                .assetType(type).externalId(external).name(external).build());
    }
    private void invitation(long workspace, long user, String hash, LocalDateTime expiry) {
        em.persist(WorkspaceInvitationEntity.builder().id(new WorkspaceMemberId(workspace, user)).tokenHash(hash).expiresAt(expiry).build());
    }
    private AdminAuditEntity audit(String action) {
        return em.createQuery("select a from AdminAuditEntity a where a.action=:action", AdminAuditEntity.class)
                .setParameter("action", action).getSingleResult();
    }
    private long auditCount() { return em.createQuery("select count(a) from AdminAuditEntity a", Long.class).getSingleResult(); }
    private void tx(Runnable action) { new TransactionTemplate(transactions).executeWithoutResult(status -> action.run()); }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Seoul")); }
    private void assertCode(ThrowingCallable action, HttpStatus status) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AdminException.class, error -> assertThat(error.status()).isEqualTo(status));
    }

    @Configuration
    @EntityScan(basePackageClasses = {UserEntity.class, UserProfileEntity.class, WorkspaceEntity.class,
            WorkspaceMemberEntity.class, WorkspaceInvitationEntity.class, PlatformConnectionEntity.class,
            MetaConnectionEntity.class, NaverConnectionEntity.class, PlatformAssetEntity.class, AdminAuditEntity.class})
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    @Import({AdminOverviewBusiness.class, AdminOverviewService.class, AdminOverviewConverter.class, AdminOverviewRepository.class,
            AdminWorkspaceBusiness.class, AdminWorkspaceService.class, AdminWorkspaceConverter.class, AdminWorkspaceRepository.class,
            AdminPlatformConnectionBusiness.class, AdminPlatformConnectionService.class,
            AdminPlatformConnectionConverter.class, AdminPlatformConnectionRepository.class,
            AdminUserRepository.class, AdminUserService.class, AdminUserMutationGuard.class,
            AdminAuditService.class, AdminAuditRepository.class})
    static class Config {
        @Bean AesGcmStringEncryptor encryptor() {
            return new AesGcmStringEncryptor(new CryptoProperties(Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))));
        }
    }
}
