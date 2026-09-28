package com.orinan.api.domain.support.service;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.support.exception.SupportErrorCode;
import com.orinan.api.domain.support.model.SupportContext;
import com.orinan.db.support.*;
import com.orinan.db.support.enums.*;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.UserRepository;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupportSessionService {
    private final SupportSessionRepository sessions;
    private final SupportTicketRepository tickets;
    private final UserRepository users;
    private final WorkspaceRepository workspaces;

    public SupportContext authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        var session = sessions.findByTokenHash(SupportTokenHash.hash(token)).orElseThrow(this::invalid);
        var ticket = tickets.findById(session.getTicketId()).orElseThrow(this::invalid);
        var admin = users.findById(session.getAdminUserId()).orElseThrow(this::invalid);
        var customer = users.findById(session.getCustomerUserId()).orElseThrow(this::invalid);
        var workspace = workspaces.findById(session.getWorkspaceId()).orElseThrow(this::invalid);
        return validate(session, ticket, admin, customer, workspace);
    }

    @Transactional
    public void end(SupportContext context) {
        // Same lock order as admin mutations: users in ID order, workspace, ticket, session.
        long first = Math.min(context.adminUserId(), context.customerUserId());
        long second = Math.max(context.adminUserId(), context.customerUserId());
        var firstUser = users.findByIdForUpdate(first).orElseThrow(this::invalid);
        var secondUser = first == second ? firstUser : users.findByIdForUpdate(second).orElseThrow(this::invalid);
        var admin = first == context.adminUserId() ? firstUser : secondUser;
        var customer = first == context.customerUserId() ? firstUser : secondUser;
        var workspace = workspaces.findByIdForUpdate(context.workspaceId()).orElseThrow(this::invalid);
        var ticket = tickets.findByIdForUpdate(context.ticketId()).orElseThrow(this::invalid);
        var session = sessions.findByIdForUpdate(context.sessionId()).orElseThrow(this::invalid);
        validate(session, ticket, admin, customer, workspace);
        session.setEndedAt(now());
    }

    private SupportContext validate(SupportSessionEntity session, SupportTicketEntity ticket, UserEntity admin,
                                    UserEntity customer, WorkspaceEntity workspace) {
        var now = now();
        if (session.getEndedAt() != null || !session.getExpiresAt().isAfter(now)
                || ticket.getApprovalExpiresAt() == null || !ticket.getApprovalExpiresAt().isAfter(now)
                || ticket.getApprovedAt() == null
                || (ticket.getStatus() != SupportTicketStatus.APPROVED && ticket.getStatus() != SupportTicketStatus.IN_PROGRESS)
                || !paymentAllowsSupport(ticket)
                || !Objects.equals(ticket.getId(), session.getTicketId())
                || !Objects.equals(ticket.getWorkspaceId(), session.getWorkspaceId())
                || !Objects.equals(ticket.getCustomerUserId(), session.getCustomerUserId())
                || !Objects.equals(ticket.getAssignedAdminId(), session.getAdminUserId())
                || ticket.getAccessMode() != session.getAccessMode()
                || !Objects.equals(admin.getId(), session.getAdminUserId())
                || !Objects.equals(customer.getId(), session.getCustomerUserId())
                || admin.getStatus() != UserStatus.REGISTERED || admin.getRole() != UserRole.ADMIN
                || customer.getStatus() != UserStatus.REGISTERED
                || admin.getAuthVersion() != session.getAdminAuthVersion()
                || customer.getAuthVersion() != session.getCustomerAuthVersion()
                || !Objects.equals(workspace.getId(), session.getWorkspaceId())
                || workspace.getUser() == null || !Objects.equals(workspace.getUser().getId(), customer.getId())) {
            throw invalid();
        }
        var expiry = session.getExpiresAt().isBefore(ticket.getApprovalExpiresAt())
                ? session.getExpiresAt() : ticket.getApprovalExpiresAt();
        return new SupportContext(session.getId(), ticket.getId(), workspace.getId(), admin.getId(), customer.getId(),
                session.getAccessMode().name(), expiry);
    }

    private ApiException invalid() { return new ApiException(SupportErrorCode.INVALID_SESSION); }
    private boolean paymentAllowsSupport(SupportTicketEntity ticket) {
        return ticket.getPaymentStatus() == SupportPaymentStatus.PAID
                || ticket.getRequestSource() == SupportRequestSource.ADMIN && ticket.getPaymentStatus() == SupportPaymentStatus.WAIVED;
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Seoul")); }
}
