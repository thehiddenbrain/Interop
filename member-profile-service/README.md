# Member Profile Service

Spring Boot 4 / Java 17 / Gradle service called by the League member portal and mobile app once per login.
For one member id it returns, in a single JSON response, the member's identity, the segmentation flags that
decide which restricted features to show, and what the member may do with their own and each family
member's information (family permissions). Package `org.point32health.memberprofile`.

Everything is evaluated **on demand**. Per request the service:

1. fetches the member, their rule facts and their family roster from the **MemberDomain** service;
2. reads the active segmentation rules for the member's company from `league_segmentation.segment_rule` and
   evaluates them (AND inside a `rule_group`, OR across groups);
3. reads the active permission rules for the member's actor relationship from
   `family_permission.family_permission_rule`, plus consent on file, and evaluates them for the member (self)
   and for each family member;
4. returns the response. Nothing is cached, so a rule change applied to the database is live on the next call.

The database is never waiting behind MemberDomain: consents and reference data are read **while** the
MemberDomain call is in flight, and the two rule queries run **in parallel** once the member is known
(virtual threads on JDK 21+). Latency is the MemberDomain call plus one round trip of two small indexed reads.

Data flow: [`docs/diagrams/member-profile-data-flow.png`](../docs/diagrams/member-profile-data-flow.png)
(sequence) and [`docs/diagrams/member-profile-components.png`](../docs/diagrams/member-profile-components.png)
(components). Design notes: [`docs/member-profile-service-design.md`](../docs/member-profile-service-design.md).
Scenario catalog the tests are built from: [`docs/member-profile-test-scenarios.md`](../docs/member-profile-test-scenarios.md).

## Endpoint

All calls are POST; the member id travels in the body, never in the URL.

```
POST /api/v1/member-profile
Content-Type: application/json

{ "memberId": "HP0000001", "impersonating": false, "explain": false }
```

| Field | Required | Meaning |
|---|---|---|
| `memberId` | yes | Member as known to MemberDomain. Letters, digits, `_`, `-`; at most 30 characters. |
| `impersonating` | no | `true` when a CSR is impersonating the member; echoed as `member.isImpersonating`. |
| `explain` | no | Include the rule-by-rule evaluation trace and timings. For support and testing, not for the portal. |

Response (the agreed payload; abridged):

```json
{
  "member": {
    "memberId": "HP0000001", "fullName": "Alexa M Miller", "firstName": "Alexa", "lastName": "Miller",
    "planName": null, "relationshipCode": "01", "memberTypeCode": "HPHC", "userTypeCode": "M",
    "isImpersonating": false, "age": 42, "activePolicy": true, "accountNumber": "****1234", "policyStatus": "ACTIVE",
    "segmentation": {
      "onlineBillPay": true, "optumRxCoverage": false, "allPublicPlansMa": false,
      "allTuftsMedicarePreferred": false, "tmpOtcMa": false, "planOfCare": false, "interoperability": false
    },
    "permissions": { "benefits": [1], "benefits.idCard": [1, 3], "claims": [1], "claims.claim": [1, 3] },
    "familyPermissions": [
      { "memberId": "HP0000002", "fullName": "Liam Miller", "relationshipCode": "03", "age": 7,
        "permissions": { "benefits": [1], "benefits.coverage": [1], "benefits.idCard": [1, 3], "claims": [1], "claims.claim": [1, 3] } },
      { "memberId": "HP0000003", "fullName": "Mia Miller", "relationshipCode": "03", "age": 15,
        "permissions": { "benefits": [1], "benefits.idCard": [1, 3] },
        "consentRequired": ["claims.authorization", "claims.claim"] }
    ]
  },
  "actionCodeDescriptions": { "1": "View", "2": "Edit", "3": "Download", "4": "Delete" }
}
```

- `member.segmentation` always contains the seven flags, true or false.
- `member.permissions` is what the member may do with their own information (viewing relationship *Self*).
- `permissions` maps a permission key to its sorted action codes. A parent key (`benefits`, `claims`, ...) is
  present with `[1]` (View) when at least one of its child keys has an action. Keys with no allowed action are absent.
- `consentRequired` lists keys that become available once consent is on file in `family_permission.family_consent`.
- `masked` lists keys whose data must be shown masked (PDC). Both lists are absent when empty.
- `explain` is present only when requested: actor relationship, the member facts used, every segmentation
  rule group with pass/fail per condition, the permission rows selected per member, and timings.

Errors always have the shape `{ "status": "ERROR", "code": "...", "message": "...", "details": [ { "field", "message" } ] }`:

| HTTP | `code` | When |
|---|---|---|
| 400 | `MALFORMED_REQUEST` | Body is not valid JSON, wrong method (405) or media type (415), unknown path (404) |
| 400 | `VALIDATION_ERROR` | `memberId` missing or invalid; `details[].field` says which field |
| 404 | `MEMBER_NOT_FOUND` | MemberDomain does not know the member |
| 422 | `MEMBER_DATA_INCOMPLETE` | No `memberTypeCode`, no age or date of birth, or a relationship code not in `relationship_code` |
| 502 | `MEMBER_DOMAIN_ERROR` | MemberDomain answered with an error status |
| 504 | `MEMBER_DOMAIN_UNREACHABLE` | MemberDomain could not be reached or timed out |
| 500 | `RULE_DATA_INVALID` | A rule row cannot be interpreted (unknown operator or relationship label) |

Swagger UI: `http://localhost:8081/swagger-ui.html` (spec at `/api-docs`). Health: `/actuator/health`
(liveness and readiness probes). Build info: `/actuator/info`.

## What the service expects from MemberDomain

`GET {member-domain.base-url}/members/{memberId}` returning JSON like:

```json
{
  "memberId": "HP0000001", "firstName": "Alexa", "lastName": "Miller", "fullName": "Alexa M Miller",
  "planName": null, "relationshipCode": "01", "memberTypeCode": "HPHC", "userTypeCode": "M",
  "dateOfBirth": "1984-03-02", "age": 42, "activePolicy": true, "accountNumber": "****1234", "policyStatus": "ACTIVE",
  "attributes": {
    "dependentType": "01", "memberCategory": "B2I", "customerCategory": "GROUP",
    "sourceSystemId": 2001, "planTypeCode": "MR", "planCode": "10EG1234", "product": "MAPD",
    "coverageActive": true, "coverageStarted": true, "hasActivePdp": false
  },
  "familyMembers": [
    { "memberId": "HP0000002", "fullName": "Liam Miller", "relationshipCode": "03", "dateOfBirth": "2019-05-14" }
  ]
}
```

`memberTypeCode` (HPHC or THP) selects the company's rules. `attributes` holds the member facts the
segmentation rules name in `api_field`; values may be strings, numbers or booleans. Either `age` or
`dateOfBirth` is required for the member and each family member. Unknown properties are ignored.

## Rule tables

| Table | Source | Purpose |
|---|---|---|
| `league_segmentation.segment_rule` | League_Segmentation_Table_Design.xlsx | One condition per row. Same `segment_name` + `company` + `rule_group` = AND; different groups = OR. |
| `family_permission.family_permission_rule` | Family_Permission_Table_Design.xlsx | Actor relationship × viewing relationship × age band → action codes, consent and masking flags. |
| `family_permission.permission_catalog` | Permission Catalog tab | The 26 permission keys and their families. |
| `family_permission.action_code` | Action Codes tab | 1 View, 2 Edit, 3 Download, 4 Delete. |
| `family_permission.relationship_code` | addition | MemberDomain relationship code → Subscriber / Spouse / Ex-Spouse / Child. |
| `family_permission.family_consent` | addition | Consent on file for CONSENT_REQUIRED rules. |

The seven segment names come from the Response Contract tab and live in the `Segment` enum: League's UI
must know each flag, so adding one is always a coordinated release. Rules for existing segments are data.

Schema and seed live in `src/main/resources/db/migration` and are applied by Flyway at startup.
`V2__seed_segmentation.sql` is the complete Loaded Rules sheet (75 conditions).
`V4__seed_family_permission_rules.sql` is a **partial** seed (profile family plus benefits and claims examples);
replace it with the full export of the "Family Permission Rules" sheet before go-live.

### Changing a rule

Rules are data. A change is a reviewed SQL script applied by the DBA; it is live on the next request.

```sql
-- Example: HPHC online bill pay also for member category B2C, as a second rule group
INSERT INTO league_segmentation.segment_rule
  (segment_name, segment_category, league_capability, company, rule_group, evaluation_order,
   logical_operator, api_field, comparison_operator, rule_value)
VALUES
  ('onlineBillPay', 'Premium payment', 'home', 'HPHC', 2, 4, 'AND', 'dependentType',    'EQUALS',     '01'),
  ('onlineBillPay', 'Premium payment', 'home', 'HPHC', 2, 5, 'AND', 'memberCategory',   'EQUALS',     'B2C'),
  ('onlineBillPay', 'Premium payment', 'home', 'HPHC', 2, 6, NULL,  'customerCategory', 'NOT_EQUALS', 'NH_39_WEEK');

-- Retire a condition instead of deleting it
UPDATE league_segmentation.segment_rule SET is_active = FALSE WHERE segment_rule_id = 42;
```

Check the effect with `POST /api/v1/member-profile` and `"explain": true`.

## Code map

```
org.point32health.memberprofile
  api/           MemberProfileController (POST), MemberProfileRequest, MemberProfileResponse, RestExceptionHandler
  common/        ApiError, ErrorCode, MemberProfileException
  config/        MemberProfileProperties, AppConfig (clock, Jackson), RestClientConfig, OpenApiConfig
  memberdomain/  MemberDomainClient, RestMemberDomainClient, MemberDomainMember
  segmentation/  Segment, MemberFacts, ComparisonOperator, SegmentRule, SegmentRuleRepository,
                 SegmentationEvaluator, SegmentationResult
  permission/    FamilyRelationship, ActorRelationship, ViewingRelationship, RelationshipResolver,
                 PermissionRule, PermissionRuleRepository, PermissionEvaluator, PermissionResult,
                 ReferenceData(Repository), ConsentRepository
  service/       MemberProfileService (the two-wave fan-out and the evaluation)
```

## Run locally

Requires Java 17 or newer and PostgreSQL. Gradle is not needed: the wrapper downloads Gradle 9.5.0 and the
dependencies on first use. The build is the same shape as the EPA Workbench and the Patient Access
workbench: Gradle 9.5.0 wrapper, Spring Boot 4.0.7 (Spring Framework 7, Jackson 3), no toolchain block,
`options.release = 17` plus `-parameters`, `springBoot { buildInfo() }`, and an `internalRepoUrl` Gradle
property that swaps Maven Central for an internal mirror (`./gradlew -PinternalRepoUrl=https://nexus.example/repository/maven-public/ build`).

```bash
createuser member_profile -P          # password: member_profile
createdb -O member_profile member_profile
./run.sh                              # Mac / Linux: builds the jar on first use, then starts it on port 8081
run.bat                               # Windows (run.cmd is the same script); finds a JDK 17+ by itself
```

Or with the wrapper directly: `./gradlew bootRun`, or `./gradlew bootJar` then
`java -jar build/libs/member-profile-service-1.0.0.jar`. Flyway creates and seeds the tables at startup.

```bash
curl -X POST http://localhost:8081/api/v1/member-profile -H 'Content-Type: application/json' \
     -d '{"memberId":"HP0000001","explain":true}'
```

**Spring Tool Suite / Eclipse**: File > Import > Gradle > Existing Gradle Project, pick the
`member-profile-service` folder. Gradle version and JDK come from the wrapper and `gradle.properties`.

**Docker**: `docker build -t member-profile-service .` then run it with `MEMBER_PROFILE_DB_URL`,
`MEMBER_PROFILE_DB_USER`, `MEMBER_PROFILE_DB_PASSWORD` and `MEMBER_DOMAIN_BASE_URL` set.

Configuration (`application.yaml`, all overridable by environment variable):

| Property | Env var | Default |
|---|---|---|
| `spring.datasource.url` | `MEMBER_PROFILE_DB_URL` | `jdbc:postgresql://localhost:5432/member_profile` |
| `spring.datasource.username` / `password` | `MEMBER_PROFILE_DB_USER` / `MEMBER_PROFILE_DB_PASSWORD` | `member_profile` |
| `spring.datasource.hikari.maximum-pool-size` | `MEMBER_PROFILE_DB_POOL_SIZE` | `16` |
| `member-profile.member-domain.base-url` | `MEMBER_DOMAIN_BASE_URL` | `http://localhost:8090/api/v1` |
| `member-profile.member-domain.connect-timeout` / `read-timeout` | | `2s` / `3s` |
| `spring.jdbc.template.query-timeout` | | `2s` |

## Tests

```bash
./gradlew test                                # unit tests (evaluators, resolver, API slice, service)
MEMBER_PROFILE_TEST_DB=true ./gradlew test    # plus the database and end-to-end tests against the local PostgreSQL
```

The database tests run the migrations, check the seed against the workbook, and exercise the endpoint with
MemberDomain stubbed. The GitHub workflow `.github/workflows/member-profile-service.yml` runs everything
against a PostgreSQL service container and builds the Docker image.

## Not in this version

- Authentication. The caller supplies the member id; put the service behind the gateway that validates the
  League session and restrict it to the portal's service identity, or add a token filter that binds the
  member id to the session. Until then `explain` should be blocked at the gateway for portal traffic.
- Caching of rules or responses. Deliberately omitted: every call reads the current rules.
- Consent capture. `family_consent` is read here; the process that writes it is outside this service.
