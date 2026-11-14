SET search_path TO app, extensions, public;

-- Read model received from Booking Service. This is not the source of truth
-- for booking state or price; it gives Finance a stable obligation snapshot.
CREATE TABLE booking_financial_snapshots (
    booking_id uuid PRIMARY KEY,
    booking_code varchar(40) NOT NULL,
    customer_id uuid,
    property_id uuid NOT NULL,
    booking_status varchar(32) NOT NULL,
    total_amount numeric(19,4) NOT NULL,
    currency char(3) NOT NULL,
    source_event_id uuid NOT NULL UNIQUE,
    source_event_occurred_at timestamptz NOT NULL,
    projected_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_finance_booking_amount CHECK (total_amount >= 0),
    CONSTRAINT ck_finance_booking_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE payment_accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(40) NOT NULL UNIQUE,
    name varchar(160) NOT NULL,
    account_type varchar(24) NOT NULL,
    provider varchar(80),
    account_number_last_four char(4),
    secret_reference varchar(500),
    currency char(3) NOT NULL DEFAULT 'VND',
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_payment_account_type CHECK (account_type IN ('BANK', 'CASH', 'PAYMENT_GATEWAY', 'OTA_CLEARING', 'OTHER')),
    CONSTRAINT ck_payment_account_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE payment_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    request_code varchar(50) NOT NULL UNIQUE,
    booking_id uuid,
    payer_type varchar(24) NOT NULL,
    payer_id uuid,
    amount numeric(19,4) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'VND',
    purpose varchar(500) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    idempotency_key varchar(160) NOT NULL UNIQUE,
    expires_at timestamptz,
    created_by_admin_user_id uuid,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_payment_request_payer_type CHECK (payer_type IN ('CUSTOMER', 'HOST', 'PARTNER', 'OTHER')),
    CONSTRAINT ck_payment_request_payer CHECK ((payer_type = 'OTHER') OR payer_id IS NOT NULL),
    CONSTRAINT ck_payment_request_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_request_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_request_status CHECK (status IN ('PENDING', 'PROCESSING', 'PARTIALLY_PAID', 'PAID', 'EXPIRED', 'CANCELLED', 'FAILED')),
    CONSTRAINT ck_payment_request_expiry CHECK (expires_at IS NULL OR expires_at > created_at)
);
CREATE INDEX ix_payment_request_booking ON payment_requests(booking_id, created_at DESC) WHERE booking_id IS NOT NULL;
CREATE INDEX ix_payment_request_pending ON payment_requests(status, expires_at) WHERE status IN ('PENDING', 'PROCESSING', 'PARTIALLY_PAID');

CREATE TABLE payment_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_request_id uuid NOT NULL REFERENCES payment_requests(id),
    attempt_number integer NOT NULL,
    payment_method varchar(32) NOT NULL,
    payment_provider varchar(80),
    external_transaction_id varchar(180),
    requested_amount numeric(19,4) NOT NULL,
    currency char(3) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'CREATED',
    failure_code varchar(100),
    failure_message varchar(1000),
    requested_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (payment_request_id, attempt_number),
    CONSTRAINT ck_payment_attempt_number CHECK (attempt_number > 0),
    CONSTRAINT ck_payment_attempt_method CHECK (payment_method IN ('BANK_TRANSFER', 'CASH', 'CARD', 'EWALLET', 'OTA_COLLECTED', 'OTHER')),
    CONSTRAINT ck_payment_attempt_amount CHECK (requested_amount > 0),
    CONSTRAINT ck_payment_attempt_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_attempt_status CHECK (status IN ('CREATED', 'PENDING', 'AUTHORIZED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_payment_attempt_metadata CHECK (jsonb_typeof(metadata) = 'object')
);
CREATE UNIQUE INDEX ux_payment_attempt_external
    ON payment_attempts(payment_provider, external_transaction_id)
    WHERE payment_provider IS NOT NULL AND external_transaction_id IS NOT NULL;

-- Every row is a real or explicitly authorized financial movement. Amount is
-- always positive; transaction_type determines business direction.
CREATE TABLE payment_transactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_code varchar(60) NOT NULL UNIQUE,
    payment_request_id uuid REFERENCES payment_requests(id) ON DELETE SET NULL,
    payment_attempt_id uuid REFERENCES payment_attempts(id) ON DELETE SET NULL,
    original_transaction_id uuid REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    booking_id uuid,
    transaction_type varchar(24) NOT NULL,
    amount numeric(19,4) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'VND',
    payment_method varchar(32) NOT NULL,
    payment_account_id uuid REFERENCES payment_accounts(id) ON DELETE SET NULL,
    payment_provider varchar(80),
    external_transaction_id varchar(180),
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    occurred_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    recorded_by_admin_user_id uuid,
    approved_by_admin_user_id uuid,
    idempotency_key varchar(160) NOT NULL UNIQUE,
    description varchar(1000),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_payment_transaction_type CHECK (transaction_type IN ('PAYMENT', 'REFUND', 'PAYOUT', 'ADJUSTMENT')),
    CONSTRAINT ck_payment_transaction_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_transaction_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_transaction_method CHECK (payment_method IN ('BANK_TRANSFER', 'CASH', 'CARD', 'EWALLET', 'OTA_COLLECTED', 'INTERNAL', 'OTHER')),
    CONSTRAINT ck_payment_transaction_status CHECK (status IN ('PENDING', 'AUTHORIZED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'REVERSED')),
    CONSTRAINT ck_payment_transaction_original CHECK (
        (transaction_type IN ('REFUND', 'ADJUSTMENT') AND original_transaction_id IS NOT NULL)
        OR transaction_type IN ('PAYMENT', 'PAYOUT')
    ),
    CONSTRAINT ck_payment_transaction_metadata CHECK (jsonb_typeof(metadata) = 'object')
);
CREATE INDEX ix_payment_transaction_booking ON payment_transactions(booking_id, occurred_at DESC) WHERE booking_id IS NOT NULL;
CREATE INDEX ix_payment_transaction_status ON payment_transactions(status, occurred_at DESC);
CREATE UNIQUE INDEX ux_payment_transaction_external
    ON payment_transactions(payment_provider, external_transaction_id)
    WHERE payment_provider IS NOT NULL AND external_transaction_id IS NOT NULL;

CREATE TABLE refunds (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_code varchar(60) NOT NULL UNIQUE,
    booking_id uuid,
    original_transaction_id uuid NOT NULL REFERENCES payment_transactions(id),
    refund_transaction_id uuid UNIQUE REFERENCES payment_transactions(id),
    amount numeric(19,4) NOT NULL,
    currency char(3) NOT NULL,
    reason varchar(1000) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'REQUESTED',
    requested_by_admin_user_id uuid NOT NULL,
    approved_by_admin_user_id uuid,
    external_refund_id varchar(180),
    idempotency_key varchar(160) NOT NULL UNIQUE,
    requested_at timestamptz NOT NULL DEFAULT now(),
    approved_at timestamptz,
    completed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_refund_amount CHECK (amount > 0),
    CONSTRAINT ck_refund_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_refund_status CHECK (status IN ('REQUESTED', 'APPROVED', 'PROCESSING', 'COMPLETED', 'REJECTED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_refund_approval CHECK ((approved_at IS NULL AND approved_by_admin_user_id IS NULL) OR (approved_at IS NOT NULL AND approved_by_admin_user_id IS NOT NULL)),
    CONSTRAINT ck_refund_completion CHECK ((status = 'COMPLETED' AND completed_at IS NOT NULL AND refund_transaction_id IS NOT NULL) OR status <> 'COMPLETED')
);
CREATE INDEX ix_refund_booking ON refunds(booking_id, requested_at DESC) WHERE booking_id IS NOT NULL;

CREATE TABLE receivables (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    receivable_code varchar(60) NOT NULL UNIQUE,
    source_key varchar(180) NOT NULL UNIQUE,
    booking_id uuid,
    debtor_type varchar(24) NOT NULL,
    debtor_id uuid,
    category varchar(40) NOT NULL,
    description varchar(500) NOT NULL,
    service_period_start date,
    service_period_end date,
    due_date date NOT NULL,
    amount_due numeric(19,4) NOT NULL,
    amount_paid numeric(19,4) NOT NULL DEFAULT 0,
    amount_waived numeric(19,4) NOT NULL DEFAULT 0,
    currency char(3) NOT NULL DEFAULT 'VND',
    status varchar(24) NOT NULL DEFAULT 'OPEN',
    closed_at timestamptz,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_receivable_debtor_type CHECK (debtor_type IN ('CUSTOMER', 'HOST', 'PARTNER', 'OTHER')),
    CONSTRAINT ck_receivable_debtor CHECK ((debtor_type = 'OTHER') OR debtor_id IS NOT NULL),
    CONSTRAINT ck_receivable_period CHECK (service_period_end IS NULL OR service_period_start IS NULL OR service_period_end >= service_period_start),
    CONSTRAINT ck_receivable_amounts CHECK (amount_due >= 0 AND amount_paid >= 0 AND amount_waived >= 0 AND amount_paid + amount_waived <= amount_due),
    CONSTRAINT ck_receivable_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_receivable_status CHECK (status IN ('OPEN', 'PARTIALLY_PAID', 'SETTLED', 'OVERDUE', 'WAIVED', 'CANCELLED')),
    CONSTRAINT ck_receivable_metadata CHECK (jsonb_typeof(metadata) = 'object')
);
CREATE INDEX ix_receivable_booking ON receivables(booking_id, due_date) WHERE booking_id IS NOT NULL;
CREATE INDEX ix_receivable_collection ON receivables(status, due_date);

CREATE TABLE receivable_allocations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    receivable_id uuid NOT NULL REFERENCES receivables(id),
    payment_transaction_id uuid NOT NULL REFERENCES payment_transactions(id),
    amount numeric(19,4) NOT NULL CHECK (amount > 0),
    allocated_by_admin_user_id uuid,
    allocated_at timestamptz NOT NULL DEFAULT now(),
    reversed_at timestamptz,
    reversal_reason varchar(500),
    UNIQUE (receivable_id, payment_transaction_id)
);

CREATE TABLE payables (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payable_code varchar(60) NOT NULL UNIQUE,
    source_key varchar(180) NOT NULL UNIQUE,
    payee_type varchar(24) NOT NULL,
    payee_id uuid NOT NULL,
    property_id uuid,
    contract_id uuid,
    contract_property_term_id uuid,
    work_order_id uuid,
    category varchar(40) NOT NULL,
    description varchar(500) NOT NULL,
    service_period_start date,
    service_period_end date,
    due_date date NOT NULL,
    amount_due numeric(19,4) NOT NULL,
    amount_paid numeric(19,4) NOT NULL DEFAULT 0,
    amount_waived numeric(19,4) NOT NULL DEFAULT 0,
    currency char(3) NOT NULL DEFAULT 'VND',
    status varchar(24) NOT NULL DEFAULT 'OPEN',
    settled_at timestamptz,
    contract_term_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_payable_payee_type CHECK (payee_type IN ('HOST', 'VENDOR', 'EMPLOYEE', 'CTV', 'PARTNER', 'OTHER')),
    CONSTRAINT ck_payable_subject CHECK (num_nonnulls(property_id, contract_id, contract_property_term_id, work_order_id) >= 1),
    CONSTRAINT ck_payable_contract_reference CHECK (
        contract_property_term_id IS NULL OR (contract_id IS NOT NULL AND property_id IS NOT NULL)
    ),
    CONSTRAINT ck_payable_period CHECK (service_period_end IS NULL OR service_period_start IS NULL OR service_period_end >= service_period_start),
    CONSTRAINT ck_payable_amounts CHECK (amount_due >= 0 AND amount_paid >= 0 AND amount_waived >= 0 AND amount_paid + amount_waived <= amount_due),
    CONSTRAINT ck_payable_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payable_status CHECK (status IN ('OPEN', 'PARTIALLY_PAID', 'SETTLED', 'OVERDUE', 'WAIVED', 'CANCELLED')),
    CONSTRAINT ck_payable_metadata CHECK (
        jsonb_typeof(metadata) = 'object'
        AND jsonb_typeof(contract_term_snapshot) = 'object'
    )
);
CREATE INDEX ix_payable_due ON payables(status, due_date);
CREATE INDEX ix_payable_host_property ON payables(payee_id, property_id, due_date);
CREATE INDEX ix_payable_contract_term ON payables(contract_property_term_id, due_date)
    WHERE contract_property_term_id IS NOT NULL;

CREATE TABLE payable_allocations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payable_id uuid NOT NULL REFERENCES payables(id),
    payout_transaction_id uuid NOT NULL REFERENCES payment_transactions(id),
    amount numeric(19,4) NOT NULL CHECK (amount > 0),
    allocated_by_admin_user_id uuid,
    allocated_at timestamptz NOT NULL DEFAULT now(),
    reversed_at timestamptz,
    reversal_reason varchar(500),
    UNIQUE (payable_id, payout_transaction_id)
);

CREATE TABLE expenses (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    expense_code varchar(60) NOT NULL UNIQUE,
    expense_category varchar(40) NOT NULL,
    property_id uuid,
    work_order_id uuid,
    vendor_id uuid,
    amount numeric(19,4) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'VND',
    description varchar(1000) NOT NULL,
    incurred_at date NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'DRAFT',
    payable_id uuid REFERENCES payables(id) ON DELETE SET NULL,
    approved_by_admin_user_id uuid,
    approved_at timestamptz,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_expense_subject CHECK (num_nonnulls(property_id, work_order_id, vendor_id) >= 1),
    CONSTRAINT ck_expense_amount CHECK (amount > 0),
    CONSTRAINT ck_expense_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_expense_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'PAYABLE_CREATED', 'PAID', 'CANCELLED')),
    CONSTRAINT ck_expense_approval CHECK ((approved_at IS NULL AND approved_by_admin_user_id IS NULL) OR (approved_at IS NOT NULL AND approved_by_admin_user_id IS NOT NULL)),
    CONSTRAINT ck_expense_metadata CHECK (jsonb_typeof(metadata) = 'object')
);
CREATE INDEX ix_expense_property_date ON expenses(property_id, incurred_at DESC) WHERE property_id IS NOT NULL;

CREATE TABLE commissions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL,
    beneficiary_admin_user_id uuid NOT NULL,
    commission_rule_code varchar(80) NOT NULL,
    calculation_base numeric(19,4) NOT NULL,
    rate_percent numeric(7,4),
    fixed_amount numeric(19,4),
    commission_amount numeric(19,4) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'VND',
    status varchar(24) NOT NULL DEFAULT 'CALCULATED',
    payable_id uuid REFERENCES payables(id) ON DELETE SET NULL,
    approved_by_admin_user_id uuid,
    approved_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_commission_basis CHECK (calculation_base >= 0 AND commission_amount >= 0),
    CONSTRAINT ck_commission_formula CHECK (num_nonnulls(rate_percent, fixed_amount) = 1),
    CONSTRAINT ck_commission_rate CHECK (rate_percent IS NULL OR rate_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_commission_fixed CHECK (fixed_amount IS NULL OR fixed_amount >= 0),
    CONSTRAINT ck_commission_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_commission_status CHECK (status IN ('CALCULATED', 'PENDING_APPROVAL', 'APPROVED', 'PAYABLE_CREATED', 'PAID', 'REVERSED', 'CANCELLED')),
    UNIQUE (booking_id, beneficiary_admin_user_id, commission_rule_code)
);
CREATE INDEX ix_commission_beneficiary ON commissions(beneficiary_admin_user_id, status, created_at DESC);

CREATE TABLE financial_documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type varchar(32) NOT NULL,
    subject_id uuid NOT NULL,
    media_id uuid NOT NULL,
    document_type varchar(40) NOT NULL,
    description varchar(500),
    uploaded_by_admin_user_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_financial_document_subject CHECK (subject_type IN ('TRANSACTION', 'REFUND', 'RECEIVABLE', 'PAYABLE', 'EXPENSE', 'COMMISSION')),
    UNIQUE (subject_type, subject_id, media_id)
);
CREATE INDEX ix_financial_document_subject ON financial_documents(subject_type, subject_id);

CREATE TABLE provider_webhook_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(80) NOT NULL,
    external_event_id varchar(180) NOT NULL,
    event_type varchar(100) NOT NULL,
    payload_sha256 char(64) NOT NULL,
    signature_valid boolean NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'RECEIVED',
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    processing_error text,
    UNIQUE (provider, external_event_id),
    CONSTRAINT ck_finance_webhook_status CHECK (status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED', 'QUARANTINED'))
);

CREATE TABLE processed_events (
    event_id uuid NOT NULL,
    consumer_name varchar(120) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer_name)
);

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(100) NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version >= 0),
    event_type varchar(160) NOT NULL,
    event_version integer NOT NULL DEFAULT 1 CHECK (event_version > 0),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    available_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    publish_attempts integer NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
    last_error text,
    correlation_id varchar(100),
    causation_id varchar(100)
);
CREATE INDEX ix_finance_outbox_pending ON outbox_events(available_at, occurred_at) WHERE published_at IS NULL;

CREATE TABLE audit_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_admin_user_id uuid,
    action varchar(100) NOT NULL,
    aggregate_type varchar(100) NOT NULL,
    aggregate_id uuid NOT NULL,
    changes jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(changes) = 'object'),
    correlation_id varchar(100),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_finance_audit_aggregate ON audit_events(aggregate_type, aggregate_id, created_at DESC);
