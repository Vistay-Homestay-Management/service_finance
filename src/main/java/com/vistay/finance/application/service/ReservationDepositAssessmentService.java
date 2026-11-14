package com.vistay.finance.application.service;

import com.vistay.finance.adapter.in.rest.FinanceOperationalAuthorization;
import com.vistay.finance.application.FinanceAuthorizationClient;
import com.vistay.finance.application.dto.AssessDepositCommand;
import com.vistay.finance.application.dto.DepositAssessmentResponse;
import com.vistay.finance.application.dto.WaiveDepositCommand;
import com.vistay.finance.domain.model.*;
import com.vistay.finance.domain.repository.FinanceCommandIdempotencyRepository;
import com.vistay.finance.domain.repository.PaymentTransactionRepository;
import com.vistay.finance.domain.repository.RefundRepository;
import com.vistay.finance.domain.repository.ReservationDepositAssessmentRepository;
import com.vistay.finance.domain.repository.RuleCatalogueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
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
    private final RefundRepository refundRepository;
    private final FinanceCommandIdempotencyRepository idempotencyRepository;
    private final FinanceOperationalAuthorization authorization;
    private final ObjectMapper objectMapper;

    public ReservationDepositAssessmentService(ReservationDepositAssessmentRepository assessmentRepository,
                                               RuleCatalogueRepository ruleCatalogueRepository,
                                               PaymentTransactionRepository paymentTransactionRepository,
                                               RefundRepository refundRepository,
                                               FinanceCommandIdempotencyRepository idempotencyRepository,
                                               FinanceOperationalAuthorization authorization,
                                               ObjectMapper objectMapper) {
        this.assessmentRepository = assessmentRepository;
        this.ruleCatalogueRepository = ruleCatalogueRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.refundRepository = refundRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.authorization = authorization;
        this.objectMapper = objectMapper;
    }

    public DepositAssessmentResponse assessDeposit(AssessDepositCommand cmd) {
        return assessDeposit(cmd, null);
    }

    public DepositAssessmentResponse assessDeposit(AssessDepositCommand cmd, FinanceOperationalAuthorization.Actor actor) {
        String commandType = "ASSESS_DEPOSIT";
        String actorType = (actor != null && actor.permission() != null && actor.permission().startsWith("finance:")) ? "ADMIN" : "SYSTEM";
        UUID actorId = (actor != null && actor.profileId() != null) ? actor.profileId() : (cmd.customerId() != null ? cmd.customerId() : cmd.propertyId());
        String fingerprint = computeFingerprint(cmd);

        // 1. Idempotency Check
        Optional<FinanceCommandIdempotency> existingByIdempotency = idempotencyRepository.findByIdempotencyKey(cmd.idempotencyKey());
        if (existingByIdempotency.isPresent()) {
            FinanceCommandIdempotency existing = existingByIdempotency.get();
            if (existing.isSucceeded()) {
                if (!existing.getCommandType().equals(commandType)
                        || !existing.getRequestHash().equals(fingerprint)
                        || (actor != null && !existing.getActorId().equals(actorId))) {
                    throw new IllegalStateException("IDEMPOTENCY_KEY_REUSED: Input parameters or actor differ for key " + cmd.idempotencyKey());
                }
                log.info("Idempotent replay for deposit assessment key: {}", cmd.idempotencyKey());
                if (existing.getResponsePayload() != null && !existing.getResponsePayload().isBlank()) {
                    try {
                        return objectMapper.readValue(existing.getResponsePayload(), DepositAssessmentResponse.class);
                    } catch (Exception e) {
                        log.warn("Failed to deserialize cached response payload: {}", e.getMessage());
                    }
                }
                return assessmentRepository.findById(existing.getResourceId())
                        .map(DepositAssessmentResponse::from)
                        .orElseThrow(() -> new IllegalStateException("Stored assessment not found for idempotency key"));
            } else if (existing.isProcessing()) {
                throw new IllegalStateException("CONCURRENT_OPERATION: Operation currently processing for idempotency key: " + cmd.idempotencyKey());
            } else if (existing.isFailed()) {
                throw new IllegalStateException("IDEMPOTENCY_FAILED: Prior command failed with code " + existing.getErrorCode());
            }
        }

        // 2. Resolve Active DEPOSIT Rule (No fallback 100%, no picking unrelated first rule)
        Instant now = Instant.now();
        RuleCatalogue matchedRule = resolveDepositRule(cmd.ruleCode(), now);

        UUID ruleId = null;
        int ruleVersion = 0;
        BigDecimal requiredAmount = BigDecimal.ZERO;
        DepositWaiverMode waiverMode = DepositWaiverMode.NONE;
        DepositAssessmentStatus status;

        if (matchedRule != null) {
            ruleId = matchedRule.getId();
            ruleVersion = matchedRule.getRuleVersion();
            RuleEvaluationResult eval = evaluateDepositRule(matchedRule, cmd.quoteTotalAmount());
            requiredAmount = eval.requiredAmount();
            waiverMode = eval.waiverMode();
            status = eval.status();
        } else {
            // Fail-closed / non-final when no effective deposit rule is matched
            requiredAmount = cmd.quoteTotalAmount();
            waiverMode = DepositWaiverMode.NONE;
            status = DepositAssessmentStatus.PENDING;
        }

        // 3. Expiry Check at creation time
        if (cmd.expiresAt().isBefore(now) || cmd.expiresAt().equals(now)) {
            status = DepositAssessmentStatus.STALE;
        }

        // 4. Financial Collection and Verification (if not already waived or stale)
        BigDecimal collectedVerifiedAmount = BigDecimal.ZERO;
        if (status != DepositAssessmentStatus.WAIVED_FINAL && status != DepositAssessmentStatus.STALE && status != DepositAssessmentStatus.PENDING) {
            if (cmd.bookingId() != null) {
                collectedVerifiedAmount = calculateNetVerifiedAdvance(cmd.bookingId(), cmd.currency());
            }
            if (collectedVerifiedAmount.compareTo(requiredAmount) >= 0 && requiredAmount.compareTo(BigDecimal.ZERO) > 0) {
                status = DepositAssessmentStatus.VERIFIED_PAID;
            } else {
                status = DepositAssessmentStatus.PAYMENT_REQUIRED;
            }
        }

        // 5. Supersede current active assessment if one exists for targetKey
        long nextRevision = 1;
        Optional<ReservationDepositAssessment> currentOpt = assessmentRepository.findByTargetKeyAndIsCurrentTrueForUpdate(cmd.targetKey());
        if (currentOpt.isPresent()) {
            ReservationDepositAssessment current = currentOpt.get();
            current.supersede();
            assessmentRepository.saveAndFlush(current); // Flush UPDATE before INSERT to prevent partial unique index collision
            nextRevision = current.getAssessmentRevision() + 1;
        }

        // 6. Persist Assessment with actual collected verified amount
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
                collectedVerifiedAmount,
                cmd.currency(),
                waiverMode,
                status,
                cmd.expiresAt(),
                cmd.idempotencyKey(),
                cmd.correlationId()
        );

        ReservationDepositAssessment saved = assessmentRepository.saveAndFlush(assessment);
        DepositAssessmentResponse response = DepositAssessmentResponse.from(saved);

        // 7. Record in FinanceCommandIdempotency with immutable response payload
        String responsePayloadJson;
        try {
            responsePayloadJson = objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            responsePayloadJson = "{\"id\":\"" + saved.getId() + "\",\"status\":\"" + saved.getStatus() + "\"}";
        }

        FinanceCommandIdempotency idempotencyRecord = new FinanceCommandIdempotency(
                cmd.idempotencyKey(),
                commandType,
                actorType,
                actorId,
                fingerprint,
                CommandIdempotencyStatus.SUCCEEDED,
                "RESERVATION_DEPOSIT_ASSESSMENT",
                saved.getId(),
                responsePayloadJson,
                null,
                cmd.expiresAt()
        );
        idempotencyRepository.save(idempotencyRecord);

        log.info("Created deposit assessment id: {}, target: {}, revision: {}, status: {}",
                saved.getId(), saved.getTargetKey(), saved.getAssessmentRevision(), saved.getStatus());
        return response;
    }

    public DepositAssessmentResponse waiveDeposit(WaiveDepositCommand cmd) {
        return waiveDeposit(cmd, null);
    }

    public DepositAssessmentResponse waiveDeposit(WaiveDepositCommand cmd, FinanceOperationalAuthorization.Actor actor) {
        String commandType = "WAIVE_DEPOSIT";
        String actorType = "ADMIN";
        UUID actorId = (actor != null && actor.profileId() != null) ? actor.profileId() : cmd.approverAdminId();
        String requestHash = computeSha256("WAIVE:" + cmd.targetKey() + ":" + actorId + ":" + cmd.reason() + ":" + cmd.expectedRevision() + ":" + cmd.expectedVersion());

        // 1. Idempotency Check for Waiver
        Optional<FinanceCommandIdempotency> existingOpt = idempotencyRepository.findByIdempotencyKey(cmd.idempotencyKey());
        if (existingOpt.isPresent()) {
            FinanceCommandIdempotency existing = existingOpt.get();
            if (existing.isSucceeded()) {
                if (!existing.getCommandType().equals(commandType)
                        || !existing.getRequestHash().equals(requestHash)
                        || !existing.getActorId().equals(actorId)) {
                    throw new IllegalStateException("IDEMPOTENCY_KEY_REUSED: Idempotency key reused with different request payload or actor for key " + cmd.idempotencyKey());
                }
                log.info("Idempotent replay for deposit waiver key: {}", cmd.idempotencyKey());
                if (existing.getResponsePayload() != null && !existing.getResponsePayload().isBlank()) {
                    try {
                        return objectMapper.readValue(existing.getResponsePayload(), DepositAssessmentResponse.class);
                    } catch (Exception e) {
                        log.warn("Failed to deserialize cached waiver response: {}", e.getMessage());
                    }
                }
            } else if (existing.isProcessing()) {
                throw new IllegalStateException("CONCURRENT_OPERATION: Operation currently processing for idempotency key: " + cmd.idempotencyKey());
            }
        }

        // 2. Resolve Active Current Assessment
        ReservationDepositAssessment assessment = assessmentRepository.findByTargetKeyAndIsCurrentTrueForUpdate(cmd.targetKey())
                .orElseThrow(() -> new NoSuchElementException("Active deposit assessment not found for target key: " + cmd.targetKey()));

        // 3. Authorize Scope against Stored Record
        if (actor != null) {
            authorizeDepositAccess(actor, assessment.getPropertyId(), assessment.getBookingId());
        }

        // 4. Validate CAS: Expected Revision and Expected Version
        if (cmd.expectedRevision() != null && assessment.getAssessmentRevision() != cmd.expectedRevision()) {
            throw new IllegalStateException("STALE_REVISION: Expected assessment revision " + cmd.expectedRevision() + " but current is " + assessment.getAssessmentRevision());
        }
        if (cmd.expectedVersion() != null && assessment.getVersion() != cmd.expectedVersion()) {
            throw new IllegalStateException("OPTIMISTIC_LOCK_CONFLICT: Expected assessment version " + cmd.expectedVersion() + " but current is " + assessment.getVersion());
        }

        // 5. Check Expiry
        Instant now = Instant.now();
        if (assessment.getExpiresAt().isBefore(now) || assessment.getExpiresAt().equals(now)) {
            throw new IllegalStateException("CANNOT_WAIVE_EXPIRED: Cannot waive an expired deposit assessment; assessment expired at " + assessment.getExpiresAt());
        }

        // 6. Validate State Transitions
        if (assessment.getStatus() == DepositAssessmentStatus.WAIVED_FINAL) {
            throw new IllegalStateException("ALREADY_WAIVED: Assessment is already in final waived state");
        }
        if (assessment.getStatus() == DepositAssessmentStatus.VERIFIED_PAID) {
            throw new IllegalStateException("CANNOT_WAIVE_PAID: Cannot waive a deposit that has already been verified and paid");
        }
        if (assessment.getStatus() == DepositAssessmentStatus.STALE) {
            throw new IllegalStateException("CANNOT_WAIVE_STALE: Cannot waive a stale assessment");
        }

        // 7. Apply Waiver Mutation
        assessment.applyWaiver(actorId, cmd.reason());
        ReservationDepositAssessment updated = assessmentRepository.saveAndFlush(assessment);
        DepositAssessmentResponse response = DepositAssessmentResponse.from(updated);

        // 8. Record in FinanceCommandIdempotency
        String responsePayloadJson;
        try {
            responsePayloadJson = objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            responsePayloadJson = "{\"id\":\"" + updated.getId() + "\",\"status\":\"WAIVED_FINAL\"}";
        }

        FinanceCommandIdempotency idempotencyRecord = new FinanceCommandIdempotency(
                cmd.idempotencyKey(),
                commandType,
                actorType,
                actorId,
                requestHash,
                CommandIdempotencyStatus.SUCCEEDED,
                "RESERVATION_DEPOSIT_ASSESSMENT",
                updated.getId(),
                responsePayloadJson,
                null,
                updated.getExpiresAt()
        );
        idempotencyRepository.save(idempotencyRecord);

        log.info("Deposit waived for target: {}, approver: {}", cmd.targetKey(), actorId);
        return response;
    }

    @Transactional(readOnly = true)
    public DepositAssessmentResponse getCurrentDecision(String targetKey) {
        ReservationDepositAssessment assessment = assessmentRepository.findByTargetKeyAndIsCurrentTrue(targetKey)
                .orElseThrow(() -> new NoSuchElementException("Active deposit assessment not found for target key: " + targetKey));
        return mapDecisionValidity(assessment);
    }

    @Transactional(readOnly = true)
    public DepositAssessmentResponse getCurrentDecisionByBookingId(UUID bookingId) {
        ReservationDepositAssessment assessment = assessmentRepository.findByBookingIdAndIsCurrentTrue(bookingId)
                .orElseThrow(() -> new NoSuchElementException("Active deposit assessment not found for booking id: " + bookingId));
        return mapDecisionValidity(assessment);
    }

    public void authorizeDepositAccess(FinanceOperationalAuthorization.Actor actor, UUID propertyId, UUID bookingId) {
        if (actor == null) return;
        var scopes = actor.snapshot().scopes(actor.permission());
        if (scopes.contains(FinanceAuthorizationClient.Scope.ALL)) {
            return;
        }
        boolean authorized = false;
        if (scopes.contains(FinanceAuthorizationClient.Scope.PARTITION) && propertyId != null) {
            try {
                authorization.requireProperty(actor, propertyId);
                authorized = true;
            } catch (ResponseStatusException e) {
                if (e.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) throw e;
            }
        }
        if (!authorized && scopes.contains(FinanceAuthorizationClient.Scope.ASSIGNED) && bookingId != null) {
            try {
                authorization.requireBooking(actor, bookingId);
                authorized = true;
            } catch (ResponseStatusException e) {
                if (e.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) throw e;
            }
        }
        if (!authorized) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Record is outside the authorized scope for " + actor.permission());
        }
    }

    public BigDecimal calculateNetVerifiedAdvance(UUID bookingId, String expectedCurrency) {
        if (bookingId == null) {
            return BigDecimal.ZERO;
        }
        List<PaymentTransaction> transactions = paymentTransactionRepository.findByBookingIdOrderByOccurredAtDesc(bookingId);
        List<Refund> refunds = refundRepository.findByBookingIdOrderByRequestedAtDesc(bookingId);

        BigDecimal verifiedPaid = transactions.stream()
                .filter(t -> t.getTransactionType() == TransactionType.PAYMENT && t.getStatus() == TransactionStatus.SUCCEEDED)
                .filter(t -> expectedCurrency == null || expectedCurrency.equalsIgnoreCase(t.getCurrency()))
                .map(PaymentTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal nonFailedRefunds = refunds.stream()
                .filter(r -> r.getStatus() != RefundStatus.FAILED && r.getStatus() != RefundStatus.REJECTED && r.getStatus() != RefundStatus.CANCELLED)
                .map(Refund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal net = verifiedPaid.subtract(nonFailedRefunds);
        return (net.compareTo(BigDecimal.ZERO) < 0) ? BigDecimal.ZERO : net;
    }

    private DepositAssessmentResponse mapDecisionValidity(ReservationDepositAssessment assessment) {
        DepositAssessmentResponse res = DepositAssessmentResponse.from(assessment);
        Instant now = Instant.now();
        if (assessment.getExpiresAt().isBefore(now) || assessment.getExpiresAt().equals(now)) {
            return new DepositAssessmentResponse(
                    res.id(), res.targetKey(), res.bookingId(), res.quoteId(), res.holdId(),
                    res.propertyId(), res.customerId(), res.assessmentRevision(), res.isCurrent(),
                    res.inputFingerprint(), res.ruleCatalogueId(), res.ruleVersion(),
                    res.trustInputVersion(), res.trustDecisionRef(), res.requiredAmount(),
                    res.collectedVerifiedAmount(), res.currency(), res.waiverMode(),
                    res.waiverReason(), res.waiverApprovedByAdminUserId(), res.waiverApprovedAt(),
                    DepositAssessmentStatus.STALE, res.expiresAt(), res.idempotencyKey(),
                    res.correlationId(), res.version(), res.createdAt(), res.updatedAt()
            );
        }
        return res;
    }

    private RuleCatalogue resolveDepositRule(String ruleCode, Instant at) {
        if (ruleCode != null && !ruleCode.isBlank()) {
            List<RuleCatalogue> rules = ruleCatalogueRepository.findActiveEffectiveRulesByTypeAndCode(RuleType.DEPOSIT, ruleCode.trim(), at);
            if (!rules.isEmpty()) {
                return rules.get(0);
            }
        }
        return null;
    }

    private RuleEvaluationResult evaluateDepositRule(RuleCatalogue rule, BigDecimal quoteTotal) {
        if (rule == null) {
            return new RuleEvaluationResult(quoteTotal, DepositWaiverMode.NONE, DepositAssessmentStatus.PENDING);
        }
        try {
            JsonNode root = objectMapper.readTree(rule.getParametersJson());
            if (root.has("fixedDepositAmount")) {
                BigDecimal fixed = new BigDecimal(root.get("fixedDepositAmount").asText());
                if (fixed.compareTo(BigDecimal.ZERO) == 0) {
                    return new RuleEvaluationResult(BigDecimal.ZERO, DepositWaiverMode.ZERO_DEPOSIT_POLICY, DepositAssessmentStatus.WAIVED_FINAL);
                } else if (fixed.compareTo(BigDecimal.ZERO) > 0) {
                    return new RuleEvaluationResult(fixed.setScale(4, RoundingMode.HALF_UP), DepositWaiverMode.NONE, DepositAssessmentStatus.PAYMENT_REQUIRED);
                }
            }
            if (root.has("depositRate")) {
                BigDecimal rate = new BigDecimal(root.get("depositRate").asText());
                if (rate.compareTo(BigDecimal.ZERO) == 0) {
                    return new RuleEvaluationResult(BigDecimal.ZERO, DepositWaiverMode.ZERO_DEPOSIT_POLICY, DepositAssessmentStatus.WAIVED_FINAL);
                } else if (rate.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal calculated = quoteTotal.multiply(rate).setScale(4, RoundingMode.HALF_UP);
                    return new RuleEvaluationResult(calculated, DepositWaiverMode.NONE, DepositAssessmentStatus.PAYMENT_REQUIRED);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse deposit rule parameters: {}", e.getMessage());
            return new RuleEvaluationResult(quoteTotal, DepositWaiverMode.NONE, DepositAssessmentStatus.PENDING);
        }
        return new RuleEvaluationResult(quoteTotal, DepositWaiverMode.NONE, DepositAssessmentStatus.PAYMENT_REQUIRED);
    }

    private String computeFingerprint(AssessDepositCommand cmd) {
        String canonical = String.join("|",
                cmd.targetKey(),
                cmd.propertyId().toString(),
                cmd.customerId() != null ? cmd.customerId().toString() : "null",
                cmd.bookingId() != null ? cmd.bookingId().toString() : "null",
                cmd.quoteId() != null ? cmd.quoteId().toString() : "null",
                cmd.holdId() != null ? cmd.holdId().toString() : "null",
                cmd.stayProductId() != null ? cmd.stayProductId().toString() : "null",
                cmd.unitId() != null ? cmd.unitId().toString() : "null",
                cmd.checkInDate() != null ? cmd.checkInDate() : "null",
                cmd.checkOutDate() != null ? cmd.checkOutDate() : "null",
                cmd.quoteTotalAmount().stripTrailingZeros().toPlainString(),
                cmd.currency() != null ? cmd.currency().trim().toUpperCase() : "VND",
                cmd.ruleCode() != null ? cmd.ruleCode().trim() : "null",
                cmd.trustInputVersion() != null ? cmd.trustInputVersion().toString() : "null",
                cmd.trustDecisionRef() != null ? cmd.trustDecisionRef().trim() : "null",
                cmd.expiresAt().toString()
        );
        return computeSha256(canonical);
    }

    private String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private record RuleEvaluationResult(BigDecimal requiredAmount, DepositWaiverMode waiverMode, DepositAssessmentStatus status) {}
}
