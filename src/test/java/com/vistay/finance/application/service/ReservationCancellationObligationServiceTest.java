package com.vistay.finance.application.service;

import tools.jackson.databind.ObjectMapper;
import com.vistay.finance.adapter.in.rest.FinanceOperationalAuthorization;
import com.vistay.finance.adapter.in.rest.FinanceOperationalAuthorization.Actor;
import com.vistay.finance.application.FinanceAuthorizationClient;
import com.vistay.finance.application.dto.*;
import com.vistay.finance.domain.model.*;
import com.vistay.finance.domain.repository.FinanceCommandIdempotencyRepository;
import com.vistay.finance.domain.repository.PaymentTransactionRepository;
import com.vistay.finance.domain.repository.RefundRepository;
import com.vistay.finance.domain.repository.ReservationCancellationObligationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationCancellationObligationServiceTest {

    @Mock
    private ReservationCancellationObligationRepository obligationRepository;

    @Mock
    private FinanceCommandIdempotencyRepository idempotencyRepository;

    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;

    @Mock
    private RefundRepository refundRepository;

    @Mock
    private FinanceOperationalAuthorization authorization;

    @Mock
    private JdbcClient jdbc;

    @Mock
    private JdbcClient.StatementSpec statementSpec;

    private ReservationCancellationObligationService service;
    private final UUID bookingId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();
    private final UUID quoteId = UUID.randomUUID();
    private final UUID sourceEventId = UUID.randomUUID();
    private final UUID adminProfileId = UUID.randomUUID();
    private final Actor actor = new Actor(UUID.randomUUID(), adminProfileId, "finance:cancellation:assess",
        new FinanceAuthorizationClient.Snapshot(true, adminProfileId, 1L, Map.of(), Map.of()));

    @BeforeEach
    void setUp() {
        mockJdbcOutboxAndProcessedEvents();
        service = new ReservationCancellationObligationService(
            obligationRepository,
            idempotencyRepository,
            paymentTransactionRepository,
            refundRepository,
            authorization,
            jdbc,
            new ObjectMapper()
        );
    }

    private void mockJdbcOutboxAndProcessedEvents() {
        lenient().when(jdbc.sql(anyString())).thenReturn(statementSpec);
        lenient().when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        lenient().when(statementSpec.update()).thenReturn(1);
    }

    private void mockLedgerVerifiedAdvance(BigDecimal amount) {
        PaymentTransaction tx = mock(PaymentTransaction.class);
        lenient().when(tx.getId()).thenReturn(UUID.randomUUID());
        lenient().when(tx.getTransactionType()).thenReturn(TransactionType.PAYMENT);
        lenient().when(tx.getStatus()).thenReturn(TransactionStatus.SUCCEEDED);
        lenient().when(tx.getAmount()).thenReturn(amount);
        lenient().when(tx.getCurrency()).thenReturn("VND");
        lenient().when(paymentTransactionRepository.findByBookingIdOrderByOccurredAtDesc(bookingId))
            .thenReturn(List.of(tx));
        lenient().when(refundRepository.findByBookingIdOrderByRequestedAtDesc(bookingId))
            .thenReturn(List.of());
    }

    // ==========================================
    // PREVIEW TESTS
    // ==========================================

    @Test
    @DisplayName("Preview: Returns verified collected advance and snapshot hash for authorized caller")
    void preview_Authorized_ReturnsVerifiedAdvance() {
        BigDecimal paymentAmount = new BigDecimal("2000000.0000");
        mockLedgerVerifiedAdvance(paymentAmount);

        CancellationPreviewResponse response = service.getVerifiedCollectedAdvanceForCancellation(bookingId, actor);

        assertThat(response).isNotNull();
        assertThat(response.bookingId()).isEqualTo(bookingId);
        assertThat(response.verifiedCollectedAmount()).isEqualByComparingTo(paymentAmount);
        assertThat(response.currency()).isEqualTo("VND");
        assertThat(response.calculationSnapshotHash()).isNotBlank();
        assertThat(response.transactionCount()).isEqualTo(1);
        verify(authorization).requireBooking(actor, bookingId);
    }

    @Test
    @DisplayName("Preview: Fails closed when pending payments exist")
    void preview_PendingPaymentsPresent_FailsClosed() {
        PaymentTransaction pendingTx = mock(PaymentTransaction.class);
        when(pendingTx.getTransactionType()).thenReturn(TransactionType.PAYMENT);
        when(pendingTx.getStatus()).thenReturn(TransactionStatus.PENDING);
        when(paymentTransactionRepository.findByBookingIdOrderByOccurredAtDesc(bookingId))
            .thenReturn(List.of(pendingTx));

        assertThatThrownBy(() -> service.getVerifiedCollectedAdvanceForCancellation(bookingId, actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("UNVERIFIED_PAYMENTS_PRESENT");
    }

    // ==========================================
    // AC01: HAPPY PATH EXECUTION
    // ==========================================

    @Test
    @DisplayName("AC01: Valid cancellation quote execution creates 1 obligation and 1 refund request")
    void executeCancellation_ValidQuote_CreatesBalancedObligationAndRefund() {
        mockJdbcOutboxAndProcessedEvents();
        BigDecimal verified = new BigDecimal("2000000.0000");
        BigDecimal refundable = new BigDecimal("1400000.0000");
        BigDecimal forfeited = new BigDecimal("600000.0000");
        mockLedgerVerifiedAdvance(verified);

        when(idempotencyRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(obligationRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(obligationRepository.save(any(ReservationCancellationObligation.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));

        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            sourceEventId, bookingId, propertyId, quoteId, "fingerprint-abc-123",
            UUID.randomUUID(), 1, verified, refundable, forfeited, "VND",
            null, null, "corr-101", "idemp-cancel-001"
        );

        CancellationObligationResponse response = service.processCancellationExecution(cmd, actor);

        assertThat(response).isNotNull();
        assertThat(response.bookingId()).isEqualTo(bookingId);
        assertThat(response.verifiedCollectedAmount()).isEqualByComparingTo(verified);
        assertThat(response.refundableAmount()).isEqualByComparingTo(refundable);
        assertThat(response.forfeitedAmount()).isEqualByComparingTo(forfeited);
        assertThat(response.status()).isEqualTo(CancellationObligationStatus.REFUND_REQUESTED.name());
        assertThat(response.refundId()).isNotNull();

        verify(refundRepository).save(any(Refund.class));
        verify(obligationRepository).save(any(ReservationCancellationObligation.class));
        verify(idempotencyRepository, atLeastOnce()).save(argThat(i -> i.getStatus() == CommandIdempotencyStatus.SUCCEEDED));
    }

    @Test
    @DisplayName("AC01: Cancellation with 0 refundable creates obligation with NO_REFUND and zero refund requests")
    void executeCancellation_ZeroRefundable_CreatesNoRefundObligation() {
        mockJdbcOutboxAndProcessedEvents();
        BigDecimal verified = new BigDecimal("1000000.0000");
        BigDecimal refundable = BigDecimal.ZERO;
        BigDecimal forfeited = new BigDecimal("1000000.0000");
        mockLedgerVerifiedAdvance(verified);

        when(idempotencyRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(obligationRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(obligationRepository.save(any(ReservationCancellationObligation.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            sourceEventId, bookingId, propertyId, quoteId, "fingerprint-000",
            UUID.randomUUID(), 1, verified, refundable, forfeited, "VND",
            null, null, "corr-102", "idemp-cancel-002"
        );

        CancellationObligationResponse response = service.processCancellationExecution(cmd, actor);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(CancellationObligationStatus.NO_REFUND.name());
        assertThat(response.refundId()).isNull();
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC01: 100% special refund with valid reason code succeeds")
    void executeCancellation_SpecialRefund100Percent_Succeeds() {
        mockJdbcOutboxAndProcessedEvents();
        BigDecimal verified = new BigDecimal("1500000.0000");
        BigDecimal refundable = new BigDecimal("1500000.0000");
        BigDecimal forfeited = BigDecimal.ZERO;
        mockLedgerVerifiedAdvance(verified);

        when(idempotencyRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(obligationRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(obligationRepository.save(any(ReservationCancellationObligation.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));

        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            sourceEventId, bookingId, propertyId, quoteId, "fingerprint-special-100",
            UUID.randomUUID(), 1, verified, refundable, forfeited, "VND",
            "HOST_EMERGENCY_OVERBOOKED", "EVIDENCE_DOC_999", "corr-103", "idemp-cancel-003"
        );

        CancellationObligationResponse response = service.processCancellationExecution(cmd, actor);

        assertThat(response).isNotNull();
        assertThat(response.specialRefundReasonCode()).isEqualTo("HOST_EMERGENCY_OVERBOOKED");
        assertThat(response.refundableAmount()).isEqualByComparingTo(verified);
        assertThat(response.forfeitedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ==========================================
    // AC02: FAIL-CLOSED & INVARIANT ENFORCEMENT
    // ==========================================

    @Test
    @DisplayName("AC02: Balance invariant violation (refundable + forfeited != verifiedCollected) fails closed")
    void executeCancellation_Imbalance_FailsClosed() {
        BigDecimal verified = new BigDecimal("2000000.0000");
        BigDecimal refundable = new BigDecimal("1000000.0000");
        BigDecimal forfeited = new BigDecimal("500000.0000"); // Sum is 1.5M != 2M!

        when(idempotencyRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            sourceEventId, bookingId, propertyId, quoteId, "fingerprint-bad-balance",
            UUID.randomUUID(), 1, verified, refundable, forfeited, "VND",
            null, null, "corr-104", "idemp-cancel-004"
        );

        assertThatThrownBy(() -> service.processCancellationExecution(cmd, actor))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("BALANCE_INVARIANT_VIOLATED");

        verify(obligationRepository, never()).save(any());
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC02: Verified collection amount mismatch between quote and Finance ledger fails closed")
    void executeCancellation_LedgerMismatch_FailsClosed() {
        BigDecimal quoteVerified = new BigDecimal("2000000.0000");
        BigDecimal ledgerVerified = new BigDecimal("1500000.0000"); // Ledger has only 1.5M!
        mockLedgerVerifiedAdvance(ledgerVerified);

        when(idempotencyRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            sourceEventId, bookingId, propertyId, quoteId, "fingerprint-ledger-diff",
            UUID.randomUUID(), 1, quoteVerified, new BigDecimal("1500000.0000"), new BigDecimal("500000.0000"),
            "VND", null, null, "corr-105", "idemp-cancel-005"
        );

        assertThatThrownBy(() -> service.processCancellationExecution(cmd, actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("VERIFIED_COLLECTION_MISMATCH");

        verify(obligationRepository, never()).save(any());
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC02: 100% special refund missing reason code fails closed")
    void executeCancellation_SpecialRefundMissingReason_FailsClosed() {
        BigDecimal verified = new BigDecimal("2000000.0000");
        mockLedgerVerifiedAdvance(verified);

        when(idempotencyRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            sourceEventId, bookingId, propertyId, quoteId, "fingerprint-no-reason",
            UUID.randomUUID(), 1, verified, verified, BigDecimal.ZERO, "VND",
            null, null, "corr-106", "idemp-cancel-006"
        );

        assertThatThrownBy(() -> service.processCancellationExecution(cmd, actor))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SPECIAL_REFUND_REASON_REQUIRED");
    }

    @Test
    @DisplayName("AC02: Idempotency key reused with different payload fails closed with IDEMPOTENCY_KEY_REUSED")
    void executeCancellation_IdempotencyReusedWithDifferentPayload_FailsClosed() {
        FinanceCommandIdempotency existing = new FinanceCommandIdempotency(
            "idemp-key-shared", "EXECUTE_CANCELLATION", "ADMIN", adminProfileId,
            "hash-original-different", Instant.now().plusSeconds(3600)
        );
        existing.markSucceeded("ReservationCancellationObligation", UUID.randomUUID(), "{}");

        when(idempotencyRepository.findByIdempotencyKey("idemp-key-shared")).thenReturn(Optional.of(existing));

        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            sourceEventId, bookingId, propertyId, quoteId, "fingerprint-diff",
            UUID.randomUUID(), 1, new BigDecimal("1000000"), new BigDecimal("500000"), new BigDecimal("500000"),
            "VND", null, null, "corr-107", "idemp-key-shared"
        );

        assertThatThrownBy(() -> service.processCancellationExecution(cmd, actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("IDEMPOTENCY_KEY_REUSED");
    }

    // ==========================================
    // AC03: REPLAY, REPAIR & RECONCILIATION
    // ==========================================

    @Test
    @DisplayName("AC03: Idempotent replay with identical payload returns stored obligation without side effects")
    void executeCancellation_IdempotentReplay_ReturnsStoredObligation() {
        BigDecimal verified = new BigDecimal("1000000.0000");
        BigDecimal refundable = new BigDecimal("700000.0000");
        BigDecimal forfeited = new BigDecimal("300000.0000");

        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            sourceEventId, bookingId, propertyId, quoteId, "fingerprint-exact",
            UUID.randomUUID(), 1, verified, refundable, forfeited, "VND",
            null, null, "corr-108", "idemp-exact-replay"
        );

        // Precompute matching hash
        String expectedHash = "CANCEL_CMD:" + bookingId + ":" + quoteId + ":fingerprint-exact:"
            + verified + ":" + refundable + ":" + forfeited;
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(expectedHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            expectedHash = sb.toString();
        } catch (Exception ignored) {}

        FinanceCommandIdempotency existing = new FinanceCommandIdempotency(
            "idemp-exact-replay", "EXECUTE_CANCELLATION", "ADMIN", adminProfileId,
            expectedHash, Instant.now().plusSeconds(3600)
        );
        existing.markSucceeded("ReservationCancellationObligation", UUID.randomUUID(), "{}");
        when(idempotencyRepository.findByIdempotencyKey("idemp-exact-replay")).thenReturn(Optional.of(existing));

        ReservationCancellationObligation storedObligation = new ReservationCancellationObligation(
            UUID.randomUUID(), bookingId, propertyId, quoteId, "fingerprint-exact",
            UUID.randomUUID(), 1, verified, refundable, forfeited, "VND",
            null, null, sourceEventId, "corr-108"
        );
        when(obligationRepository.findByCancellationQuoteId(quoteId)).thenReturn(Optional.of(storedObligation));

        CancellationObligationResponse response = service.processCancellationExecution(cmd, actor);

        assertThat(response).isNotNull();
        assertThat(response.bookingId()).isEqualTo(bookingId);
        // Assert no new writes
        verify(obligationRepository, never()).save(any());
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC03: Provider refund failure transitions obligation to REFUND_FAILED and never reactivates booking")
    void reconcileRefundOutcome_Failure_SetsRefundFailedWithoutAffectingBooking() {
        mockJdbcOutboxAndProcessedEvents();
        UUID obligationId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        ReservationCancellationObligation obligation = new ReservationCancellationObligation(
            obligationId, bookingId, propertyId, quoteId, "fingerprint-fail-test",
            UUID.randomUUID(), 1, new BigDecimal("1000000"), new BigDecimal("700000"), new BigDecimal("300000"),
            "VND", null, null, sourceEventId, "corr-109"
        );
        obligation.markRefundRequested(refundId);

        when(obligationRepository.findById(obligationId)).thenReturn(Optional.of(obligation));
        when(obligationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Refund refund = mock(Refund.class);
        when(refundRepository.findById(refundId)).thenReturn(Optional.of(refund));

        ReconcileRefundOutcomeCommand reconcileCmd = new ReconcileRefundOutcomeCommand(
            obligationId, refundId, false, "INSUFFICIENT_MERCHANT_FUNDS",
            "Gateway error: card 4111-1111-1111-1111 declined", "corr-109"
        );

        CancellationObligationResponse result = service.reconcileRefundOutcome(reconcileCmd);

        assertThat(result.status()).isEqualTo(CancellationObligationStatus.REFUND_FAILED.name());
        assertThat(result.failureCode()).isEqualTo("INSUFFICIENT_MERCHANT_FUNDS");
        // Ensure sensitive card info is sanitized
        assertThat(result.failureMessage()).doesNotContain("4111-1111-1111-1111");
        assertThat(result.failureMessage()).contains("[REDACTED]");

        verify(refund).markFailed();
        verify(obligationRepository).save(argThat(o -> o.getStatus() == CancellationObligationStatus.REFUND_FAILED));
    }

    @Test
    @DisplayName("AC03: Provider refund success transitions obligation to REFUNDED")
    void reconcileRefundOutcome_Success_SetsRefunded() {
        mockJdbcOutboxAndProcessedEvents();
        UUID obligationId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        ReservationCancellationObligation obligation = new ReservationCancellationObligation(
            obligationId, bookingId, propertyId, quoteId, "fingerprint-success-test",
            UUID.randomUUID(), 1, new BigDecimal("1000000"), new BigDecimal("700000"), new BigDecimal("300000"),
            "VND", null, null, sourceEventId, "corr-110"
        );
        obligation.markRefundRequested(refundId);

        when(obligationRepository.findById(obligationId)).thenReturn(Optional.of(obligation));
        when(obligationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Refund refund = mock(Refund.class);
        when(refundRepository.findById(refundId)).thenReturn(Optional.of(refund));

        ReconcileRefundOutcomeCommand reconcileCmd = new ReconcileRefundOutcomeCommand(
            obligationId, refundId, true, null, null, "corr-110"
        );

        CancellationObligationResponse result = service.reconcileRefundOutcome(reconcileCmd);

        assertThat(result.status()).isEqualTo(CancellationObligationStatus.REFUNDED.name());
        verify(refund).markCompleted(any());
    }
}
