-- Member Profile Service schema.
-- Rules are read from these tables on every request; nothing is cached in the service.
--
-- league_segmentation.segment_rule and family_permission.family_permission_rule follow the column design in
-- League_Segmentation_Table_Design.xlsx (segment_rule Design, PostgreSQL Script) and
-- Family_Permission_Table_Design.xlsx (Column Name sheet). permission_catalog and action_code are the
-- Permission Catalog and Action Codes tabs. relationship_code and family_consent are additions this service
-- needs: the mapping from MemberDomain relationship codes to the relationship words the rules use, and the
-- consent on file that CONSENT_REQUIRED rules depend on.

CREATE SCHEMA IF NOT EXISTS league_segmentation;
CREATE SCHEMA IF NOT EXISTS family_permission;

-- ---------------------------------------------------------------------------
-- Segmentation (League_Segmentation_Table_Design.xlsx)
-- ---------------------------------------------------------------------------

-- One row per condition. Conditions in the same (segment_name, company, rule_group) are ANDed; a segment is
-- true when at least one complete rule group matches (groups are ORed). Parenthesis columns are not required.
-- logical_operator connects a condition to the next for the reader: AND within a group, OR on the last
-- condition of a group when another group follows, NULL on the last condition of the segment/company.
CREATE TABLE IF NOT EXISTS league_segmentation.segment_rule (
    segment_rule_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    segment_name        VARCHAR(100) NOT NULL,
    segment_category    VARCHAR(100) NOT NULL,
    league_capability   VARCHAR(50)  NOT NULL,
    company             VARCHAR(10)  NOT NULL,
    rule_group          INTEGER      NOT NULL DEFAULT 1,
    evaluation_order    INTEGER      NOT NULL,
    logical_operator    VARCHAR(3)   NULL,
    api_field           VARCHAR(100) NOT NULL,
    comparison_operator VARCHAR(30)  NOT NULL,
    rule_value          TEXT         NULL,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_segment_name_camel_case CHECK (segment_name ~ '^[a-z][A-Za-z0-9]*$'),
    CONSTRAINT ck_segment_company CHECK (company IN ('HPHC', 'THP')),
    CONSTRAINT ck_rule_group_positive CHECK (rule_group > 0),
    CONSTRAINT ck_evaluation_order_positive CHECK (evaluation_order > 0),
    CONSTRAINT ck_logical_operator CHECK (logical_operator IS NULL OR logical_operator IN ('AND', 'OR')),
    CONSTRAINT ck_comparison_operator CHECK (comparison_operator IN
        ('EQUALS', 'NOT_EQUALS', 'IN', 'NOT_IN', 'CONTAINS', 'NOT_CONTAINS', 'IS_TRUE', 'IS_FALSE', 'GREATER_THAN')),
    CONSTRAINT ck_rule_value_present CHECK (
        comparison_operator IN ('IS_TRUE', 'IS_FALSE') OR (rule_value IS NOT NULL AND btrim(rule_value) <> ''))
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_segment_rule_expression_order
    ON league_segmentation.segment_rule (segment_name, company, evaluation_order);

-- The per-request query: all active conditions for one company, already in evaluation order.
CREATE INDEX IF NOT EXISTS ix_segment_rule_company_active
    ON league_segmentation.segment_rule (company, is_active, segment_name, rule_group, evaluation_order);

-- ---------------------------------------------------------------------------
-- Family permissions (Family_Permission_Table_Design.xlsx)
-- ---------------------------------------------------------------------------

-- Permission Catalog tab: every permission key the response may contain and its family.
CREATE TABLE IF NOT EXISTS family_permission.permission_catalog (
    permission_key    VARCHAR(150) PRIMARY KEY,
    permission_family VARCHAR(50)  NOT NULL,
    description       VARCHAR(200) NOT NULL,
    CONSTRAINT ck_permission_key_format CHECK (permission_key ~ '^[a-z][A-Za-z0-9]*(\.[a-z][A-Za-z0-9]*)?$')
);

-- Action Codes tab.
CREATE TABLE IF NOT EXISTS family_permission.action_code (
    action_code SMALLINT    PRIMARY KEY,
    description VARCHAR(50) NOT NULL
);

-- One flattened table of relationship-permission rules (Column Name sheet).
--   actor_relationship:   Subscriber | Spouse | Ex-Spouse | Adult Child | Child (teenager) | Child (minor)
--   viewing_relationship: Self | Subscriber | Spouse | Child | Adult dependent (any relationship) | All other family members
--   action_codes:         default action codes; empty array means no default access
--   access_status:        FULL_ACCESS, NO_ACCESS, CONSENT_REQUIRED, MASKED_ACCESS and related states
CREATE TABLE IF NOT EXISTS family_permission.family_permission_rule (
    permission_rule_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    permission_family      VARCHAR(50)  NOT NULL,
    permission_key         VARCHAR(150) NOT NULL REFERENCES family_permission.permission_catalog (permission_key),
    actor_relationship     VARCHAR(100) NOT NULL,
    viewing_relationship   VARCHAR(150) NOT NULL,
    age_description        VARCHAR(50)  NOT NULL,
    minimum_age            SMALLINT     NULL,
    maximum_age            SMALLINT     NULL,
    action_codes           SMALLINT[]   NOT NULL DEFAULT '{}',
    access_status          VARCHAR(40)  NOT NULL,
    consent_required       BOOLEAN      NOT NULL DEFAULT FALSE,
    administrative_consent BOOLEAN      NOT NULL DEFAULT FALSE,
    revocable              BOOLEAN      NOT NULL DEFAULT FALSE,
    masked_data            BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active              BOOLEAN      NOT NULL DEFAULT TRUE,
    source_sheet           VARCHAR(50)  NOT NULL,
    source_row             INTEGER      NOT NULL,
    source_access_text     TEXT         NULL,
    notes                  TEXT         NULL,
    CONSTRAINT ck_actor_relationship CHECK (actor_relationship IN
        ('Subscriber', 'Spouse', 'Ex-Spouse', 'Adult Child', 'Child (teenager)', 'Child (minor)')),
    CONSTRAINT ck_viewing_relationship CHECK (viewing_relationship IN
        ('Self', 'Subscriber', 'Spouse', 'Child', 'Adult dependent (any relationship)', 'All other family members')),
    CONSTRAINT ck_age_bounds CHECK (minimum_age IS NULL OR maximum_age IS NULL OR minimum_age <= maximum_age),
    CONSTRAINT ck_access_status CHECK (access_status IN
        ('FULL_ACCESS', 'NO_ACCESS', 'CONSENT_REQUIRED', 'CONSENT_REQUIRED_ADMIN', 'REVOCABLE_ACCESS',
         'MASKED_ACCESS', 'NOT_APPLICABLE', 'REVIEW_REQUIRED')),
    CONSTRAINT ck_family_matches_key CHECK (permission_family = split_part(permission_key, '.', 1))
);

-- The per-request query: all active rules for one actor relationship and the viewing relationships present.
CREATE INDEX IF NOT EXISTS ix_family_permission_rule_actor_active
    ON family_permission.family_permission_rule (actor_relationship, is_active, viewing_relationship, permission_key);

-- ---------------------------------------------------------------------------
-- Additions this service needs
-- ---------------------------------------------------------------------------

-- Maps the MemberDomain relationship code to the relationship words the rules use.
CREATE TABLE IF NOT EXISTS family_permission.relationship_code (
    relationship_code VARCHAR(10)  PRIMARY KEY,
    relationship      VARCHAR(50)  NOT NULL,
    description       VARCHAR(100) NOT NULL,
    CONSTRAINT ck_relationship CHECK (relationship IN ('Subscriber', 'Spouse', 'Ex-Spouse', 'Child'))
);

-- Consent on file for CONSENT_REQUIRED rules. Written by the consent capture process, read here per
-- request. A row with revoked_at set no longer counts.
CREATE TABLE IF NOT EXISTS family_permission.family_consent (
    consent_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_member_id   VARCHAR(30)  NOT NULL,
    viewed_member_id  VARCHAR(30)  NOT NULL,
    permission_family VARCHAR(50)  NOT NULL,
    granted_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    granted_by        VARCHAR(100) NULL,
    revoked_at        TIMESTAMPTZ  NULL
);

CREATE INDEX IF NOT EXISTS ix_family_consent_actor
    ON family_permission.family_consent (actor_member_id, viewed_member_id, permission_family);
