# Member Profile Service – Design

Status: implemented in `member-profile-service/` (see its README). This document records the design
decisions and the open questions.

**Decision after review (2026-09-22): the service evaluates on demand.** Every call reads the member from
MemberDomain and the rules from the database, and returns the flags. No rule cache, no version polling,
no response cache. A rule change applied to the database is live on the next request. Sections of this
document that described caching, polling and a rule set version are superseded by that decision and have
been trimmed; what remains still applies.

---

## 1. Design goals

| Goal | What it means here |
|---|---|
| Fast on the login path | One MemberDomain call plus a handful of small indexed reads of the rule tables. Rule evaluation itself is microseconds. |
| Rules change without a deploy | Rule changes happen 2 to 6 times a year. They are data, applied by SQL, and live on the next request. |
| Easy to manage | Two evaluators, a handful of small tables, no cache to operate, one way to debug a wrong answer (`?explain=true`). |
| Complete for the known scenarios | Both companies (HPHC, THP), all seven segments, all six permission families, consent, masking, age bands, catch-all relationships. |
| Not open-ended | Operators, fields, segments and permission keys are closed lists validated at load time. Bad rule data is rejected at load, never at login. |

---

## 2. Verdict on the table-driven approach

**Keep it.** A database table is the right home for these rules, for three reasons:

1. The rule shape is simple and stable: flat conditions over a handful of member attributes,
   grouped by AND-within-group, OR-across-groups. That does not need a rules engine (Drools,
   Easy Rules, expression languages). A 100-line evaluator covers it.
2. Changes are infrequent but must not wait for a release train. SQL applied by a DBA is live on
   the next request, without an admin UI.
3. The volume is tiny: about 110 segmentation condition rows and about 430 permission rows.
   The whole rule set fits in memory many thousands of times over. Loading it is a one-time
   cost measured in milliseconds.

**What to change from the workbook design** (details in sections 5 and 6):

- Read the rules per request, as the workbook describes, with one indexed query per rule table
  (segments, segmentation rules for the company, permission rules for the actor relationship,
  consent for the member, two reference tables). All inside one read-only transaction.
- Drop `logical_operator` from the segmentation table. It is fully implied by `rule_group`
  (AND inside a group, OR between groups) and having it stored separately is a source of
  inconsistent rows. `evaluation_order` stays only as a stable sort/uniqueness key.
- Drop the "Derived from child permissions" rows and the NOT_APPLICABLE rows from the
  permission table as evaluated data. Parent keys are computed. Not-applicable cases are
  simply absent. This removes roughly a third of the 430 rows and every rule that can drift
  from its children.
- Replace free-text relationship labels ("Child (teenager)", "All other family members") with
  short codes and let the service derive them from relationship code plus age.
- Make the seven segment names and the 26 permission keys closed lists in code. A new segment
  or permission key is useless until League's UI knows about it, so it always ships with a
  coordinated release anyway. Rules for existing keys stay in the database.

**What was considered and rejected**

| Option | Why not |
|---|---|
| Rules as YAML in the repo, deployed with the service | Simplest possible, and honestly adequate at 2 to 6 changes a year. Rejected only because the team has already agreed that rule changes must not require a deployment. If that constraint ever relaxes, this is the fallback. |
| Rules engine (Drools, Easy Rules) or expression language (SpEL, MVEL) | Adds a runtime, a DSL to learn, and a security surface (expression injection through rule data). The conditions here never need it. |
| Admin UI for editing rules | Not worth building for a handful of changes a year. A reviewed SQL script plus a validate endpoint gives the same safety at a fraction of the cost. Revisit if changes become monthly. |
| Precomputing segmentation nightly for all members | Moves work off the login path, but member facts change during the day (new coverage, termination) and the batch adds infrastructure. Evaluation is microseconds; the upstream fetch is the cost either way. |
| In-memory rule cache with version polling or LISTEN/NOTIFY | Rejected in review: the team wants every request to read the current rules with no refresh mechanism to operate. The rule tables are tiny and indexed, so the per-request reads cost well under a millisecond each. |

---

## 3. API

### 3.1 Login-path endpoint

All calls are POST: the member id travels in the JSON body, never in the URL.

```
POST /api/v1/member-profile
Content-Type: application/json

{ "memberId": "HPxxxxxxx", "impersonating": false, "explain": false }
```

### 3.2 Response

The agreed payload, unchanged in shape: `member` carries the identity fields, `segmentation` (the seven
flags) and `familyPermissions`; `actionCodeDescriptions` explains the action codes. Three fields were
added because the rule data needs them:

- `member.permissions`: what the member may do with their own information (the rule table has "Self"
  rows, such as a teenager not seeing their own SOGI data).
- `familyPermissions[].consentRequired`: keys that become available once consent is on file. A map of
  action arrays alone cannot say "allowed after consent".
- `familyPermissions[].masked`: keys whose data must be shown PDC-masked.

See `member-profile-service/README.md` for the full example and the error table.

### 3.3 Operational endpoints (not on the login path)

`"explain": true` on the same endpoint returns the full evaluation trace: member facts used, each rule
group with pass/fail per condition, the permission rows selected per family member, and timings. It is
the first tool support reaches for. Block it at the gateway for portal traffic.

---

## 4. Request flow

Rendered diagrams: `docs/diagrams/member-profile-data-flow.png` (sequence) and
`docs/diagrams/member-profile-components.png` (components). Sources are the `.mmd` files beside them.

```
League portal / mobile
        │  POST /api/v1/member-profile  { memberId }
        ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Member Profile Service                                              │
│                                                                     │
│ Wave 1 (parallel)                                                   │
│   MemberDomain GET member ─────────────┐                            │
│   family_consent WHERE actor = member  │  need only the member id   │
│   relationship_code, action_code ──────┘                            │
│                                                                     │
│ company = memberTypeCode, actor = f(relationshipCode, age)          │
│ viewing relationship + age for each family member                   │
│                                                                     │
│ Wave 2 (parallel)                                                   │
│   segment_rule WHERE company = :company                             │
│   family_permission_rule WHERE actor = :actor AND viewing IN (...)  │
│                                                                     │
│ Evaluate segmentation (AND in group, OR across groups)              │
│ Evaluate permissions for self + each family member                  │
│ Assemble response, return                                           │
└─────────────────────────────────────────────────────────────────────┘
        ▲                                   ▲
        │ rule tables, per request          │ member facts, roster
   PostgreSQL (member_profile)         MemberDomain service
```

### 4.1 Member facts

The segmentation rules reference these member attributes (from the loaded rules):

`dependentType`, `memberCategory`, `customerCategory`, `sourceSystemId`, `planTypeCode`,
`planCode`, `coverageGroupTypeCode`, `product`, `subsidiary`, `hasActivePdp`,
`hasPharmacyRider`, `hasTmpMedicalCoverage`, `basicMedicalDrugCoverageIndicator`,
`coverageActive`, `coverageStarted`, `groupProductEffectiveForCoverage`,
`planOfCareCoverageEligible`.

The service defines this list as a **member fact registry** in code (an enum with name and
type: string, boolean, number). Rule loading rejects any `api_field` not in the registry.
Adding a fact is a small code change plus the Member API providing it, which is a coordinated
change anyway.

Facts are fetched once per request from the existing Member API and held in an immutable
`MemberFacts` object. The evaluator never touches the network.

### 4.2 Segmentation evaluation

Rows are read already ordered by segment, rule group and evaluation order and evaluated in one pass with
short-circuiting (a failed condition ends its group, a matched group ends its segment) unless explain was
requested, in which case every condition is evaluated and recorded.


At load time each database row becomes a compiled `Condition` (field accessor, operator,
pre-parsed value: a trimmed upper-cased string, a `Set<String>` for IN / NOT_IN, a number for
GREATER_THAN, nothing for IS_TRUE / IS_FALSE). Conditions are grouped into `RuleGroup`s by
`(segment, company, rule_group)`, and groups into a `Segment` by `(segment, company)`.

Evaluation per segment: `any(group.allConditionsPass(facts))`. A segment with no active groups
for the member's company evaluates to false. Missing or null facts make a condition false
(and the explain endpoint says so). Comparison rules:

| Operator | Behaviour |
|---|---|
| EQUALS / NOT_EQUALS | Case-insensitive, trimmed string compare. Numbers compared as their canonical string ("2001"). |
| IN / NOT_IN | Membership in the pre-parsed set, same normalization. |
| CONTAINS / NOT_CONTAINS | Substring on the fact's string value (planCode CONTAINS "EG"). |
| IS_TRUE / IS_FALSE | Fact coerced to boolean; null is neither, so both are false on null. |
| GREATER_THAN | Numeric compare; non-numeric fact is false. |

Cost: about 110 conditions, each a hash lookup and a string compare. Well under 50 microseconds
per member. Not worth optimizing further.

### 4.3 Family permission evaluation

At load time permission rows are indexed as
`Map<actorRelationship, Map<viewingRelationship, List<PermissionRule>>>`.

Per family member:

1. Derive **actor relationship** of the logged-in member from their relationship code and age:
   SUBSCRIBER, SPOUSE, EX_SPOUSE, ADULT_CHILD (18+), CHILD_TEEN (13 to 17), CHILD_MINOR (0 to 12).
2. Derive **viewing relationship** of the viewed member relative to the actor: SELF, SUBSCRIBER,
   SPOUSE, CHILD, ADULT_DEPENDENT, OTHER (the catch-all "All other family members").
3. Select rules: exact `(actor, viewing)` rows whose age bounds contain the viewed member's
   age. **Only if none exist for a permission key**, fall back to `(actor, OTHER)` rows for
   that key. This is the precedence rule the workbook flagged as missing from the SQL; it is
   simpler in code than as a ranked CTE.
4. For each selected row: if `consent_required` and consent is not on file, put the key in
   `consentRequired` and give no actions; otherwise union the action codes. If `masked_data`,
   add the key to `masked`.
5. Compute parent keys: for each family (benefits, claims, ...) with at least one child key
   holding an action, set the parent to `[1]`.

Self permissions use the same path with viewing relationship SELF.

### 4.4 Timeouts and failure

- **No caching.** Rules are read per request inside one read-only transaction, so a rule change
  applied mid-request is seen entirely or not at all. There is no refresh mechanism to operate.
- **MemberDomain timeouts**: 2 seconds to connect, 3 seconds to read. Unreachable or timed out is
  `504 MEMBER_DOMAIN_UNREACHABLE`; an error status or unreadable body is `502 MEMBER_DOMAIN_ERROR`. The
  portal decides what to show.
- **Incomplete member data** (memberTypeCode not HPHC/THP, unknown relationship code, no age or date of
  birth) is a `422 MEMBER_DATA_INCOMPLETE` rather than a guessed answer.
- **Unknown member** is a `404 MEMBER_NOT_FOUND`.
- **Database**: connect and socket timeouts on the JDBC connection, a 2 second query timeout, a fixed-size
  pool with a 2 second checkout timeout, and a 5 second bound on waiting for a parallel rule read.

---

## 5. Data model

### 5.1 Segmentation rules

Schema `league_segmentation`, table `segment_rule`. Compared with the workbook:

| Column | Keep? | Note |
|---|---|---|
| segment_rule_id | keep | Identity PK. |
| segment_name | keep | Must be one of the seven known segments; validated at load, not just camelCase. |
| segment_category, league_capability | keep, informational | Not evaluated. Fine to keep for the business reader. |
| company | keep | HPHC or THP. |
| rule_group | keep | The only grouping mechanism. AND within, OR across. |
| evaluation_order | keep | Stable ordering and uniqueness only. Not evaluated. |
| logical_operator | **drop** | Fully implied by rule_group. Storing it invites rows where it disagrees with the group. |
| open_parenthesis, close_parenthesis | already dropped | The application query still selects them; remove from the query. |
| api_field | keep | Must be in the member fact registry. |
| comparison_operator | keep | Check constraint stays. |
| rule_value | keep | Required unless operator is IS_TRUE / IS_FALSE; validated at load. |
| is_active | keep | |
| effective_from, effective_to | optional, nullable | Would let a plan-year change be loaded in December and switch on January 1. Not in V1 of the schema; add with a `WHERE now() BETWEEN` clause in the per-request query when needed. |
| updated_at, updated_by, change_note | **add** | Who changed what and why. Cheap and the first thing asked in an incident. |

Uniqueness stays `(segment_name, company, evaluation_order)`.

### 5.2 Rule set version

Not needed with on-demand evaluation. Removed.

### 5.3 Family permission rules

Schema `family_permission`, table `family_permission_rule`. Compared with the workbook:

| Column | Keep? | Note |
|---|---|---|
| permission_rule_id | keep | |
| permission_family | drop or derive | It is the prefix of `permission_key`. Derive on load. |
| permission_key | keep | Must be in the permission catalog (26 keys); validated at load. |
| actor_relationship | keep, **as code** | SUBSCRIBER, SPOUSE, EX_SPOUSE, ADULT_CHILD, CHILD_TEEN, CHILD_MINOR. |
| viewing_relationship | keep, **as code** | SELF, SUBSCRIBER, SPOUSE, CHILD, ADULT_DEPENDENT, OTHER. |
| age_description | drop | Derived from min/max for display. |
| minimum_age, maximum_age | keep | Null means unbounded. |
| action_codes SMALLINT[] | keep | Empty allowed only when consent_required is true. |
| access_status | keep, informational | FULL_ACCESS, NO_ACCESS, CONSENT_REQUIRED, CONSENT_REQUIRED_ADMIN, REVOCABLE_ACCESS, MASKED_ACCESS. Not evaluated; the booleans are. |
| consent_required, administrative_consent, revocable, masked_data | keep | Evaluated (consent_required, masked_data) or informational (the other two, until a feature needs them). |
| is_active | keep | |
| source_sheet, source_row, source_access_text, notes | keep | Traceability back to the policy workbook. Never evaluated. |
| effective_from, effective_to, updated_at, updated_by, change_note | **add** | Same as segmentation. |

Rows to **stop storing as evaluated data**:

- "Derived from child permissions" parent rows (about 70). The service computes them.
  Storing them lets a parent say View while every child says No Access.
- NOT_APPLICABLE / "N/A" rows (about 60). Absence already means no access. Keep them in the
  workbook for the business record if useful, but do not load them (or load with
  `is_active = false`, which the loader ignores).
- NO_ACCESS rows with an empty array and no consent flag (about 100) carry no information for
  the evaluator. They may stay for auditability; the loader skips them.

That leaves roughly 200 rows that actually decide something.

### 5.4 Reference data kept in code, not the database

| Item | Why in code |
|---|---|
| The seven segment names | League UI must know each flag; adding one is a joint release. |
| The 26 permission keys and 6 families | Same reason. |
| Action codes 1 to 4 | Contract-level constant. |
| Member fact registry (17 fields and their types) | Ties to the Member API contract. |
| Relationship code to actor / viewing relationship mapping | A handful of codes, changes with the eligibility system, not with policy. |
| Age band boundaries (12/13, 17/18) | Legal thresholds. If they ever move, everything moves. |

---

## 6. Changing the rules

Expected frequency: 2 to 6 times a year, tied to plan-year changes, new programs, or a new
segment condition.

1. **Author** the change in the workbook (it stays the business source of truth) and write the
   affected rows as a targeted `INSERT` / `UPDATE` / `UPDATE ... SET is_active = false` script,
   never a `TRUNCATE` in production. Fill `updated_by` and `change_note`.
2. **Review** the script as a pull request in a `rules/` folder of this repository. The
   reviewer sees exactly which conditions change. Git history is the audit trail.
3. **Apply in a lower environment** and call
   `POST /api/v1/member-profile` with `"explain": true` for two or three representative members to
   confirm the intended effect.
4. **Apply in production** (DBA, normal change window). It is live on the next request.

Rollback is the reverse script, or `is_active = false` on the new rows.

---

## 7. Validation

Database check constraints reject a row at insert or update time when it has:

- an unknown segment name, permission key, api_field, operator, relationship code or company;
- a null `rule_value` for an operator that needs one, or a non-numeric value for GREATER_THAN;
- an empty `action_codes` without `consent_required`;
- `minimum_age > maximum_age`;
- a duplicate `(segment_name, company, evaluation_order)` or duplicate
  `(permission_key, actor, viewing, min_age, max_age)`.

Things the constraints cannot catch, to check with the explain endpoint after a change:

- a segment with no active rule group for one company (it will always be false there);
- a permission key with no active rows for some actor relationship.

---

## 8. Security

- All calls are POST; the member id is in the body, never in a URL or access log.
- Service-to-service API key on `/api/**`, mandatory in prod. The portal or gateway asserts which
  member logged in; binding that to the session token is their job (see the README's Security section
  for the JWT alternative).
- Family permissions are computed only for members returned by the roster call for this
  member, so the service cannot be used to probe permissions for arbitrary member ids.
- Response contains no PHI beyond names, ages and masked account number. Claims and documents
  themselves are served by their own APIs, which enforce the same rules.
- `explain` is off in prod. Error messages are fixed per code; internals stay in the log. Member ids
  are logged as hashed prefixes only. Request bodies are capped at 8 KB.
- Rule values are data, never code. No expression language means no injection surface.

---

## 9. Observability

- Structured log per request: memberId (hashed in non-prod as needed), company, actor relationship,
  upstream latency, rule query latency, segments that evaluated true, count of family
  members.
- Metrics: request latency (p50/p95/p99), MemberDomain latency, rule query latency,
  per-segment true rate. A segment whose true rate drops
  from 30 percent to 0 after a rule change is the alarm that matters.
- Health: `/actuator/health` includes rules loaded (version, age) and Member API reachability.

---

## 10. Testing strategy

- **Golden rule tests**: for every rule group in the seeded data, one member fact set that
  matches and one that misses by exactly one condition. Generated once from the workbook, kept
  as a table-driven test so a rule change that breaks an existing group is caught.
- **Permission matrix tests**: for each actor relationship, each viewing relationship and each
  age band, the expected map. Derived from the workbook rows.
- **Evaluator unit tests**: operator semantics, null facts, precedence of exact over OTHER,
  parent derivation, consent and masked handling.
- **Contract test**: the JSON response against a schema shared with League.
- **Load test**: 200 requests per second sustained with a stubbed Member API, asserting p99
  under 50 milliseconds of service-side time. This proves the evaluator and rule reads; the real
  p99 is the Member API's.

---

## 11. Open questions and gaps

These need an answer before implementation. Suggested defaults are given so work can start.

1. **Which coverage feeds the rules?** A member can hold more than one coverage (medical plus
   dental, Medicare plus PDP). Rules like `planTypeCode NOT_IN SCO,PDP` assume one value.
   Suggested default: evaluate against the member's primary active medical coverage; if
   several, evaluate each and a segment is true if any coverage satisfies it.
2. **Where is consent recorded?** Implemented as `family_permission.family_consent` in this service's
   database, read once per request; the process that writes it is still to be decided. Two semantics were
   decided in review and need business confirmation: a consent-required row whose `action_codes` is empty
   grants View once consent is on file (the workbook says "actions become available after consent" without
   naming them), and a masked row is reported under `masked` while consent is still pending so the UI can
   say the data will be masked.
3. **Ex-spouse detection.** The relationship code alone may not distinguish a spouse from an
   ex-spouse who is still on the policy. Suggested default: treat as SPOUSE until the
   eligibility data can flag it; ex-spouse rules in the table stay dormant.
4. **Impersonation permissions.** When a CSR impersonates a member, should family permissions
   be the member's, or the CSR's full view? Suggested default: the member's, so the CSR sees
   exactly what the member sees.
5. **Terminated members.** `activePolicy = false` in the sample. Should segmentation still be
   evaluated, or forced all false? Suggested default: evaluate; several rules already include
   `coverageActive`, and the business may want bill pay visible for a final invoice.
6. **Age as of when?** Suggested default: as of the request date, from date of birth. A
   dependent turning 18 changes bands on their birthday, which matches policy.
7. **Self permissions in the contract.** Confirm League will use `selfPermissions`. If not, it
   can be dropped and the SELF rows ignored.
8. **MemberDomain outage.** Confirm League is happy to treat a `502` from this service as
   "show the neutral experience" rather than failing the login.
9. **Segmentation for family members.** The notes say segmentation applies to the logged-in
   member only. Confirm there is no case (a parent paying a child's premium) where a
   dependent's segmentation is needed. If there is, it is the same evaluator run per family
   member with their own facts, at the cost of one more upstream call each.
10. **Application query cleanup.** The segmentation "Application Query" tab still selects
    `open_parenthesis` and `close_parenthesis`. Remove them to match the DDL.

---

## 12. Project shape

Implemented in `member-profile-service/` (package `org.point32health.memberprofile`; Gradle 9.5.0 wrapper, Groovy DSL, Spring Boot 4.0.7, Java 17 release target, JDBC, Flyway, springdoc; same build shape as the EPA and Patient Access workbenches):

```
member-profile-service/src/main/java/org/point32health/memberprofile/
  api/            MemberProfileController (POST), MemberProfileRequest, MemberProfileResponse, RestExceptionHandler
  common/         ApiError, ErrorCode, MemberProfileException
  memberdomain/   MemberDomainClient (interface), RestMemberDomainClient, MemberDomainMember
  segmentation/   Segment, MemberFacts, ComparisonOperator, SegmentRule, SegmentRuleRepository, SegmentationEvaluator
  permission/     FamilyRelationship, ActorRelationship, ViewingRelationship, RelationshipResolver, PermissionRule,
                  PermissionRuleRepository, PermissionEvaluator, ReferenceDataRepository, ConsentRepository
  service/        MemberProfileService (two-wave fan-out, evaluation, response)
  common/         ApiError, ErrorCode, MemberProfileException, MemberIds
  config/         properties, RestClient, OpenAPI, Jackson/Clock/executor (AppConfig), API key and body size filters
member-profile-service/src/main/resources/db/migration/
  V1 schema (tables as in the workbooks plus relationship_code and family_consent), V2 segmentation seed (complete), V3 permission reference data,
  V4 family permission rules (partial; replace with the workbook export)
docs/diagrams/    member-profile-data-flow (sequence), member-profile-components
```
