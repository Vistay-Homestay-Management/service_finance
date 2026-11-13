SET search_path TO app, extensions, public;

CREATE TABLE IF NOT EXISTS finance_command_idempotency (
    idempotency_key varchar(160) PRIMARY KEY,
    command_type varchar(80) NOT NULL,
    actor_type varchar(24) NOT NULL CHECK (actor_type IN ('ADMIN', 'CUSTOMER', 'SYSTEM')),
    actor_id uuid NOT NULL,
    request_hash char(64) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')),
    resource_type varchar(64),
    resource_id uuid,
    response_payload jsonb,
    error_code varchar(80),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);

CREATE TABLE IF NOT EXISTS reservation_deposit_assessments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    target_key varchar(120) NOT NULL,
    booking_id uuid,
    quote_id uuid,
    hold_id uuid,
    property_id uuid NOT NULL,
    customer_id uuid,
    assessment_revision bigint NOT NULL DEFAULT 1 CHECK (assessment_revision >= 1),
    is_current boolean NOT NULL DEFAULT true,
    input_fingerprint char(64) NOT NULL,
    rule_catalogue_id uuid REFERENCES finance_rule_catalogues(id),
    rule_version integer NOT NULL,
    trust_input_version bigint,
    trust_decision_ref varchar(120),
    required_amount numeric(19,4) NOT NULL CHECK (required_amount >= 0),
    collected_verified_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK (collected_verified_amount >= 0),
    currency char(3) NOT NULL DEFAULT 'VND' CHECK (currency ~ '^[A-Z]{3}$'),
    waiver_mode varchar(32) NOT NULL DEFAULT 'NONE' CHECK (waiver_mode IN ('NONE', 'ZERO_DEPOSIT_POLICY', 'MANUAL_WAIVER')),
    waiver_reason varchar(1000),
    waiver_approved_by_admin_user_id uuid,
    waiver_approved_at timestamptz,
    status varchar(24) NOT NULL CHECK (status IN ('PENDING', 'PAYMENT_REQUIRED', 'VERIFIED_PAID', 'WAIVER_REVIEW', 'WAIVED_FINAL', 'FAILED', 'STALE')),
    expires_at timestamptz NOT NULL,
    idempotency_key varchar(160) NOT NULL UNIQUE,
    correlation_id varchar(100) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_deposit_assessment_revision UNIQUE (target_key, assessment_revision),
    CONSTRAINT ck_deposit_waiver_approved CHECK (
        (status <> 'WAIVED_FINAL') OR 
        (waiver_mode = 'ZERO_DEPOSIT_POLICY') OR 
        (waiver_approved_by_admin_user_id IS NOT NULL AND waiver_approved_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_deposit_assessment_current 
    ON reservation_deposit_assessments(target_key) 
    WHERE is_current = true;

CREATE INDEX IF NOT EXISTS idx_deposit_assessment_property 
    ON reservation_deposit_assessments(property_id);

CREATE INDEX IF NOT EXISTS idx_deposit_assessment_booking 
    ON reservation_deposit_assessments(booking_id) 
    WHERE booking_id IS NOT NULL;
