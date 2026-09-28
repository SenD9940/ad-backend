package com.orinan.api.domain.support.service;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.support.controller.model.SupportCreateRequest;
import com.orinan.api.domain.support.controller.model.SupportResponse.*;
import com.orinan.api.domain.support.converter.SupportConverter;
import com.orinan.api.domain.support.exception.SupportErrorCode;
import com.orinan.api.domain.support.payment.TossPaymentProperties;
import com.orinan.db.adminaudit.AdminAuditEntity;
import com.orinan.db.adminaudit.AdminAuditRepository;
import com.orinan.db.support.SupportTicketEntity;
import com.orinan.db.support.SupportTicketRepository;
import com.orinan.db.user.UserRepository;
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
public class SupportRequestService {
    private final UserRepository users;
    private final WorkspaceRepository workspaces;
    private final SupportTicketRepository tickets;
    private final AdminAuditRepository audit;
    private final SupportConverter converter;
    private final TossPaymentProperties paymentProperties;

    public Offer offer(long workspaceId, long userId) {
        positive(workspaceId, userId);
        var user = users.findById(userId).orElseThrow(() -> new ApiException(SupportErrorCode.ACCESS_DENIED));
        if (user.getStatus() != UserStatus.REGISTERED) throw new ApiException(SupportErrorCode.ACCESS_DENIED);
        var workspace = workspaces.findById(workspaceId).orElseThrow(() -> new ApiException(SupportErrorCode.NOT_FOUND));
        owner(workspace, userId);
        return new Offer(paymentProperties.isConfigured(), SupportTerms.AMOUNT_KRW, SupportTerms.VERSION, SupportTerms.TEXT);
    }

    @Transactional
    public Ticket create(long workspaceId, long userId, SupportCreateRequest request) {
        positive(workspaceId, userId);
        validate(request);
        // Match the existing consent/admin lock order and bind the request to the current owner.
        var user = users.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(SupportErrorCode.ACCESS_DENIED));
        if (user.getStatus() != UserStatus.REGISTERED) throw new ApiException(SupportErrorCode.ACCESS_DENIED);
        var workspace = workspaces.findByIdForUpdate(workspaceId).orElseThrow(() -> new ApiException(SupportErrorCode.NOT_FOUND));
        owner(workspace, userId);
        if (!paymentProperties.isConfigured()) throw new ApiException(SupportErrorCode.PAYMENT_UNAVAILABLE);
        if (!SupportTerms.VERSION.equals(request.termsVersion())) throw new ApiException(SupportErrorCode.TERMS_CHANGED);
        var now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        var ticket = tickets.save(SupportTicketEntity.customerRequest(workspaceId, userId, request.title().strip(),
                request.description().strip(), request.accessMode(), SupportTerms.AMOUNT_KRW, SupportTerms.VERSION,
                SupportTerms.snapshot(request.accessMode()), now));
        audit.save(new AdminAuditEntity(userId, "SUPPORT_CUSTOMER_REQUEST", "SUPPORT_TICKET", ticket.getId(),
                "고객 기술 지원 신청 및 약관 동의", null,
                "source:CUSTOMER;mode:" + ticket.getAccessMode() + ";amount_krw:" + ticket.getAmountKrw()
                        + ";terms_version:" + ticket.getTermsVersion(), now));
        return converter.ticket(ticket);
    }

    private void validate(SupportCreateRequest request) {
        if (request == null || request.title() == null || request.title().isBlank() || request.title().length() > 150
                || request.description() == null || request.description().isBlank() || request.description().length() > 1000
                || request.accessMode() == null || request.termsVersion() == null || request.termsVersion().isBlank()
                || request.termsVersion().length() > 80 || !Boolean.TRUE.equals(request.acceptedTerms())) {
            throw new ApiException(SupportErrorCode.INVALID_REQUEST);
        }
    }
    private void positive(long workspaceId, long userId) {
        if (workspaceId < 1 || userId < 1) throw new ApiException(SupportErrorCode.INVALID_REQUEST);
    }
    private void owner(WorkspaceEntity workspace, long userId) {
        if (workspace.getUser() == null || !Objects.equals(workspace.getUser().getId(), userId))
            throw new ApiException(SupportErrorCode.ACCESS_DENIED);
    }
}
