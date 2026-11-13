package com.vistay.finance.application.service;

import com.vistay.finance.application.dto.AssessDepositCommand;
import com.vistay.finance.application.dto.DepositAssessmentResponse;
import com.vistay.finance.application.dto.WaiveDepositCommand;
import com.vistay.finance.domain.model.*;
import com.vistay.finance.domain.repository.FinanceCommandIdempotencyRepository;
import com.vistay.finance.domain.repository.PaymentTransactionRepository;
import com.vistay.finance.domain.repository.ReservationDepositAssessmentRepository;
import com.vistay.finance.domain.repository.RuleCatalogueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationDepositAssessmentServiceTest {

    @Mock
    private ReservationDepositAssessmentRepository assessmentRepository;

    @Mock
    private RuleCatalogueRepository ruleCatalogueRepository;

    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;

    @Mock
    private FinanceCommandIdempotencyRepository idempotencyRepository;

    private ObjectMapper objectMapper;
    private ReservationDepositAssessmentService service;

    private UUID propertyId;
    private UUID bookingId;
    private UUID customerId;
    private UUID adminUserId;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ReservationDepositAssessmentService(
                assessmentRepository,
                ruleCatalogueRepository,
                paymentTransactionRepository,
                idempotencyRepository,
                objectMapper
        );

        propertyId = UUID.randomUUID();
        bookingId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        adminUserId = UUID.randomUUID();
    }

    @Test
    @DisplayName("AC01: When verified collected amount covers required deposit, status is VERIFIED_PAID")
    void assessDeposit_whenPaidInFull_returnsVerifiedPaid() {
        UUID ruleId = UUID.randomUUID();
        RuleCatalogue rule = new RuleCatalogue(
                "DEP-STD", "Standard Deposit", RuleType.DEPOSIT, 1, "Desc",
                "{\"depositRate\":0.3}", Instant.now().minus(1, ChronoUnit.DAYS), null, "SYSTEM"
        );

        when(assessmentRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(ruleCatalogueRepository.findActiveEffectiveRulesByTypeAndCode(eq(RuleType.DEPOSIT), eq("DEP-STD"), any()))
                .thenReturn(List.of(rule));
        when(paymentTransactionRepository.calculateTotalPaidAmountByBookingId(bookingId))
                .thenReturn(new BigDecimal("300000.0000"));
        when(assessmentRepository.findByTargetKeyAndIsCurrentTrue(anyString())).thenReturn(Optional.empty());
        when(assessmentRepository.save(any(ReservationDepositAssessment.class))).thenAnswer(inv -> inv.getArgument(0));

        AssessDepositCommand cmd = new AssessDepositCommand(
                "booking:" + bookingId,
                bookingId,
                null,
                null,
                propertyId,
                customerId,
                new BigDecimal("1000000.0000"),
                "VND",
                "DEP-STD",
                1L,
                "TR-123",
                Instant.now().plus(2, ChronoUnit.HOURS),
                "idemp-ac01-paid",
                "corr-1"
        );

        DepositAssessmentResponse res = service.assessDeposit(cmd);

        assertThat(res.status()).isEqualTo(DepositAssessmentStatus.VERIFIED_PAID);
        assertThat(res.requiredAmount()).isEqualByComparingTo("300000.0000");
        assertThat(res.waiverMode()).isEqualTo(DepositWaiverMode.NONE);
        assertThat(res.isCurrent()).isTrue();
        assertThat(res.assessmentRevision()).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC01: When collected amount is less than required, status is PAYMENT_REQUIRED")
    void assessDeposit_whenUnpaid_returnsPaymentRequired() {
        RuleCatalogue rule = new RuleCatalogue(
                "DEP-STD", "Standard Deposit", RuleType.DEPOSIT, 1, "Desc",
                "{\"fixedDepositAmount\":500000}", Instant.now().minus(1, ChronoUnit.DAYS), null, "SYSTEM"
        );

        when(assessmentRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(ruleCatalogueRepository.findActiveEffectiveRulesByTypeAndCode(eq(RuleType.DEPOSIT), eq("DEP-STD"), any()))
                .thenReturn(List.of(rule));
        when(paymentTransactionRepository.calculateTotalPaidAmountByBookingId(bookingId))
                .thenReturn(BigDecimal.ZERO);
        when(assessmentRepository.findByTargetKeyAndIsCurrentTrue(anyString())).thenReturn(Optional.empty());
        when(assessmentRepository.save(any(ReservationDepositAssessment.class))).thenAnswer(inv -> inv.getArgument(0));

        AssessDepositCommand cmd = new AssessDepositCommand(
                "booking:" + bookingId,
                bookingId,
                null,
                null,
                propertyId,
                customerId,
                new BigDecimal("2000000.0000"),
                "VND",
                "DEP-STD",
                null,
                null,
                Instant.now().plus(1, ChronoUnit.HOURS),
                "idemp-ac01-unpaid",
                "corr-2"
        );

        DepositAssessmentResponse res = service.assessDeposit(cmd);

        assertThat(res.status()).isEqualTo(DepositAssessmentStatus.PAYMENT_REQUIRED);
        assertThat(res.requiredAmount()).isEqualByComparingTo("500000.0000");
    }

    @Test
    @DisplayName("AC01: Zero deposit rule automatically sets WAIVED_FINAL with ZERO_DEPOSIT_POLICY")
    void assessDeposit_zeroDepositPolicy_returnsWaivedFinal() {
        RuleCatalogue rule = new RuleCatalogue(
                "DEP-ZERO", "Zero Deposit", RuleType.DEPOSIT, 1, "Desc",
                "{\"depositRate\":0.0}", Instant.now().minus(1, ChronoUnit.DAYS), null, "SYSTEM"
        );

        when(assessmentRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(ruleCatalogueRepository.findActiveEffectiveRulesByTypeAndCode(eq(RuleType.DEPOSIT), eq("DEP-ZERO"), any()))
                .thenReturn(List.of(rule));
        when(assessmentRepository.findByTargetKeyAndIsCurrentTrue(anyString())).thenReturn(Optional.empty());
        when(assessmentRepository.save(any(ReservationDepositAssessment.class))).thenAnswer(inv -> inv.getArgument(0));

        AssessDepositCommand cmd = new AssessDepositCommand(
                "booking:" + bookingId,
                bookingId,
                null,
                null,
                propertyId,
                customerId,
                new BigDecimal("1500000.0000"),
                "VND",
                "DEP-ZERO",
                null,
                null,
                Instant.now().plus(1, ChronoUnit.HOURS),
                "idemp-zero-dep",
                "corr-3"
        );

        DepositAssessmentResponse res = service.assessDeposit(cmd);

        assertThat(res.status()).isEqualTo(DepositAssessmentStatus.WAIVED_FINAL);
        assertThat(res.waiverMode()).isEqualTo(DepositWaiverMode.ZERO_DEPOSIT_POLICY);
        assertThat(res.requiredAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("AC02: Authorized manual waiver transitions status to WAIVED_FINAL with approver audit")
    void waiveDeposit_whenAuthorized_returnsWaivedFinal() {
        ReservationDepositAssessment existing = new ReservationDepositAssessment(
                "hold:123",
                null,
                null,
                UUID.randomUUID(),
                propertyId,
                customerId,
                1L,
                "fingerprint-123",
                null,
                1,
                null,
                null,
                new BigDecimal("500000.0000"),
                BigDecimal.ZERO,
                "VND",
                DepositWaiverMode.NONE,
                DepositAssessmentStatus.PAYMENT_REQUIRED,
                Instant.now().plus(1, ChronoUnit.HOURS),
                "idemp-orig",
                "corr-orig"
        );

        when(assessmentRepository.findByIdempotencyKey("idemp-waive-1")).thenReturn(Optional.empty());
        when(assessmentRepository.findByTargetKeyAndIsCurrentTrue("hold:123")).thenReturn(Optional.of(existing));
        when(assessmentRepository.save(any(ReservationDepositAssessment.class))).thenAnswer(inv -> inv.getArgument(0));

        WaiveDepositCommand cmd = new WaiveDepositCommand(
                "hold:123",
                adminUserId,
                "VIP customer courtesy waiver",
                "idemp-waive-1",
                "corr-waive"
        );

        DepositAssessmentResponse res = service.waiveDeposit(cmd);

        assertThat(res.status()).isEqualTo(DepositAssessmentStatus.WAIVED_FINAL);
        assertThat(res.waiverMode()).isEqualTo(DepositWaiverMode.MANUAL_WAIVER);
        assertThat(res.waiverApprovedByAdminUserId()).isEqualTo(adminUserId);
        assertThat(res.waiverReason()).isEqualTo("VIP customer courtesy waiver");
        assertThat(res.waiverApprovedAt()).isNotNull();
    }

    @Test
    @DisplayName("AC02: Attempting to waive an already paid deposit throws IllegalStateException")
    void waiveDeposit_whenAlreadyPaid_throwsIllegalStateException() {
        ReservationDepositAssessment paid = new ReservationDepositAssessment(
                "hold:123",
                null,
                null,
                UUID.randomUUID(),
                propertyId,
                customerId,
                1L,
                "fingerprint-123",
                null,
                1,
                null,
                null,
                new BigDecimal("500000.0000"),
                new BigDecimal("500000.0000"),
                "VND",
                DepositWaiverMode.NONE,
                DepositAssessmentStatus.VERIFIED_PAID,
                Instant.now().plus(1, ChronoUnit.HOURS),
                "idemp-paid",
                "corr-paid"
        );

        when(assessmentRepository.findByIdempotencyKey("idemp-waive-fail")).thenReturn(Optional.empty());
        when(assessmentRepository.findByTargetKeyAndIsCurrentTrue("hold:123")).thenReturn(Optional.of(paid));

        WaiveDepositCommand cmd = new WaiveDepositCommand(
                "hold:123",
                adminUserId,
                "Reason",
                "idemp-waive-fail",
                "corr-waive"
        );

        assertThatThrownBy(() -> service.waiveDeposit(cmd))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot waive a deposit that has already been verified and paid");
    }

    @Test
    @DisplayName("AC03: Idempotent replay with identical key and parameters returns existing assessment")
    void assessDeposit_idempotentReplay_returnsExisting() {
        AssessDepositCommand cmd = new AssessDepositCommand(
                "booking:" + bookingId,
                bookingId,
                null,
                null,
                propertyId,
                customerId,
                new BigDecimal("1000000.0000"),
                "VND",
                null,
                null,
                null,
                Instant.now().plus(2, ChronoUnit.HOURS),
                "idemp-replay-test",
                "corr-replay"
        );

        ReservationDepositAssessment existing = new ReservationDepositAssessment(
                cmd.targetKey(),
                bookingId,
                null,
                null,
                propertyId,
                customerId,
                1L,
                "93db9ebba829676757dfd24838d21c2514589d700344b1c70e7e174b5bc05972", // will match canonical fingerprint
                null,
                1,
                null,
                null,
                new BigDecimal("1000000.0000"),
                BigDecimal.ZERO,
                "VND",
                DepositWaiverMode.NONE,
                DepositAssessmentStatus.PAYMENT_REQUIRED,
                cmd.expiresAt(),
                cmd.idempotencyKey(),
                cmd.correlationId()
        );

        // First call generates fingerprint; let's capture it or stub return
        when(assessmentRepository.findByIdempotencyKey(cmd.idempotencyKey())).thenReturn(Optional.empty());
        when(assessmentRepository.findByTargetKeyAndIsCurrentTrue(anyString())).thenReturn(Optional.empty());
        when(assessmentRepository.save(any(ReservationDepositAssessment.class))).thenAnswer(inv -> inv.getArgument(0));

        DepositAssessmentResponse firstRun = service.assessDeposit(cmd);

        // Second call with same idempotency key
        ReservationDepositAssessment recorded = new ReservationDepositAssessment(
                cmd.targetKey(), bookingId, null, null, propertyId, customerId, 1L,
                firstRun.inputFingerprint(), null, 1, null, null,
                firstRun.requiredAmount(), BigDecimal.ZERO, "VND", DepositWaiverMode.NONE,
                firstRun.status(), cmd.expiresAt(), cmd.idempotencyKey(), cmd.correlationId()
        );
        when(assessmentRepository.findByIdempotencyKey(cmd.idempotencyKey())).thenReturn(Optional.of(recorded));

        DepositAssessmentResponse secondRun = service.assessDeposit(cmd);

        assertThat(secondRun.inputFingerprint()).isEqualTo(firstRun.inputFingerprint());
        assertThat(secondRun.requiredAmount()).isEqualByComparingTo(firstRun.requiredAmount());
    }

    @Test
    @DisplayName("AC03: Idempotency key reused with different parameters throws IDEMPOTENCY_KEY_REUSED")
    void assessDeposit_keyReusedWithDifferentParams_throwsException() {
        ReservationDepositAssessment existing = new ReservationDepositAssessment(
                "booking:" + bookingId,
                bookingId,
                null,
                null,
                propertyId,
                customerId,
                1L,
                "different-fingerprint-abc",
                null,
                1,
                null,
                null,
                new BigDecimal("500000.0000"),
                BigDecimal.ZERO,
                "VND",
                DepositWaiverMode.NONE,
                DepositAssessmentStatus.PAYMENT_REQUIRED,
                Instant.now().plus(2, ChronoUnit.HOURS),
                "reused-key",
                "corr"
        );

        when(assessmentRepository.findByIdempotencyKey("reused-key")).thenReturn(Optional.of(existing));

        AssessDepositCommand cmd = new AssessDepositCommand(
                "booking:" + bookingId,
                bookingId,
                null,
                null,
                propertyId,
                customerId,
                new BigDecimal("1000000.0000"),
                "VND",
                null,
                null,
                null,
                Instant.now().plus(2, ChronoUnit.HOURS),
                "reused-key",
                "corr"
        );

        assertThatThrownBy(() -> service.assessDeposit(cmd))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    @DisplayName("AC03: New assessment supersedes previous revision and increments revision number")
    void assessDeposit_newAssessment_supersedesPrevious() {
        ReservationDepositAssessment prevRevision = new ReservationDepositAssessment(
                "booking:" + bookingId,
                bookingId,
                null,
                null,
                propertyId,
                customerId,
                1L,
                "old-fingerprint",
                null,
                1,
                null,
                null,
                new BigDecimal("500000.0000"),
                BigDecimal.ZERO,
                "VND",
                DepositWaiverMode.NONE,
                DepositAssessmentStatus.PAYMENT_REQUIRED,
                Instant.now().plus(2, ChronoUnit.HOURS),
                "idemp-rev-1",
                "corr-1"
        );

        when(assessmentRepository.findByIdempotencyKey("idemp-rev-2")).thenReturn(Optional.empty());
        when(assessmentRepository.findByTargetKeyAndIsCurrentTrue("booking:" + bookingId)).thenReturn(Optional.of(prevRevision));
        when(assessmentRepository.save(any(ReservationDepositAssessment.class))).thenAnswer(inv -> inv.getArgument(0));

        AssessDepositCommand cmd = new AssessDepositCommand(
                "booking:" + bookingId,
                bookingId,
                null,
                null,
                propertyId,
                customerId,
                new BigDecimal("600000.0000"),
                "VND",
                null,
                null,
                null,
                Instant.now().plus(2, ChronoUnit.HOURS),
                "idemp-rev-2",
                "corr-2"
        );

        DepositAssessmentResponse res = service.assessDeposit(cmd);

        assertThat(prevRevision.isCurrent()).isFalse();
        assertThat(res.isCurrent()).isTrue();
        assertThat(res.assessmentRevision()).isEqualTo(2L);
    }
}
