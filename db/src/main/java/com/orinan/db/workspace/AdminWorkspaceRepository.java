package com.orinan.db.workspace;

import com.orinan.db.workspace.projection.*;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.workspaceinvitation.WorkspaceInvitationEntity;
import com.orinan.db.workspacemember.WorkspaceMemberEntity;
import com.orinan.db.workspacemember.WorkspaceMemberId;
import com.orinan.db.workspacemember.enums.WorkspaceMemberRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;

@Repository
@RequiredArgsConstructor
public class AdminWorkspaceRepository {
    private final EntityManager em;

    public Page<AdminWorkspaceProjection> search(String query, Long ownerId, Pageable pageable) {
        String where = " where 1=1";
        Map<String, Object> args = new LinkedHashMap<>();
        if (query != null && !query.isBlank()) {
            where += " and (lower(w.name) like :q escape '!' or lower(u.email) like :q escape '!')";
            args.put("q", literalSearchPattern(query));
        }
        if (ownerId != null) { where += " and u.id=:owner"; args.put("owner", ownerId); }
        return page(workspaceSelect() + where + " order by w.id desc",
                "select count(w) from WorkspaceEntity w left join w.user u" + where, args, pageable, this::workspace);
    }

    public Optional<AdminWorkspaceProjection> findSummary(long id) {
        return em.createQuery(workspaceSelect() + " where w.id=:id", Object[].class).setParameter("id", id)
                .getResultStream().findFirst().map(this::workspace);
    }

    public Page<AdminWorkspaceMemberProjection> findMembers(long workspaceId, Long ownerId, Pageable pageable) {
        // Include a legacy owner even if their membership row is absent.
        String from = " from UserEntity u left join UserProfileEntity p on p.user.id=u.id "
                + "left join WorkspaceMemberEntity m on m.id.userId=u.id and m.id.workspaceId=:workspace"
                + " where u.id=:owner or m.id.userId is not null";
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("workspace", workspaceId); args.put("owner", ownerId);
        return page("select u.id,u.email,p.name,u.status,m.registeredAt" + from + " order by u.id asc",
                "select count(u)" + from, args, pageable,
                row -> new AdminWorkspaceMemberProjection((Long) row[0], (String) row[1], (String) row[2],
                        (UserStatus) row[3], Objects.equals(row[0], ownerId), (LocalDateTime) row[4]));
    }

    public Page<AdminWorkspaceInvitationProjection> findInvitations(long workspaceId, Pageable pageable) {
        String from = " from WorkspaceInvitationEntity i left join UserEntity u on u.id=i.id.userId where i.id.workspaceId=:workspace";
        return page("select i.id.userId,u.email,i.expiresAt" + from + " order by i.id.userId asc", "select count(i)" + from,
                Map.of("workspace", workspaceId), pageable,
                row -> new AdminWorkspaceInvitationProjection((Long) row[0], (String) row[1], (LocalDateTime) row[2]));
    }

    public Optional<WorkspaceEntity> findByIdForUpdate(long id) {
        return Optional.ofNullable(em.find(WorkspaceEntity.class, id, LockModeType.PESSIMISTIC_WRITE));
    }

    public Optional<WorkspaceMemberEntity> findMemberForUpdate(long workspaceId, long userId) {
        return Optional.ofNullable(em.find(WorkspaceMemberEntity.class,
                new WorkspaceMemberId(workspaceId, userId), LockModeType.PESSIMISTIC_WRITE));
    }

    public void ensureMember(long workspaceId, long userId) {
        var id = new WorkspaceMemberId(workspaceId, userId);
        if (em.find(WorkspaceMemberEntity.class, id) == null)
            em.persist(WorkspaceMemberEntity.builder().id(id).role(WorkspaceMemberRole.MEMBER).build());
    }

    public void changeOwner(WorkspaceEntity workspace, long userId) {
        workspace.setUser(em.getReference(UserEntity.class, userId));
    }

    public void removeMember(WorkspaceMemberEntity member) { em.remove(member); }

    public Optional<WorkspaceInvitationEntity> findInvitationForUpdate(long workspaceId, long userId) {
        return Optional.ofNullable(em.find(WorkspaceInvitationEntity.class,
                new WorkspaceMemberId(workspaceId, userId), LockModeType.PESSIMISTIC_WRITE));
    }

    public void removeInvitation(WorkspaceInvitationEntity invitation) { em.remove(invitation); }

    private String literalSearchPattern(String query) {
        return "%" + query.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
    }

    private String workspaceSelect() {
        return "select w.id,w.name,u.id,u.email,u.status,"
                + "(select count(m) from WorkspaceMemberEntity m where m.id.workspaceId=w.id and m.id.userId<>u.id),"
                + "(select count(c) from PlatformConnectionEntity c where c.workspace.id=w.id),w.registeredAt,w.updatedAt"
                + " from WorkspaceEntity w left join w.user u";
    }

    private AdminWorkspaceProjection workspace(Object[] row) {
        return new AdminWorkspaceProjection((Long) row[0], (String) row[1], (Long) row[2], (String) row[3], (UserStatus) row[4],
                (Long) row[5] + (row[2] == null ? 0 : 1), (Long) row[6], (LocalDateTime) row[7], (LocalDateTime) row[8]);
    }

    private <T> Page<T> page(String select, String count, Map<String, Object> args, Pageable pageable, Function<Object[], T> convert) {
        var rows = em.createQuery(select, Object[].class);
        var total = em.createQuery(count, Long.class);
        args.forEach((key, value) -> { rows.setParameter(key, value); total.setParameter(key, value); });
        return new PageImpl<>(rows.setFirstResult((int) pageable.getOffset()).setMaxResults(pageable.getPageSize())
                .getResultList().stream().map(convert).toList(), pageable, total.getSingleResult());
    }
}
