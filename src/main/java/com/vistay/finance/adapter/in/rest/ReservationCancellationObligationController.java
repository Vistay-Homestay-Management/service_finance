package com.vistay.finance.adapter.in.rest;

import com.vistay.finance.application.dto.*;
import com.vistay.finance.application.service.ReservationCancellationObligationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/cancellations")
public class ReservationCancellationObligationController {

    private final ReservationCancellationObligationService obligationService;
    private final FinanceOperationalAuthorization authorization;

    public ReservationCancellationObligationController(
        ReservationCancellationObligationService obligationService,
        FinanceOperationalAuthorization authorization
    ) {
        this.obligationService = obligationService;
        this.authorization = authorization;
    }

    @GetMapping("/preview/{bookingId}")
    public ResponseEntity<CancellationPreviewResponse> getCancellationPreview(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID bookingId
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.RESERVATION_DECISION_VIEW);
        authorization.requireBooking(actor, bookingId);
        return ResponseEntity.ok(obligationService.getVerifiedCollectedAdvanceForCancellation(bookingId, actor));
    }

    @PostMapping("/obligations")
    public ResponseEntity<CancellationObligationResponse> executeCancellation(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody ExecuteCancellationCommand command
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.CANCELLATION_ASSESS);
        authorization.requireBooking(actor, command.bookingId());
        if (command.propertyId() != null) {
            authorization.requireProperty(actor, command.propertyId());
        }
        if (command.refundableAmount() != null && command.refundableAmount().compareTo(BigDecimal.ZERO) > 0) {
            authorization.require(jwt, FinanceOperationalAuthorization.REFUND_CREATE);
        }

        CancellationObligationResponse response = obligationService.processCancellationExecution(command, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/obligations/{id}/reconcile-refund")
    public ResponseEntity<CancellationObligationResponse> reconcileRefundOutcome(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @Valid @RequestBody ReconcileRefundOutcomeCommand command
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.CANCELLATION_ASSESS);
        var existing = obligationService.getObligationById(id);
        authorization.requireBooking(actor, existing.bookingId());
        if (!id.equals(command.obligationId())) {
            throw new IllegalArgumentException("Path obligation ID does not match command obligation ID");
        }
        return ResponseEntity.ok(obligationService.reconcileRefundOutcome(command));
    }

    @GetMapping("/obligations/{id}")
    public ResponseEntity<CancellationObligationResponse> getObligationById(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.RESERVATION_DECISION_VIEW);
        var response = obligationService.getObligationById(id);
        authorization.requireBooking(actor, response.bookingId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/obligations/failed-refunds")
    public ResponseEntity<List<CancellationObligationResponse>> getFailedRefunds(
        @AuthenticationPrincipal Jwt jwt
    ) {
        authorization.requireAll(jwt, FinanceOperationalAuthorization.CANCELLATION_ASSESS);
        return ResponseEntity.ok(obligationService.getFailedRefundObligations());
    }
}
