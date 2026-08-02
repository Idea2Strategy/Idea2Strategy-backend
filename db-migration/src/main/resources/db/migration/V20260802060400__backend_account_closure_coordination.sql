-- A12 close coordination and the product-approved initial retention snapshot.

CREATE TYPE identity.account_closure_domain AS ENUM (
    'BOT', 'TRADING', 'COMPETITION', 'NOTIFICATION', 'INTEGRATION'
);
CREATE TYPE identity.account_closure_readiness_status AS ENUM (
    'FREEZE_REQUESTED', 'FROZEN', 'SETTLEMENT_REQUIRED', 'SETTLED', 'BLOCKED'
);

CREATE TABLE identity.account_closure_runs (
    correlation_id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES identity.accounts (id),
    lifecycle_version bigint NOT NULL,
    cancellation_deadline_at timestamptz NOT NULL,
    started_at timestamptz NOT NULL,
    last_checked_at timestamptz NOT NULL,
    closed_at timestamptz,
    UNIQUE (account_id, lifecycle_version, cancellation_deadline_at)
);

CREATE TABLE identity.account_closure_readiness (
    correlation_id uuid NOT NULL REFERENCES identity.account_closure_runs (correlation_id) ON DELETE CASCADE,
    account_id uuid NOT NULL REFERENCES identity.accounts (id),
    domain identity.account_closure_domain NOT NULL,
    status identity.account_closure_readiness_status NOT NULL,
    reason_code varchar(80) NOT NULL,
    evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    observed_at timestamptz NOT NULL,
    PRIMARY KEY (correlation_id, domain),
    CONSTRAINT account_closure_readiness_evidence_object CHECK (jsonb_typeof(evidence) = 'object')
);

CREATE INDEX account_closure_readiness_account_idx
    ON identity.account_closure_readiness (account_id, status, observed_at);

CREATE TABLE operations.account_integrations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES identity.accounts (id),
    integration_code varchar(80) NOT NULL,
    status varchar(20) NOT NULL,
    freeze_requested_at timestamptz,
    closed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT account_integration_status_supported CHECK (status IN ('ACTIVE', 'CLOSING', 'CLOSED')),
    CONSTRAINT account_integration_closing_timestamp CHECK (status <> 'CLOSING' OR freeze_requested_at IS NOT NULL),
    CONSTRAINT account_integration_closed_timestamp CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL),
    UNIQUE (account_id, integration_code)
);

-- Lookup bindings stay unavailable during quarantine and are physically released only by the due worker.
ALTER TABLE identity.account_emails
    ALTER COLUMN email_lookup_hmac DROP NOT NULL,
    ALTER COLUMN email_lookup_key_version DROP NOT NULL,
    ADD CONSTRAINT account_email_lookup_binding_consistent CHECK (
        (email_lookup_hmac IS NULL AND email_lookup_key_version IS NULL)
        OR (email_lookup_hmac IS NOT NULL AND email_lookup_key_version IS NOT NULL)
    );

INSERT INTO identity.account_retention_policy_versions
    (version, effective_from, approved_at, approved_by, basis_reference)
VALUES
    ('A12-2026-08-02', '2026-08-02 00:00:00+00', '2026-08-02 00:00:00+00',
     'kcrmin', 'A12_PRODUCT_OWNER_APPROVAL')
ON CONFLICT (version) DO NOTHING;

INSERT INTO identity.account_retention_policy_rules
    (policy_version, data_category, disposition, retention_days, legal_basis_code)
VALUES
    ('A12-2026-08-02', 'PROFILE', 'DELETE', 0, 'ACCOUNT_CLOSED'),
    ('A12-2026-08-02', 'CONTACT_IDENTIFIER', 'DELETE', 30, 'IDENTIFIER_REUSE_QUARANTINE'),
    ('A12-2026-08-02', 'AUTH_CREDENTIAL', 'DELETE', 30, 'SECURITY_INVESTIGATION_WINDOW'),
    ('A12-2026-08-02', 'POLICY_CONSENT', 'RETAIN', NULL, 'CONSENT_EVIDENCE'),
    ('A12-2026-08-02', 'ACCOUNT_LIFECYCLE_AUDIT', 'RETAIN', NULL, 'AUDIT_EVIDENCE'),
    ('A12-2026-08-02', 'TRADING_FINANCIAL_RECORD', 'RETAIN', NULL, 'FINANCIAL_RECORD'),
    ('A12-2026-08-02', 'BOT_STRATEGY_EVALUATION', 'ANONYMIZE', 30, 'SERVICE_EXIT'),
    ('A12-2026-08-02', 'OPERATIONS_DELIVERY_LOG', 'DELETE', 90, 'OPERATIONS_RETENTION')
ON CONFLICT (policy_version, data_category) DO NOTHING;

COMMENT ON TABLE identity.account_closure_readiness IS
    'Fail-closed evidence for all five A12 close boundaries. CLOSED requires every domain to be FROZEN or SETTLED.';
COMMENT ON TABLE operations.account_integrations IS
    'Concrete shared-database boundary for external integrations; missing rows mean no integration, never an assumed remote success.';
