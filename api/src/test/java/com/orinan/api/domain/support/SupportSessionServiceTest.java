package com.orinan.api.domain.support;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.support.exception.SupportErrorCode;
import com.orinan.api.domain.support.service.SupportSessionService;
import com.orinan.db.support.*;
import com.orinan.db.support.enums.*;
import com.orinan.db.user.*;
import com.orinan.db.user.enums.*;
import com.orinan.db.workspace.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportSessionServiceTest {
    private static final String TOKEN = "a".repeat(43);
    private final SupportSessionRepository sessions = mock(SupportSessionRepository.class);
    private final SupportTicketRepository tickets = mock(SupportTicketRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final SupportSessionService service = new SupportSessionService(sessions, tickets, users, workspaces);
    private SupportSessionEntity session;
    private SupportTicketEntity ticket;
    private UserEntity admin;
    private UserEntity customer;
    private WorkspaceEntity workspace;

    @BeforeEach void setup() {
        var now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        admin = UserEntity.builder().id(3L).role(UserRole.ADMIN).status(UserStatus.REGISTERED).authVersion(2).build();
        customer = UserEntity.builder().id(4L).role(UserRole.CUSTOMER).status(UserStatus.REGISTERED).authVersion(5).build();
        workspace = WorkspaceEntity.builder().id(10L).user(customer).build();
        ticket = new SupportTicketEntity(10, 4, 3, "상품 등록 지원", "입력 내용 확인", SupportAccessMode.OPERATE, 30000, now);
        ReflectionTestUtils.setField(ticket, "id", 2L);
        ticket.setStatus(SupportTicketStatus.IN_PROGRESS); ticket.setPaymentStatus(SupportPaymentStatus.PAID);
        ticket.setApprovedAt(now.minusHours(1)); ticket.setApprovalExpiresAt(now.plusDays(7));
        session = new SupportSessionEntity(2, 3, 4, 10, SupportTokenHash.hash(TOKEN), 2, 5,
                SupportAccessMode.OPERATE, now, now.plusMinutes(30));
        ReflectionTestUtils.setField(session, "id", 1L);
        when(sessions.findByTokenHash(SupportTokenHash.hash(TOKEN))).thenReturn(Optional.of(session));
        when(tickets.findById(2L)).thenReturn(Optional.of(ticket));
        when(users.findById(3L)).thenReturn(Optional.of(admin));
        when(users.findById(4L)).thenReturn(Optional.of(customer));
        when(workspaces.findById(10L)).thenReturn(Optional.of(workspace));
    }

    @Test void usesOnlyTheSecretHashAndReturnsMetadataWithoutAnyToken() {
        var context = service.authenticate(TOKEN);
        assertThat(context.sessionId()).isEqualTo(1);
        assertThat(context.customerUserId()).isEqualTo(4);
        assertThat(context.accessMode()).isEqualTo("OPERATE");
        assertThat(context.toString()).doesNotContain(TOKEN, SupportTokenHash.hash(TOKEN));
        verify(sessions).findByTokenHash(SupportTokenHash.hash(TOKEN));
        verify(sessions, never()).findByTokenHash(TOKEN);
    }

    @Test void unknownAndMalformedSecretsCannotAuthenticate() {
        for (String token : new String[]{"invalid", TOKEN + "x", TOKEN + "," + TOKEN, " " + TOKEN, "b".repeat(43)}) {
            assertThatThrownBy(() -> service.authenticate(token)).isInstanceOfSatisfying(ApiException.class,
                    exception -> assertThat(exception.getCodeIfs()).isEqualTo(SupportErrorCode.INVALID_SESSION));
        }
        verify(sessions, never()).findByTokenHash("invalid");
    }

    @Test void endedOrExpiredSessionsAreRejected() {
        session.setEndedAt(LocalDateTime.now()); denied();
        session.setEndedAt(null);
        ReflectionTestUtils.setField(session, "expiresAt", LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusSeconds(1)); denied();
    }

    @Test void consentAndPaymentAreRequiredOnEveryRequest() {
        for (SupportTicketStatus state : new SupportTicketStatus[]{SupportTicketStatus.REQUESTED, SupportTicketStatus.COMPLETED,
                SupportTicketStatus.CANCELLED}) { ticket.setStatus(state); denied(); }
        ticket.setStatus(SupportTicketStatus.APPROVED);
        ticket.setPaymentStatus(SupportPaymentStatus.UNPAID); denied();
        ticket.setPaymentStatus(SupportPaymentStatus.WAIVED);
        assertThat(service.authenticate(TOKEN)).isNotNull();
        ticket.setApprovalExpiresAt(LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusSeconds(1)); denied();
        ticket.setApprovalExpiresAt(null); denied();
    }

    @Test void permissionStatusAndSessionRevocationsInvalidateTheGrant() {
        admin.setStatus(UserStatus.SUSPENDED); denied(); admin.setStatus(UserStatus.REGISTERED);
        admin.setRole(UserRole.CUSTOMER); denied(); admin.setRole(UserRole.ADMIN);
        admin.setAuthVersion(3); denied(); admin.setAuthVersion(2);
        customer.setStatus(UserStatus.SUSPENDED); denied(); customer.setStatus(UserStatus.REGISTERED);
        customer.setAuthVersion(6); denied(); customer.setAuthVersion(5);
        workspace.setUser(UserEntity.builder().id(99L).build()); denied();
        workspace.setUser(null); denied();
    }

    @Test void customerSessionsRequireVerifiedPaymentAndCannotUseLegacyWaivers() {
        ReflectionTestUtils.setField(ticket, "requestSource", SupportRequestSource.CUSTOMER);
        for (SupportPaymentStatus payment : new SupportPaymentStatus[]{SupportPaymentStatus.UNPAID, SupportPaymentStatus.WAIVED}) {
            ticket.setPaymentStatus(payment);
            denied();
        }
        ticket.setPaymentStatus(SupportPaymentStatus.PAID);
        assertThat(service.authenticate(TOKEN)).isNotNull();
        // A confirmed refund changes the ticket payment state and blocks the existing token immediately.
        ticket.setPaymentStatus(SupportPaymentStatus.UNPAID);
        denied();
    }

    @Test void ticketSessionIdentityAndModeMustMatchExactly() {
        for (String field : new String[]{"assignedAdminId", "customerUserId", "workspaceId"}) {
            Object before = ReflectionTestUtils.getField(ticket, field);
            ReflectionTestUtils.setField(ticket, field, 99L); denied(); ReflectionTestUtils.setField(ticket, field, before);
        }
        ReflectionTestUtils.setField(ticket, "accessMode", SupportAccessMode.READ_ONLY); denied();
    }

    @Test void sessionMetadataCannotOutliveConsent() {
        var expires = LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusMinutes(5);
        ticket.setApprovalExpiresAt(expires);
        assertThat(service.authenticate(TOKEN).expiresAt()).isEqualTo(expires);
    }

    @Test void endLocksBothUsersWorkspaceTicketAndSessionBeforeRevoking() {
        when(users.findByIdForUpdate(3L)).thenReturn(Optional.of(admin));
        when(users.findByIdForUpdate(4L)).thenReturn(Optional.of(customer));
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        when(tickets.findByIdForUpdate(2L)).thenReturn(Optional.of(ticket));
        when(sessions.findByIdForUpdate(1L)).thenReturn(Optional.of(session));
        var context = service.authenticate(TOKEN);
        service.end(context);
        assertThat(session.getEndedAt()).isNotNull();
        var ordered = inOrder(users, workspaces, tickets, sessions);
        ordered.verify(users).findByIdForUpdate(3L); ordered.verify(users).findByIdForUpdate(4L);
        ordered.verify(workspaces).findByIdForUpdate(10L); ordered.verify(tickets).findByIdForUpdate(2L);
        ordered.verify(sessions).findByIdForUpdate(1L);
    }

    private void denied() {
        assertThatThrownBy(() -> service.authenticate(TOKEN)).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getCodeIfs()).isEqualTo(SupportErrorCode.INVALID_SESSION));
    }
}
