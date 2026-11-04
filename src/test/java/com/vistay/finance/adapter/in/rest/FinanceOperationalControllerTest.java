package com.vistay.finance.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vistay.finance.application.FinanceAuthorizationClient.Scope;
import com.vistay.finance.application.FinanceAuthorizationClient.Snapshot;
import com.vistay.finance.application.dto.CreatePaymentRequestCommand;
import com.vistay.finance.application.dto.CreateRefundCommand;
import com.vistay.finance.application.dto.PaymentRequestResponse;
import com.vistay.finance.application.dto.RefundResponse;
import com.vistay.finance.application.service.PaymentRequestService;
import com.vistay.finance.application.service.PaymentTransactionService;
import com.vistay.finance.domain.model.PayerType;
import com.vistay.finance.domain.model.PaymentRequestStatus;
import com.vistay.finance.domain.model.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;

class FinanceOperationalControllerTest {
    private final FinanceOperationalAuthorization authorization = mock(FinanceOperationalAuthorization.class);
    private final Jwt jwt = mock(Jwt.class);
    private final UUID accountId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();

    @Test
    void paymentCreateRequiresExactPermissionAndPersistsAuthenticatedActor() {
        var service = mock(PaymentRequestService.class);
        var controller = new PaymentRequestController(service, authorization);
        var actor = actor(FinanceOperationalAuthorization.PAYMENT_CREATE);
        var spoofableInput = new CreatePaymentRequestCommand(bookingId, PayerType.CUSTOMER,
            UUID.randomUUID(), BigDecimal.TEN, "VND", "Deposit", "payment-key", null, null);
        var response = paymentResponse();
        when(authorization.require(jwt, FinanceOperationalAuthorization.PAYMENT_CREATE)).thenReturn(actor);
        when(authorization.bindActor(actor, null)).thenReturn(profileId);
        when(service.createPaymentRequest(any())).thenReturn(response);

        controller.createPaymentRequest(jwt, spoofableInput);

        var command = ArgumentCaptor.forClass(CreatePaymentRequestCommand.class);
        verify(service).createPaymentRequest(command.capture());
        assertThat(command.getValue().createdByAdminUserId()).isEqualTo(profileId);
        verify(authorization, times(2)).requireBooking(actor, bookingId);
    }

    @Test
    void refundRequestUsesOriginalTransactionBookingAndAuthenticatedActor() {
        var service = mock(PaymentTransactionService.class);
        var controller = new RefundController(service, authorization);
        var actor = actor(FinanceOperationalAuthorization.REFUND_REQUEST);
        var transactionId = UUID.randomUUID();
        var input = new CreateRefundCommand(null, transactionId, BigDecimal.ONE, "VND",
            "Duplicate", profileId, "refund-key");
        when(authorization.require(jwt, FinanceOperationalAuthorization.REFUND_REQUEST)).thenReturn(actor);
        when(authorization.bindActor(actor, profileId)).thenReturn(profileId);
        when(service.getBookingIdForTransaction(transactionId)).thenReturn(bookingId);
        when(service.requestRefund(any())).thenReturn(refundResponse(transactionId));

        controller.requestRefund(jwt, input);

        var command = ArgumentCaptor.forClass(CreateRefundCommand.class);
        verify(service).requestRefund(command.capture());
        assertThat(command.getValue().bookingId()).isEqualTo(bookingId);
        assertThat(command.getValue().requestedByAdminUserId()).isEqualTo(profileId);
        verify(authorization, times(2)).requireBooking(actor, bookingId);
    }

    @Test
    void refundApprovalAuthorizesRecordBeforeMutation() {
        var service = mock(PaymentTransactionService.class);
        var controller = new RefundController(service, authorization);
        var actor = actor(FinanceOperationalAuthorization.REFUND_APPROVE);
        var refundId = UUID.randomUUID();
        var current = refundResponse(UUID.randomUUID());
        when(authorization.require(jwt, FinanceOperationalAuthorization.REFUND_APPROVE)).thenReturn(actor);
        when(authorization.bindActor(actor, null)).thenReturn(profileId);
        when(service.getRefundById(refundId)).thenReturn(current);
        when(service.approveAndExecuteRefund(refundId, profileId)).thenReturn(current);

        controller.approveRefund(jwt, refundId, null);

        verify(authorization).requireBooking(actor, bookingId);
        verify(service).approveAndExecuteRefund(refundId, profileId);
    }

    private FinanceOperationalAuthorization.Actor actor(String permission) {
        var snapshot = new Snapshot(true, profileId, 1,
            Map.of(permission, Set.of(Scope.ALL)), Map.of());
        return new FinanceOperationalAuthorization.Actor(accountId, profileId, permission, snapshot);
    }

    private PaymentRequestResponse paymentResponse() {
        return new PaymentRequestResponse(UUID.randomUUID(), "PAY-1", bookingId, PayerType.CUSTOMER,
            UUID.randomUUID(), BigDecimal.TEN, "VND", "Deposit", PaymentRequestStatus.PENDING,
            "payment-key", null, Instant.now(), Instant.now());
    }

    private RefundResponse refundResponse(UUID transactionId) {
        return new RefundResponse(UUID.randomUUID(), "RFD-1", bookingId, transactionId, null,
            BigDecimal.ONE, "VND", "Duplicate", RefundStatus.REQUESTED, profileId, null,
            null, "refund-key", Instant.now(), null, null);
    }
}
