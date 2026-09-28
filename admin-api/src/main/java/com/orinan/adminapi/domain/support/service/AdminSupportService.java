package com.orinan.adminapi.domain.support.service;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.db.support.*;
import com.orinan.db.support.enums.SupportTicketStatus;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminSupportService {
    private final SupportTicketRepository tickets;
    private final SupportSessionRepository sessions;
    private final SupportActionRepository actions;
    private final WorkspaceRepository workspaces;

    public Page<SupportTicketEntity> tickets(SupportTicketStatus status, Long customer, Long workspace, Pageable page) {
        return tickets.findAll(status, customer, workspace, page);
    }
    public SupportTicketEntity ticket(long id) { return tickets.findById(id).orElseThrow(() -> missing("지원 요청")); }
    public SupportTicketRepository.Scope scope(long id) { return tickets.findScopeById(id).orElseThrow(() -> missing("지원 요청")); }
    public SupportTicketEntity lockTicket(long id) { return tickets.findByIdForUpdate(id).orElseThrow(() -> missing("지원 요청")); }
    public WorkspaceEntity lockWorkspace(long id) { return workspaces.findByIdForUpdate(id).orElseThrow(() -> missing("워크스페이스")); }
    public SupportTicketEntity save(SupportTicketEntity ticket) { return tickets.save(ticket); }
    public SupportSessionEntity save(SupportSessionEntity session) { return sessions.save(session); }
    public void endSessions(long ticketId, LocalDateTime now) { sessions.endActiveByTicketId(ticketId, now); }
    public long sessionTicketId(long id) { return sessions.findTicketIdById(id).orElseThrow(() -> missing("지원 세션")); }
    public SupportSessionEntity lockSession(long id) { return sessions.findByIdForUpdate(id).orElseThrow(() -> missing("지원 세션")); }
    public Page<SupportSessionEntity> sessions(long ticketId, Pageable page) { return sessions.findAllByTicketId(ticketId, page); }
    public Page<SupportActionEntity> actions(long ticketId, Pageable page) { return actions.findAllByTicketId(ticketId, page); }

    private AdminException missing(String name) { return new AdminException(HttpStatus.NOT_FOUND, name + "을 찾을 수 없습니다."); }
}
