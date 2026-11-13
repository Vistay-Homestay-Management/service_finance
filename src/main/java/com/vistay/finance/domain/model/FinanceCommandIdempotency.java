package com.vistay.finance.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "finance_command_idempotency",
    schema = "app",
    indexes = {
        @Index(name = "ix_command_idempotency_expiry", columnList = "status, expires_at")
    }
)
public class FinanceCommandIdempotency {

    @Id
    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @Column(name = "command_type", nullable = false, length = 80)
    private String commandType;

    @Column(name = "actor_type", nullable = false, length = 24)
    private String actorType;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private CommandIdempotencyStatus status;

    @Column(name = "resource_type", length = 64)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", columnDefinition = "jsonb")
    private String responsePayload;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected FinanceCommandIdempotency() {}

    public FinanceCommandIdempotency(
        String idempotencyKey,
        String commandType,
        String actorType,
        UUID actorId,
        String requestHash,
        Instant expiresAt
    ) {
        this.idempotencyKey = idempotencyKey;
        this.commandType = commandType;
        this.actorType = actorType;
        this.actorId = actorId;
        this.requestHash = requestHash;
        this.status = CommandIdempotencyStatus.PROCESSING;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public void markSucceeded(String resourceType, UUID resourceId, String responsePayload) {
        this.status = CommandIdempotencyStatus.SUCCEEDED;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.responsePayload = responsePayload;
        this.completedAt = Instant.now();
    }

    public void markFailed(String errorCode) {
        this.status = CommandIdempotencyStatus.FAILED;
        this.errorCode = errorCode;
        this.completedAt = Instant.now();
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public String getCommandType() { return commandType; }
    public String getActorType() { return actorType; }
    public UUID getActorId() { return actorId; }
    public String getRequestHash() { return requestHash; }
    public CommandIdempotencyStatus getStatus() { return status; }
    public String getResourceType() { return resourceType; }
    public UUID getResourceId() { return resourceId; }
    public String getResponsePayload() { return responsePayload; }
    public String getErrorCode() { return errorCode; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
