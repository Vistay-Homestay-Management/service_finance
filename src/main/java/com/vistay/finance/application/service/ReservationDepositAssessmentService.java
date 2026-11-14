package com.vistay.finance.application.service;

import com.vistay.finance.adapter.in.rest.FinanceOperationalAuthorization;
import com.vistay.finance.application.FinanceAuthorizationClient;
import com.vistay.finance.application.FinanceBookingAuthorizationClient;
import com.vistay.finance.application.FinancePropertyPartitionClient;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import java.time.Duration;
import java.time.Instant;
import java.util.*;

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
    private final FinancePropertyPartitionClient propertyPartitions;
    private final FinanceBookingAuthorizationClient bookingClient;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    @Autowired
    ReservationDepositAssessmentService(
            ReservationDepositAssessmentRepository assessmentRepository,
            RuleCatalogueRepository ruleCatalogueRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            RefundRepository refundRepository,
            FinanceCommandIdempotencyRepository idempotencyRepository,
            FinanceOperationalAuthorization authorization,
            FinancePropertyPartitionClient propertyPartitions,
            FinanceBookingAuthorizationClient bookingClient,
            JdbcClient jdbcClient,
            ObjectMapper objectMapper
    ) {
        this.assessmentRepository = assessmentRepository;
        this.ruleCatalogueRepository = ruleCatalogueRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.refundRepository = refundRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.authorization = authorization;
        this.propertyPartitions = propertyPartitions;
        this.bookingClient = bookingClient;
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public ReservationDepositAssessmentService(
            ReservationDepositAssessmentRepository assessmentRepository,
            RuleCatalogueRepository ruleCatalogueRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            RefundRepository refundRepository,
            FinanceCommandIdempotencyRepository idempotencyRepository,
            FinanceOperationalAuthorization authorization,
            ObjectMapper objectMapper
    ) {
        this(assessmentRepository, ruleCatalogueRepository, paymentTransactionRepository, refundRepository,
             idempotencyRepository, authorization, null, null, null, objectMapper);
    }

    public DepositAssessmentResponse assessDeposit(AssessDepositCommand cmd, FinanceOperationalAuthorization.Actor actor) {
        Objects.requireNonNull(actor, "Authenticated finance actor is required");
        String commandType = "ASSESS_DEPOSIT";
        String actorType = "ADMIN";
        UUID actorId = Objects.requireNonNull(actor.profileId(), "Authenticated finance profile is required");
        String fingerprint = computeFingerprint(cmd);

        // 1. Authoritative Validation of Property and Booking
        if (propertyPartitions != null && cmd.propertyId() != null) {
            var resolution = propertyPartitions.resolve(cmd.propertyId());
            if (!resolution.mapped()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Property not found or partition unmapped: " + cmd.propertyId());
            }
        }
        if (bookingClient != null && cmd.bookingId() != null) {
            bookingClient.get(cmd.bookingId());
        }

        // 2. Atomic Claim before side effects
        Optional<DepositAssessmentResponse> cached = tryClaimIdempotency(
                cmd.idempotencyKey(), commandType, actorType, actorId, fingerprint, cmd.expiresAt(), actor, cmd.propertyId(), cmd.bookingId()
        );
        if (cached.isPresent()) {
            return cached.get();
        }

        try {
            // 3. Serialize on targetKey
            serializeTargetKey(cmd.targetKey());

            // 4. Resolve Active DEPOSIT Rule (server authority)
            Instant now = Instant.now();
            Instant effectiveExpiresAt = cmd.expiresAt();
            if (effectiveExpiresAt.isBefore(now) || effectiveExpiresAt.equals(now)) {
                // Already expired
            } else if (effectiveExpiresAt.isAfter(now.plus(Duration.ofMinutes(30)))) {
                // Clamped to maximum hold policy duration (30m)
                effectiveExpiresAt = now.plus(Duration.ofMinutes(30));
            }

            RuleCatalogue matchedRule = resolveDepositRule(cmd.ruleCode(), now);
            UUID ruleId = null;
            int ruleVersion = 0;
            BigDecimal requiredAmount;
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

            // Expiry check at creation
            if (effectiveExpiresAt.isBefore(now) || effectiveExpiresAt.equals(now)) {
                status = DepositAssessmentStatus.STALE;
            }

            // 5. Financial Collection and Verification with exact canonical allocation proof
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

            // 6. Supersede current active assessment if one exists for targetKey
            long nextRevision = 1;
            Optional<ReservationDepositAssessment> currentOpt = assessmentRepository.findByTargetKeyAndIsCurrentTrueForUpdate(cmd.targetKey());
            if (currentOpt.isPresent()) {
                ReservationDepositAssessment current = currentOpt.get();
                current.supersede();
                assessmentRepository.saveAndFlush(current); // Flush UPDATE before INSERT to prevent partial unique index collision
                nextRevision = current.getAssessmentRevision() + 1;

                recordOutboxEvent(current.getId(), current.getVersion(), "vistay.finance.deposit-assessment-superseded.v1", Map.of(
                        "assessmentId", current.getId().toString(),
                        "targetKey", current.getTargetKey(),
                        "supersededByRevision", nextRevision
                ), cmd.correlationId());
            }

            // 7. Persist New Assessment
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
                    effectiveExpiresAt,
                    cmd.idempotencyKey(),
                    cmd.correlationId()
            );

            ReservationDepositAssessment saved = assessmentRepository.saveAndFlush(assessment);
            DepositAssessmentResponse response = DepositAssessmentResponse.from(saved);

            // Record outbox event for creation
            Map<String, Object> outboxPayload = new HashMap<>();
            outboxPayload.put("assessmentId", saved.getId().toString());
            outboxPayload.put("targetKey", saved.getTargetKey());
            if (saved.getBookingId() != null) outboxPayload.put("bookingId", saved.getBookingId().toString());
            outboxPayload.put("propertyId", saved.getPropertyId().toString());
            outboxPayload.put("revision", saved.getAssessmentRevision());
            outboxPayload.put("status", saved.getStatus().name());
            outboxPayload.put("requiredAmount", saved.getRequiredAmount().toPlainString());
            outboxPayload.put("collectedVerifiedAmount", saved.getCollectedVerifiedAmount().toPlainString());
            outboxPayload.put("currency", saved.getCurrency());
            outboxPayload.put("waiverMode", saved.getWaiverMode().name());
            recordOutboxEvent(saved.getId(), saved.getVersion(), "vistay.finance.deposit-assessment-created.v1", outboxPayload, cmd.correlationId());

            // 8. Complete Idempotency Record
            completeIdempotency(cmd.idempotencyKey(), saved.getId(), response);

            log.info("Created deposit assessment id: {}, target: {}, revision: {}, status: {}",
                    saved.getId(), saved.getTargetKey(), saved.getAssessmentRevision(), saved.getStatus());
            return response;
        } catch (Exception ex) {
            failIdempotency(cmd.idempotencyKey(), ex);
            throw ex;
        }
    }

    public DepositAssessmentResponse waiveDeposit(WaiveDepositCommand cmd, FinanceOperationalAuthorization.Actor actor) {
        Objects.requireNonNull(actor, "Authenticated finance actor is required");
        String commandType = "WAIVE_DEPOSIT";
        String actorType = "ADMIN";
        UUID actorId = Objects.requireNonNull(actor.profileId(), "Authenticated finance profile is required");
        String requestHash = computeSha256("WAIVE:" + cmd.targetKey() + ":" + actorId + ":" + cmd.reason() + ":" + cmd.expectedRevision() + ":" + cmd.expectedVersion());

        // 1. Atomic Claim before side effects
        Optional<DepositAssessmentResponse> cached = tryClaimIdempotency(
                cmd.idempotencyKey(), commandType, actorType, actorId, requestHash, Instant.now().plus(Duration.ofHours(24)), actor, null, null
        );
        if (cached.isPresent()) {
            return cached.get();
        }

        try {
            // 2. Resolve Active Current Assessment with row lock
            ReservationDepositAssessment assessment = assessmentRepository.findByTargetKeyAndIsCurrentTrueForUpdate(cmd.targetKey())
                    .orElseThrow(() -> new NoSuchElementException("Active deposit assessment not found for target key: " + cmd.targetKey()));

            // 3. Authorize Scope against Stored Record
            authorizeDepositAccess(actor, assessment.getPropertyId(), assessment.getBookingId());

            // 4. Validate CAS: Expected Revision and Expected Version (MANDATORY)
            if (assessment.getAssessmentRevision() != cmd.expectedRevision()) {
                throw new IllegalStateException("STALE_REVISION: Expected assessment revision " + cmd.expectedRevision() + " but current is " + assessment.getAssessmentRevision());
            }
            if (assessment.getVersion() != cmd.expectedVersion()) {
                throw new IllegalStateException("OPTIMISTIC_LOCK_CONFLICT: Expected assessment version " + cmd.expectedVersion() + " but current is " + assessment.getVersion());
            }

            // 5. Check Expiry
            Instant now = Instant.now();
            if (assessment.getExpiresAt().isBefore(now) || assessment.getExpiresAt().equals(now)) {
                throw new IllegalStateException("CANNOT_WAIVE_EXPIRED: Cannot waive an expired deposit assessment; assessment expired at " + assessment.getExpiresAt());
            }

            // 6. Validate State Transitions
            if (assessment.getStatus() == DepositAssessmentStatus.PENDING) {
                throw new IllegalStateException("CANNOT_WAIVE_PENDING: Cannot waive an assessment in PENDING status without resolved deposit policy");
            }
            if (assessment.getStatus() == DepositAssessmentStatus.WAIVED_FINAL) {
                throw new IllegalStateException("ALREADY_WAIVED: Assessment is already in final waived state");
            }
            if (assessment.getStatus() == DepositAssessmentStatus.VERIFIED_PAID) {
                throw new IllegalStateException("CANNOT_WAIVE_PAID: Cannot waive a deposit that has already been verified and paid");
            }
            if (assessment.getStatus() == DepositAssessmentStatus.STALE) {
                throw new IllegalStateException("CANNOT_WAIVE_STALE: Cannot waive a stale assessment");
            }
            if (assessment.getStatus() == DepositAssessmentStatus.FAILED) {
                throw new IllegalStateException("CANNOT_WAIVE_FAILED: Cannot waive a failed assessment");
            }

            // 7. Apply Waiver Mutation
            assessment.applyWaiver(actorId, cmd.reason());
            ReservationDepositAssessment updated = assessmentRepository.saveAndFlush(assessment);
            DepositAssessmentResponse response = DepositAssessmentResponse.from(updated);

            // Record outbox and audit events
            Map<String, Object> waiveOutbox = new HashMap<>();
            waiveOutbox.put("assessmentId", updated.getId().toString());
            waiveOutbox.put("targetKey", updated.getTargetKey());
            waiveOutbox.put("approverAdminId", actorId.toString());
            waiveOutbox.put("waiverReason", cmd.reason());
            waiveOutbox.put("status", updated.getStatus().name());
            waiveOutbox.put("waiverMode", updated.getWaiverMode().name());
            recordOutboxEvent(updated.getId(), updated.getVersion(), "vistay.finance.deposit-assessment-waived.v1", waiveOutbox, cmd.correlationId());

            recordAuditEvent(actorId, "finance:deposit:waive", updated.getId(), Map.of(
                    "waiverMode", updated.getWaiverMode().name(),
                    "status", updated.getStatus().name(),
                    "waiverReason", cmd.reason()
            ), cmd.correlationId());

            // 8. Complete Idempotency
            completeIdempotency(cmd.idempotencyKey(), updated.getId(), response);

            log.info("Deposit waived for target: {}, approver: {}", cmd.targetKey(), actorId);
            return response;
        } catch (Exception ex) {
            failIdempotency(cmd.idempotencyKey(), ex);
            throw ex;
        }
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

    @Transactional(readOnly = true)
    public DepositAssessmentResponse getReservationConfirmationDecision(String targetKey) {
        return getCurrentDecision(targetKey);
    }

    @Transactional(readOnly = true)
    public DepositAssessmentResponse getReservationConfirmationDecisionByBookingId(UUID bookingId) {
        return getCurrentDecisionByBookingId(bookingId);
    }

    public void authorizeDepositAccess(FinanceOperationalAuthorization.Actor actor, UUID propertyId, UUID bookingId) {
        Objects.requireNonNull(actor, "Authenticated finance actor is required");
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

    public BigDecimal calculateNetVerifiedAdvance(UUID bookingId, String currency) {
        if (bookingId == null) {
            return BigDecimal.ZERO;
        }
        String expectedCurrency = (currency != null && !currency.isBlank()) ? currency.trim().toUpperCase() : "VND";

        List<PaymentTransaction> transactions = paymentTransactionRepository.findByBookingIdOrderByOccurredAtDesc(bookingId);
        List<Refund> refunds = refundRepository.findByBookingIdOrderByRequestedAtDesc(bookingId);

        // Sum SUCCEEDED PAYMENTs in expectedCurrency
        Set<UUID> succeededPaymentIds = new HashSet<>();
        BigDecimal verifiedPaid = BigDecimal.ZERO;
        BigDecimal verifiedReversals = BigDecimal.ZERO;

        for (PaymentTransaction t : transactions) {
            if (expectedCurrency.equalsIgnoreCase(t.getCurrency()) && t.getStatus() == TransactionStatus.SUCCEEDED) {
                if (t.getTransactionType() == TransactionType.PAYMENT) {
                    verifiedPaid = verifiedPaid.add(t.getAmount());
                    succeededPaymentIds.add(t.getId());
                } else if (t.getTransactionType() == TransactionType.REFUND || t.getTransactionType() == TransactionType.ADJUSTMENT) {
                    verifiedReversals = verifiedReversals.add(t.getAmount());
                }
            }
        }

        // Deduct non-failed refunds matching bookingId and expectedCurrency
        BigDecimal nonFailedRefunds = BigDecimal.ZERO;
        for (Refund r : refunds) {
            if (bookingId.equals(r.getBookingId())
                    && expectedCurrency.equalsIgnoreCase(r.getCurrency())
                    && r.getStatus() != RefundStatus.FAILED
                    && r.getStatus() != RefundStatus.REJECTED
                    && r.getStatus() != RefundStatus.CANCELLED) {
                nonFailedRefunds = nonFailedRefunds.add(r.getAmount());
            }
        }

        BigDecimal net = verifiedPaid.subtract(verifiedReversals).subtract(nonFailedRefunds);
        return (net.compareTo(BigDecimal.ZERO) < 0) ? BigDecimal.ZERO : net;
    }

    private void serializeTargetKey(String targetKey) {
        if (jdbcClient != null && targetKey != null && !targetKey.isBlank()) {
            try {
                jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtext(:targetKey))")
                        .param("targetKey", targetKey)
                        .query()
                        .singleRow();
            } catch (Exception ignored) {
                // Not on PostgreSQL or mock in unit tests
            }
        }
    }

    private Optional<DepositAssessmentResponse> tryClaimIdempotency(
            String idempotencyKey, String commandType, String actorType, UUID actorId,
            String requestHash, Instant expiresAt, FinanceOperationalAuthorization.Actor actor,
            UUID propertyId, UUID bookingId
    ) {
        boolean claimed = false;
        if (jdbcClient != null) {
            try {
                int updated = jdbcClient.sql("""
                    INSERT INTO app.finance_command_idempotency (
                        idempotency_key, command_type, actor_type, actor_id, request_hash,
                        status, expires_at, created_at
                    ) VALUES (
                        :key, :commandType, :actorType, :actorId, :hash,
                        'PROCESSING', :expiresAt, now()
                    ) ON CONFLICT (idempotency_key) DO NOTHING
                """)
                .param("key", idempotencyKey)
                .param("commandType", commandType)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("hash", requestHash)
                .param("expiresAt", expiresAt)
                .update();
                claimed = (updated > 0);
            } catch (Exception e) {
                log.debug("Atomic claim SQL failed, falling back to repository: {}", e.getMessage());
            }
        }

        if (!claimed) {
            Optional<FinanceCommandIdempotency> existingOpt = idempotencyRepository.findByIdempotencyKey(idempotencyKey);
            if (existingOpt.isEmpty()) {
                existingOpt = idempotencyRepository.findByIdempotencyKeyForUpdate(idempotencyKey);
            }
            if (existingOpt.isPresent()) {
                FinanceCommandIdempotency existing = existingOpt.get();
                if (existing.isProcessing()) {
                    throw new IllegalStateException("CONCURRENT_OPERATION: Operation currently processing for idempotency key: " + idempotencyKey);
                }
                if (existing.isSucceeded()) {
                    if (!existing.getCommandType().equals(commandType)
                            || !existing.getRequestHash().equals(requestHash)
                            || !existing.getActorId().equals(actorId)) {
                        throw new IllegalStateException("IDEMPOTENCY_KEY_REUSED: Input parameters or actor differ for key " + idempotencyKey);
                    }
                    // Record scope check before cached return
                    if (actor != null && (propertyId != null || bookingId != null)) {
                        authorizeDepositAccess(actor, propertyId, bookingId);
                    }
                    if (existing.getResponsePayload() != null && !existing.getResponsePayload().isBlank()) {
                        try {
                            DepositAssessmentResponse cached = objectMapper.readValue(existing.getResponsePayload(), DepositAssessmentResponse.class);
                            log.info("Idempotent replay returning immutable cached response for key: {}", idempotencyKey);
                            return Optional.of(cached);
                        } catch (Exception e) {
                            throw new IllegalStateException("CORRUPTED_IDEMPOTENCY_PAYLOAD: Failed to deserialize cached immutable response for key: " + idempotencyKey, e);
                        }
                    }
                    throw new IllegalStateException("CORRUPTED_IDEMPOTENCY_PAYLOAD: Cached response payload is empty for key: " + idempotencyKey);
                }
                if (existing.isFailed()) {
                    throw new IllegalStateException("IDEMPOTENCY_FAILED: Prior command failed with code " + existing.getErrorCode());
                }
            } else if (jdbcClient == null) {
                // When jdbcClient is absent (plain unit tests), record PROCESSING using JPA
                FinanceCommandIdempotency record = new FinanceCommandIdempotency(
                        idempotencyKey, commandType, actorType, actorId, requestHash,
                        CommandIdempotencyStatus.PROCESSING, "RESERVATION_DEPOSIT_ASSESSMENT", null, null, null, expiresAt
                );
                idempotencyRepository.saveAndFlush(record);
            }
        }
        return Optional.empty();
    }

    private void completeIdempotency(String idempotencyKey, UUID resourceId, DepositAssessmentResponse response) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize response payload for idempotency", e);
        }

        if (jdbcClient != null) {
            try {
                jdbcClient.sql("""
                    UPDATE app.finance_command_idempotency
                    SET status = 'SUCCEEDED',
                        resource_type = 'RESERVATION_DEPOSIT_ASSESSMENT',
                        resource_id = :resourceId,
                        response_payload = CAST(:payload AS jsonb),
                        completed_at = now()
                    WHERE idempotency_key = :key
                """)
                .param("resourceId", resourceId)
                .param("payload", payloadJson)
                .param("key", idempotencyKey)
                .update();
                return;
            } catch (Exception e) {
                log.warn("Failed to complete idempotency via JdbcClient: {}", e.getMessage());
            }
        }

        idempotencyRepository.findByIdempotencyKeyForUpdate(idempotencyKey).ifPresent(record -> {
            record.markSucceeded(resourceId, payloadJson);
            idempotencyRepository.saveAndFlush(record);
        });
    }

    private void failIdempotency(String idempotencyKey, Exception ex) {
        String errorCode = (ex.getMessage() != null && ex.getMessage().length() <= 80) ? ex.getMessage() : "COMMAND_FAILED";
        if (jdbcClient != null) {
            try {
                jdbcClient.sql("""
                    UPDATE app.finance_command_idempotency
                    SET status = 'FAILED',
                        error_code = :errorCode,
                        completed_at = now()
                    WHERE idempotency_key = :key
                """)
                .param("errorCode", errorCode)
                .param("key", idempotencyKey)
                .update();
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            idempotencyRepository.findByIdempotencyKey(idempotencyKey).ifPresent(record -> {
                record.markFailed(errorCode);
                idempotencyRepository.saveAndFlush(record);
            });
        } catch (Exception ignored) {
        }
    }

    private void recordOutboxEvent(UUID aggregateId, long version, String eventType, Map<String, Object> payload, String correlationId) {
        if (jdbcClient == null) return;
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            jdbcClient.sql("""
                INSERT INTO app.outbox_events (
                    aggregate_type, aggregate_id, aggregate_version,
                    event_type, event_version, payload,
                    occurred_at, available_at, correlation_id
                ) VALUES (
                    'ReservationDepositAssessment', :aggregateId, :version,
                    :eventType, 1, CAST(:payload AS jsonb),
                    now(), now(), :correlationId
                )
            """)
            .param("aggregateId", aggregateId)
            .param("version", version)
            .param("eventType", eventType)
            .param("payload", payloadJson)
            .param("correlationId", correlationId)
            .update();
        } catch (Exception ex) {
            log.warn("Failed to persist outbox event {}: {}", eventType, ex.getMessage());
            throw new IllegalStateException("Failed to record transactional outbox event", ex);
        }
    }

    private void recordAuditEvent(UUID actorId, String action, UUID aggregateId, Map<String, Object> changes, String correlationId) {
        if (jdbcClient == null) return;
        try {
            String changesJson = objectMapper.writeValueAsString(changes);
            jdbcClient.sql("""
                INSERT INTO app.audit_events (
                    actor_admin_user_id, action, aggregate_type, aggregate_id,
                    changes, correlation_id, created_at
                ) VALUES (
                    :actorId, :action, 'ReservationDepositAssessment', :aggregateId,
                    CAST(:changes AS jsonb), :correlationId, now()
                )
            """)
            .param("actorId", actorId)
            .param("action", action)
            .param("aggregateId", aggregateId)
            .param("changes", changesJson)
            .param("correlationId", correlationId)
            .update();
        } catch (Exception ex) {
            log.warn("Failed to persist audit event {}: {}", action, ex.getMessage());
            throw new IllegalStateException("Failed to record audit event", ex);
        }
    }

    private DepositAssessmentResponse mapDecisionValidity(ReservationDepositAssessment assessment) {
        DepositAssessmentResponse res = DepositAssessmentResponse.from(assessment);
        Instant now = Instant.now();

        // 1. Timestamp expiry check
        if (assessment.getExpiresAt().isBefore(now) || assessment.getExpiresAt().equals(now)) {
            return withStatus(res, DepositAssessmentStatus.STALE);
        }

        // 2. Material financial change re-validation for VERIFIED_PAID decisions
        if (assessment.getStatus() == DepositAssessmentStatus.VERIFIED_PAID && assessment.getBookingId() != null) {
            BigDecimal currentNet = calculateNetVerifiedAdvance(assessment.getBookingId(), assessment.getCurrency());
            if (currentNet.compareTo(assessment.getRequiredAmount()) < 0) {
                log.warn("Assessment {} was VERIFIED_PAID but current net verified advance {} < required {}; invalidating to STALE",
                        assessment.getId(), currentNet, assessment.getRequiredAmount());
                return withStatus(res, DepositAssessmentStatus.STALE);
            }
        }

        return res;
    }

    private DepositAssessmentResponse withStatus(DepositAssessmentResponse res, DepositAssessmentStatus newStatus) {
        return new DepositAssessmentResponse(
                res.id(), res.targetKey(), res.bookingId(), res.quoteId(), res.holdId(),
                res.propertyId(), res.customerId(), res.assessmentRevision(), res.isCurrent(),
                res.inputFingerprint(), res.ruleCatalogueId(), res.ruleVersion(),
                res.trustInputVersion(), res.trustDecisionRef(), res.requiredAmount(),
                res.collectedVerifiedAmount(), res.currency(), res.waiverMode(),
                res.waiverReason(), res.waiverApprovedByAdminUserId(), res.waiverApprovedAt(),
                newStatus, res.expiresAt(), res.idempotencyKey(),
                res.correlationId(), res.version(), res.createdAt(), res.updatedAt()
        );
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
