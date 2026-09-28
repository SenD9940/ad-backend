package com.orinan.adminapi.domain.support.controller;

import com.orinan.adminapi.common.api.Api;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.adminapi.domain.auth.model.AdminPrincipal;
import com.orinan.adminapi.domain.support.business.AdminSupportBusiness;
import com.orinan.adminapi.domain.support.controller.model.*;
import com.orinan.db.support.enums.SupportTicketStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin-api/support")
public class AdminSupportApiController {
    private final AdminSupportBusiness support;

    @GetMapping("/tickets")
    public Api<PageResponse<AdminSupportTicketResponse>> tickets(@RequestParam(required = false) SupportTicketStatus status,
            @RequestParam(required = false) Long customerUserId, @RequestParam(required = false) Long workspaceId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return Api.OK(support.tickets(status, customerUserId, workspaceId, page, size));
    }
    @GetMapping("/tickets/{id}")
    public Api<AdminSupportTicketResponse> ticket(@PathVariable long id) { return Api.OK(support.ticket(id)); }
    @PostMapping("/tickets")
    public Api<AdminSupportTicketResponse> create(@AuthenticationPrincipal AdminPrincipal actor,
            @Valid @RequestBody AdminSupportTicketRequest request) { return Api.OK(support.create(actor.id(), request)); }
    @PostMapping("/tickets/{id}/payment")
    public Api<AdminSupportTicketResponse> payment(@AuthenticationPrincipal AdminPrincipal actor, @PathVariable long id,
            @Valid @RequestBody AdminSupportPaymentRequest request) { return Api.OK(support.payment(actor.id(), id, request)); }
    @PostMapping("/tickets/{id}/sessions")
    public Api<AdminSupportSessionTokenResponse> start(@AuthenticationPrincipal AdminPrincipal actor, @PathVariable long id,
            @Valid @RequestBody AdminSupportReasonRequest request) { return Api.OK(support.start(actor.id(), id, request)); }
    @GetMapping("/tickets/{id}/sessions")
    public Api<PageResponse<AdminSupportSessionResponse>> sessions(@PathVariable long id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return Api.OK(support.sessions(id, page, size));
    }
    @GetMapping("/tickets/{id}/actions")
    public Api<PageResponse<AdminSupportActionResponse>> actions(@PathVariable long id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return Api.OK(support.actions(id, page, size));
    }
    @PostMapping("/tickets/{id}/complete")
    public Api<AdminSupportTicketResponse> complete(@AuthenticationPrincipal AdminPrincipal actor, @PathVariable long id,
            @Valid @RequestBody AdminSupportReasonRequest request) { return Api.OK(support.complete(actor.id(), id, request)); }
    @PostMapping("/tickets/{id}/cancel")
    public Api<AdminSupportTicketResponse> cancel(@AuthenticationPrincipal AdminPrincipal actor, @PathVariable long id,
            @Valid @RequestBody AdminSupportReasonRequest request) { return Api.OK(support.cancel(actor.id(), id, request)); }
    @PostMapping("/sessions/{id}/end")
    public Api<AdminSupportSessionResponse> end(@AuthenticationPrincipal AdminPrincipal actor, @PathVariable long id,
            @Valid @RequestBody AdminSupportReasonRequest request) { return Api.OK(support.end(actor.id(), id, request)); }
}
