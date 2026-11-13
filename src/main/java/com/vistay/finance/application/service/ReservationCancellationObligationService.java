package com.vistay.finance.application.service;

import tools.jackson.databind.ObjectMapper;
import com.vistay.finance.adapter.in.rest.FinanceOperationalAuthorization;
import com.vistay.finance.adapter.in.rest.FinanceOperationalAuthorization.Actor;
import com.vistay.finance.application.dto.*;
import com.vistay.finance.domain.model.*;
import com.vistay.finance.domain.repository.FinanceCommandIdempotencyRepository;
import com.vistay.finance.domain.repository.PaymentTransactionRepository;
import com.vistay.finance.domain.repository.RefundRepository;
import com.vistay.finance.domain.repository.ReservationCancellationObligationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ReservationCancellationObligationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationCancellationObligationService.class);
    private static final String CONSUMER_NAME = "finance-cancellation-executed-v1";

    private final ReservationCancellationObligationRepository obligationRepository;
    private final FinanceCommandIdempotencyRepository idempotencyRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RefundRepository refundRepository;
    private final FinanceOperationalAuthorization authorization;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ReservationCancellationObligationService(
        ReservationCancellationObligationRepository obligationRepository,
        FinanceCommandIdempotencyRepository idempotencyRepository,
        PaymentTransactionRepository paymentTransactionRepository,
        RefundRepository refundRepository,
        FinanceOperationalAuthorization authorization,
        JdbcClient jdbc,
        ObjectMapper objectMapper
    ) {
        this.obligationRepository = obligationRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.refundRepository = refundRepository;
        this.authorization = authorization;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Supplies verified collected advance for Booking cancellation preview.
     * Permission: finance:reservation-decision:view
     */
    @Transactional(readOnly = true)
    public CancellationPreviewResponse getVerifiedCollectedAdvanceForCancellation(UUID bookingId, Actor actor) {
        if (bookingId == null) {
            throw new IllegalArgumentException("Booking ID cannot be null");
        }
        if (actor != null) {
            authorization.requireBooking(actor, bookingId);
        }

        List<PaymentTransaction> transactions = paymentTransactionRepository.findByBookingIdOrderByOccurredAtDesc(bookingId);
        List<Refund> refunds = refundRepository.findByBookingIdOrderByRequestedAtDesc(bookingId);

        // Fail-closed check: if any payment transaction is pending verification, throw exception
        boolean hasPendingPayments = transactions.stream()
            .anyMatch(t -> t.getTransactionType() == TransactionType.PAYMENT && t.getStatus() == TransactionStatus.PENDING);
        if (hasPendingPayments) {
            throw new IllegalStateException("UNVERIFIED_PAYMENTS_PRESENT: Cannot preview cancellation while payments are pending verification");
        }

        BigDecimal verifiedPaid = transactions.stream()
            .filter(t -> t.getTransactionType() == TransactionType.PAYMENT && t.getStatus() == TransactionStatus.SUCCEEDED)
            .map(PaymentTransaction::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal nonFailedRefunds = refunds.stream()
            .filter(r -> r.getStatus() != RefundStatus.FAILED && r.getStatus() != RefundStatus.REJECTED && r.getStatus() != RefundStatus.CANCELLED)
            .map(Refund::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal netAdvance = verifiedPaid.subtract(nonFailedRefunds);
        if (netAdvance.compareTo(BigDecimal.ZERO) < 0) {
            netAdvance = BigDecimal.ZERO;
        }

        String currency = transactions.stream()
            .filter(t -> t.getCurrency() != null && !t.getCurrency().isBlank())
            .map(PaymentTransaction::getCurrency)
            .findFirst()
            .orElse("VND");

        String snapshotHash = computeSha256(bookingId + ":" + netAdvance + ":" + transactions.size() + ":" + refunds.size());

        List<PaymentTransactionSummaryDto> transactionDtos = transactions.stream()
            .map(t -> new PaymentTransactionSummaryDto(
                t.getId(),
                t.getTransactionCode(),
                t.getTransactionType().name(),
                t.getAmount(),
                t.getCurrency(),
                t.getPaymentMethod() != null ? t.getPaymentMethod().name() : null,
                t.getStatus().name(),
                t.getOccurredAt()
            ))
            .toList();

        return new CancellationPreviewResponse(
            bookingId,
            netAdvance,
            currency,
            transactions.size(),
            snapshotHash,
            transactionDtos
        );
    }

    /**
     * Consumes cancellation executed command and creates one obligation and refund request.
     * Permission: finance:cancellation:assess, and finance:refund:create if refundable > 0.
     */
    @Transactional
    public CancellationObligationResponse processCancellationExecution(ExecuteCancellationCommand cmd, Actor actor) {
        if (cmd == null) {
            throw new IllegalArgumentException("Command cannot be null");
        }

        // 1. Authorization checks
        if (actor != null) {
            authorization.requireBooking(actor, cmd.bookingId());
            if (cmd.propertyId() != null) {
                authorization.requireProperty(actor, cmd.propertyId());
            }
        }

        // 2. Command Idempotency check
        String requestHash = computeSha256(
            "CANCEL_CMD:" + cmd.bookingId() + ":" + cmd.cancellationQuoteId() + ":"
                + cmd.cancellationQuoteFingerprint() + ":" + cmd.verifiedCollectedAmount()
                + ":" + cmd.refundableAmount() + ":" + cmd.forfeitedAmount()
        );

        Optional<FinanceCommandIdempotency> existingIdempotency = idempotencyRepository.findByIdempotencyKey(cmd.idempotencyKey());
        if (existingIdempotency.isPresent()) {
            FinanceCommandIdempotency idemp = existingIdempotency.get();
            if (idemp.isSucceeded()) {
                if (!idemp.getRequestHash().equals(requestHash)) {
                    throw new IllegalStateException("IDEMPOTENCY_KEY_REUSED: Idempotency key reused with different request payload");
                }
                return obligationRepository.findByCancellationQuoteId(cmd.cancellationQuoteId())
                    .map(CancellationObligationResponse::from)
                    .orElseThrow(() -> new IllegalStateException("Stored obligation not found for idempotency key"));
            } else if (idemp.isProcessing()) {
                throw new IllegalStateException("CONCURRENT_OPERATION: Operation currently processing for idempotency key: " + cmd.idempotencyKey());
            }
        }

        // Record idempotency in PROCESSING state
        FinanceCommandIdempotency idempotency = new FinanceCommandIdempotency(
            cmd.idempotencyKey(),
            "EXECUTE_CANCELLATION",
            actor != null ? "ADMIN" : "SYSTEM",
            actor != null ? actor.profileId() : UUID.randomUUID(),
            requestHash,
            Instant.now().plus(24, ChronoUnit.HOURS)
        );
        idempotencyRepository.save(idempotency);

        // 3. Event deduplication via processed_events
        int eventInserted = jdbc.sql("""
                INSERT INTO app.processed_events(event_id, consumer_name)
                VALUES (:eventId, :consumer)
                ON CONFLICT DO NOTHING
                """)
            .param("eventId", cmd.sourceEventId())
            .param("consumer", CONSUMER_NAME)
            .update();

        if (eventInserted == 0) {
            log.info("Event {} already processed by consumer {}", cmd.sourceEventId(), CONSUMER_NAME);
            var existing = obligationRepository.findBySourceEventId(cmd.sourceEventId());
            if (existing.isPresent()) {
                return CancellationObligationResponse.from(existing.get());
            }
        }

        // 4. Invariant 1: refundable + forfeited == verifiedCollected
        BigDecimal expectedTotal = cmd.refundableAmount().add(cmd.forfeitedAmount());
        if (expectedTotal.compareTo(cmd.verifiedCollectedAmount()) != 0) {
            idempotency.markFailed("BALANCE_INVARIANT_VIOLATED");
            idempotencyRepository.save(idempotency);
            throw new IllegalArgumentException("BALANCE_INVARIANT_VIOLATED: Refundable (" + cmd.refundableAmount()
                + ") + forfeited (" + cmd.forfeitedAmount() + ") does not equal verified collected (" + cmd.verifiedCollectedAmount() + ")");
        }

        // 5. Invariant 2: verified collected advance check against Finance ledger
        BigDecimal ledgerAdvance = calculateVerifiedAdvance(cmd.bookingId());
        if (cmd.verifiedCollectedAmount().compareTo(ledgerAdvance) != 0) {
            idempotency.markFailed("VERIFIED_COLLECTION_MISMATCH");
            idempotencyRepository.save(idempotency);
            throw new IllegalStateException("VERIFIED_COLLECTION_MISMATCH: Quote verified collected amount ("
                + cmd.verifiedCollectedAmount() + ") differs from Finance ledger verified advance (" + ledgerAdvance + ")");
        }

        // 6. Invariant 3: Special refund reason required for 100% refund or special refund
        if (cmd.forfeitedAmount().compareTo(BigDecimal.ZERO) == 0 && cmd.refundableAmount().compareTo(BigDecimal.ZERO) > 0) {
            if (cmd.specialRefundReasonCode() == null || cmd.specialRefundReasonCode().trim().length() < 3) {
                idempotency.markFailed("SPECIAL_REFUND_REASON_REQUIRED");
                idempotencyRepository.save(idempotency);
                throw new IllegalArgumentException("SPECIAL_REFUND_REASON_REQUIRED: Special 100% refund requires explicit reason code of at least 3 characters");
            }
        }

        // Check if obligation already exists for this booking
        if (obligationRepository.findByBookingId(cmd.bookingId()).isPresent()) {
            idempotency.markFailed("OBLIGATION_ALREADY_EXISTS");
            idempotencyRepository.save(idempotency);
            throw new IllegalStateException("OBLIGATION_ALREADY_EXISTS: Cancellation obligation already exists for booking: " + cmd.bookingId());
        }

        // 7. Create Obligation
        ReservationCancellationObligation obligation = new ReservationCancellationObligation(
            UUID.randomUUID(),
            cmd.bookingId(),
            cmd.propertyId(),
            cmd.cancellationQuoteId(),
            cmd.cancellationQuoteFingerprint(),
            cmd.policyRuleId(),
            cmd.policyRuleVersion(),
            cmd.verifiedCollectedAmount(),
            cmd.refundableAmount(),
            cmd.forfeitedAmount(),
            cmd.currency() != null ? cmd.currency() : "VND",
            cmd.specialRefundReasonCode(),
            cmd.specialRefundEvidenceRef(),
            cmd.sourceEventId(),
            cmd.correlationId()
        );

        // 8. If refundable > 0, create refund request in refunds ledger
        if (cmd.refundableAmount().compareTo(BigDecimal.ZERO) > 0) {
            UUID originalTxId = findOriginalTransactionId(cmd.bookingId());
            String refundCode = "RFD-CANCEL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            String refundIdempKey = "cancel-refund:" + cmd.bookingId() + ":" + cmd.cancellationQuoteId();

            Refund refund = new Refund(
                UUID.randomUUID(),
                refundCode,
                cmd.bookingId(),
                originalTxId,
                cmd.refundableAmount(),
                obligation.getCurrency(),
                "Cancellation refund for booking " + cmd.bookingId() + " (quote " + cmd.cancellationQuoteId() + ")",
                actor != null ? actor.profileId() : UUID.randomUUID(),
                refundIdempKey
            );
            Refund savedRefund = refundRepository.save(refund);
            obligation.markRefundRequested(savedRefund.getId());
        }

        ReservationCancellationObligation saved = obligationRepository.save(obligation);

        // 9. Emit transactional outbox event
        String eventPayload = String.format(
            "{\"obligationId\":\"%s\",\"bookingId\":\"%s\",\"cancellationQuoteId\":\"%s\",\"refundableAmount\":\"%s\",\"forfeitedAmount\":\"%s\",\"status\":\"%s\",\"correlationId\":\"%s\"}",
            saved.getId(), saved.getBookingId(), saved.getCancellationQuoteId(),
            saved.getRefundableAmount(), saved.getForfeitedAmount(), saved.getStatus(), saved.getCorrelationId()
        );

        jdbc.sql("""
                INSERT INTO app.outbox_events(
                    aggregate_type, aggregate_id, aggregate_version,
                    event_type, event_version, payload,
                    correlation_id
                ) VALUES (
                    'CancellationObligation', :aggregateId, :version,
                    'vistay.finance.cancellation-obligation-created.v1', 1, :payload::jsonb,
                    :correlationId
                )
                """)
            .param("aggregateId", saved.getId())
            .param("version", saved.getVersion())
            .param("payload", eventPayload)
            .param("correlationId", saved.getCorrelationId())
            .update();

        // 10. Mark idempotency succeeded
        idempotency.markSucceeded("ReservationCancellationObligation", saved.getId(), eventPayload);
        idempotencyRepository.save(idempotency);

        log.info("Successfully processed cancellation obligation {} for booking {}", saved.getId(), saved.getBookingId());
        return CancellationObligationResponse.from(saved);
    }

    /**
     * Reconciles payment provider refund outcome without reactivating the booking.
     */
    @Transactional
    public CancellationObligationResponse reconcileRefundOutcome(ReconcileRefundOutcomeCommand cmd) {
        if (cmd == null || cmd.obligationId() == null) {
            throw new IllegalArgumentException("Reconcile command and obligation ID cannot be null");
        }

        ReservationCancellationObligation obligation = obligationRepository.findById(cmd.obligationId())
            .orElseThrow(() -> new NoSuchElementException("Cancellation obligation not found: " + cmd.obligationId()));

        if (cmd.success()) {
            obligation.markRefunded();
            if (obligation.getRefundId() != null) {
                refundRepository.findById(obligation.getRefundId()).ifPresent(refund -> {
                    refund.markCompleted(UUID.randomUUID());
                    refundRepository.save(refund);
                });
            }
            log.info("Reconciled refund outcome SUCCESS for obligation {}", obligation.getId());
        } else {
            // Failure stays REFUND_FAILED and repairable. Never reactivates booking.
            obligation.markRefundFailed(
                cmd.failureCode() != null ? cmd.failureCode() : "PROVIDER_REFUND_FAILED",
                cmd.failureMessage() != null ? sanitizeMessage(cmd.failureMessage()) : "Refund failed at provider"
            );
            if (obligation.getRefundId() != null) {
                refundRepository.findById(obligation.getRefundId()).ifPresent(refund -> {
                    refund.markFailed();
                    refundRepository.save(refund);
                });
            }
            log.warn("Reconciled refund outcome FAILED for obligation {}: code={}", obligation.getId(), obligation.getFailureCode());
        }

        ReservationCancellationObligation updated = obligationRepository.save(obligation);

        // Record outbox event for reconciliation result
        String payload = String.format(
            "{\"obligationId\":\"%s\",\"bookingId\":\"%s\",\"status\":\"%s\",\"failureCode\":\"%s\",\"correlationId\":\"%s\"}",
            updated.getId(), updated.getBookingId(), updated.getStatus(),
            updated.getFailureCode() != null ? updated.getFailureCode() : "",
            cmd.correlationId()
        );

        jdbc.sql("""
                INSERT INTO app.outbox_events(
                    aggregate_type, aggregate_id, aggregate_version,
                    event_type, event_version, payload,
                    correlation_id
                ) VALUES (
                    'CancellationObligation', :aggregateId, :version,
                    'vistay.finance.cancellation-refund-reconciled.v1', 1, :payload::jsonb,
                    :correlationId
                )
                """)
            .param("aggregateId", updated.getId())
            .param("version", updated.getVersion())
            .param("payload", payload)
            .param("correlationId", cmd.correlationId())
            .update();

        return CancellationObligationResponse.from(updated);
    }

    @Transactional(readOnly = true)
    public CancellationObligationResponse getObligationById(UUID obligationId) {
        return obligationRepository.findById(obligationId)
            .map(CancellationObligationResponse::from)
            .orElseThrow(() -> new NoSuchElementException("Cancellation obligation not found: " + obligationId));
    }

    @Transactional(readOnly = true)
    public Optional<CancellationObligationResponse> getObligationByBookingId(UUID bookingId) {
        return obligationRepository.findByBookingId(bookingId)
            .map(CancellationObligationResponse::from);
    }

    @Transactional(readOnly = true)
    public List<CancellationObligationResponse> getFailedRefundObligations() {
        return obligationRepository.findByStatus(CancellationObligationStatus.REFUND_FAILED)
            .stream()
            .map(CancellationObligationResponse::from)
            .toList();
    }

    private BigDecimal calculateVerifiedAdvance(UUID bookingId) {
        List<PaymentTransaction> transactions = paymentTransactionRepository.findByBookingIdOrderByOccurredAtDesc(bookingId);
        List<Refund> refunds = refundRepository.findByBookingIdOrderByRequestedAtDesc(bookingId);

        BigDecimal verifiedPaid = transactions.stream()
            .filter(t -> t.getTransactionType() == TransactionType.PAYMENT && t.getStatus() == TransactionStatus.SUCCEEDED)
            .map(PaymentTransaction::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal nonFailedRefunds = refunds.stream()
            .filter(r -> r.getStatus() != RefundStatus.FAILED && r.getStatus() != RefundStatus.REJECTED && r.getStatus() != RefundStatus.CANCELLED)
            .map(Refund::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal net = verifiedPaid.subtract(nonFailedRefunds);
        return net.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : net;
    }

    private UUID findOriginalTransactionId(UUID bookingId) {
        List<PaymentTransaction> txs = paymentTransactionRepository.findByBookingIdOrderByOccurredAtDesc(bookingId);
        return txs.stream()
            .filter(t -> t != null && t.getTransactionType() == TransactionType.PAYMENT && t.getStatus() == TransactionStatus.SUCCEEDED && t.getId() != null)
            .map(PaymentTransaction::getId)
            .findFirst()
            .orElse(UUID.randomUUID());
    }

    private String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private String sanitizeMessage(String message) {
        if (message == null) return null;
        // Strip out any potential card or account numbers
        return message.replaceAll("\\b(?:\\d[ -]*?){13,16}\\b", "[REDACTED]");
    }
}
