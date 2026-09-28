package com.orinan.api.domain.support.payment;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.domain.support.payment.SupportPaymentModels.*;
import com.orinan.api.domain.user.controller.model.UserResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/support/tickets/{ticketId}")
public class SupportPaymentController {
    private final SupportPaymentService payments;

    @PostMapping("/payment-order")
    public Api<Order> order(@PathVariable long workspaceId, @PathVariable long ticketId,
                            @UserSession UserResponse user, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(payments.createOrder(workspaceId, ticketId, user.getId()));
    }

    @PostMapping("/payment-confirm")
    public Api<Result> confirm(@PathVariable long workspaceId, @PathVariable long ticketId,
                              @Valid @RequestBody Confirm request, @UserSession UserResponse user,
                              HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(payments.confirm(workspaceId, ticketId, user.getId(), request));
    }

    @GetMapping("/payment")
    public Api<Result> payment(@PathVariable long workspaceId, @PathVariable long ticketId,
                              @RequestParam(required = false) String orderId, @UserSession UserResponse user,
                              HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(payments.payment(workspaceId, ticketId, user.getId(), orderId));
    }
}
