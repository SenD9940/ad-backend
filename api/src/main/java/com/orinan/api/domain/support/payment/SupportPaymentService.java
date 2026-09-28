package com.orinan.api.domain.support.payment;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.support.payment.SupportPaymentModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Orchestrates separately committed preparation and result transactions around one provider call. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class SupportPaymentService {
    private final SupportPaymentTransactionService transactions;
    private final TossPaymentsClient toss;

    public Order createOrder(long workspaceId, long ticketId, long customerId) {
        return transactions.createOrder(workspaceId, ticketId, customerId);
    }
    public Result confirm(long workspaceId, long ticketId, long customerId, Confirm request) {
        return resolve(transactions.prepareConfirm(workspaceId, ticketId, customerId, request), false);
    }
    public Result payment(long workspaceId, long ticketId, long customerId, String orderId) {
        return resolve(transactions.prepareQuery(workspaceId, ticketId, customerId, orderId), true);
    }
    public void webhook(String orderId) {
        var preparation = transactions.prepareWebhook(orderId);
        if (preparation == null) return;
        var result = resolve(preparation, true);
        if ("UNKNOWN".equals(result.status()) || "CONFIRMING".equals(result.status())) {
            throw new ApiException(SupportPaymentErrorCode.RECONCILE_REQUIRED);
        }
    }

    private Result resolve(SupportPaymentTransactionService.Preparation preparation, boolean failOnReadError) {
        var attempt = preparation.attempt();
        if (attempt == null) return preparation.result();
        TossPaymentsClient.PaymentSnapshot snapshot;
        try {
            snapshot = attempt.confirm() ? toss.confirm(attempt.paymentKey(), attempt.orderId(), attempt.amount())
                    : toss.getByPaymentKey(attempt.paymentKey());
        } catch (TossPaymentException exception) {
            Result result = transactions.failed(attempt, exception.isAmbiguous());
            if (failOnReadError) throw new ApiException(SupportPaymentErrorCode.RECONCILE_REQUIRED);
            return result;
        }
        return transactions.apply(attempt, snapshot);
    }
}
