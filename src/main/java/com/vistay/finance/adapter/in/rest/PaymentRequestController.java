package com.vistay.finance.adapter.in.rest;

import com.vistay.finance.application.dto.CreatePaymentRequestCommand;
import com.vistay.finance.application.dto.PaymentRequestResponse;
import com.vistay.finance.application.service.PaymentRequestService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments/requests")
public class PaymentRequestController {

    private final PaymentRequestService paymentRequestService;
    private final FinanceOperationalAuthorization authorization;

    public PaymentRequestController(
        PaymentRequestService paymentRequestService,
        FinanceOperationalAuthorization authorization
    ) {
        this.paymentRequestService = paymentRequestService;
        this.authorization = authorization;
    }

    @PostMapping
    public ResponseEntity<PaymentRequestResponse> createPaymentRequest(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody CreatePaymentRequestCommand command
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.PAYMENT_CREATE);
        authorization.requireBooking(actor, command.bookingId());
        var boundCommand = new CreatePaymentRequestCommand(command.bookingId(), command.payerType(),
            command.payerId(), command.amount(), command.currency(), command.purpose(),
            command.idempotencyKey(), command.expiresAt(),
            authorization.bindActor(actor, command.createdByAdminUserId()));
        PaymentRequestResponse response = paymentRequestService.createPaymentRequest(boundCommand);
        authorization.requireBooking(actor, response.bookingId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentRequestResponse> getPaymentRequestById(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.PAYMENT_READ);
        var response = paymentRequestService.getPaymentRequestById(id);
        authorization.requireBooking(actor, response.bookingId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/code/{code}")
    public ResponseEntity<PaymentRequestResponse> getPaymentRequestByCode(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable String code
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.PAYMENT_READ);
        var response = paymentRequestService.getPaymentRequestByCode(code);
        authorization.requireBooking(actor, response.bookingId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<List<PaymentRequestResponse>> getPaymentRequestsByBooking(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID bookingId
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.PAYMENT_READ);
        authorization.requireBooking(actor, bookingId);
        return ResponseEntity.ok(paymentRequestService.getPaymentRequestsByBooking(bookingId));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<PaymentRequestResponse> cancelPaymentRequest(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.PAYMENT_CANCEL);
        var current = paymentRequestService.getPaymentRequestById(id);
        authorization.requireBooking(actor, current.bookingId());
        return ResponseEntity.ok(paymentRequestService.cancelPaymentRequest(id));
    }
}
