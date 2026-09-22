# Member Profile Service – Design

Status: proposal for review. No code yet.

The Member Profile Service (formerly "MAPS", Member Access Policy Service) is called once
every time a member logs in to the League member portal or mobile app. It returns, in one
response, who the member is, which restricted features apply to them (segmentation), and what
they may do with each family member's information (family permissions). League uses it to
decide which links, messages and actions to show. It is an authorization signal, not the
authorization itself: every downstream API must still enforce the same rules on its own.

This document takes the two workbooks (`League_Segmentation_Table_Design.xlsx` and
`Family_Permission_Table_Design.xlsx`) and the design notes as the starting point, keeps what
works, simplifies what does not need to be there, and lists the gaps that still need a decision.

---

## 1. Design goals

| Goal | What it means here |
|---|---|
| Fast on the login path | No database call while serving a login. Rules live in memory. Latency is bounded by the upstream member data calls, nothing else. |
| Rules change without a deploy | Rule changes happen 2 to 6 times a year. They are data, applied by SQL, and picked up by the running service within minutes. |
| Easy to manage | One evaluator, two small tables, one refresh mechanism, one way to debug a wrong answer. |
| Complete for the known scenarios | Both companies (HPHC, THP), all seven segments, all six permission families, consent, masking, age bands, catch-all relationships. |
| Not open-ended | Operators, fields, segments and permission keys are closed lists validated at load time. Bad rule data is rejected at load, never at login. |

---

## 2. Verdict on the table-driven approach

**Keep it.** A database table is the right home for these rules, for three reasons:

1. The rule shape is simple and stable: flat conditions over a handful of member attributes,
   grouped by AND-within-group, OR-across-groups. That does not need a rules engine (Drools,
   Easy Rules, expression languages). A 100-line evaluator covers it.
2. Changes are infrequent but must not wait for a release train. SQL applied by a DBA, picked up
   by a version poll, gives that without an admin UI.
3. The volume is tiny: about 110 segmentation condition rows and about 430 permission rows.
   The whole rule set fits in memory many thousands of times over. Loading it is a one-time
   cost measured in milliseconds.

**What to change from the workbook design** (details in sections 5 and 6):

- Do not query the database per login. The workbook describes "one database call" per request.
  Replace that with in-memory rules refreshed on a version check. Zero database calls on the
  login path.
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
| Redis / distributed cache for rules | Unnecessary. Each instance loads the rules itself in milliseconds and polls a version row. Instances converge within one poll interval. |

---

## 3. API

### 3.1 Login-path endpoint

```
GET /api/v1/member-profile
Authorization: Bearer <League session token>
Accept: application/json
```

The member identity comes from the token, never from a query parameter, so a member cannot ask
for another member's profile. For impersonation (CSR viewing as a member), the token carries
both identities and the response sets `member.isImpersonating = true`.

Optional query parameter `include=segmentation,familyPermissions` lets a client skip a section
it does not need (for example the mobile app on a screen that only needs segmentation).
Default is everything.

A service-to-service variant for internal callers, `GET /api/v1/members/{memberId}/profile`,
uses client-credentials auth and is otherwise identical. Only add it when an internal caller
actually needs it.

### 3.2 Response

```json
{
  "member": {
    "memberId": "HPxxxxxxx",
    "fullName": "Alxxx M Mxxx",
    "firstName": "Alxxxa",
    "lastName": "Mxxxx",
    "planName": null,
    "relationshipCode": "01",
    "memberTypeCode": "HPHC",
    "userTypeCode": "M",
    "isImpersonating": false,
    "age": 42,
    "activePolicy": true,
    "accountNumber": "****1234",
    "policyStatus": "ACTIVE",
    "company": "HPHC"
  },
  "segmentation": {
    "onlineBillPay": true,
    "optumRxCoverage": false,
    "allPublicPlansMa": false,
    "allTuftsMedicarePreferred": false,
    "tmpOtcMa": false,
    "planOfCare": false,
    "interoperability": true
  },
  "selfPermissions": {
    "benefits": [1],
    "benefits.idCard": [1, 3],
    "profile.raceEthnicityLanguage": [1, 2]
  },
  "familyPermissions": [
    {
      "memberId": "HPxxxx02",
      "fullName": "Lxxxxx",
      "relationshipCode": "03",
      "age": 7,
      "permissions": {
        "benefits": [1],
        "benefits.coverage": [1],
        "benefits.idCard": [1, 3],
        "claims": [1],
        "claims.eob": [1, 3],
        "documents": [1],
        "documents.file": [1, 3, 4]
      },
      "consentRequired": ["claims.claim"],
      "masked": ["claims.claim"]
    }
  ],
  "actionCodes": { "1": "View", "2": "Edit", "3": "Download", "4": "Delete" },
  "meta": {
    "rulesVersion": "2026-09-15.3",
    "generatedAt": "2026-09-22T14:31:07Z",
    "degraded": false
  }
}
```

Differences from the workbook's contract, and why:

- **`selfPermissions`** is new. The permission sheet has rows with viewing relationship
  "Self" (for example a teenager cannot see their own SOGI data). Without this block that
  information has nowhere to go.
- **`consentRequired` and `masked`** per family member are new. The rule table records
  consent-required and masked-data cases, but a map of action arrays alone cannot say "you may
  see this once consent is on file" or "you may see this, but PDC-masked". Two short string
  arrays carry that without changing the shape of `permissions`.
- **`meta.rulesVersion`** tells support which rule set produced the answer. Essential when
  someone asks "why did this member see the bill pay link yesterday and not today".
- **`meta.degraded`** is true when an upstream call failed and the service fell back to safe
  defaults (see 4.4). League can choose to show a neutral experience rather than a wrong one.
- **`member.company`** (HPHC or THP) is explicit because both rule tables key on it.
- All seven segmentation keys are always present, true or false, as the workbook requires.
  Permission keys with no allowed actions are omitted; a parent key is present with `[1]` when
  at least one child key has any action.

### 3.3 Operational endpoints (not on the login path)

| Endpoint | Purpose |
|---|---|
| `POST /admin/rules/refresh` | Reload rules now instead of waiting for the poll. Returns the load report. |
| `GET /admin/rules/status` | Current `rulesVersion`, load time, counts per segment and family, and any warnings from the last load. |
| `POST /admin/rules/validate` | Load the rules from the database into a scratch evaluator and report errors without swapping the live set. Used after applying SQL in a lower environment. |
| `GET /admin/members/{memberId}/explain` | Full evaluation trace: member facts used, each rule group with pass/fail per condition, the permission rows selected per family member. The first tool support reaches for. Restricted to support roles. |

These live behind the service's internal auth and are excluded from the public gateway.

---

## 4. Request flow

```
League portal / mobile
        │  GET /api/v1/member-profile  (bearer token)
        ▼
┌──────────────────────────────────────────────────────────────┐
│ Member Profile Service                                       │
│                                                              │
│ 1. Authenticate token → memberId, company, impersonation     │
│ 2. Response cache lookup (memberId)  ── hit ──► return       │
│ 3. Fetch in parallel:                                        │
│      a. member facts   (Member API)                          │
│      b. family roster  (Member API / eligibility)            │
│ 4. Evaluate segmentation  (in-memory compiled rules)         │
│ 5. For self + each family member:                            │
│      derive actor/viewing relationship + age band            │
│      look up permissions (in-memory index)                   │
│ 6. Assemble response, store in response cache, return        │
└──────────────────────────────────────────────────────────────┘
        ▲                                   ▲
        │ rules (poll every N min)          │ member facts, roster
   PostgreSQL                          Member API (existing)
   league_segmentation.segment_rule
   family_permission.family_permission_rule
   rule_set_version
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

### 4.4 Caching, timeouts and failure

- **Rules cache**: in-process, immutable snapshot, swapped atomically on refresh. Refresh is
  triggered by a `rule_set_version` poll every 5 minutes (one tiny query per instance) or by
  the admin refresh endpoint. If a refresh fails validation, the previous snapshot stays live
  and the failure is logged and exposed on the status endpoint. The service refuses to start
  if the initial load fails; a service without rules must not answer logins.
- **Response cache**: in-process (Caffeine), keyed by `memberId`, TTL 5 minutes, invalidated on
  rules refresh. It absorbs the portal and mobile app calling within the same session, page
  reloads, and retries. It is an optimization, not a correctness mechanism: a member whose
  coverage changes sees the new answer within 5 minutes, which matches how often the source
  systems themselves update. Set TTL to 0 to disable.
- **Upstream timeouts**: member facts and family roster calls run in parallel with a 2 second
  timeout each and one retry on connection failure only. Total budget on the login path:
  under 3 seconds worst case, typically the Member API's own latency plus a millisecond.
- **Failure policy (fail closed)**: if member facts cannot be fetched, segmentation returns
  all false and `meta.degraded = true`. If the roster cannot be fetched, `familyPermissions`
  is empty and `degraded = true`. The member block is still returned from the token and
  whatever succeeded. A member who briefly does not see the bill pay link is recoverable; a
  member who sees a dependent's claims they should not is not.
- **Multiple instances**: nothing is shared. Each instance polls the version and caches
  independently. Convergence within one poll interval is acceptable for rules that change a
  few times a year.

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
| effective_from, effective_to | **add**, nullable | Lets a plan-year change be loaded in December and switch on January 1 without anyone being awake. Evaluated at load and on the poll (a rule crossing its date triggers a refresh). |
| updated_at, updated_by, change_note | **add** | Who changed what and why. Cheap and the first thing asked in an incident. |

Uniqueness stays `(segment_name, company, evaluation_order)`.

### 5.2 Rule set version

One-row table `rule_set_version (version TEXT, updated_at TIMESTAMPTZ)` in a shared schema,
bumped by every rule change script as its last statement. The service polls this row, not the
rule tables. `version` is free text such as `2026-09-15.3` and appears in `meta.rulesVersion`.

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

1. **Author** the change in the workbook (it stays the business source of truth) and export the
   affected rows as an SQL script. The workbook's "PostgreSQL Script" tab already does this for
   the full set; for a change, the script should be a targeted `UPDATE` / `INSERT` /
   `UPDATE ... SET is_active = false`, never a `TRUNCATE` in production. It ends with an
   `UPDATE rule_set_version`.
2. **Review** the script as a pull request in a `rules/` folder of this repository. The
   reviewer sees exactly which conditions change. Git history is the audit trail.
3. **Apply in a lower environment**, call `POST /admin/rules/validate`, fix anything it
   reports, then call `GET /admin/members/{id}/explain` for two or three representative
   members to confirm the intended effect.
4. **Apply in production** (DBA, normal change window). The service picks it up on the next
   poll (within 5 minutes) or immediately via `POST /admin/rules/refresh`.
5. **Verify** with `GET /admin/rules/status` that every instance reports the new version.

Rollback is the reverse script, or `is_active = false` on the new rows, plus a version bump.

Use `effective_from` for date-driven changes so the script can be applied early and switch on
its own.

---

## 7. Validation at load time

Rules are rejected as a set (previous set stays live) when any row has:

- an unknown segment name, permission key, api_field, operator, relationship code or company;
- a null `rule_value` for an operator that needs one, or a non-numeric value for GREATER_THAN;
- an empty `action_codes` without `consent_required`;
- `minimum_age > maximum_age`;
- a duplicate `(segment_name, company, evaluation_order)` or duplicate
  `(permission_key, actor, viewing, min_age, max_age)`.

Warnings (loaded, but shown on the status endpoint):

- a segment with no active rule group for one company (it will always be false there);
- a permission key with no active rows for some actor relationship;
- `effective_to` in the past on an active row.

---

## 8. Security

- Member identity from the token only. No `memberId` parameter on the login-path endpoint.
- Family permissions are computed only for members returned by the roster call for this
  member, so the service cannot be used to probe permissions for arbitrary member ids.
- Response contains no PHI beyond names, ages and masked account number. Claims and documents
  themselves are served by their own APIs, which enforce the same rules.
- Admin endpoints on an internal port or path, behind service auth, not exposed through the
  public gateway. The explain endpoint logs who asked about whom.
- Rule values are data, never code. No expression language means no injection surface.

---

## 9. Observability

- Structured log per request: memberId (hashed in non-prod as needed), company, rulesVersion,
  upstream latencies, cache hit, degraded flag, segments that evaluated true, count of family
  members.
- Metrics: request latency (p50/p95/p99), upstream latency per dependency, response cache hit
  ratio, rules refresh outcome and age, per-segment true rate. A segment whose true rate drops
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
  under 50 milliseconds of service-side time. This proves the evaluator and cache; the real
  p99 is the Member API's.

---

## 11. Open questions and gaps

These need an answer before implementation. Suggested defaults are given so work can start.

1. **Which coverage feeds the rules?** A member can hold more than one coverage (medical plus
   dental, Medicare plus PDP). Rules like `planTypeCode NOT_IN SCO,PDP` assume one value.
   Suggested default: evaluate against the member's primary active medical coverage; if
   several, evaluate each and a segment is true if any coverage satisfies it.
2. **Where is consent recorded?** Consent-required rows need a lookup (consent on file for
   actor, viewed member, permission family). Suggested default: a small
   `family_consent` table in this service's database, written by whatever process captures
   consent today, read once per request alongside the roster.
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
8. **Degraded responses.** Confirm League prefers a fast fail-closed answer with
   `degraded = true` over a login error when the Member API is down.
9. **Segmentation for family members.** The notes say segmentation applies to the logged-in
   member only. Confirm there is no case (a parent paying a child's premium) where a
   dependent's segmentation is needed. If there is, it is the same evaluator run per family
   member with their own facts, at the cost of one more upstream call each.
10. **Application query cleanup.** The segmentation "Application Query" tab still selects
    `open_parenthesis` and `close_parenthesis`. Remove them to match the DDL.

---

## 12. Suggested project shape

Same conventions as the CMS-1500 service in this repository (Spring Boot 3.5, Java 17,
springdoc, actuator), as a separate module or service:

```
member-profile-service/
  api/          controllers, response DTOs, error handling
  member/       Member API client, MemberFacts, roster, relationship derivation
  segmentation/ rule model, loader, compiler, evaluator
  permission/   rule model, loader, index, evaluator, consent lookup
  rules/        version poll, refresh, validation report, admin endpoints
  config/       properties (poll interval, cache TTL, timeouts, admin auth)
  db/migration/ Flyway: schemas, tables, version row, initial seed
rules/          reviewed SQL change scripts, one file per change
```

Dependencies to add beyond the current pom: `spring-boot-starter-data-jdbc` (or plain
`JdbcClient`), `postgresql`, `flyway-core`, `caffeine`, `spring-boot-starter-security` for the
token and admin auth.

Implementation order once the open questions are settled: schema and seed, loader and
validation, segmentation evaluator with golden tests, permission evaluator with matrix tests,
Member API client, endpoint and contract test, admin endpoints, load test.
