package com.vistay.finance.application.service;

import com.vistay.finance.application.dto.AmendmentProposalSnapshot;
import com.vistay.finance.application.dto.ApproveAmendmentWaiverCommand;
import com.vistay.finance.application.dto.AssessReservationAmendmentCommand;
import com.vistay.finance.application.dto.ReservationAmendmentAssessmentResponse;
import com.vistay.finance.application.dto.ReservationAmendmentExecutedCommand;
import com.vistay.finance.domain.model.AmendmentAssessmentStatus;
import com.vistay.finance.domain.model.AmendmentRequiredAction;
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
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReservationAmendmentAssessmentService {

    private final ReservationAmendmentAssessmentRepository assessmentRepository;
    private final RuleCatalogueRepository ruleRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RefundRepository refundRepository;
    private final ReservationAmendmentLedgerSupport ledgerSupport;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public record AuthoritativeScopeContext(UUID propertyId, UUID customerId) {}

    public ReservationAmendmentAssessmentService(
        ReservationAmendmentAssessmentRepository assessmentRepository,
        RuleCatalogueRepository ruleRepository,
        PaymentTransactionRepository paymentTransactionRepository,
        RefundRepository refundRepository,
        ReservationAmendmentLedgerSupport ledgerSupport,
        ObjectMapper objectMapper,
        @Autowired(required = false) MeterRegistry meterRegistry
    ) {
        this.assessmentRepository = assessmentRepository;
        this.ruleRepository = ruleRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.refundRepository = refundRepository;
        this.ledgerSupport = ledgerSupport;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ReservationAmendmentAssessmentResponse assessAmendment(
        AssessReservationAmendmentCommand cmd,
        UUID actorId,
        Set<ReservationFieldGroup> grantedGroups
    ) {
        String beforeFp = cmd.beforeSnapshot().canonicalSha256();
        String afterFp = cmd.afterSnapshot().canonicalSha256();
        String requestHash = computeSha256(String.join("|",
            cmd.bookingId().toString(),
            cmd.proposalId().trim(),
            beforeFp,
            afterFp,
            cmd.expiresAt().toString()
        ));

        var idem = ledgerSupport.checkAndLockIdempotency(
            cmd.idempotencyKey(),
            "ASSESS_RESERVATION_AMENDMENT",
            "ADMIN",
            actorId != null ? actorId : UUID.fromString("00000000-0000-0000-0000-000000000001"),
            requestHash,
            cmd.expiresAt()
        );

        if (idem.replayed()) {
            var existingOpt = assessmentRepository.findByIdempotencyKey(cmd.idempotencyKey());
            if (existingOpt.isPresent()) {
                var existing = existingOpt.get();
                recordMetric("replayed", existing.getStatus().name());
                return ReservationAmendmentAssessmentResponse.fromEntity(
                    existing,
                    grantedGroups,
                    existing.getStatus().isExecutableFinal(),
                    true
                );
            }
        }

        Instant now = Instant.now();
        RuleCatalogue effectiveRule = resolveDepositRule(cmd.ruleCode(), now);

        BigDecimal beforeReq = calculateRequiredAmount(effectiveRule, cmd.beforeSnapshot());
        BigDecimal afterReq = calculateRequiredAmount(effectiveRule, cmd.afterSnapshot());

        BigDecimal totalPaid = paymentTransactionRepository.calculateTotalPaidAmountByBookingId(cmd.bookingId());
        if (totalPaid == null) totalPaid = BigDecimal.ZERO;
        BigDecimal totalRefunded = paymentTransactionRepository.calculateTotalRefundedAmountByBookingId(cmd.bookingId());
        if (totalRefunded == null) totalRefunded = BigDecimal.ZERO;
        BigDecimal verifiedCollected = totalPaid.subtract(totalRefunded);
        if (verifiedCollected.compareTo(BigDecimal.ZERO) < 0) {
            verifiedCollected = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal delta = afterReq.subtract(verifiedCollected);

        AmendmentRequiredAction action;
        AmendmentAssessmentStatus status;

        if (cmd.afterSnapshot().manualWaiverRequested()) {
            action = AmendmentRequiredAction.WAIVER_REVIEW;
            status = AmendmentAssessmentStatus.WAIVER_REVIEW;
        } else if (delta.compareTo(BigDecimal.ZERO) > 0) {
            action = AmendmentRequiredAction.COLLECTION_REQUIRED;
            status = AmendmentAssessmentStatus.COLLECTION_REQUIRED;
        } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
            action = AmendmentRequiredAction.REFUND_DUE;
            status = AmendmentAssessmentStatus.REFUND_DUE;
        } else {
            action = AmendmentRequiredAction.NO_CHANGE;
            status = AmendmentAssessmentStatus.VERIFIED;
        }

        Optional<ReservationAmendmentAssessment> currentOpt = assessmentRepository.findCurrentForUpdate(
            cmd.bookingId(),
            cmd.proposalId()
        );

        long revision = 1L;
        if (currentOpt.isPresent()) {
            ReservationAmendmentAssessment current = currentOpt.get();
            if (cmd.expectedVersion() != null && !cmd.expectedVersion().equals(current.getVersion())) {
                throw new ReservationAmendmentException(
                    HttpStatus.CONFLICT,
                    "OPTIMISTIC_LOCK_CONFLICT",
                    "Amendment assessment version mismatch for booking " + cmd.bookingId()
                );
            }
            revision = current.getAmendmentRevision() + 1;
            current.markStale(now);
            assessmentRepository.saveAndFlush(current);
        } else {
            revision = assessmentRepository.findTopByBookingIdAndProposalIdOrderByAmendmentRevisionDesc(
                cmd.bookingId(),
                cmd.proposalId()
            ).map(a -> a.getAmendmentRevision() + 1).orElse(1L);
        }

        String correlationId = cmd.correlationId() != null && !cmd.correlationId().isBlank()
            ? cmd.correlationId().trim()
            : UUID.randomUUID().toString();

        ReservationAmendmentAssessment assessment = new ReservationAmendmentAssessment(
            UUID.randomUUID(),
            cmd.bookingId(),
            cmd.afterSnapshot().propertyId(),
            cmd.afterSnapshot().customerId(),
            cmd.proposalId(),
            revision,
            beforeFp,
            afterFp,
            effectiveRule.getId(),
            effectiveRule.getRuleVersion(),
            beforeReq,
            afterReq,
            verifiedCollected,
            delta,
            cmd.afterSnapshot().currency(),
            action,
            status,
            actorId,
            cmd.expiresAt(),
            cmd.idempotencyKey(),
            correlationId
        );

        ReservationAmendmentAssessment saved = assessmentRepository.saveAndFlush(assessment);

        Map<String, Object> outboxPayload = new HashMap<>();
        outboxPayload.put("assessmentId", saved.getId().toString());
        outboxPayload.put("bookingId", saved.getBookingId().toString());
        outboxPayload.put("proposalId", saved.getProposalId());
        outboxPayload.put("revision", saved.getAmendmentRevision());
        outboxPayload.put("status", saved.getStatus().name());
        outboxPayload.put("requiredAction", saved.getRequiredAction().name());
        outboxPayload.put("deltaAmount", saved.getDeltaAmount().toPlainString());
        outboxPayload.put("currency", saved.getCurrency());

        ledgerSupport.recordOutboxEvent(
            "RESERVATION_AMENDMENT_ASSESSMENT",
            saved.getId(),
            saved.getVersion() != null ? saved.getVersion() : 0L,
            "vistay.finance.amendment-assessed.v1",
            1,
            outboxPayload,
            correlationId,
            cmd.idempotencyKey()
        );

        ledgerSupport.recordAuditEvent(
            actorId,
            "ASSESS_RESERVATION_AMENDMENT",
            "RESERVATION_AMENDMENT_ASSESSMENT",
            saved.getId(),
            outboxPayload,
            correlationId
        );

        ReservationAmendmentAssessmentResponse response = ReservationAmendmentAssessmentResponse.fromEntity(
            saved,
            grantedGroups,
            saved.getStatus().isExecutableFinal(),
            false
        );

        ledgerSupport.recordSucceeded(
            cmd.idempotencyKey(),
            "RESERVATION_AMENDMENT_ASSESSMENT",
            saved.getId(),
            response
        );

        recordMetric("assessed", status.name());
        return response;
    }

    @Transactional(readOnly = true)
    public AuthoritativeScopeContext resolveAuthoritativeScope(UUID bookingId, String proposalId) {
        var assessmentOpt = assessmentRepository.findFirstByBookingIdAndProposalIdAndCurrentTrue(bookingId, proposalId)
            .or(() -> assessmentRepository.findTopByBookingIdAndProposalIdOrderByAmendmentRevisionDesc(bookingId, proposalId));
        if (assessmentOpt.isPresent()) {
            var a = assessmentOpt.get();
            return new AuthoritativeScopeContext(a.getPropertyId(), a.getCustomerId());
        }
        var bookingOpt = ledgerSupport.findBookingContext(bookingId);
        if (bookingOpt.isPresent()) {
            var b = bookingOpt.get();
            return new AuthoritativeScopeContext(b.propertyId(), b.customerId());
        }
        throw new ReservationAmendmentException(
            HttpStatus.NOT_FOUND,
            "ASSESSMENT_NOT_FOUND",
            "No authoritative amendment or booking record found for booking " + bookingId + " proposal " + proposalId
        );
    }

    @Transactional(readOnly = true)
    public AuthoritativeScopeContext resolveOrVerifyAssessScope(UUID bookingId, UUID suppliedPropertyId, UUID suppliedCustomerId) {
        var bookingOpt = ledgerSupport.findBookingContext(bookingId);
        if (bookingOpt.isPresent()) {
            var b = bookingOpt.get();
            if (suppliedPropertyId != null && !suppliedPropertyId.equals(b.propertyId())) {
                throw new ReservationAmendmentException(
                    HttpStatus.FORBIDDEN,
                    "PROPERTY_SPOOF_ATTEMPT",
                    "Supplied property " + suppliedPropertyId + " does not match authoritative booking property " + b.propertyId()
                );
            }
            return new AuthoritativeScopeContext(b.propertyId(), b.customerId());
        }
        var latestAssessments = assessmentRepository.findByBookingIdOrderByCreatedAtDesc(bookingId);
        if (!latestAssessments.isEmpty()) {
            var a = latestAssessments.get(0);
            if (suppliedPropertyId != null && !suppliedPropertyId.equals(a.getPropertyId())) {
                throw new ReservationAmendmentException(
                    HttpStatus.FORBIDDEN,
                    "PROPERTY_SPOOF_ATTEMPT",
                    "Supplied property " + suppliedPropertyId + " does not match authoritative booking property " + a.getPropertyId()
                );
            }
            return new AuthoritativeScopeContext(a.getPropertyId(), a.getCustomerId());
        }
        return new AuthoritativeScopeContext(suppliedPropertyId, suppliedCustomerId);
    }

    @Transactional(readOnly = true)
    public ReservationAmendmentAssessmentResponse getFinalDecision(
        UUID bookingId,
        String proposalId,
        String expectedAfterFingerprint,
        Long expectedVersion,
        Set<ReservationFieldGroup> grantedGroups
    ) {
        ReservationAmendmentAssessment assessment = assessmentRepository.findFirstByBookingIdAndProposalIdAndCurrentTrue(
            bookingId,
            proposalId
        ).orElseThrow(() -> new ReservationAmendmentException(
            HttpStatus.NOT_FOUND,
            "ASSESSMENT_NOT_FOUND",
            "No active amendment assessment found for booking " + bookingId + " proposal " + proposalId
        ));

        if (expectedVersion != null && !expectedVersion.equals(assessment.getVersion())) {
            throw new ReservationAmendmentException(
                HttpStatus.CONFLICT,
                "OPTIMISTIC_LOCK_CONFLICT",
                "Amendment assessment version mismatch"
            );
        }

        Instant now = Instant.now();
        if (!assessment.getExpiresAt().isAfter(now)) {
            return ReservationAmendmentAssessmentResponse.fromEntityWithStatus(
                assessment,
                AmendmentAssessmentStatus.STALE,
                grantedGroups,
                false,
                false
            );
        }

        if (expectedAfterFingerprint != null && !expectedAfterFingerprint.isBlank()) {
            if (!assessment.getAfterSnapshotFingerprint().equalsIgnoreCase(expectedAfterFingerprint.trim())) {
                return ReservationAmendmentAssessmentResponse.fromEntityWithStatus(
                    assessment,
                    AmendmentAssessmentStatus.STALE,
                    grantedGroups,
                    false,
                    false
                );
            }
        }

        boolean executable = assessment.getStatus().isExecutableFinal();
        return ReservationAmendmentAssessmentResponse.fromEntity(assessment, grantedGroups, executable, false);
    }

    @Transactional
    public ReservationAmendmentAssessmentResponse verifyCollection(
        UUID bookingId,
        String proposalId,
        Long expectedVersion,
        String idempotencyKey,
        String correlationId,
        UUID actorId,
        Set<ReservationFieldGroup> grantedGroups
    ) {
        ReservationAmendmentAssessment assessment = assessmentRepository.findCurrentForUpdate(
            bookingId,
            proposalId
        ).orElseThrow(() -> new ReservationAmendmentException(
            HttpStatus.NOT_FOUND,
            "ASSESSMENT_NOT_FOUND",
            "No current amendment assessment found for booking " + bookingId + " proposal " + proposalId
        ));

        if (expectedVersion != null && !expectedVersion.equals(assessment.getVersion())) {
            throw new ReservationAmendmentException(
                HttpStatus.CONFLICT,
                "OPTIMISTIC_LOCK_CONFLICT",
                "Amendment assessment version mismatch"
            );
        }

        if (assessment.getStatus() != AmendmentAssessmentStatus.COLLECTION_REQUIRED) {
            throw new ReservationAmendmentException(
                HttpStatus.CONFLICT,
                "INVALID_STATUS_FOR_COLLECTION_VERIFICATION",
                "Collection verification is only allowed when assessment is in COLLECTION_REQUIRED status, current: " + assessment.getStatus()
            );
        }

        Instant now = Instant.now();
        if (!assessment.getExpiresAt().isAfter(now)) {
            throw new ReservationAmendmentException(
                HttpStatus.CONFLICT,
                "HOLD_OR_PROPOSAL_EXPIRED",
                "Amendment hold or proposal has expired"
            );
        }

        BigDecimal totalPaid = paymentTransactionRepository.calculateTotalPaidAmountByBookingId(bookingId);
        if (totalPaid == null) totalPaid = BigDecimal.ZERO;
        BigDecimal totalRefunded = paymentTransactionRepository.calculateTotalRefundedAmountByBookingId(bookingId);
        if (totalRefunded == null) totalRefunded = BigDecimal.ZERO;
        BigDecimal verified = totalPaid.subtract(totalRefunded);
        if (verified.compareTo(BigDecimal.ZERO) < 0) {
            verified = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        if (verified.compareTo(assessment.getAfterRequiredAmount()) >= 0) {
            assessment.verifyCollectionSatisfied(verified, now);
            ReservationAmendmentAssessment saved = assessmentRepository.saveAndFlush(assessment);

            Map<String, Object> outbox = Map.of(
                "assessmentId", saved.getId().toString(),
                "bookingId", saved.getBookingId().toString(),
                "proposalId", saved.getProposalId(),
                "status", saved.getStatus().name()
            );

            ledgerSupport.recordOutboxEvent(
                "RESERVATION_AMENDMENT_ASSESSMENT",
                saved.getId(),
                saved.getVersion(),
                "vistay.finance.amendment-verified.v1",
                1,
                outbox,
                correlationId,
                idempotencyKey
            );

            ledgerSupport.recordAuditEvent(
                actorId,
                "VERIFY_AMENDMENT_COLLECTION",
                "RESERVATION_AMENDMENT_ASSESSMENT",
                saved.getId(),
                outbox,
                correlationId
            );

            recordMetric("verified", saved.getStatus().name());
            return ReservationAmendmentAssessmentResponse.fromEntity(saved, grantedGroups, true, false);
        }

        return ReservationAmendmentAssessmentResponse.fromEntity(assessment, grantedGroups, false, false);
    }

    @Transactional
    public ReservationAmendmentAssessmentResponse approveWaiver(
        ApproveAmendmentWaiverCommand cmd,
        UUID actorId,
        Set<ReservationFieldGroup> grantedGroups
    ) {
        String requestHash = computeSha256(String.join("|",
            cmd.bookingId().toString(),
            cmd.proposalId().trim(),
            cmd.reason().trim(),
            actorId != null ? actorId.toString() : "NONE"
        ));

        var idem = ledgerSupport.checkAndLockIdempotency(
            cmd.idempotencyKey(),
            "APPROVE_AMENDMENT_WAIVER",
            "ADMIN",
            actorId != null ? actorId : UUID.fromString("00000000-0000-0000-0000-000000000001"),
            requestHash,
            Instant.now().plusSeconds(86400)
        );

        ReservationAmendmentAssessment assessment = assessmentRepository.findCurrentForUpdate(
            cmd.bookingId(),
            cmd.proposalId()
        ).orElseThrow(() -> new ReservationAmendmentException(
            HttpStatus.NOT_FOUND,
            "ASSESSMENT_NOT_FOUND",
            "No current amendment assessment found for booking " + cmd.bookingId()
        ));

        if (idem.replayed()) {
            return ReservationAmendmentAssessmentResponse.fromEntity(
                assessment,
                grantedGroups,
                assessment.getStatus().isExecutableFinal(),
                true
            );
        }

        if (cmd.expectedVersion() != null && !cmd.expectedVersion().equals(assessment.getVersion())) {
            throw new ReservationAmendmentException(
                HttpStatus.CONFLICT,
                "OPTIMISTIC_LOCK_CONFLICT",
                "Amendment assessment version mismatch"
            );
        }

        if (assessment.getAssessedByActorId() != null && assessment.getAssessedByActorId().equals(actorId)) {
            throw new ReservationAmendmentException(
                HttpStatus.FORBIDDEN,
                "MAKER_CHECKER_VIOLATION",
                "Assessor cannot approve their own amendment waiver"
            );
        }

        Instant now = Instant.now();
        if (!assessment.getExpiresAt().isAfter(now)) {
            throw new ReservationAmendmentException(
                HttpStatus.CONFLICT,
                "HOLD_OR_PROPOSAL_EXPIRED",
                "Amendment proposal has expired"
            );
        }

        try {
            assessment.approveWaiver(actorId, cmd.reason(), now);
        } catch (IllegalStateException ise) {
            throw new ReservationAmendmentException(
                HttpStatus.CONFLICT,
                ise.getMessage(),
                ise.getMessage()
            );
        }

        ReservationAmendmentAssessment saved = assessmentRepository.saveAndFlush(assessment);

        Map<String, Object> audit = Map.of(
            "assessmentId", saved.getId().toString(),
            "bookingId", saved.getBookingId().toString(),
            "proposalId", saved.getProposalId(),
            "approverId", actorId.toString(),
            "reason", cmd.reason()
        );

        ledgerSupport.recordOutboxEvent(
            "RESERVATION_AMENDMENT_ASSESSMENT",
            saved.getId(),
            saved.getVersion(),
            "vistay.finance.amendment-waiver-approved.v1",
            1,
            audit,
            cmd.correlationId(),
            cmd.idempotencyKey()
        );

        ledgerSupport.recordAuditEvent(
            actorId,
            "APPROVE_AMENDMENT_WAIVER",
            "RESERVATION_AMENDMENT_ASSESSMENT",
            saved.getId(),
            audit,
            cmd.correlationId()
        );

        ReservationAmendmentAssessmentResponse response = ReservationAmendmentAssessmentResponse.fromEntity(
            saved,
            grantedGroups,
            true,
            false
        );

        ledgerSupport.recordSucceeded(
            cmd.idempotencyKey(),
            "RESERVATION_AMENDMENT_ASSESSMENT",
            saved.getId(),
            response
        );

        recordMetric("waiver_approved", saved.getStatus().name());
        return response;
    }

    @Transactional
    public void processAmendmentExecutedEvent(ReservationAmendmentExecutedCommand cmd) {
        boolean claimed = ledgerSupport.tryClaimEvent(cmd.eventId(), "finance-amendment-executed-v1");
        if (!claimed) {
            return;
        }

        ReservationAmendmentAssessment assessment = assessmentRepository.findCurrentForUpdate(
            cmd.bookingId(),
            cmd.proposalId()
        ).orElseThrow(() -> new ReservationAmendmentException(
            HttpStatus.NOT_FOUND,
            "ASSESSMENT_NOT_FOUND",
            "Cannot execute amendment: assessment not found for booking " + cmd.bookingId() + " proposal " + cmd.proposalId()
        ));

        if (!assessment.getAfterSnapshotFingerprint().equalsIgnoreCase(cmd.afterSnapshotFingerprint().trim())) {
            throw new ReservationAmendmentException(
                HttpStatus.CONFLICT,
                "AMENDMENT_FINGERPRINT_MISMATCH",
                "Executed event fingerprint does not match assessed amendment snapshot"
            );
        }

        if (!assessment.getStatus().isExecutableFinal()) {
            throw new ReservationAmendmentException(
                HttpStatus.CONFLICT,
                "AMENDMENT_NOT_EXECUTABLE",
                "Cannot execute amendment in non-final status " + assessment.getStatus()
            );
        }

        UUID resultingReceivableId = null;
        UUID resultingRefundId = null;

        if (assessment.getStatus() == AmendmentAssessmentStatus.WAIVED_FINAL) {
            // Waiver was approved by checker, no compensating obligation needed.
        } else if (assessment.getDeltaAmount().compareTo(BigDecimal.ZERO) > 0) {
            UUID customerId = cmd.customerId() != null ? cmd.customerId() : assessment.getCustomerId();
            if (customerId == null) {
                customerId = ledgerSupport.findCustomerIdByBookingId(cmd.bookingId()).orElse(null);
            }
            if (customerId == null) {
                throw new ReservationAmendmentException(
                    HttpStatus.BAD_REQUEST,
                    "CUSTOMER_DEBTOR_REQUIRED",
                    "Customer debtor id is required to create amendment receivable"
                );
            }
            boolean fullyCollected = assessment.getVerifiedCollectedAmount().compareTo(assessment.getAfterRequiredAmount()) >= 0;
            if (fullyCollected) {
                resultingReceivableId = ledgerSupport.insertSettledCompensatingReceivable(
                    cmd.bookingId(),
                    customerId,
                    cmd.proposalId(),
                    cmd.eventId(),
                    cmd.dueDate(),
                    assessment.getDeltaAmount(),
                    assessment.getCurrency()
                );
            } else {
                resultingReceivableId = ledgerSupport.insertCompensatingReceivable(
                    cmd.bookingId(),
                    customerId,
                    cmd.proposalId(),
                    cmd.eventId(),
                    cmd.dueDate(),
                    assessment.getDeltaAmount(),
                    assessment.getCurrency()
                );
            }
        } else if (assessment.getDeltaAmount().compareTo(BigDecimal.ZERO) < 0) {
            var originalTx = paymentTransactionRepository.findFirstByBookingIdAndTransactionTypeAndStatusOrderByOccurredAtDesc(
                cmd.bookingId(),
                TransactionType.PAYMENT,
                TransactionStatus.SUCCEEDED
            ).orElseThrow(() -> new ReservationAmendmentException(
                HttpStatus.CONFLICT,
                "ORIGINAL_PAYMENT_TRANSACTION_NOT_FOUND",
                "No succeeded original payment transaction found to issue refund"
            ));

            UUID adminUserId = cmd.actorAdminUserId() != null
                ? cmd.actorAdminUserId()
                : (assessment.getAssessedByActorId() != null
                    ? assessment.getAssessedByActorId()
                    : UUID.fromString("00000000-0000-0000-0000-000000000001"));

            BigDecimal refundAmount = assessment.getDeltaAmount().abs();
            String refundCode = "REF-AMD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            String idemKey = "amendment-refund:" + cmd.eventId();

            Refund refund = new Refund(
                UUID.randomUUID(),
                refundCode,
                cmd.bookingId(),
                originalTx.getId(),
                refundAmount,
                assessment.getCurrency(),
                "Compensating refund for amendment " + cmd.proposalId(),
                adminUserId,
                idemKey
            );
            Refund savedRefund = refundRepository.save(refund);
            resultingRefundId = savedRefund.getId();
        }

        assessment.markExecuted(cmd.eventId(), resultingReceivableId, resultingRefundId, Instant.now());
        assessmentRepository.saveAndFlush(assessment);

        Map<String, Object> outbox = new HashMap<>();
        outbox.put("assessmentId", assessment.getId().toString());
        outbox.put("bookingId", assessment.getBookingId().toString());
        outbox.put("proposalId", assessment.getProposalId());
        outbox.put("executedEventId", cmd.eventId().toString());
        if (resultingReceivableId != null) outbox.put("resultingReceivableId", resultingReceivableId.toString());
        if (resultingRefundId != null) outbox.put("resultingRefundId", resultingRefundId.toString());

        ledgerSupport.recordOutboxEvent(
            "RESERVATION_AMENDMENT_ASSESSMENT",
            assessment.getId(),
            assessment.getVersion(),
            "vistay.finance.amendment-executed.v1",
            1,
            outbox,
            cmd.correlationId(),
            cmd.eventId().toString()
        );

        ledgerSupport.recordAuditEvent(
            cmd.actorAdminUserId(),
            "AMENDMENT_EXECUTED",
            "RESERVATION_AMENDMENT_ASSESSMENT",
            assessment.getId(),
            outbox,
            cmd.correlationId()
        );

        recordMetric("executed", assessment.getStatus().name());
    }

    private RuleCatalogue resolveDepositRule(String ruleCode, Instant at) {
        List<RuleCatalogue> rules;
        if (ruleCode != null && !ruleCode.isBlank()) {
            rules = ruleRepository.findActiveEffectiveRulesByTypeAndCode(RuleType.DEPOSIT, ruleCode.trim(), at);
        } else {
            rules = ruleRepository.findActiveEffectiveRulesByType(RuleType.DEPOSIT, at);
        }
        if (rules.isEmpty()) {
            throw new ReservationAmendmentException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "EFFECTIVE_RULE_UNAVAILABLE",
                "No active effective DEPOSIT rule found for amendment reassessment"
            );
        }
        return rules.get(0);
    }

    private BigDecimal calculateRequiredAmount(RuleCatalogue rule, AmendmentProposalSnapshot snapshot) {
        BigDecimal lodging = snapshot.lodgingAmount() != null ? snapshot.lodgingAmount() : BigDecimal.ZERO;
        String json = rule.getParametersJson();
        if (json != null && !json.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(json);
                if (root.hasNonNull("depositRate")) {
                    BigDecimal rate = new BigDecimal(root.get("depositRate").asText());
                    return lodging.multiply(rate).setScale(4, RoundingMode.HALF_UP);
                }
                if (root.hasNonNull("fixedDepositAmount")) {
                    BigDecimal fixed = new BigDecimal(root.get("fixedDepositAmount").asText());
                    return fixed.setScale(4, RoundingMode.HALF_UP);
                }
            } catch (Exception ex) {
                // fall back to full lodging amount
            }
        }
        return lodging.setScale(4, RoundingMode.HALF_UP);
    }

    private static String computeSha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void recordMetric(String action, String status) {
        if (meterRegistry != null) {
            try {
                meterRegistry.counter("finance.amendment.assessment", "action", action, "status", status).increment();
            } catch (Exception ignored) {
            }
        }
    }
}
