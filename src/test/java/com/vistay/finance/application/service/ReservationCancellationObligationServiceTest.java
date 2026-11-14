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
import com.vistay.finance.domain.repository.RuleCatalogueRepository;
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

    private static final String VALID_FINGERPRINT = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Mock
    private ReservationCancellationObligationRepository obligationRepository;

    @Mock
    private FinanceCommandIdempotencyRepository idempotencyRepository;

    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;

    @Mock
    private RefundRepository refundRepository;

    @Mock
    private RuleCatalogueRepository ruleCatalogueRepository;

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
        RuleCatalogue mockRule = mock(RuleCatalogue.class);
        lenient().when(mockRule.getStatus()).thenReturn(RuleStatus.ACTIVE);
        lenient().when(mockRule.getRuleVersion()).thenReturn(1);
        lenient().when(ruleCatalogueRepository.findById(any())).thenReturn(Optional.of(mockRule));
        lenient().when(ruleCatalogueRepository.existsById(any())).thenReturn(true);
        service = new ReservationCancellationObligationService(
            obligationRepository,
            idempotencyRepository,
            paymentTransactionRepository,
            refundRepository,
            ruleCatalogueRepository,
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
        verify(authorization).requireReservationAccess(actor, bookingId, null, null);
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
            sourceEventId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
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
        verify(idempotencyRepository, atLeastOnce()).save(argThat(FinanceCommandIdempotency::isSucceeded));
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
            sourceEventId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
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
    @DisplayName("AC01: 100% special refund with valid reason code and evidence ref succeeds")
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
            sourceEventId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
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
            sourceEventId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
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
            sourceEventId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
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
    @DisplayName("AC02: Policy rule not found in catalogue fails closed")
    void executeCancellation_PolicyRuleNotFound_FailsClosed() {
        BigDecimal verified = new BigDecimal("2000000.0000");
        mockLedgerVerifiedAdvance(verified);

        when(idempotencyRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(ruleCatalogueRepository.findById(any())).thenReturn(Optional.empty());

        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            sourceEventId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 1, verified, new BigDecimal("1400000.0000"), new BigDecimal("600000.0000"),
            "VND", null, null, "corr-rule-404", "idemp-rule-404"
        );

        assertThatThrownBy(() -> service.processCancellationExecution(cmd, actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("POLICY_RULE_NOT_FOUND");
    }

    @Test
    @DisplayName("AC02: Non-hex or invalid length quote fingerprint fails closed")
    void executeCancellation_InvalidFingerprint_FailsClosed() {
        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            sourceEventId, bookingId, propertyId, quoteId, "invalid-short-fp",
            UUID.randomUUID(), 1, new BigDecimal("1000000"), new BigDecimal("500000"), new BigDecimal("500000"),
            "VND", null, null, "corr-invalid-fp", "idemp-invalid-fp"
        );

        assertThatThrownBy(() -> service.processCancellationExecution(cmd, actor))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("INVALID_QUOTE_FINGERPRINT");
    }

    @Test
    @DisplayName("AC02: Currency mismatch between command and ledger fails closed")
    void executeCancellation_CurrencyMismatch_FailsClosed() {
        BigDecimal verified = new BigDecimal("1000000.0000");
        mockLedgerVerifiedAdvance(verified); // ledger is VND

        when(idempotencyRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            sourceEventId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 1, verified, new BigDecimal("600000.0000"), new BigDecimal("400000.0000"),
            "USD", null, null, "corr-curr-diff", "idemp-curr-diff"
        );

        assertThatThrownBy(() -> service.processCancellationExecution(cmd, actor))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CURRENCY_MISMATCH");
    }

    @Test
    @DisplayName("AC02: 100% special refund missing reason code fails closed")
    void executeCancellation_SpecialRefundMissingReason_FailsClosed() {
        BigDecimal verified = new BigDecimal("2000000.0000");
        mockLedgerVerifiedAdvance(verified);

        when(idempotencyRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            sourceEventId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 1, verified, verified, BigDecimal.ZERO, "VND",
            null, null, "corr-106", "idemp-cancel-006"
        );

        assertThatThrownBy(() -> service.processCancellationExecution(cmd, actor))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SPECIAL_REFUND_REASON_REQUIRED");
    }

    @Test
    @DisplayName("AC02: 100% special refund missing evidence ref fails closed")
    void executeCancellation_SpecialRefundMissingEvidence_FailsClosed() {
        BigDecimal verified = new BigDecimal("2000000.0000");
        mockLedgerVerifiedAdvance(verified);

        when(idempotencyRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            sourceEventId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 1, verified, verified, BigDecimal.ZERO, "VND",
            "HOST_EMERGENCY", "   ", "corr-no-ev", "idemp-no-ev"
        );

        assertThatThrownBy(() -> service.processCancellationExecution(cmd, actor))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SPECIAL_REFUND_EVIDENCE_REQUIRED");
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
            sourceEventId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
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
            sourceEventId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 1, verified, refundable, forfeited, "VND",
            null, null, "corr-108", "idemp-exact-replay"
        );

        // Precompute matching hash
        String expectedHash = "CANCEL_CMD:" + bookingId + ":" + quoteId + ":" + VALID_FINGERPRINT + ":"
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
            UUID.randomUUID(), bookingId, propertyId, quoteId, VALID_FINGERPRINT,
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
    @DisplayName("Finding F: Source event replay completes idempotency key instead of leaving it in PROCESSING")
    void executeCancellation_SourceEventReplay_CompletesIdempotencyKey() {
        BigDecimal verified = new BigDecimal("1000000.0000");
        BigDecimal refundable = new BigDecimal("700000.0000");
        BigDecimal forfeited = new BigDecimal("300000.0000");

        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            sourceEventId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 1, verified, refundable, forfeited, "VND",
            null, null, "corr-dedup", "idemp-key-new"
        );

        when(idempotencyRepository.findByIdempotencyKey("idemp-key-new")).thenReturn(Optional.empty());

        // Simulate processed_events conflict (event already processed)
        when(jdbc.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.update()).thenReturn(0);

        ReservationCancellationObligation existingObligation = new ReservationCancellationObligation(
            UUID.randomUUID(), bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 1, verified, refundable, forfeited, "VND",
            null, null, sourceEventId, "corr-dedup"
        );
        when(obligationRepository.findBySourceEventId(sourceEventId)).thenReturn(Optional.of(existingObligation));

        CancellationObligationResponse response = service.processCancellationExecution(cmd, actor);

        assertThat(response).isNotNull();
        assertThat(response.bookingId()).isEqualTo(bookingId);

        // Verify that the new idempotency key was marked SUCCEEDED with existing obligation snapshot
        ArgumentCaptor<FinanceCommandIdempotency> captor = ArgumentCaptor.forClass(FinanceCommandIdempotency.class);
        verify(idempotencyRepository, atLeast(2)).save(captor.capture());
        FinanceCommandIdempotency lastSaved = captor.getValue();
        assertThat(lastSaved.isSucceeded()).isTrue();
        assertThat(lastSaved.getResourceId()).isEqualTo(existingObligation.getId());
    }

    @Test
    @DisplayName("Finding F: Source event replay with differing payload fails closed with EVENT_PAYLOAD_MISMATCH")
    void executeCancellation_SourceEventReplayWithDifferentPayload_FailsClosed() {
        BigDecimal verified = new BigDecimal("1000000.0000");
        BigDecimal refundable = new BigDecimal("700000.0000");
        BigDecimal forfeited = new BigDecimal("300000.0000");

        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            sourceEventId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 1, verified, refundable, forfeited, "VND",
            null, null, "corr-mismatch", "idemp-key-mismatch"
        );

        when(idempotencyRepository.findByIdempotencyKey("idemp-key-mismatch")).thenReturn(Optional.empty());

        when(jdbc.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.update()).thenReturn(0);

        // Existing obligation had differing refundable amount (500000 instead of 700000)
        ReservationCancellationObligation existingObligation = new ReservationCancellationObligation(
            UUID.randomUUID(), bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 1, verified, new BigDecimal("500000.0000"), new BigDecimal("500000.0000"),
            "VND", null, null, sourceEventId, "corr-mismatch"
        );
        when(obligationRepository.findBySourceEventId(sourceEventId)).thenReturn(Optional.of(existingObligation));

        assertThatThrownBy(() -> service.processCancellationExecution(cmd, actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EVENT_PAYLOAD_MISMATCH");

        verify(idempotencyRepository, atLeastOnce()).save(argThat(FinanceCommandIdempotency::isFailed));
    }

    @Test
    @DisplayName("Finding A & AC03: Provider refund success creates real PaymentTransaction and marks refund completed")
    void reconcileRefundOutcome_Success_CreatesRealPaymentTransactionAndCompletesRefund() {
        mockJdbcOutboxAndProcessedEvents();
        UUID obligationId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        UUID originalTxId = UUID.randomUUID();

        ReservationCancellationObligation obligation = new ReservationCancellationObligation(
            obligationId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 1, new BigDecimal("1000000"), new BigDecimal("700000"), new BigDecimal("300000"),
            "VND", null, null, sourceEventId, "corr-110"
        );
        obligation.markRefundRequested(refundId);

        when(obligationRepository.findById(obligationId)).thenReturn(Optional.of(obligation));
        when(obligationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Refund refund = new Refund(
            refundId, "RFD-TEST", bookingId, originalTxId, new BigDecimal("700000"),
            "VND", "Reason", adminProfileId, "idemp-ref"
        );
        when(refundRepository.findById(refundId)).thenReturn(Optional.of(refund));

        when(paymentTransactionRepository.findByPaymentProviderAndExternalTransactionId(any(), any())).thenReturn(Optional.empty());
        when(paymentTransactionRepository.save(any(PaymentTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        ReconcileRefundOutcomeCommand reconcileCmd = new ReconcileRefundOutcomeCommand(
            obligationId, refundId, true, null, null, "VIETQR", "ext-vietqr-999", "corr-110"
        );

        CancellationObligationResponse result = service.reconcileRefundOutcome(reconcileCmd);

        assertThat(result.status()).isEqualTo(CancellationObligationStatus.REFUNDED.name());

        // Verify PaymentTransaction created with real data and linked to original transaction
        ArgumentCaptor<PaymentTransaction> txCaptor = ArgumentCaptor.forClass(PaymentTransaction.class);
        verify(paymentTransactionRepository).save(txCaptor.capture());
        PaymentTransaction savedTx = txCaptor.getValue();
        assertThat(savedTx.getTransactionType()).isEqualTo(TransactionType.REFUND);
        assertThat(savedTx.getStatus()).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(savedTx.getAmount()).isEqualByComparingTo(new BigDecimal("700000"));
        assertThat(savedTx.getOriginalTransactionId()).isEqualTo(originalTxId);
        assertThat(savedTx.getPaymentProvider()).isEqualTo("VIETQR");
        assertThat(savedTx.getExternalTransactionId()).isEqualTo("ext-vietqr-999");

        // Verify Refund completed with savedTx.getId()
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refund.getRefundTransactionId()).isEqualTo(savedTx.getId());
    }

    @Test
    @DisplayName("Finding E: Callback with mismatched refundId fails closed with REFUND_IDENTITY_MISMATCH")
    void reconcileRefundOutcome_MismatchedRefundId_FailsClosed() {
        UUID obligationId = UUID.randomUUID();
        UUID actualRefundId = UUID.randomUUID();
        UUID wrongRefundId = UUID.randomUUID();

        ReservationCancellationObligation obligation = new ReservationCancellationObligation(
            obligationId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 1, new BigDecimal("1000000"), new BigDecimal("700000"), new BigDecimal("300000"),
            "VND", null, null, sourceEventId, "corr-mismatch"
        );
        obligation.markRefundRequested(actualRefundId);

        when(obligationRepository.findById(obligationId)).thenReturn(Optional.of(obligation));

        ReconcileRefundOutcomeCommand reconcileCmd = new ReconcileRefundOutcomeCommand(
            obligationId, wrongRefundId, true, null, null, "corr-mismatch"
        );

        assertThatThrownBy(() -> service.reconcileRefundOutcome(reconcileCmd))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("REFUND_IDENTITY_MISMATCH");
    }

    @Test
    @DisplayName("Finding E: Idempotent replay of already REFUNDED obligation returns result without recreating transaction")
    void reconcileRefundOutcome_AlreadyRefunded_ReturnsExistingWithoutSideEffects() {
        UUID obligationId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        ReservationCancellationObligation obligation = new ReservationCancellationObligation(
            obligationId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 1, new BigDecimal("1000000"), new BigDecimal("700000"), new BigDecimal("300000"),
            "VND", null, null, sourceEventId, "corr-replay"
        );
        obligation.markRefundRequested(refundId);
        obligation.markRefunded();

        when(obligationRepository.findById(obligationId)).thenReturn(Optional.of(obligation));

        ReconcileRefundOutcomeCommand reconcileCmd = new ReconcileRefundOutcomeCommand(
            obligationId, refundId, true, null, null, "corr-replay"
        );

        CancellationObligationResponse result = service.reconcileRefundOutcome(reconcileCmd);

        assertThat(result.status()).isEqualTo(CancellationObligationStatus.REFUNDED.name());
        verify(paymentTransactionRepository, never()).save(any());
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC03: Provider refund failure transitions obligation to REFUND_FAILED and never reactivates booking")
    void reconcileRefundOutcome_Failure_SetsRefundFailedWithoutAffectingBooking() {
        mockJdbcOutboxAndProcessedEvents();
        UUID obligationId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        ReservationCancellationObligation obligation = new ReservationCancellationObligation(
            obligationId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
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
    @DisplayName("Finding 3.4: Reconcile success missing paymentProvider or externalTransactionId fails closed")
    void reconcileRefundOutcome_MissingProviderEvidence_FailsClosed() {
        UUID obligationId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        ReservationCancellationObligation obligation = new ReservationCancellationObligation(
            obligationId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 1, new BigDecimal("1000000"), new BigDecimal("700000"), new BigDecimal("300000"),
            "VND", null, null, sourceEventId, "corr-missing-ev"
        );
        obligation.markRefundRequested(refundId);

        when(obligationRepository.findById(obligationId)).thenReturn(Optional.of(obligation));

        // Missing paymentProvider
        ReconcileRefundOutcomeCommand cmdNoProvider = new ReconcileRefundOutcomeCommand(
            obligationId, refundId, true, null, null, null, "ext-123", "corr-missing-ev"
        );
        assertThatThrownBy(() -> service.reconcileRefundOutcome(cmdNoProvider))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PROVIDER_EVIDENCE_REQUIRED");

        // Missing externalTransactionId
        ReconcileRefundOutcomeCommand cmdNoExtId = new ReconcileRefundOutcomeCommand(
            obligationId, refundId, true, null, null, "VIETQR", "   ", "corr-missing-ev"
        );
        assertThatThrownBy(() -> service.reconcileRefundOutcome(cmdNoExtId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PROVIDER_EVIDENCE_REQUIRED");
    }

    @Test
    @DisplayName("Finding 3.4: Reconcile with mismatched amount or currency fails closed")
    void reconcileRefundOutcome_AmountOrCurrencyMismatch_FailsClosed() {
        UUID obligationId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        ReservationCancellationObligation obligation = new ReservationCancellationObligation(
            obligationId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 1, new BigDecimal("1000000"), new BigDecimal("700000"), new BigDecimal("300000"),
            "VND", null, null, sourceEventId, "corr-mismatch"
        );
        obligation.markRefundRequested(refundId);

        when(obligationRepository.findById(obligationId)).thenReturn(Optional.of(obligation));

        // Mismatched amount
        ReconcileRefundOutcomeCommand cmdWrongAmount = new ReconcileRefundOutcomeCommand(
            obligationId, refundId, true, new BigDecimal("999999"), "VND",
            null, null, "VIETQR", "ext-123", "corr-mismatch"
        );
        assertThatThrownBy(() -> service.reconcileRefundOutcome(cmdWrongAmount))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("REFUND_AMOUNT_MISMATCH");

        // Mismatched currency
        ReconcileRefundOutcomeCommand cmdWrongCurrency = new ReconcileRefundOutcomeCommand(
            obligationId, refundId, true, new BigDecimal("700000"), "USD",
            null, null, "VIETQR", "ext-123", "corr-mismatch"
        );
        assertThatThrownBy(() -> service.reconcileRefundOutcome(cmdWrongCurrency))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("REFUND_CURRENCY_MISMATCH");
    }

    @Test
    @DisplayName("Finding 3.4: Duplicate replay with conflicting externalTransactionId fails closed with RECONCILIATION_CONFLICT")
    void reconcileRefundOutcome_ReconciliationConflict_FailsClosed() {
        UUID obligationId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();

        ReservationCancellationObligation obligation = new ReservationCancellationObligation(
            obligationId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 1, new BigDecimal("1000000"), new BigDecimal("700000"), new BigDecimal("300000"),
            "VND", null, null, sourceEventId, "corr-conflict"
        );
        obligation.markRefundRequested(refundId);
        obligation.markRefunded();

        when(obligationRepository.findById(obligationId)).thenReturn(Optional.of(obligation));

        Refund refund = mock(Refund.class);
        when(refund.getRefundTransactionId()).thenReturn(txId);
        when(refundRepository.findById(refundId)).thenReturn(Optional.of(refund));

        PaymentTransaction existingTx = mock(PaymentTransaction.class);
        when(existingTx.getExternalTransactionId()).thenReturn("ext-original-123");
        when(paymentTransactionRepository.findById(txId)).thenReturn(Optional.of(existingTx));

        // Replay with different externalTransactionId
        ReconcileRefundOutcomeCommand cmdConflicting = new ReconcileRefundOutcomeCommand(
            obligationId, refundId, true, new BigDecimal("700000"), "VND",
            null, null, "VIETQR", "ext-conflicting-999", "corr-conflict"
        );

        assertThatThrownBy(() -> service.reconcileRefundOutcome(cmdConflicting))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("RECONCILIATION_CONFLICT");
    }

    @Test
    @DisplayName("Finding 3.4: Operator retry on REFUND_FAILED transitions to REFUND_REQUESTED and emits retry outbox event")
    void retryFailedRefund_Success_TransitionsToRefundRequestedAndDispatchesOutbox() {
        mockJdbcOutboxAndProcessedEvents();
        UUID obligationId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        ReservationCancellationObligation obligation = new ReservationCancellationObligation(
            obligationId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 1, new BigDecimal("1000000"), new BigDecimal("700000"), new BigDecimal("300000"),
            "VND", null, null, sourceEventId, "corr-retry"
        );
        obligation.markRefundRequested(refundId);
        obligation.markRefundFailed("PROVIDER_TIMEOUT", "Gateway timed out");

        when(obligationRepository.findById(obligationId)).thenReturn(Optional.of(obligation));
        when(obligationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Refund refund = mock(Refund.class);
        when(refundRepository.findById(refundId)).thenReturn(Optional.of(refund));
        when(refundRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CancellationObligationResponse response = service.retryFailedRefund(obligationId, "corr-retry-op", actor);

        assertThat(response.status()).isEqualTo(CancellationObligationStatus.REFUND_REQUESTED.name());
        verify(refund).retry();
        verify(refundRepository).save(refund);
        verify(obligationRepository).save(argThat(o -> o.getStatus() == CancellationObligationStatus.REFUND_REQUESTED));
    }

    @Test
    @DisplayName("Finding 3.4: Operator retry on non-failed obligation fails closed with CANNOT_RETRY_REFUND")
    void retryFailedRefund_NotFailed_FailsClosed() {
        UUID obligationId = UUID.randomUUID();

        ReservationCancellationObligation obligation = new ReservationCancellationObligation(
            obligationId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 1, new BigDecimal("1000000"), new BigDecimal("700000"), new BigDecimal("300000"),
            "VND", null, null, sourceEventId, "corr-retry"
        );
        // Status is PENDING
        when(obligationRepository.findById(obligationId)).thenReturn(Optional.of(obligation));

        assertThatThrownBy(() -> service.retryFailedRefund(obligationId, "corr-retry", actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CANNOT_RETRY_REFUND");
    }

    @Test
    @DisplayName("Finding 3.3: Cancellation with non-existent or inactive policy rule version fails closed")
    void executeCancellation_PolicyRuleVersionMismatch_FailsClosed() {
        BigDecimal verified = new BigDecimal("1000000.0000");
        mockLedgerVerifiedAdvance(verified);

        when(idempotencyRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(ruleCatalogueRepository.findById(any())).thenReturn(Optional.empty());

        ExecuteCancellationCommand cmd = new ExecuteCancellationCommand(
            sourceEventId, bookingId, propertyId, quoteId, VALID_FINGERPRINT,
            UUID.randomUUID(), 99, verified, new BigDecimal("700000"), new BigDecimal("300000"), "VND",
            null, null, "corr-rule-fail", "idemp-rule-fail"
        );

        assertThatThrownBy(() -> service.processCancellationExecution(cmd, actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("POLICY_RULE_NOT_FOUND");
    }
}
