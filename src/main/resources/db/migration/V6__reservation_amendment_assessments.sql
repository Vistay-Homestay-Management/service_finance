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

CREATE TABLE IF NOT EXISTS reservation_amendment_assessments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL,
    property_id uuid NOT NULL,
    customer_id uuid,
    proposal_id varchar(120) NOT NULL,
    amendment_revision bigint NOT NULL CHECK (amendment_revision >= 1),
    is_current boolean NOT NULL DEFAULT true,
    before_snapshot_fingerprint char(64) NOT NULL,
    after_snapshot_fingerprint char(64) NOT NULL,
    rule_catalogue_id uuid REFERENCES finance_rule_catalogues(id),
    rule_version integer NOT NULL CHECK (rule_version >= 1),
    before_required_amount numeric(19,4) NOT NULL CHECK (before_required_amount >= 0),
    after_required_amount numeric(19,4) NOT NULL CHECK (after_required_amount >= 0),
    verified_collected_amount numeric(19,4) NOT NULL CHECK (verified_collected_amount >= 0),
    delta_amount numeric(19,4) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'VND' CHECK (currency ~ '^[A-Z]{3}$'),
    required_action varchar(24) NOT NULL,
    status varchar(24) NOT NULL,
    waiver_reason varchar(1000),
    assessed_by_actor_id uuid,
    approved_by_admin_user_id uuid,
    approved_at timestamptz,
    executed_event_id uuid UNIQUE,
    resulting_receivable_id uuid REFERENCES receivables(id),
    resulting_refund_id uuid REFERENCES refunds(id),
    expires_at timestamptz NOT NULL,
    idempotency_key varchar(160) NOT NULL UNIQUE,
    correlation_id varchar(100) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_amendment_req_action CHECK (
        required_action IN ('COLLECTION_REQUIRED', 'REFUND_DUE', 'NO_CHANGE', 'WAIVER_REVIEW')
    ),
    CONSTRAINT ck_amendment_status CHECK (
        status IN (
            'PREVIEWED',
            'COLLECTION_REQUIRED',
            'REFUND_DUE',
            'NO_CHANGE',
            'WAIVER_REVIEW',
            'VERIFIED',
            'WAIVED_FINAL',
            'STALE',
            'EXECUTED'
        )
    ),
    CONSTRAINT ck_amendment_approval CHECK (
        (approved_at IS NULL AND approved_by_admin_user_id IS NULL)
        OR (approved_at IS NOT NULL AND approved_by_admin_user_id IS NOT NULL)
    ),
    CONSTRAINT ck_amendment_waiver_maker_checker CHECK (
        approved_by_admin_user_id IS NULL
        OR assessed_by_actor_id IS NULL
        OR approved_by_admin_user_id <> assessed_by_actor_id
    ),
    UNIQUE (booking_id, proposal_id, amendment_revision)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_amendment_assessment_current
    ON reservation_amendment_assessments(booking_id, proposal_id)
    WHERE is_current = true;

CREATE INDEX IF NOT EXISTS ix_amendment_assessment_booking
    ON reservation_amendment_assessments(booking_id, proposal_id, updated_at DESC);
