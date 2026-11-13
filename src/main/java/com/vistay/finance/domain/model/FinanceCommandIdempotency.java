package com.vistay.finance.domain.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "finance_command_idempotency", schema = "app")
public class FinanceCommandIdempotency {

    @Id
    @Column(name = "idempotency_key", length = 160, nullable = false)
    private String idempotencyKey;

    @Column(name = "command_type", length = 80, nullable = false)
    private String commandType;

    @Column(name = "actor_type", length = 24, nullable = false)
    private String actorType;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "request_hash", length = 64, nullable = false)
    private String requestHash;

    @Column(name = "status", length = 24, nullable = false)
    private String status;

    @Column(name = "resource_type", length = 64)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

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

    protected FinanceCommandIdempotency() {
    }

    public FinanceCommandIdempotency(String idempotencyKey,
                                     String commandType,
                                     String actorType,
                                     UUID actorId,
                                     String requestHash,
                                     String status,
                                     String resourceType,
                                     UUID resourceId,
                                     String responsePayload,
                                     String errorCode,
                                     Instant expiresAt) {
        this.idempotencyKey = idempotencyKey;
        this.commandType = commandType;
        this.actorType = actorType;
        this.actorId = actorId;
        this.requestHash = requestHash;
        this.status = status;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.responsePayload = responsePayload;
        this.errorCode = errorCode;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public void markSucceeded(String responsePayload, UUID resourceId) {
        this.status = "SUCCEEDED";
        this.responsePayload = responsePayload;
        this.resourceId = resourceId;
        this.completedAt = Instant.now();
    }

    public void markFailed(String errorCode) {
        this.status = "FAILED";
        this.errorCode = errorCode;
        this.completedAt = Instant.now();
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public String getCommandType() { return commandType; }
    public String getActorType() { return actorType; }
    public UUID getActorId() { return actorId; }
    public String getRequestHash() { return requestHash; }
    public String getStatus() { return status; }
    public String getResourceType() { return resourceType; }
    public UUID getResourceId() { return resourceId; }
    public String getResponsePayload() { return responsePayload; }
    public String getErrorCode() { return errorCode; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
