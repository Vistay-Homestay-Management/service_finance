SET search_path TO app, extensions, public;

CREATE TABLE settlement_periods (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    period_code varchar(60) NOT NULL UNIQUE,
    host_id uuid NOT NULL,
    property_id uuid,
    start_date date NOT NULL,
    end_date date NOT NULL,
    due_date date NOT NULL,
    total_gross_revenue numeric(19,4) NOT NULL DEFAULT 0,
    total_commission numeric(19,4) NOT NULL DEFAULT 0,
    total_cleaning_fees numeric(19,4) NOT NULL DEFAULT 0,
    total_adjustments numeric(19,4) NOT NULL DEFAULT 0,
    total_net_payout numeric(19,4) NOT NULL DEFAULT 0,
    currency char(3) NOT NULL DEFAULT 'VND',
    status varchar(24) NOT NULL DEFAULT 'OPEN',
    opened_by_admin_user_id uuid,
    approved_by_admin_user_id uuid,
    approved_at timestamptz,
    notes text,
    payable_id uuid REFERENCES payables(id) ON DELETE SET NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_settlement_period_dates CHECK (end_date >= start_date),
    CONSTRAINT ck_settlement_period_due CHECK (due_date >= end_date),
    CONSTRAINT ck_settlement_period_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_settlement_period_status CHECK (status IN ('OPEN', 'CALCULATED', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'PAYABLE_CREATED', 'PAID', 'CANCELLED', 'REOPENED')),
    CONSTRAINT ck_settlement_approval CHECK ((approved_at IS NULL AND approved_by_admin_user_id IS NULL) OR (approved_at IS NOT NULL AND approved_by_admin_user_id IS NOT NULL))
);
CREATE INDEX ix_settlement_period_host ON settlement_periods(host_id, start_date DESC);
CREATE INDEX ix_settlement_period_status ON settlement_periods(status, due_date);

CREATE TABLE settlement_lines (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    settlement_period_id uuid NOT NULL REFERENCES settlement_periods(id) ON DELETE CASCADE,
    booking_id uuid NOT NULL,
    booking_code varchar(40) NOT NULL,
    check_in_date date,
    check_out_date date,
    gross_revenue numeric(19,4) NOT NULL DEFAULT 0,
    commission_rate numeric(7,4) NOT NULL DEFAULT 0,
    commission_amount numeric(19,4) NOT NULL DEFAULT 0,
    cleaning_fee numeric(19,4) NOT NULL DEFAULT 0,
    net_payout numeric(19,4) NOT NULL DEFAULT 0,
    currency char(3) NOT NULL DEFAULT 'VND',
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_settlement_line_revenue CHECK (gross_revenue >= 0),
    CONSTRAINT ck_settlement_line_commission CHECK (commission_amount >= 0),
    CONSTRAINT ck_settlement_line_currency CHECK (currency ~ '^[A-Z]{3}$')
);
CREATE INDEX ix_settlement_line_period ON settlement_lines(settlement_period_id);
CREATE INDEX ix_settlement_line_booking ON settlement_lines(booking_id);

CREATE TABLE settlement_adjustments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    settlement_period_id uuid NOT NULL REFERENCES settlement_periods(id) ON DELETE CASCADE,
    adjustment_type varchar(40) NOT NULL,
    amount numeric(19,4) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'VND',
    reason varchar(1000) NOT NULL,
    created_by_admin_user_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_settlement_adj_type CHECK (adjustment_type IN ('PENALTY', 'COMPENSATION', 'DAMAGE_FEE', 'MANUAL_CREDIT', 'MANUAL_DEBIT')),
    CONSTRAINT ck_settlement_adj_currency CHECK (currency ~ '^[A-Z]{3}$')
);
CREATE INDEX ix_settlement_adj_period ON settlement_adjustments(settlement_period_id);

