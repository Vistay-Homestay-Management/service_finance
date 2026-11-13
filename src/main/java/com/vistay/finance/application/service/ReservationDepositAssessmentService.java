package com.vistay.finance.application.service;

import com.vistay.finance.application.dto.AssessDepositCommand;
import com.vistay.finance.application.dto.DepositAssessmentResponse;
import com.vistay.finance.application.dto.WaiveDepositCommand;
import com.vistay.finance.domain.model.*;
import com.vistay.finance.domain.repository.FinanceCommandIdempotencyRepository;
import com.vistay.finance.domain.repository.PaymentTransactionRepository;
import com.vistay.finance.domain.repository.ReservationDepositAssessmentRepository;
import com.vistay.finance.domain.repository.RuleCatalogueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ReservationDepositAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(ReservationDepositAssessmentService.class);

    private final ReservationDepositAssessmentRepository assessmentRepository;
    private final RuleCatalogueRepository ruleCatalogueRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final FinanceCommandIdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public ReservationDepositAssessmentService(ReservationDepositAssessmentRepository assessmentRepository,
                                              RuleCatalogueRepository ruleCatalogueRepository,
                                              PaymentTransactionRepository paymentTransactionRepository,
                                              FinanceCommandIdempotencyRepository idempotencyRepository,
                                              ObjectMapper objectMapper) {
        this.assessmentRepository = assessmentRepository;
        this.ruleCatalogueRepository = ruleCatalogueRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    public DepositAssessmentResponse assessDeposit(AssessDepositCommand cmd) {
        String fingerprint = computeFingerprint(cmd);

        // 1. Idempotency Check
        Optional<ReservationDepositAssessment> existingByIdempotency = assessmentRepository.findByIdempotencyKey(cmd.idempotencyKey());
        if (existingByIdempotency.isPresent()) {
            ReservationDepositAssessment existing = existingByIdempotency.get();
            if (!existing.getInputFingerprint().equals(fingerprint)) {
                throw new IllegalStateException("IDEMPOTENCY_KEY_REUSED: Input parameters differ for key " + cmd.idempotencyKey());
            }
            log.info("Idempotent hit for deposit assessment key: {}", cmd.idempotencyKey());
            return DepositAssessmentResponse.from(existing);
        }

        // 2. Resolve Active DEPOSIT Rule
        Instant now = Instant.now();
        RuleCatalogue matchedRule = resolveDepositRule(cmd.ruleCode(), now);

        UUID ruleId = null;
        int ruleVersion = 1;
        BigDecimal requiredAmount;

        if (matchedRule != null) {
            ruleId = matchedRule.getId();
            ruleVersion = matchedRule.getRuleVersion();
            requiredAmount = calculateRequiredAmount(matchedRule.getParametersJson(), cmd.quoteTotalAmount());
        } else {
            // Default 100% deposit if no specific catalogue rule is active
            requiredAmount = cmd.quoteTotalAmount();
        }

        // 3. Evaluate Status & Verification
        DepositWaiverMode waiverMode = DepositWaiverMode.NONE;
        DepositAssessmentStatus status;

        if (requiredAmount.compareTo(BigDecimal.ZERO) == 0) {
            waiverMode = DepositWaiverMode.ZERO_DEPOSIT_POLICY;
            status = DepositAssessmentStatus.WAIVED_FINAL;
        } else {
            BigDecimal collectedAmount = BigDecimal.ZERO;
            if (cmd.bookingId() != null) {
                collectedAmount = paymentTransactionRepository.calculateTotalPaidAmountByBookingId(cmd.bookingId());
                if (collectedAmount == null) {
                    collectedAmount = BigDecimal.ZERO;
                }
            }

            if (collectedAmount.compareTo(requiredAmount) >= 0) {
                status = DepositAssessmentStatus.VERIFIED_PAID;
            } else if (cmd.expiresAt().isBefore(now)) {
                status = DepositAssessmentStatus.STALE;
            } else {
                status = DepositAssessmentStatus.PAYMENT_REQUIRED;
            }
        }

        // 4. Supersede current active assessment if one exists for targetKey
        long nextRevision = 1;
        Optional<ReservationDepositAssessment> currentOpt = assessmentRepository.findByTargetKeyAndIsCurrentTrue(cmd.targetKey());
        if (currentOpt.isPresent()) {
            ReservationDepositAssessment current = currentOpt.get();
            current.supersede();
            assessmentRepository.save(current);
            nextRevision = current.getAssessmentRevision() + 1;
        }

        // 5. Persist Assessment
        ReservationDepositAssessment assessment = new ReservationDepositAssessment(
                cmd.targetKey(),
                cmd.bookingId(),
                cmd.quoteId(),
                cmd.holdId(),
                cmd.propertyId(),
                cmd.customerId(),
                nextRevision,
                fingerprint,
                ruleId,
                ruleVersion,
                cmd.trustInputVersion(),
                cmd.trustDecisionRef(),
                requiredAmount,
                BigDecimal.ZERO,
                cmd.currency(),
                waiverMode,
                status,
                cmd.expiresAt(),
                cmd.idempotencyKey(),
                cmd.correlationId()
        );

        ReservationDepositAssessment saved = assessmentRepository.save(assessment);

        // 6. Record in FinanceCommandIdempotency
        FinanceCommandIdempotency idempotencyRecord = new FinanceCommandIdempotency(
                cmd.idempotencyKey(),
                "ASSESS_DEPOSIT",
                "SYSTEM",
                cmd.propertyId(),
                fingerprint,
                "SUCCEEDED",
                "RESERVATION_DEPOSIT_ASSESSMENT",
                saved.getId(),
                "{\"status\":\"" + saved.getStatus() + "\",\"requiredAmount\":\"" + saved.getRequiredAmount() + "\"}",
                null,
                cmd.expiresAt()
        );
        idempotencyRepository.save(idempotencyRecord);

        log.info("Created deposit assessment id: {}, target: {}, revision: {}, status: {}",
                saved.getId(), saved.getTargetKey(), saved.getAssessmentRevision(), saved.getStatus());
        return DepositAssessmentResponse.from(saved);
    }

    public DepositAssessmentResponse waiveDeposit(WaiveDepositCommand cmd) {
        // Idempotency check
        Optional<ReservationDepositAssessment> existingByIdempotency = assessmentRepository.findByIdempotencyKey(cmd.idempotencyKey());
        if (existingByIdempotency.isPresent()) {
            return DepositAssessmentResponse.from(existingByIdempotency.get());
        }

        ReservationDepositAssessment assessment = assessmentRepository.findByTargetKeyAndIsCurrentTrue(cmd.targetKey())
                .orElseThrow(() -> new NoSuchElementException("Active deposit assessment not found for target key: " + cmd.targetKey()));

        assessment.applyWaiver(cmd.approverAdminId(), cmd.reason());
        ReservationDepositAssessment updated = assessmentRepository.save(assessment);

        log.info("Deposit waived for target: {}, approver: {}", cmd.targetKey(), cmd.approverAdminId());
        return DepositAssessmentResponse.from(updated);
    }

    @Transactional(readOnly = true)
    public DepositAssessmentResponse getCurrentDecision(String targetKey) {
        return assessmentRepository.findByTargetKeyAndIsCurrentTrue(targetKey)
                .map(DepositAssessmentResponse::from)
                .orElseThrow(() -> new NoSuchElementException("Active deposit assessment not found for target key: " + targetKey));
    }

    @Transactional(readOnly = true)
    public DepositAssessmentResponse getCurrentDecisionByBookingId(UUID bookingId) {
        return assessmentRepository.findByBookingIdAndIsCurrentTrue(bookingId)
                .map(DepositAssessmentResponse::from)
                .orElseThrow(() -> new NoSuchElementException("Active deposit assessment not found for booking id: " + bookingId));
    }

    private RuleCatalogue resolveDepositRule(String ruleCode, Instant at) {
        if (ruleCode != null && !ruleCode.isBlank()) {
            List<RuleCatalogue> rules = ruleCatalogueRepository.findActiveEffectiveRulesByTypeAndCode(RuleType.DEPOSIT, ruleCode.trim(), at);
            if (!rules.isEmpty()) {
                return rules.get(0);
            }
        }
        List<RuleCatalogue> rules = ruleCatalogueRepository.findActiveEffectiveRulesByType(RuleType.DEPOSIT, at);
        return rules.isEmpty() ? null : rules.get(0);
    }

    private BigDecimal calculateRequiredAmount(String parametersJson, BigDecimal quoteTotal) {
        if (parametersJson == null || parametersJson.isBlank()) {
            return quoteTotal;
        }
        try {
            JsonNode root = objectMapper.readTree(parametersJson);
            if (root.has("fixedDepositAmount")) {
                BigDecimal fixed = new BigDecimal(root.get("fixedDepositAmount").asText());
                if (fixed.compareTo(BigDecimal.ZERO) >= 0) {
                    return fixed.setScale(4, RoundingMode.HALF_UP);
                }
            }
            if (root.has("depositRate")) {
                BigDecimal rate = new BigDecimal(root.get("depositRate").asText());
                return quoteTotal.multiply(rate).setScale(4, RoundingMode.HALF_UP);
            }
        } catch (Exception e) {
            log.warn("Failed to parse deposit rule parameters, defaulting to quote total: {}", e.getMessage());
        }
        return quoteTotal;
    }

    private String computeFingerprint(AssessDepositCommand cmd) {
        String canonical = String.join("|",
                cmd.targetKey(),
                cmd.propertyId().toString(),
                cmd.customerId() != null ? cmd.customerId().toString() : "null",
                cmd.quoteTotalAmount().stripTrailingZeros().toPlainString(),
                cmd.currency() != null ? cmd.currency().trim().toUpperCase() : "VND",
                cmd.ruleCode() != null ? cmd.ruleCode().trim() : "null",
                cmd.trustInputVersion() != null ? cmd.trustInputVersion().toString() : "null"
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
