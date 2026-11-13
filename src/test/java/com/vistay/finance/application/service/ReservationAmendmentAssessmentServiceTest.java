package com.vistay.finance.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vistay.finance.application.dto.AmendmentProposalSnapshot;
import com.vistay.finance.application.dto.ApproveAmendmentWaiverCommand;
import com.vistay.finance.application.dto.AssessReservationAmendmentCommand;
import com.vistay.finance.application.dto.ReservationAmendmentAssessmentResponse;
import com.vistay.finance.application.dto.ReservationAmendmentExecutedCommand;
import com.vistay.finance.domain.model.AmendmentAssessmentStatus;
import com.vistay.finance.domain.model.AmendmentRequiredAction;
import com.vistay.finance.domain.model.PaymentTransaction;
import com.vistay.finance.domain.model.Refund;
import com.vistay.finance.domain.model.ReservationAmendmentAssessment;
import com.vistay.finance.domain.model.ReservationFieldGroup;
import com.vistay.finance.domain.model.RuleCatalogue;
import com.vistay.finance.domain.model.RuleType;
import com.vistay.finance.domain.model.TransactionStatus;
import com.vistay.finance.domain.model.TransactionType;
import com.vistay.finance.domain.repository.PaymentTransactionRepository;
import com.vistay.finance.domain.repository.RefundRepository;
import com.vistay.finance.domain.repository.ReservationAmendmentAssessmentRepository;
import com.vistay.finance.domain.repository.RuleCatalogueRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class ReservationAmendmentAssessmentServiceTest {

    private ReservationAmendmentAssessmentRepository assessmentRepository;
    private RuleCatalogueRepository ruleRepository;
    private PaymentTransactionRepository paymentTransactionRepository;
    private RefundRepository refundRepository;
    private ReservationAmendmentLedgerSupport ledgerSupport;
    private ObjectMapper objectMapper;
    private ReservationAmendmentAssessmentService service;

    private final UUID bookingId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();
    private final UUID unitId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final String proposalId = "prop-rev-01";

    @BeforeEach
    void setUp() {
        assessmentRepository = mock(ReservationAmendmentAssessmentRepository.class);
        ruleRepository = mock(RuleCatalogueRepository.class);
        paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        refundRepository = mock(RefundRepository.class);
        ledgerSupport = mock(ReservationAmendmentLedgerSupport.class);
        objectMapper = new ObjectMapper();

        service = new ReservationAmendmentAssessmentService(
            assessmentRepository,
            ruleRepository,
            paymentTransactionRepository,
            refundRepository,
            ledgerSupport,
            objectMapper,
            new SimpleMeterRegistry()
        );
    }

    @Test
    @DisplayName("AC01: Reassesses amendment with 30% deposit rule -> COLLECTION_REQUIRED when delta > 0")
    void assessAmendment_withCollectionRequired_success() {
        var before = snapshot(BigDecimal.valueOf(10000000), false);
        var after = snapshot(BigDecimal.valueOf(15000000), false);
        var cmd = new AssessReservationAmendmentCommand(
            bookingId, proposalId, "DEP-STD", before, after,
            Instant.now().plusSeconds(1800), "idem-key-1", "corr-1", actorId, null
        );

        when(ledgerSupport.checkAndLockIdempotency(anyString(), anyString(), anyString(), any(), anyString(), any()))
            .thenReturn(new ReservationAmendmentLedgerSupport.IdempotencyRecord(false, null, null));

        RuleCatalogue rule = mock(RuleCatalogue.class);
        when(rule.getId()).thenReturn(UUID.randomUUID());
        when(rule.getRuleVersion()).thenReturn(1);
        when(rule.getParametersJson()).thenReturn("{\"depositRate\": 0.30, \"currency\": \"VND\"}");
        when(ruleRepository.findActiveEffectiveRulesByTypeAndCode(eq(RuleType.DEPOSIT), eq("DEP-STD"), any()))
            .thenReturn(List.of(rule));

        when(paymentTransactionRepository.calculateTotalPaidAmountByBookingId(bookingId))
            .thenReturn(BigDecimal.valueOf(3000000));
        when(paymentTransactionRepository.calculateTotalRefundedAmountByBookingId(bookingId))
            .thenReturn(BigDecimal.ZERO);

        when(assessmentRepository.findCurrentForUpdate(bookingId, proposalId))
            .thenReturn(Optional.empty());
        when(assessmentRepository.findTopByBookingIdAndProposalIdOrderByAmendmentRevisionDesc(bookingId, proposalId))
            .thenReturn(Optional.empty());
        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ReservationAmendmentAssessmentResponse res = service.assessAmendment(cmd, actorId, Set.of(
            ReservationFieldGroup.RESERVATION_SUMMARY,
            ReservationFieldGroup.FINANCIAL,
            ReservationFieldGroup.INTERNAL_AUDIT
        ));

        assertThat(res.bookingId()).isEqualTo(bookingId);
        assertThat(res.proposalId()).isEqualTo(proposalId);
        assertThat(res.requiredAction()).isEqualTo(AmendmentRequiredAction.COLLECTION_REQUIRED);
        assertThat(res.status()).isEqualTo(AmendmentAssessmentStatus.COLLECTION_REQUIRED);
        assertThat(res.executable()).isFalse();
        assertThat(res.afterRequiredAmount()).isEqualByComparingTo(BigDecimal.valueOf(4500000));
        assertThat(res.verifiedCollectedAmount()).isEqualByComparingTo(BigDecimal.valueOf(3000000));
        assertThat(res.deltaAmount()).isEqualByComparingTo(BigDecimal.valueOf(1500000));

        verify(ledgerSupport).recordOutboxEvent(anyString(), any(), anyLong(), anyString(), eq(1), any(), any(), any());
        verify(ledgerSupport).recordAuditEvent(eq(actorId), anyString(), anyString(), any(), any(), any());
        verify(ledgerSupport).recordSucceeded(eq("idem-key-1"), anyString(), any(), any());
    }

    @Test
    @DisplayName("AC01: Reassesses amendment -> REFUND_DUE when delta < 0")
    void assessAmendment_withRefundDue_success() {
        var before = snapshot(BigDecimal.valueOf(15000000), false);
        var after = snapshot(BigDecimal.valueOf(10000000), false);
        var cmd = new AssessReservationAmendmentCommand(
            bookingId, proposalId, "DEP-STD", before, after,
            Instant.now().plusSeconds(1800), "idem-key-refund", "corr-2", actorId, null
        );

        when(ledgerSupport.checkAndLockIdempotency(anyString(), anyString(), anyString(), any(), anyString(), any()))
            .thenReturn(new ReservationAmendmentLedgerSupport.IdempotencyRecord(false, null, null));

        RuleCatalogue rule = mock(RuleCatalogue.class);
        when(rule.getId()).thenReturn(UUID.randomUUID());
        when(rule.getRuleVersion()).thenReturn(2);
        when(rule.getParametersJson()).thenReturn("{\"depositRate\": 0.30, \"currency\": \"VND\"}");
        when(ruleRepository.findActiveEffectiveRulesByTypeAndCode(eq(RuleType.DEPOSIT), eq("DEP-STD"), any()))
            .thenReturn(List.of(rule));

        when(paymentTransactionRepository.calculateTotalPaidAmountByBookingId(bookingId))
            .thenReturn(BigDecimal.valueOf(4500000));
        when(paymentTransactionRepository.calculateTotalRefundedAmountByBookingId(bookingId))
            .thenReturn(BigDecimal.ZERO);

        when(assessmentRepository.findCurrentForUpdate(bookingId, proposalId)).thenReturn(Optional.empty());
        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ReservationAmendmentAssessmentResponse res = service.assessAmendment(cmd, actorId, Set.of(
            ReservationFieldGroup.RESERVATION_SUMMARY,
            ReservationFieldGroup.FINANCIAL
        ));

        assertThat(res.requiredAction()).isEqualTo(AmendmentRequiredAction.REFUND_DUE);
        assertThat(res.status()).isEqualTo(AmendmentAssessmentStatus.REFUND_DUE);
        assertThat(res.deltaAmount()).isEqualByComparingTo(BigDecimal.valueOf(-1500000));
    }

    @Test
    @DisplayName("AC01: Manual waiver requested produces WAIVER_REVIEW")
    void assessAmendment_withManualWaiverRequested() {
        var before = snapshot(BigDecimal.valueOf(10000000), false);
        var after = snapshot(BigDecimal.valueOf(15000000), true);
        var cmd = new AssessReservationAmendmentCommand(
            bookingId, proposalId, null, before, after,
            Instant.now().plusSeconds(1800), "idem-waiver", "corr-3", actorId, null
        );

        when(ledgerSupport.checkAndLockIdempotency(anyString(), anyString(), anyString(), any(), anyString(), any()))
            .thenReturn(new ReservationAmendmentLedgerSupport.IdempotencyRecord(false, null, null));

        RuleCatalogue rule = mock(RuleCatalogue.class);
        when(rule.getId()).thenReturn(UUID.randomUUID());
        when(rule.getRuleVersion()).thenReturn(1);
        when(rule.getParametersJson()).thenReturn("{}");
        when(ruleRepository.findActiveEffectiveRulesByType(eq(RuleType.DEPOSIT), any()))
            .thenReturn(List.of(rule));

        when(paymentTransactionRepository.calculateTotalPaidAmountByBookingId(bookingId)).thenReturn(BigDecimal.ZERO);
        when(paymentTransactionRepository.calculateTotalRefundedAmountByBookingId(bookingId)).thenReturn(BigDecimal.ZERO);
        when(assessmentRepository.findCurrentForUpdate(bookingId, proposalId)).thenReturn(Optional.empty());
        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ReservationAmendmentAssessmentResponse res = service.assessAmendment(cmd, actorId, Set.of(ReservationFieldGroup.RESERVATION_SUMMARY));
        assertThat(res.requiredAction()).isEqualTo(AmendmentRequiredAction.WAIVER_REVIEW);
        assertThat(res.status()).isEqualTo(AmendmentAssessmentStatus.WAIVER_REVIEW);
    }

    @Test
    @DisplayName("Fail-closed: Throws EFFECTIVE_RULE_UNAVAILABLE when no active rule is found")
    void assessAmendment_failClosed_whenNoActiveRule() {
        var before = snapshot(BigDecimal.valueOf(10000000), false);
        var after = snapshot(BigDecimal.valueOf(12000000), false);
        var cmd = new AssessReservationAmendmentCommand(
            bookingId, proposalId, "UNKNOWN-RULE", before, after,
            Instant.now().plusSeconds(1800), "idem-key-missing", "corr-4", actorId, null
        );

        when(ledgerSupport.checkAndLockIdempotency(anyString(), anyString(), anyString(), any(), anyString(), any()))
            .thenReturn(new ReservationAmendmentLedgerSupport.IdempotencyRecord(false, null, null));
        when(ruleRepository.findActiveEffectiveRulesByTypeAndCode(any(), any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.assessAmendment(cmd, actorId, Set.of()))
            .isInstanceOf(ReservationAmendmentException.class)
            .satisfies(ex -> {
                ReservationAmendmentException rae = (ReservationAmendmentException) ex;
                assertThat(rae.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(rae.getErrorCode()).isEqualTo("EFFECTIVE_RULE_UNAVAILABLE");
            });
    }

    @Test
    @DisplayName("AC02: Optimistic lock CAS mismatch throws OPTIMISTIC_LOCK_CONFLICT")
    void assessAmendment_casMismatch_throwsConflict() {
        var before = snapshot(BigDecimal.valueOf(10000000), false);
        var after = snapshot(BigDecimal.valueOf(12000000), false);
        var cmd = new AssessReservationAmendmentCommand(
            bookingId, proposalId, "DEP-STD", before, after,
            Instant.now().plusSeconds(1800), "idem-key-cas", "corr-5", actorId, 1L
        );

        when(ledgerSupport.checkAndLockIdempotency(anyString(), anyString(), anyString(), any(), anyString(), any()))
            .thenReturn(new ReservationAmendmentLedgerSupport.IdempotencyRecord(false, null, null));

        RuleCatalogue rule = mock(RuleCatalogue.class);
        when(ruleRepository.findActiveEffectiveRulesByTypeAndCode(any(), any(), any())).thenReturn(List.of(rule));

        ReservationAmendmentAssessment current = mock(ReservationAmendmentAssessment.class);
        when(current.getVersion()).thenReturn(2L);
        when(assessmentRepository.findCurrentForUpdate(bookingId, proposalId)).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.assessAmendment(cmd, actorId, Set.of()))
            .isInstanceOf(ReservationAmendmentException.class)
            .satisfies(ex -> {
                ReservationAmendmentException rae = (ReservationAmendmentException) ex;
                assertThat(rae.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(rae.getErrorCode()).isEqualTo("OPTIMISTIC_LOCK_CONFLICT");
            });
    }

    @Test
    @DisplayName("Maker-Checker: Assessor cannot approve their own waiver")
    void approveWaiver_makerCheckerViolation_throwsForbidden() {
        var cmd = new ApproveAmendmentWaiverCommand(
            bookingId, proposalId, 0L, "VIP guest exception", "waiver-idem-1", "corr-w", actorId
        );

        when(ledgerSupport.checkAndLockIdempotency(anyString(), anyString(), anyString(), any(), anyString(), any()))
            .thenReturn(new ReservationAmendmentLedgerSupport.IdempotencyRecord(false, null, null));

        ReservationAmendmentAssessment assessment = mock(ReservationAmendmentAssessment.class);
        when(assessment.getVersion()).thenReturn(0L);
        when(assessment.getAssessedByActorId()).thenReturn(actorId); // same actor!
        when(assessmentRepository.findCurrentForUpdate(bookingId, proposalId)).thenReturn(Optional.of(assessment));

        assertThatThrownBy(() -> service.approveWaiver(cmd, actorId, Set.of()))
            .isInstanceOf(ReservationAmendmentException.class)
            .satisfies(ex -> {
                ReservationAmendmentException rae = (ReservationAmendmentException) ex;
                assertThat(rae.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(rae.getErrorCode()).isEqualTo("MAKER_CHECKER_VIOLATION");
            });
    }

    @Test
    @DisplayName("Checker approves waiver successfully -> WAIVED_FINAL and executable=true")
    void approveWaiver_success() {
        UUID checkerId = UUID.randomUUID();
        var cmd = new ApproveAmendmentWaiverCommand(
            bookingId, proposalId, 0L, "VIP guest exception", "waiver-idem-2", "corr-w2", checkerId
        );

        when(ledgerSupport.checkAndLockIdempotency(anyString(), anyString(), anyString(), any(), anyString(), any()))
            .thenReturn(new ReservationAmendmentLedgerSupport.IdempotencyRecord(false, null, null));

        var snapshot = snapshot(BigDecimal.valueOf(10000000), true);
        var assessment = new ReservationAmendmentAssessment(
            UUID.randomUUID(), bookingId, propertyId, customerId, proposalId, 1L,
            snapshot.canonicalSha256(), snapshot.canonicalSha256(), UUID.randomUUID(), 1,
            BigDecimal.valueOf(3000000), BigDecimal.valueOf(3000000), BigDecimal.ZERO,
            BigDecimal.valueOf(3000000), "VND", AmendmentRequiredAction.WAIVER_REVIEW,
            AmendmentAssessmentStatus.WAIVER_REVIEW, actorId, Instant.now().plusSeconds(1800),
            "key-waiver", "corr-waiver"
        );

        when(assessmentRepository.findCurrentForUpdate(bookingId, proposalId)).thenReturn(Optional.of(assessment));
        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ReservationAmendmentAssessmentResponse res = service.approveWaiver(cmd, checkerId, Set.of(ReservationFieldGroup.RESERVATION_SUMMARY));
        assertThat(res.status()).isEqualTo(AmendmentAssessmentStatus.WAIVED_FINAL);
        assertThat(res.executable()).isTrue();
    }

    @Test
    @DisplayName("Final decision: expired hold marks STALE and executable=false")
    void getFinalDecision_expiredHold_returnsStale() {
        var snapshot = snapshot(BigDecimal.valueOf(10000000), false);
        var expiredAssessment = new ReservationAmendmentAssessment(
            UUID.randomUUID(), bookingId, propertyId, customerId, proposalId, 1L,
            snapshot.canonicalSha256(), snapshot.canonicalSha256(), UUID.randomUUID(), 1,
            BigDecimal.valueOf(3000000), BigDecimal.valueOf(3000000), BigDecimal.valueOf(3000000),
            BigDecimal.ZERO, "VND", AmendmentRequiredAction.NO_CHANGE,
            AmendmentAssessmentStatus.VERIFIED, actorId, Instant.now().minusSeconds(10), // expired!
            "key-exp", "corr-exp"
        );

        when(assessmentRepository.findFirstByBookingIdAndProposalIdAndCurrentTrue(bookingId, proposalId))
            .thenReturn(Optional.of(expiredAssessment));

        ReservationAmendmentAssessmentResponse res = service.getFinalDecision(bookingId, proposalId, null, null, Set.of());
        assertThat(res.status()).isEqualTo(AmendmentAssessmentStatus.STALE);
        assertThat(res.executable()).isFalse();
    }

    @Test
    @DisplayName("Executed event: creates compensating receivable when delta > 0 without mutating payments")
    void processAmendmentExecutedEvent_createsReceivable() {
        UUID eventId = UUID.randomUUID();
        var snapshot = snapshot(BigDecimal.valueOf(10000000), false);
        var fp = snapshot.canonicalSha256();

        var assessment = new ReservationAmendmentAssessment(
            UUID.randomUUID(), bookingId, propertyId, customerId, proposalId, 1L,
            fp, fp, UUID.randomUUID(), 1,
            BigDecimal.valueOf(3000000), BigDecimal.valueOf(5000000), BigDecimal.valueOf(3000000),
            BigDecimal.valueOf(2000000), "VND", AmendmentRequiredAction.COLLECTION_REQUIRED,
            AmendmentAssessmentStatus.VERIFIED, actorId, Instant.now().plusSeconds(1800),
            "key-exec-1", "corr-exec-1"
        );

        when(ledgerSupport.tryClaimEvent(eventId, "finance-amendment-executed-v1")).thenReturn(true);
        when(assessmentRepository.findCurrentForUpdate(bookingId, proposalId)).thenReturn(Optional.of(assessment));
        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID createdRecId = UUID.randomUUID();
        when(ledgerSupport.insertCompensatingReceivable(eq(bookingId), eq(customerId), eq(proposalId), eq(eventId), any(), eq(BigDecimal.valueOf(2000000)), eq("VND")))
            .thenReturn(createdRecId);

        var cmd = new ReservationAmendmentExecutedCommand(
            eventId, bookingId, proposalId, fp, customerId, LocalDate.now().plusDays(2), "corr-exec-1", actorId
        );

        service.processAmendmentExecutedEvent(cmd);

        assertThat(assessment.getStatus()).isEqualTo(AmendmentAssessmentStatus.EXECUTED);
        assertThat(assessment.getResultingReceivableId()).isEqualTo(createdRecId);
        assertThat(assessment.getExecutedEventId()).isEqualTo(eventId);

        // Verify zero modifications to payment transactions or refunds
        verify(paymentTransactionRepository, never()).save(any());
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("Executed event: creates compensating refund when delta < 0")
    void processAmendmentExecutedEvent_createsRefund() {
        UUID eventId = UUID.randomUUID();
        var snapshot = snapshot(BigDecimal.valueOf(10000000), false);
        var fp = snapshot.canonicalSha256();

        var assessment = new ReservationAmendmentAssessment(
            UUID.randomUUID(), bookingId, propertyId, customerId, proposalId, 1L,
            fp, fp, UUID.randomUUID(), 1,
            BigDecimal.valueOf(5000000), BigDecimal.valueOf(3000000), BigDecimal.valueOf(5000000),
            BigDecimal.valueOf(-2000000), "VND", AmendmentRequiredAction.REFUND_DUE,
            AmendmentAssessmentStatus.VERIFIED, actorId, Instant.now().plusSeconds(1800),
            "key-exec-2", "corr-exec-2"
        );

        when(ledgerSupport.tryClaimEvent(eventId, "finance-amendment-executed-v1")).thenReturn(true);
        when(assessmentRepository.findCurrentForUpdate(bookingId, proposalId)).thenReturn(Optional.of(assessment));
        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentTransaction origTx = mock(PaymentTransaction.class);
        UUID origTxId = UUID.randomUUID();
        when(origTx.getId()).thenReturn(origTxId);
        when(paymentTransactionRepository.findFirstByBookingIdAndTransactionTypeAndStatusOrderByOccurredAtDesc(
            bookingId, TransactionType.PAYMENT, TransactionStatus.SUCCEEDED
        )).thenReturn(Optional.of(origTx));

        UUID refundId = UUID.randomUUID();
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            return r;
        });

        var cmd = new ReservationAmendmentExecutedCommand(
            eventId, bookingId, proposalId, fp, customerId, LocalDate.now().plusDays(2), "corr-exec-2", actorId
        );

        service.processAmendmentExecutedEvent(cmd);

        assertThat(assessment.getStatus()).isEqualTo(AmendmentAssessmentStatus.EXECUTED);
        assertThat(assessment.getResultingRefundId()).isNotNull();

        var refundCaptor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getAmount()).isEqualByComparingTo(BigDecimal.valueOf(2000000));
        assertThat(refundCaptor.getValue().getOriginalTransactionId()).isEqualTo(origTxId);
    }

    @Test
    @DisplayName("Executed event: duplicate event claim returns idempotent no-op")
    void processAmendmentExecutedEvent_duplicateEvent_noOp() {
        UUID eventId = UUID.randomUUID();
        when(ledgerSupport.tryClaimEvent(eventId, "finance-amendment-executed-v1")).thenReturn(false);

        var cmd = new ReservationAmendmentExecutedCommand(
            eventId, bookingId, proposalId, "fp", customerId, LocalDate.now().plusDays(2), "corr", actorId
        );

        service.processAmendmentExecutedEvent(cmd);

        verify(assessmentRepository, never()).findCurrentForUpdate(any(), any());
    }

    private AmendmentProposalSnapshot snapshot(BigDecimal lodgingAmount, boolean manualWaiver) {
        return new AmendmentProposalSnapshot(
            propertyId, unitId, customerId, LocalDate.now().plusDays(10), LocalDate.now().plusDays(14),
            "DELUXE_ROOM", lodgingAmount, "VND", 1L, manualWaiver
        );
    }
}
