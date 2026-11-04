package com.vistay.finance.adapter.in.rest;

import com.vistay.finance.application.dto.CreateRefundCommand;
import com.vistay.finance.application.dto.RefundResponse;
import com.vistay.finance.application.service.PaymentTransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments/refunds")
public class RefundController {

    private final PaymentTransactionService paymentTransactionService;
    private final FinanceOperationalAuthorization authorization;

    public RefundController(
        PaymentTransactionService paymentTransactionService,
        FinanceOperationalAuthorization authorization
    ) {
        this.paymentTransactionService = paymentTransactionService;
        this.authorization = authorization;
    }

    @PostMapping
    public ResponseEntity<RefundResponse> requestRefund(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody CreateRefundCommand command
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.REFUND_REQUEST);
        var bookingId = paymentTransactionService.getBookingIdForTransaction(command.originalTransactionId());
        if (command.bookingId() != null && !command.bookingId().equals(bookingId)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Booking id does not match the original transaction");
        }
        authorization.requireBooking(actor, bookingId);
        var boundCommand = new CreateRefundCommand(bookingId, command.originalTransactionId(),
            command.amount(), command.currency(), command.reason(),
            authorization.bindActor(actor, command.requestedByAdminUserId()), command.idempotencyKey());
        RefundResponse response = paymentTransactionService.requestRefund(boundCommand);
        authorization.requireBooking(actor, response.bookingId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<RefundResponse> approveRefund(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @RequestParam(required = false) UUID adminUserId
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.REFUND_APPROVE);
        var current = paymentTransactionService.getRefundById(id);
        authorization.requireBooking(actor, current.bookingId());
        UUID authenticatedAdminUserId = authorization.bindActor(actor, adminUserId);
        RefundResponse response = paymentTransactionService.approveAndExecuteRefund(id, authenticatedAdminUserId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<List<RefundResponse>> getRefundsByBooking(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID bookingId
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.REFUND_READ);
        authorization.requireBooking(actor, bookingId);
        return ResponseEntity.ok(paymentTransactionService.getRefundsByBooking(bookingId));
    }
}
