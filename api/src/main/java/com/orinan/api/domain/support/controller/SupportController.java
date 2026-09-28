package com.orinan.api.domain.support.controller;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.support.business.SupportBusiness;
import com.orinan.api.domain.support.controller.model.SupportRequest;
import com.orinan.api.domain.support.controller.model.SupportCreateRequest;
import com.orinan.api.domain.support.controller.model.SupportResponse.*;
import com.orinan.api.domain.support.exception.SupportErrorCode;
import com.orinan.api.domain.support.model.SupportContext;
import com.orinan.api.domain.user.controller.model.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class SupportController {
    private final SupportBusiness business;

    @GetMapping("/workspaces/{workspaceId}/support/offer")
    public Api<Offer> offer(@PathVariable long workspaceId, @UserSession UserResponse user, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(business.offer(workspaceId, user.getId()));
    }

    @PostMapping("/workspaces/{workspaceId}/support/tickets")
    public Api<Ticket> create(@PathVariable long workspaceId, @RequestBody @Valid SupportCreateRequest request,
                              @UserSession UserResponse user, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(business.create(workspaceId, user.getId(), request));
    }

    @GetMapping("/workspaces/{workspaceId}/support/tickets")
    public Api<Page<Ticket>> tickets(@PathVariable long workspaceId, @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size, @UserSession UserResponse user,
                                    HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(business.tickets(workspaceId, user.getId(), page, size));
    }

    @PostMapping("/workspaces/{workspaceId}/support/tickets/{ticketId}/approve")
    public Api<Ticket> approve(@PathVariable long workspaceId, @PathVariable long ticketId,
                              @RequestBody @Valid SupportRequest request, @UserSession UserResponse user,
                              HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(business.approve(workspaceId, ticketId, user.getId(), request.reason()));
    }

    @PostMapping("/workspaces/{workspaceId}/support/tickets/{ticketId}/revoke")
    public Api<Ticket> revoke(@PathVariable long workspaceId, @PathVariable long ticketId,
                             @RequestBody @Valid SupportRequest request, @UserSession UserResponse user,
                             HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(business.revoke(workspaceId, ticketId, user.getId(), request.reason()));
    }

    @GetMapping("/support/session")
    public Api<Session> session(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(business.session(context(request)));
    }

    @PostMapping("/support/session/end")
    public Api<Ended> end(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(business.end(context(request)));
    }

    private SupportContext context(HttpServletRequest request) {
        if (request.getAttribute(SupportContext.REQUEST_ATTRIBUTE) instanceof SupportContext context) return context;
        throw new ApiException(SupportErrorCode.INVALID_SESSION);
    }
}
