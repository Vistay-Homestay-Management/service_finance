SET search_path TO app, extensions, public;

-- Idempotency storage for externally-invoked Finance commands
CREATE TABLE IF NOT EXISTS finance_command_idempotency (
    idempotency_key varchar(160) PRIMARY KEY,
    command_type varchar(80) NOT NULL,
    actor_type varchar(24) NOT NULL,
    actor_id uuid NOT NULL,
    request_hash char(64) NOT NULL,
    status varchar(24) NOT NULL,
    resource_type varchar(64),
    resource_id uuid,
    response_payload jsonb,
    error_code varchar(80),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT ck_command_idempotency_actor CHECK (actor_type IN ('ADMIN', 'CUSTOMER', 'SYSTEM')),
    CONSTRAINT ck_command_idempotency_status CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_command_idempotency_payload CHECK (response_payload IS NULL OR jsonb_typeof(response_payload) = 'object')
);

CREATE INDEX IF NOT EXISTS ix_command_idempotency_expiry ON finance_command_idempotency(status, expires_at);

-- SCRUM-569: Finance cancellation obligation ledger
CREATE TABLE IF NOT EXISTS reservation_cancellation_obligations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL UNIQUE,
    property_id uuid NOT NULL,
    cancellation_quote_id uuid NOT NULL UNIQUE,
    cancellation_quote_fingerprint char(64) NOT NULL,
    policy_rule_id uuid REFERENCES finance_rule_catalogues(id),
    policy_rule_version integer,
    verified_collected_amount numeric(19,4) NOT NULL,
    refundable_amount numeric(19,4) NOT NULL,
    forfeited_amount numeric(19,4) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'VND',
    special_refund_reason_code varchar(64),
    special_refund_evidence_ref varchar(255),
    refund_id uuid UNIQUE REFERENCES refunds(id),
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    failure_code varchar(80),
    failure_message varchar(500),
    source_event_id uuid NOT NULL UNIQUE,
    correlation_id varchar(100) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_cancel_obligation_amounts CHECK (verified_collected_amount >= 0 AND refundable_amount >= 0 AND forfeited_amount >= 0),
    CONSTRAINT ck_cancel_obligation_balance CHECK (refundable_amount + forfeited_amount = verified_collected_amount),
    CONSTRAINT ck_cancel_obligation_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_cancel_obligation_status CHECK (status IN ('PENDING', 'REFUND_DUE', 'NO_REFUND', 'REFUND_REQUESTED', 'REFUNDED', 'REFUND_FAILED')),
    CONSTRAINT ck_cancel_special_refund CHECK (special_refund_reason_code IS NULL OR length(trim(special_refund_reason_code)) >= 3)
);

CREATE INDEX IF NOT EXISTS ix_cancel_obligation_property ON reservation_cancellation_obligations(property_id);
CREATE INDEX IF NOT EXISTS ix_cancel_obligation_status ON reservation_cancellation_obligations(status, updated_at DESC);
