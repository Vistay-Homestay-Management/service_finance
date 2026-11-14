package com.vistay.finance.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.vistay.finance.application.FinanceAuthorizationClient;
import com.vistay.finance.application.dto.*;
import com.vistay.finance.application.service.ReservationCancellationObligationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class ReservationCancellationObligationControllerTest {

    private final ReservationCancellationObligationService service = mock(ReservationCancellationObligationService.class);
    private final FinanceOperationalAuthorization authorization = mock(FinanceOperationalAuthorization.class);
    private final ReservationCancellationObligationController controller =
        new ReservationCancellationObligationController(service, authorization);

    private final Jwt jwt = mock(Jwt.class);
    private final UUID bookingId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();
    private final UUID obligationId = UUID.randomUUID();
    private final UUID adminProfileId = UUID.randomUUID();
    private final FinanceOperationalAuthorization.Actor actor =
        new FinanceOperationalAuthorization.Actor(UUID.randomUUID(), adminProfileId, "finance:cancellation:assess",
            new FinanceAuthorizationClient.Snapshot(true, adminProfileId, 1L, Map.of(), Map.of()));

    @BeforeEach
    void setUp() {
        when(authorization.require(eq(jwt), anyString())).thenReturn(actor);
        when(authorization.requireAll(eq(jwt), anyString())).thenReturn(actor);
    }

    @Test
    @DisplayName("Controller: Preview endpoint enforces RESERVATION_DECISION_VIEW and reservation access")
    void preview_EnforcesReservationDecisionView() {
        CancellationPreviewResponse expected = new CancellationPreviewResponse(
            bookingId, new BigDecimal("1000000"), "VND", 1, "hash-123", List.of()
        );
        when(service.getVerifiedCollectedAdvanceForCancellation(bookingId, propertyId, actor)).thenReturn(expected);

        ResponseEntity<CancellationPreviewResponse> response = controller.getCancellationPreview(jwt, bookingId, propertyId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);

        verify(authorization).require(jwt, FinanceOperationalAuthorization.RESERVATION_DECISION_VIEW);
        verify(authorization).requireReservationAccess(actor, bookingId, propertyId, null);
    }

    @Test
    @DisplayName("Controller: Execution endpoint enforces CANCELLATION_ASSESS, REFUND_CREATE, and reservation access")
    void execute_EnforcesCancellationAssessAndRefundCreate() {
        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            UUID.randomUUID(), bookingId, propertyId, UUID.randomUUID(), "fingerprint-ctrl",
            UUID.randomUUID(), 1, new BigDecimal("1000000"), new BigDecimal("700000"),
            new BigDecimal("300000"), "VND", null, null, "corr-ctrl", "idemp-ctrl"
        );

        CancellationObligationResponse expected = new CancellationObligationResponse(
            obligationId, bookingId, propertyId, cmd.cancellationQuoteId(), cmd.cancellationQuoteFingerprint(),
            cmd.policyRuleId(), cmd.policyRuleVersion(), cmd.verifiedCollectedAmount(), cmd.refundableAmount(),
            cmd.forfeitedAmount(), "VND", null, null, UUID.randomUUID(), "REFUND_REQUESTED", null, null,
            cmd.sourceEventId(), "corr-ctrl", 0L, Instant.now(), Instant.now()
        );

        when(service.processCancellationExecution(cmd, actor)).thenReturn(expected);

        ResponseEntity<CancellationObligationResponse> response = controller.executeCancellation(jwt, cmd);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(expected);

        verify(authorization).require(jwt, FinanceOperationalAuthorization.CANCELLATION_ASSESS);
        verify(authorization, atLeastOnce()).requireReservationAccess(actor, bookingId, propertyId, null);
        verify(authorization).require(jwt, FinanceOperationalAuthorization.REFUND_CREATE);
    }

    @Test
    @DisplayName("Finding C: When cancel has ALL scope but refund actor scope denied for branch partition, throws FORBIDDEN")
    void execute_WhenRefundActorDeniedScope_ThrowsForbidden() {
        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            UUID.randomUUID(), bookingId, propertyId, UUID.randomUUID(), "fingerprint-ctrl",
            UUID.randomUUID(), 1, new BigDecimal("1000000"), new BigDecimal("700000"),
            new BigDecimal("300000"), "VND", null, null, "corr-ctrl", "idemp-ctrl"
        );

        var cancelActor = new FinanceOperationalAuthorization.Actor(UUID.randomUUID(), adminProfileId,
            FinanceOperationalAuthorization.CANCELLATION_ASSESS,
            new FinanceAuthorizationClient.Snapshot(true, adminProfileId, 1L, Map.of(), Map.of()));

        var refundActor = new FinanceOperationalAuthorization.Actor(UUID.randomUUID(), adminProfileId,
            FinanceOperationalAuthorization.REFUND_CREATE,
            new FinanceAuthorizationClient.Snapshot(true, adminProfileId, 1L, Map.of(), Map.of()));

        when(authorization.require(jwt, FinanceOperationalAuthorization.CANCELLATION_ASSESS)).thenReturn(cancelActor);
        when(authorization.require(jwt, FinanceOperationalAuthorization.REFUND_CREATE)).thenReturn(refundActor);

        // Cancel actor passes, but refund actor is denied scope for this property partition
        when(authorization.requireReservationAccess(cancelActor, bookingId, propertyId, null)).thenReturn(null);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Record is outside the authorized scope for finance:refund:create"))
            .when(authorization).requireReservationAccess(refundActor, bookingId, propertyId, null);

        assertThatThrownBy(() -> controller.executeCancellation(jwt, cmd))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("outside the authorized scope for finance:refund:create");

        verify(service, never()).processCancellationExecution(any(), any());
    }

    @Test
    @DisplayName("Controller: Reconcile endpoint enforces CANCELLATION_ASSESS and reservation access")
    void reconcile_EnforcesCancellationAssessAndReservationAccess() {
        CancellationObligationResponse existing = new CancellationObligationResponse(
            obligationId, bookingId, propertyId, UUID.randomUUID(), "fp",
            null, null, new BigDecimal("1000000"), new BigDecimal("700000"),
            new BigDecimal("300000"), "VND", null, null, UUID.randomUUID(), "REFUND_REQUESTED", null, null,
            UUID.randomUUID(), "corr", 0L, Instant.now(), Instant.now()
        );
        when(service.getObligationById(obligationId)).thenReturn(existing);

        ReconcileRefundOutcomeCommand cmd = new ReconcileRefundOutcomeCommand(
            obligationId, UUID.randomUUID(), true, null, null, "corr-rec"
        );

        when(service.reconcileRefundOutcome(cmd)).thenReturn(existing);

        ResponseEntity<CancellationObligationResponse> response = controller.reconcileRefundOutcome(jwt, obligationId, cmd);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(authorization).require(jwt, FinanceOperationalAuthorization.CANCELLATION_ASSESS);
        verify(authorization).requireReservationAccess(actor, bookingId, propertyId, null);
    }

    @Test
    @DisplayName("Controller: Failed refunds endpoint requires ALL scope")
    void getFailedRefunds_RequiresAllScope() {
        when(service.getFailedRefundObligations()).thenReturn(List.of());

        ResponseEntity<List<CancellationObligationResponse>> response = controller.getFailedRefunds(jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(authorization).requireAll(jwt, FinanceOperationalAuthorization.CANCELLATION_ASSESS);
    }

    @Test
    @DisplayName("Finding 3.2: When caller supplies property that contradicts authoritative booking, controller rejects with FORBIDDEN")
    void execute_WhenSuppliedPropertyContradictsAuthoritativeBooking_ThrowsForbidden() {
        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            UUID.randomUUID(), bookingId, propertyId, UUID.randomUUID(), "fingerprint-ctrl",
            UUID.randomUUID(), 1, new BigDecimal("1000000"), new BigDecimal("700000"),
            new BigDecimal("300000"), "VND", null, null, "corr-ctrl", "idemp-ctrl"
        );

        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Supplied property ID does not match authoritative reservation property"))
            .when(authorization).requireReservationAccess(actor, bookingId, propertyId, null);

        assertThatThrownBy(() -> controller.executeCancellation(jwt, cmd))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Supplied property ID does not match authoritative reservation property");

        verify(service, never()).processCancellationExecution(any(), any());
    }

    @Test
    @DisplayName("Finding 3.4: retryFailedRefund endpoint enforces CANCELLATION_ASSESS, reservation access, and invokes service")
    void retryFailedRefund_EnforcesCancellationAssessAndReservationAccess() {
        CancellationObligationResponse existing = new CancellationObligationResponse(
            obligationId, bookingId, propertyId, UUID.randomUUID(), "fp",
            null, null, new BigDecimal("1000000"), new BigDecimal("700000"),
            new BigDecimal("300000"), "VND", null, null, UUID.randomUUID(), "REFUND_FAILED", null, null,
            UUID.randomUUID(), "corr", 0L, Instant.now(), Instant.now()
        );
        when(service.getObligationById(obligationId)).thenReturn(existing);

        CancellationObligationResponse retried = new CancellationObligationResponse(
            obligationId, bookingId, propertyId, existing.cancellationQuoteId(), existing.cancellationQuoteFingerprint(),
            null, null, existing.verifiedCollectedAmount(), existing.refundableAmount(),
            existing.forfeitedAmount(), "VND", null, null, UUID.randomUUID(), "REFUND_REQUESTED", null, null,
            existing.sourceEventId(), "corr-retry", 1L, Instant.now(), Instant.now()
        );
        when(service.retryFailedRefund(obligationId, "corr-retry", actor)).thenReturn(retried);

        ResponseEntity<CancellationObligationResponse> response =
            controller.retryFailedRefund(jwt, obligationId, "corr-retry");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(retried);
        verify(authorization).require(jwt, FinanceOperationalAuthorization.CANCELLATION_ASSESS);
        verify(authorization).requireReservationAccess(actor, bookingId, propertyId, null);
        verify(service).retryFailedRefund(obligationId, "corr-retry", actor);
    }
}
