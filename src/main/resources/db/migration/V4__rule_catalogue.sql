SET search_path TO app, extensions, public;

CREATE TABLE finance_rule_catalogues (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_code varchar(60) NOT NULL UNIQUE,
    rule_name varchar(160) NOT NULL,
    rule_type varchar(32) NOT NULL,
    rule_version integer NOT NULL DEFAULT 1,
    opt_lock_version bigint NOT NULL DEFAULT 0,
    description text,
    parameters_json text NOT NULL DEFAULT '{}',
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    status varchar(24) NOT NULL DEFAULT 'DRAFT',
    created_by_admin_user_id varchar(100) NOT NULL,
    submitted_at timestamptz,
    approved_by_admin_user_id varchar(100),
    approved_at timestamptz,
    rejection_reason varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_rule_catalogue_type CHECK (rule_type IN ('PRICING', 'CANCELLATION', 'DEPOSIT', 'REFUND', 'SETTLEMENT')),
    CONSTRAINT ck_rule_catalogue_status CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'REJECTED', 'ARCHIVED')),
    CONSTRAINT ck_rule_catalogue_dates CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT ck_rule_maker_checker CHECK (approved_by_admin_user_id IS NULL OR approved_by_admin_user_id <> created_by_admin_user_id)
);

CREATE INDEX ix_rule_catalogue_type_status ON finance_rule_catalogues(rule_type, status);
CREATE INDEX ix_rule_catalogue_code ON finance_rule_catalogues(rule_code);
CREATE INDEX ix_rule_catalogue_effective ON finance_rule_catalogues(effective_from, effective_to);

