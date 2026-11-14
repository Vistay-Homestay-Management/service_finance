package com.vistay.finance.application.service;

import com.vistay.finance.adapter.in.rest.FinanceOperationalAuthorization;
import com.vistay.finance.adapter.in.rest.ReservationDepositAssessmentController;
import com.vistay.finance.application.FinanceAuthorizationClient.Scope;
import com.vistay.finance.application.FinanceAuthorizationClient.Snapshot;
import com.vistay.finance.application.dto.AssessDepositCommand;
import com.vistay.finance.application.dto.DepositAssessmentResponse;
import com.vistay.finance.application.dto.WaiveDepositCommand;
import com.vistay.finance.domain.model.*;
import com.vistay.finance.domain.repository.FinanceCommandIdempotencyRepository;
import com.vistay.finance.domain.repository.PaymentTransactionRepository;
import com.vistay.finance.domain.repository.RefundRepository;
import com.vistay.finance.domain.repository.ReservationDepositAssessmentRepository;
import com.vistay.finance.domain.repository.RuleCatalogueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private RefundRepository refundRepository;

    @Mock
    private FinanceCommandIdempotencyRepository idempotencyRepository;

    @Mock
    private FinanceOperationalAuthorization authorization;

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
                refundRepository,
                idempotencyRepository,
                authorization,
                objectMapper
        );

        propertyId = UUID.randomUUID();
        bookingId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        adminUserId = UUID.randomUUID();
    }

    private FinanceOperationalAuthorization.Actor createActor(String permission, Scope... scopes) {
        Snapshot snapshot = new Snapshot(true, adminUserId, 1L, Map.of(permission, Set.of(scopes)), Map.of());
        return new FinanceOperationalAuthorization.Actor(UUID.randomUUID(), adminUserId, permission, snapshot);
    }

    private PaymentTransaction createPaymentTransaction(UUID bookingId, BigDecimal amount, String currency, TransactionStatus status) {
        return new PaymentTransaction(
                UUID.randomUUID(), "PTX-1", null, null, null, bookingId,
                TransactionType.PAYMENT, amount, currency, PaymentMethod.BANK_TRANSFER,
                null, "MOMO", "EXT-1", status, "idemp-tx-" + UUID.randomUUID(), "Desc", adminUserId
        );
    }

    private Refund createRefund(UUID bookingId, BigDecimal amount, String currency) {
        Refund r = new Refund(
                UUID.randomUUID(), "REF-1", bookingId, UUID.randomUUID(),
                amount, currency, "Customer request", adminUserId, "idemp-ref-" + UUID.randomUUID()
        );
        r.approve(adminUserId);
        r.markCompleted(UUID.randomUUID());
        return r;
    }

    @Test
    @DisplayName("AC01: When verified collected amount covers required deposit, status is VERIFIED_PAID and collected amount is persisted")
    void assessDeposit_whenPaidInFull_returnsVerifiedPaidAndPersistsCollectedAmount() {
        RuleCatalogue rule = new RuleCatalogue(
                "DEP-STD", "Standard Deposit", RuleType.DEPOSIT, 1, "Desc",
                "{\"depositRate\":0.3}", Instant.now().minus(1, ChronoUnit.DAYS), null, "SYSTEM"
        );

        when(idempotencyRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(ruleCatalogueRepository.findActiveEffectiveRulesByTypeAndCode(eq(RuleType.DEPOSIT), eq("DEP-STD"), any()))
                .thenReturn(List.of(rule));

        PaymentTransaction tx = createPaymentTransaction(bookingId, new BigDecimal("300000.0000"), "VND", TransactionStatus.SUCCEEDED);
        when(paymentTransactionRepository.findByBookingIdOrderByOccurredAtDesc(bookingId)).thenReturn(List.of(tx));
        when(refundRepository.findByBookingIdOrderByRequestedAtDesc(bookingId)).thenReturn(List.of());

        when(assessmentRepository.findByTargetKeyAndIsCurrentTrueForUpdate(anyString())).thenReturn(Optional.empty());
        when(assessmentRepository.saveAndFlush(any(ReservationDepositAssessment.class))).thenAnswer(inv -> inv.getArgument(0));

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
        assertThat(res.collectedVerifiedAmount()).isEqualByComparingTo("300000.0000");
        assertThat(res.waiverMode()).isEqualTo(DepositWaiverMode.NONE);
        assertThat(res.isCurrent()).isTrue();
        assertThat(res.assessmentRevision()).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC01: Paid then refunded money is deducted from verified collected advance")
    void assessDeposit_whenPaidThenRefunded_deductsRefundFromCollectedAdvance() {
        RuleCatalogue rule = new RuleCatalogue(
                "DEP-STD", "Standard Deposit", RuleType.DEPOSIT, 1, "Desc",
                "{\"fixedDepositAmount\":300000}", Instant.now().minus(1, ChronoUnit.DAYS), null, "SYSTEM"
        );

        when(idempotencyRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(ruleCatalogueRepository.findActiveEffectiveRulesByTypeAndCode(eq(RuleType.DEPOSIT), eq("DEP-STD"), any()))
                .thenReturn(List.of(rule));

        // 500k paid
        PaymentTransaction tx = createPaymentTransaction(bookingId, new BigDecimal("500000.0000"), "VND", TransactionStatus.SUCCEEDED);
        when(paymentTransactionRepository.findByBookingIdOrderByOccurredAtDesc(bookingId)).thenReturn(List.of(tx));

        // 300k refunded
        Refund refund = createRefund(bookingId, new BigDecimal("300000.0000"), "VND");
        when(refundRepository.findByBookingIdOrderByRequestedAtDesc(bookingId)).thenReturn(List.of(refund));

        when(assessmentRepository.findByTargetKeyAndIsCurrentTrueForUpdate(anyString())).thenReturn(Optional.empty());
        when(assessmentRepository.saveAndFlush(any(ReservationDepositAssessment.class))).thenAnswer(inv -> inv.getArgument(0));

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
                "idemp-refund-test",
                "corr-ref-test"
        );

        DepositAssessmentResponse res = service.assessDeposit(cmd);

        // Required 300k, Net paid = 500k - 300k = 200k -> 200k < 300k -> PAYMENT_REQUIRED
        assertThat(res.collectedVerifiedAmount()).isEqualByComparingTo("200000.0000");
        assertThat(res.status()).isEqualTo(DepositAssessmentStatus.PAYMENT_REQUIRED);
    }

    @Test
    @DisplayName("AC02: Missing or ineffective rule does NOT fallback 100% or pick first rule, returns PENDING fail-closed")
    void assessDeposit_whenRuleMissing_returnsPendingFailClosed() {
        when(idempotencyRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(ruleCatalogueRepository.findActiveEffectiveRulesByTypeAndCode(any(), any(), any()))
                .thenReturn(List.of()); // No matching rule

        when(assessmentRepository.findByTargetKeyAndIsCurrentTrueForUpdate(anyString())).thenReturn(Optional.empty());
        when(assessmentRepository.saveAndFlush(any(ReservationDepositAssessment.class))).thenAnswer(inv -> inv.getArgument(0));

        AssessDepositCommand cmd = new AssessDepositCommand(
                "booking:" + bookingId,
                bookingId,
                null,
                null,
                propertyId,
                customerId,
                new BigDecimal("1000000.0000"),
                "VND",
                "NON-EXISTENT-RULE",
                null,
                null,
                Instant.now().plus(2, ChronoUnit.HOURS),
                "idemp-no-rule",
                "corr-no-rule"
        );

        DepositAssessmentResponse res = service.assessDeposit(cmd);

        assertThat(res.status()).isEqualTo(DepositAssessmentStatus.PENDING);
        assertThat(res.waiverMode()).isEqualTo(DepositWaiverMode.NONE);
        assertThat(res.ruleCatalogueId()).isNull();
    }

    @Test
    @DisplayName("AC01: Zero deposit rule policy produces WAIVED_FINAL with ZERO_DEPOSIT_POLICY")
    void assessDeposit_zeroDepositPolicy_returnsWaivedFinal() {
        RuleCatalogue rule = new RuleCatalogue(
                "DEP-ZERO", "Zero Deposit", RuleType.DEPOSIT, 1, "Desc",
                "{\"depositRate\":0.0}", Instant.now().minus(1, ChronoUnit.DAYS), null, "SYSTEM"
        );

        when(idempotencyRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(ruleCatalogueRepository.findActiveEffectiveRulesByTypeAndCode(eq(RuleType.DEPOSIT), eq("DEP-ZERO"), any()))
                .thenReturn(List.of(rule));
        when(assessmentRepository.findByTargetKeyAndIsCurrentTrueForUpdate(anyString())).thenReturn(Optional.empty());
        when(assessmentRepository.saveAndFlush(any(ReservationDepositAssessment.class))).thenAnswer(inv -> inv.getArgument(0));

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
    @DisplayName("AC02: Expiry at creation time produces STALE status")
    void assessDeposit_whenExpiredAtCreation_returnsStale() {
        RuleCatalogue rule = new RuleCatalogue(
                "DEP-STD", "Standard Deposit", RuleType.DEPOSIT, 1, "Desc",
                "{\"depositRate\":0.3}", Instant.now().minus(1, ChronoUnit.DAYS), null, "SYSTEM"
        );

        when(idempotencyRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(ruleCatalogueRepository.findActiveEffectiveRulesByTypeAndCode(eq(RuleType.DEPOSIT), eq("DEP-STD"), any()))
                .thenReturn(List.of(rule));
        when(assessmentRepository.findByTargetKeyAndIsCurrentTrueForUpdate(anyString())).thenReturn(Optional.empty());
        when(assessmentRepository.saveAndFlush(any(ReservationDepositAssessment.class))).thenAnswer(inv -> inv.getArgument(0));

        // expiresAt in the past
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
                null,
                null,
                Instant.now().minus(10, ChronoUnit.MINUTES),
                "idemp-expired",
                "corr-expired"
        );

        DepositAssessmentResponse res = service.assessDeposit(cmd);
        assertThat(res.status()).isEqualTo(DepositAssessmentStatus.STALE);
    }

    @Test
    @DisplayName("AC02: Querying expired decision returns STALE status at point in time")
    void getCurrentDecision_whenExpired_returnsStale() {
        ReservationDepositAssessment expiredAssessment = new ReservationDepositAssessment(
                "hold:123", null, null, UUID.randomUUID(), propertyId, customerId, 1L,
                "fingerprint", null, 1, null, null, new BigDecimal("500000.0000"),
                new BigDecimal("500000.0000"), "VND", DepositWaiverMode.NONE,
                DepositAssessmentStatus.VERIFIED_PAID, Instant.now().minus(5, ChronoUnit.MINUTES),
                "idemp-1", "corr-1"
        );

        when(assessmentRepository.findByTargetKeyAndIsCurrentTrue("hold:123"))
                .thenReturn(Optional.of(expiredAssessment));

        DepositAssessmentResponse res = service.getCurrentDecision("hold:123");
        assertThat(res.status()).isEqualTo(DepositAssessmentStatus.STALE);
    }

    @Test
    @DisplayName("AC02: Waiving an expired assessment throws IllegalStateException")
    void waiveDeposit_whenExpired_throwsException() {
        ReservationDepositAssessment expired = new ReservationDepositAssessment(
                "hold:123", null, null, UUID.randomUUID(), propertyId, customerId, 1L,
                "fingerprint", null, 1, null, null, new BigDecimal("500000.0000"),
                BigDecimal.ZERO, "VND", DepositWaiverMode.NONE,
                DepositAssessmentStatus.PAYMENT_REQUIRED, Instant.now().minus(10, ChronoUnit.MINUTES),
                "idemp-orig", "corr-orig"
        );

        when(idempotencyRepository.findByIdempotencyKey("idemp-waive-exp")).thenReturn(Optional.empty());
        when(assessmentRepository.findByTargetKeyAndIsCurrentTrueForUpdate("hold:123")).thenReturn(Optional.of(expired));

        WaiveDepositCommand cmd = new WaiveDepositCommand(
                "hold:123", adminUserId, "Reason", 1L, 0L, "idemp-waive-exp", "corr-exp"
        );

        assertThatThrownBy(() -> service.waiveDeposit(cmd))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANNOT_WAIVE_EXPIRED");
    }

    @Test
    @DisplayName("AC02: Cross-scope waiver is rejected with 403 Forbidden")
    void waiveDeposit_whenUnauthorizedScope_throwsForbidden() {
        ReservationDepositAssessment assessment = new ReservationDepositAssessment(
                "hold:123", bookingId, null, UUID.randomUUID(), propertyId, customerId, 1L,
                "fingerprint", null, 1, null, null, new BigDecimal("500000.0000"),
                BigDecimal.ZERO, "VND", DepositWaiverMode.NONE,
                DepositAssessmentStatus.PAYMENT_REQUIRED, Instant.now().plus(1, ChronoUnit.HOURS),
                "idemp-orig", "corr-orig"
        );

        when(idempotencyRepository.findByIdempotencyKey("idemp-waive-cross")).thenReturn(Optional.empty());
        when(assessmentRepository.findByTargetKeyAndIsCurrentTrueForUpdate("hold:123")).thenReturn(Optional.of(assessment));

        // Actor has PARTITION scope, but requireProperty throws 403 because it belongs to another branch
        var actor = createActor(ReservationDepositAssessmentController.DEPOSIT_WAIVE, Scope.PARTITION);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Record outside scope"))
                .when(authorization).requireProperty(actor, propertyId);

        WaiveDepositCommand cmd = new WaiveDepositCommand(
                "hold:123", adminUserId, "Cross scope waive", 1L, 0L, "idemp-waive-cross", "corr-cross"
        );

        assertThatThrownBy(() -> service.waiveDeposit(cmd, actor))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("AC02: Retrying waiver with old expected revision when revision has moved throws STALE_REVISION")
    void waiveDeposit_staleRevision_throwsException() {
        ReservationDepositAssessment currentRev2 = new ReservationDepositAssessment(
                "hold:123", null, null, UUID.randomUUID(), propertyId, customerId, 2L,
                "fingerprint", null, 1, null, null, new BigDecimal("500000.0000"),
                BigDecimal.ZERO, "VND", DepositWaiverMode.NONE,
                DepositAssessmentStatus.PAYMENT_REQUIRED, Instant.now().plus(1, ChronoUnit.HOURS),
                "idemp-rev2", "corr-rev2"
        );

        when(idempotencyRepository.findByIdempotencyKey("idemp-waive-stale")).thenReturn(Optional.empty());
        when(assessmentRepository.findByTargetKeyAndIsCurrentTrueForUpdate("hold:123")).thenReturn(Optional.of(currentRev2));

        // Caller expected revision 1, but database is already on revision 2
        WaiveDepositCommand cmd = new WaiveDepositCommand(
                "hold:123", adminUserId, "Stale waiver retry", 1L, 0L, "idemp-waive-stale", "corr-stale"
        );

        assertThatThrownBy(() -> service.waiveDeposit(cmd))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STALE_REVISION");
    }

    @Test
    @DisplayName("AC03: Idempotent replay of assess command returns cached immutable response payload")
    void assessDeposit_idempotentReplay_returnsCachedResponse() throws Exception {
        String key = "idemp-replay-cached";
        AssessDepositCommand cmd = new AssessDepositCommand(
                "booking:" + bookingId, bookingId, null, null, propertyId, customerId,
                new BigDecimal("1000000.0000"), "VND", "DEP-STD", 1L, "TR-1",
                Instant.now().plus(2, ChronoUnit.HOURS), key, "corr"
        );

        DepositAssessmentResponse cachedResponse = new DepositAssessmentResponse(
                UUID.randomUUID(), cmd.targetKey(), bookingId, null, null, propertyId, customerId,
                1L, true, "fp", UUID.randomUUID(), 1, 1L, "TR-1",
                new BigDecimal("300000.0000"), new BigDecimal("300000.0000"), "VND",
                DepositWaiverMode.NONE, null, null, null, DepositAssessmentStatus.VERIFIED_PAID,
                cmd.expiresAt(), key, "corr", 0L, Instant.now(), Instant.now()
        );
        String cachedJson = objectMapper.writeValueAsString(cachedResponse);

        FinanceCommandIdempotency idemp = new FinanceCommandIdempotency(
                key, "ASSESS_DEPOSIT", "ADMIN", adminUserId, "different-hash",
                CommandIdempotencyStatus.SUCCEEDED, "RESERVATION_DEPOSIT_ASSESSMENT",
                cachedResponse.id(), cachedJson, null, cmd.expiresAt()
        );

        // When requestHash does not match:
        when(idempotencyRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(idemp));

        assertThatThrownBy(() -> service.assessDeposit(cmd, createActor(ReservationDepositAssessmentController.DEPOSIT_ASSESS, Scope.ALL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    @DisplayName("AC03: Concurrent in-flight command with status PROCESSING throws CONCURRENT_OPERATION")
    void assessDeposit_whenProcessing_throwsConcurrentOperation() {
        String key = "idemp-concurrent";
        AssessDepositCommand cmd = new AssessDepositCommand(
                "booking:" + bookingId, bookingId, null, null, propertyId, customerId,
                new BigDecimal("1000000.0000"), "VND", null, null, null,
                Instant.now().plus(2, ChronoUnit.HOURS), key, "corr"
        );

        FinanceCommandIdempotency processingIdemp = new FinanceCommandIdempotency(
                key, "ASSESS_DEPOSIT", "ADMIN", adminUserId, "hash",
                CommandIdempotencyStatus.PROCESSING, null, null, null, null, cmd.expiresAt()
        );

        when(idempotencyRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(processingIdemp));

        assertThatThrownBy(() -> service.assessDeposit(cmd))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONCURRENT_OPERATION");
    }

    @Test
    @DisplayName("Field-group projection: toSafeSummary redacts internal and audit metadata")
    void depositAssessmentResponse_toSafeSummary_redactsSensitiveFields() {
        DepositAssessmentResponse full = new DepositAssessmentResponse(
                UUID.randomUUID(), "hold:123", bookingId, null, null, propertyId, customerId,
                1L, true, "secret-fingerprint-64char", UUID.randomUUID(), 1, 42L, "TR-DECISION-REF",
                new BigDecimal("500000.0000"), BigDecimal.ZERO, "VND", DepositWaiverMode.MANUAL_WAIVER,
                "Confidential waiver reason", adminUserId, Instant.now(), DepositAssessmentStatus.WAIVED_FINAL,
                Instant.now().plus(1, ChronoUnit.HOURS), "idemp-key-secret", "corr-id-secret",
                0L, Instant.now(), Instant.now()
        );

        DepositAssessmentResponse safe = full.toSafeSummary();

        assertThat(safe.inputFingerprint()).isNull();
        assertThat(safe.trustInputVersion()).isNull();
        assertThat(safe.trustDecisionRef()).isNull();
        assertThat(safe.waiverReason()).isNull();
        assertThat(safe.waiverApprovedByAdminUserId()).isNull();
        assertThat(safe.idempotencyKey()).isNull();
        assertThat(safe.correlationId()).isNull();

        // Baseline fields preserved
        assertThat(safe.targetKey()).isEqualTo("hold:123");
        assertThat(safe.propertyId()).isEqualTo(propertyId);
        assertThat(safe.status()).isEqualTo(DepositAssessmentStatus.WAIVED_FINAL);
        assertThat(safe.requiredAmount()).isEqualByComparingTo("500000.0000");
    }
}
