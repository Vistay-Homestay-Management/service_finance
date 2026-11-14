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

    @GetMapping("/preview")
    public ResponseEntity<CancellationPreviewResponse> getCancellationPreview(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam UUID bookingId,
        @RequestParam(required = false) UUID propertyId
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.RESERVATION_DECISION_VIEW);
        authorization.requireReservationAccess(actor, bookingId, propertyId, null);
        return ResponseEntity.ok(obligationService.getVerifiedCollectedAdvanceForCancellation(bookingId, propertyId, actor));
    }

    @PostMapping("/obligations")
    public ResponseEntity<CancellationObligationResponse> executeCancellation(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody ExecuteCancellationCommand command
    ) {
        // Enforce action and scope specifically for CANCELLATION_ASSESS
        var cancelActor = authorization.require(jwt, FinanceOperationalAuthorization.CANCELLATION_ASSESS);
        var authoritativeBooking = authorization.requireReservationAccess(cancelActor, command.bookingId(), command.propertyId(), null);
        UUID resolvedPropertyId = authoritativeBooking != null && authoritativeBooking.propertyId() != null
            ? authoritativeBooking.propertyId()
            : command.propertyId();

        // Finding C: Enforce action and scope specifically for REFUND_CREATE (pairing grant + actor scope)
        if (command.refundableAmount() != null && command.refundableAmount().compareTo(BigDecimal.ZERO) > 0) {
            var refundActor = authorization.require(jwt, FinanceOperationalAuthorization.REFUND_CREATE);
            authorization.requireReservationAccess(refundActor, command.bookingId(), resolvedPropertyId, null);
        }

        CancellationObligationResponse response = obligationService.processCancellationExecution(command, cancelActor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/obligations/{id}/retry-refund")
    public ResponseEntity<CancellationObligationResponse> retryFailedRefund(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @RequestParam String correlationId
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.CANCELLATION_ASSESS);
        var existing = obligationService.getObligationById(id);
        authorization.requireReservationAccess(actor, existing.bookingId(), existing.propertyId(), null);
        return ResponseEntity.ok(obligationService.retryFailedRefund(id, correlationId, actor));
    }

    @PostMapping("/obligations/{id}/reconcile-refund")
    public ResponseEntity<CancellationObligationResponse> reconcileRefundOutcome(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @Valid @RequestBody ReconcileRefundOutcomeCommand command
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.CANCELLATION_ASSESS);
        var existing = obligationService.getObligationById(id);
        if (!id.equals(command.obligationId())) {
            throw new IllegalArgumentException("Path obligation ID does not match command obligation ID");
        }
        authorization.requireReservationAccess(actor, existing.bookingId(), existing.propertyId(), null);
        return ResponseEntity.ok(obligationService.reconcileRefundOutcome(command));
    }

    @GetMapping("/obligations/{id}")
    public ResponseEntity<CancellationObligationResponse> getObligationById(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        var actor = authorization.require(jwt, FinanceOperationalAuthorization.RESERVATION_DECISION_VIEW);
        var response = obligationService.getObligationById(id);
        authorization.requireReservationAccess(actor, response.bookingId(), response.propertyId(), null);
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
