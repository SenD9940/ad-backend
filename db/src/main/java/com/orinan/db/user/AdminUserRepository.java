package com.orinan.db.user;

import com.orinan.db.user.projection.AdminUserSummaryProjection;
import com.orinan.db.user.projection.AdminUserWorkspaceProjection;
import com.orinan.db.user.projection.AdminUserLockProjection;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Scalar queries intentionally avoid loading passwords or decrypting unrelated profile PII. */
@Repository
public class AdminUserRepository {
    private static final String USER_FROM = " FROM users u LEFT JOIN user_profiles p ON p.user_id = u.id ";
    private static final String USER_COLUMNS = """
            SELECT u.id, u.email, p.name, u.status, u.role, u.last_login_at,
                   u.registered_at, u.updated_at, u.un_registered_at,
                   (SELECT COUNT(*) FROM workspaces own WHERE own.user_id = u.id),
                   (SELECT COUNT(*) FROM workspaces w WHERE w.user_id = u.id OR EXISTS
                       (SELECT 1 FROM workspace_members m WHERE m.workspace_id = w.id AND m.user_id = u.id))
            """;

    private final EntityManager entityManager;

    public AdminUserRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Page<AdminUserSummaryProjection> search(String query, UserStatus status, UserRole role, Pageable page) {
        StringBuilder filter = new StringBuilder(" WHERE 1 = 1");
        if (query != null && !query.isBlank()) {
            filter.append(" AND (LOWER(u.email) LIKE :query ESCAPE '!' OR LOWER(p.name) LIKE :query ESCAPE '!')");
        }
        if (status != null) filter.append(" AND u.status = :status");
        if (role != null) filter.append(" AND u.role = :role");

        Query count = entityManager.createNativeQuery("SELECT COUNT(*)" + USER_FROM + filter);
        Query records = entityManager.createNativeQuery(USER_COLUMNS + USER_FROM + filter + " ORDER BY u.id DESC");
        for (Query statement : List.of(count, records)) {
            if (query != null && !query.isBlank()) statement.setParameter("query", literalSearchPattern(query));
            if (status != null) statement.setParameter("status", status.name());
            if (role != null) statement.setParameter("role", role.name());
        }
        long total = number(count.getSingleResult());
        List<AdminUserSummaryProjection> items = rows(paginate(records, page)).stream().map(AdminUserRepository::summary).toList();
        return new PageImpl<>(items, page, total);
    }

    public Optional<AdminUserSummaryProjection> findById(long id) {
        return rows(entityManager.createNativeQuery(USER_COLUMNS + USER_FROM + " WHERE u.id = :id")
                .setParameter("id", id)).stream().findFirst().map(AdminUserRepository::summary);
    }

    public Page<AdminUserWorkspaceProjection> workspaces(long userId, Pageable page) {
        String from = """
                 FROM workspaces w LEFT JOIN users owner ON owner.id = w.user_id
                 WHERE w.user_id = :userId OR EXISTS
                   (SELECT 1 FROM workspace_members m WHERE m.workspace_id = w.id AND m.user_id = :userId)
                """;
        long total = number(entityManager.createNativeQuery("SELECT COUNT(*)" + from)
                .setParameter("userId", userId).getSingleResult());
        Query records = entityManager.createNativeQuery("""
                SELECT w.id, w.name, owner.id, owner.email,
                       CASE WHEN w.user_id = :userId THEN 'OWNER' ELSE 'MEMBER' END,
                       w.registered_at, w.updated_at
                """ + from + " ORDER BY w.id DESC").setParameter("userId", userId);
        List<AdminUserWorkspaceProjection> items = rows(paginate(records, page)).stream().map(row -> new AdminUserWorkspaceProjection(
                number(row[0]), (String) row[1], row[2] == null ? null : number(row[2]),
                (String) row[3], (String) row[4], date(row[5]), date(row[6]))).toList();
        return new PageImpl<>(items, page, total);
    }

    public Optional<AdminUserLockProjection> lockById(long id) {
        return rows(entityManager.createNativeQuery("""
                SELECT id, email, status, role, auth_version FROM users WHERE id = :id FOR UPDATE
                """).setParameter("id", id)).stream().findFirst().map(row -> new AdminUserLockProjection(
                number(row[0]), (String) row[1], UserStatus.valueOf((String) row[2]),
                UserRole.valueOf((String) row[3]), number(row[4])));
    }

    public void updateStatus(long id, UserStatus status, long version, LocalDateTime now) {
        entityManager.createNativeQuery("""
                UPDATE users SET status = :status, auth_version = :version, updated_at = :now WHERE id = :id
                """).setParameter("status", status.name()).setParameter("version", version)
                .setParameter("now", now).setParameter("id", id).executeUpdate();
    }

    public void updateRole(long id, UserRole role, long version, LocalDateTime now) {
        entityManager.createNativeQuery("""
                UPDATE users SET role = :role, auth_version = :version, updated_at = :now WHERE id = :id
                """).setParameter("role", role.name()).setParameter("version", version)
                .setParameter("now", now).setParameter("id", id).executeUpdate();
    }

    public void updateAuthVersion(long id, long version, LocalDateTime now) {
        entityManager.createNativeQuery("""
                UPDATE users SET auth_version = :version, updated_at = :now WHERE id = :id
                """).setParameter("version", version).setParameter("now", now)
                .setParameter("id", id).executeUpdate();
    }

    public int revokeSessions(long id, LocalDateTime now) {
        return entityManager.createNativeQuery("""
                UPDATE tokens SET status = 'EXPIRED', revoked_at = :now
                WHERE user_id = :id AND status = 'ACTIVE'
                """).setParameter("now", now).setParameter("id", id).executeUpdate();
    }

    static String literalSearchPattern(String query) {
        return "%" + query.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
    }

    private static Query paginate(Query query, Pageable page) {
        if (page.getOffset() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Page offset exceeds the supported integer range");
        }
        return query.setFirstResult((int) page.getOffset()).setMaxResults(page.getPageSize());
    }

    private static AdminUserSummaryProjection summary(Object[] row) {
        return new AdminUserSummaryProjection(number(row[0]), (String) row[1], (String) row[2],
                UserStatus.valueOf((String) row[3]), UserRole.valueOf((String) row[4]),
                date(row[5]), date(row[6]), date(row[7]), date(row[8]), number(row[9]), number(row[10]));
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rows(Query query) {
        return query.getResultList();
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static LocalDateTime date(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime dateTime) return dateTime;
        return ((Timestamp) value).toLocalDateTime();
    }
}
