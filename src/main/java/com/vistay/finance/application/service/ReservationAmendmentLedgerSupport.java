package com.vistay.finance.application.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class ReservationAmendmentLedgerSupport {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ReservationAmendmentLedgerSupport(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record IdempotencyRecord(
        boolean replayed,
        UUID resourceId,
        String responsePayloadJson
    ) {}

    public record ExistingCommandRow(
        String idempotencyKey,
        String requestHash,
        String status,
        String resourceType,
        UUID resourceId,
        String responsePayload,
        String errorCode
    ) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public IdempotencyRecord checkAndLockIdempotency(
        String idempotencyKey,
        String commandType,
        String actorType,
        UUID actorId,
        String requestHash,
        Instant expiresAt
    ) {
        Optional<ExistingCommandRow> existing = jdbc.sql("""
                SELECT idempotency_key, request_hash, status, resource_type, resource_id,
                       response_payload::text AS response_payload, error_code
                FROM app.finance_command_idempotency
                WHERE idempotency_key = :key
                FOR UPDATE
                """)
            .param("key", idempotencyKey)
            .query(ExistingCommandRow.class)
            .optional();

        if (existing.isPresent()) {
            ExistingCommandRow row = existing.get();
            if (!row.requestHash().trim().equalsIgnoreCase(requestHash.trim())) {
                throw new ReservationAmendmentException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency key has already been used with different request parameters"
                );
            }
            if ("PROCESSING".equalsIgnoreCase(row.status())) {
                throw new ReservationAmendmentException(
                    HttpStatus.CONFLICT,
                    "CONCURRENT_REQUEST_IN_FLIGHT",
                    "A command with this idempotency key is currently processing"
                );
            }
            if ("SUCCEEDED".equalsIgnoreCase(row.status())) {
                return new IdempotencyRecord(true, row.resourceId(), row.responsePayload());
            }
            throw new ReservationAmendmentException(
                HttpStatus.BAD_REQUEST,
                row.errorCode() != null ? row.errorCode() : "PREVIOUS_COMMAND_FAILED",
                "Previous command execution with this idempotency key failed"
            );
        }

        try {
            jdbc.sql("""
                    INSERT INTO app.finance_command_idempotency(
                        idempotency_key, command_type, actor_type, actor_id,
                        request_hash, status, expires_at, created_at
                    )
                    VALUES (
                        :key, :commandType, :actorType, :actorId,
                        :requestHash, 'PROCESSING', :expiresAt, now()
                    )
                    """)
                .param("key", idempotencyKey)
                .param("commandType", commandType)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("requestHash", requestHash)
                .param("expiresAt", expiresAt)
                .update();
            return new IdempotencyRecord(false, null, null);
        } catch (DuplicateKeyException race) {
            return checkAndLockIdempotency(idempotencyKey, commandType, actorType, actorId, requestHash, expiresAt);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSucceeded(
        String idempotencyKey,
        String resourceType,
        UUID resourceId,
        Object responsePayload
    ) {
        String jsonPayload = null;
        if (responsePayload != null) {
            try {
                jsonPayload = objectMapper.writeValueAsString(responsePayload);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to serialize response payload for idempotency record", ex);
            }
        }

        jdbc.sql("""
                UPDATE app.finance_command_idempotency
                SET status = 'SUCCEEDED',
                    resource_type = :resourceType,
                    resource_id = :resourceId,
                    response_payload = CAST(:payload AS jsonb),
                    completed_at = now()
                WHERE idempotency_key = :key
                """)
            .param("key", idempotencyKey)
            .param("resourceType", resourceType)
            .param("resourceId", resourceId)
            .param("payload", jsonPayload)
            .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFailed(String idempotencyKey, String errorCode) {
        jdbc.sql("""
                UPDATE app.finance_command_idempotency
                SET status = 'FAILED',
                    error_code = :errorCode,
                    completed_at = now()
                WHERE idempotency_key = :key
                """)
            .param("key", idempotencyKey)
            .param("errorCode", errorCode)
            .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryClaimEvent(UUID eventId, String consumerName) {
        int inserted = jdbc.sql("""
                INSERT INTO app.processed_events(event_id, consumer_name)
                VALUES (:eventId, :consumer)
                ON CONFLICT DO NOTHING
                """)
            .param("eventId", eventId)
            .param("consumer", consumerName)
            .update();
        return inserted > 0;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> findCustomerIdByBookingId(UUID bookingId) {
        return jdbc.sql("""
                SELECT customer_id
                FROM app.booking_financial_snapshots
                WHERE booking_id = :bookingId
                """)
            .param("bookingId", bookingId)
            .query(UUID.class)
            .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID insertCompensatingReceivable(
        UUID bookingId,
        UUID customerId,
        String proposalId,
        UUID executedEventId,
        LocalDate dueDate,
        BigDecimal amountDue,
        String currency
    ) {
        UUID receivableId = UUID.randomUUID();
        String receivableCode = "REC-AMD-" + receivableId.toString().substring(0, 8).toUpperCase();
        String sourceKey = "amendment:" + bookingId + ":" + proposalId + ":" + executedEventId;
        String description = "Compensating obligation for reservation amendment " + proposalId;

        jdbc.sql("""
                INSERT INTO app.receivables(
                    id, receivable_code, source_key, booking_id,
                    debtor_type, debtor_id, category, description,
                    due_date, amount_due, amount_paid, amount_waived,
                    currency, status, created_at, updated_at
                )
                VALUES (
                    :id, :code, :sourceKey, :bookingId,
                    'CUSTOMER', :debtorId, 'RESERVATION_AMENDMENT', :description,
                    :dueDate, :amountDue, 0, 0,
                    :currency, 'OPEN', now(), now()
                )
                """)
            .param("id", receivableId)
            .param("code", receivableCode)
            .param("sourceKey", sourceKey)
            .param("bookingId", bookingId)
            .param("debtorId", customerId)
            .param("description", description)
            .param("dueDate", dueDate != null ? dueDate : LocalDate.now().plusDays(1))
            .param("amountDue", amountDue)
            .param("currency", currency)
            .update();

        return receivableId;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOutboxEvent(
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        String eventType,
        int eventVersion,
        Map<String, Object> payload,
        String correlationId,
        String causationId
    ) {
        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize outbox event payload", ex);
        }

        jdbc.sql("""
                INSERT INTO app.outbox_events(
                    aggregate_type, aggregate_id, aggregate_version,
                    event_type, event_version, payload,
                    occurred_at, available_at, correlation_id, causation_id
                )
                VALUES (
                    :aggregateType, :aggregateId, :aggregateVersion,
                    :eventType, :eventVersion, CAST(:payload AS jsonb),
                    now(), now(), :correlationId, :causationId
                )
                """)
            .param("aggregateType", aggregateType)
            .param("aggregateId", aggregateId)
            .param("aggregateVersion", aggregateVersion)
            .param("eventType", eventType)
            .param("eventVersion", eventVersion)
            .param("payload", jsonPayload)
            .param("correlationId", correlationId)
            .param("causationId", causationId)
            .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAuditEvent(
        UUID actorAdminUserId,
        String action,
        String aggregateType,
        UUID aggregateId,
        Map<String, Object> changes,
        String correlationId
    ) {
        String jsonChanges = "{}";
        if (changes != null && !changes.isEmpty()) {
            try {
                jsonChanges = objectMapper.writeValueAsString(changes);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to serialize audit changes", ex);
            }
        }

        jdbc.sql("""
                INSERT INTO app.audit_events(
                    actor_admin_user_id, action, aggregate_type, aggregate_id,
                    changes, correlation_id, created_at
                )
                VALUES (
                    :actorId, :action, :aggregateType, :aggregateId,
                    CAST(:changes AS jsonb), :correlationId, now()
                )
                """)
            .param("actorId", actorAdminUserId)
            .param("action", action)
            .param("aggregateType", aggregateType)
            .param("aggregateId", aggregateId)
            .param("changes", jsonChanges)
            .param("correlationId", correlationId)
            .update();
    }
}
