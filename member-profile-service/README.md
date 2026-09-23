# Member Profile Service

Spring Boot service called by the League member portal and mobile app at login. For one member id it
returns, in a single JSON response, the member's identity, the segmentation flags that decide which
restricted features to show, and what the member may do with each family member's information.

Everything is evaluated **on demand**. Per request the service:

1. fetches the member, their rule facts and their family roster from the **MemberDomain** service;
2. reads the active segmentation rules for the member's company from `league_segmentation.segment_rule`
   and evaluates them (AND inside a `rule_group`, OR across groups);
3. reads the active permission rules for the member's actor relationship from
   `family_permission.family_permission_rule`, plus consent on file, and evaluates them for the member
   (self) and for each family member;
4. returns the response. Nothing is cached, so a rule change applied to the database is live on the next call.

Data flow: [`docs/diagrams/member-profile-data-flow.png`](../docs/diagrams/member-profile-data-flow.png)
(sequence) and [`docs/diagrams/member-profile-components.png`](../docs/diagrams/member-profile-components.png)
(components). Design notes: [`docs/member-profile-service-design.md`](../docs/member-profile-service-design.md).

## Endpoint

```
GET /api/v1/members/{memberId}/profile
    ?explain=true            optional: include the rule-by-rule evaluation trace (support, testing)
    X-Impersonating: true    optional header: a CSR is impersonating the member
```

Response (abridged):

```json
{
  "member": { "memberId": "HP0000001", "fullName": "Alexa M Miller", "relationshipCode": "01",
              "memberTypeCode": "HPHC", "userTypeCode": "M", "company": "HPHC", "isImpersonating": false,
              "age": 42, "activePolicy": true, "accountNumber": "****1234", "policyStatus": "ACTIVE" },
  "segmentation": { "onlineBillPay": true, "optumRxCoverage": false, "allPublicPlansMa": false,
                    "allTuftsMedicarePreferred": false, "tmpOtcMa": false, "planOfCare": false,
                    "interoperability": false },
  "selfPermissions": { "benefits": [1], "benefits.idCard": [1, 3], "claims": [1], "claims.claim": [1, 3] },
  "familyPermissions": [
    { "memberId": "HP0000002", "fullName": "Liam Miller", "relationshipCode": "03", "age": 7,
      "permissions": { "benefits": [1], "benefits.coverage": [1], "benefits.idCard": [1, 3], "claims": [1], "claims.claim": [1, 3] } },
    { "memberId": "HP0000003", "fullName": "Mia Miller", "relationshipCode": "03", "age": 15,
      "permissions": { "benefits": [1], "benefits.idCard": [1, 3] },
      "consentRequired": ["claims.authorization", "claims.claim"] }
  ],
  "actionCodes": { "1": "View", "2": "Edit", "3": "Download", "4": "Delete" },
  "meta": { "generatedAt": "2026-09-22T17:09:59Z" }
}
```

- `segmentation` always contains every active row of `league_segmentation.segment`, true or false.
- `permissions` maps a permission key to its sorted action codes. A parent key (`benefits`, `claims`, ...)
  is present with `[1]` (View) when at least one of its child keys has an action. Keys with no actions are absent.
- `consentRequired` lists keys that would be granted once consent is on file in `family_permission.family_consent`.
- `masked` lists keys whose data must be shown masked (PDC).

Errors: `404 MEMBER_NOT_FOUND`, `400 INVALID_REQUEST`, `422 MEMBER_DATA_INCOMPLETE` (unknown relationship
code, no company, no age or date of birth), `502 MEMBER_DOMAIN_UNAVAILABLE`.

Swagger UI: `http://localhost:8081/swagger-ui.html`.

## What the service expects from MemberDomain

`GET {member-domain.base-url}/members/{memberId}` returning JSON like:

```json
{
  "memberId": "HP0000001", "firstName": "Alexa", "lastName": "Miller", "fullName": "Alexa M Miller",
  "planName": null, "relationshipCode": "01", "memberTypeCode": "HPHC", "userTypeCode": "M",
  "company": "HPHC", "dateOfBirth": "1984-03-02", "age": 42, "activePolicy": true,
  "accountNumber": "****1234", "policyStatus": "ACTIVE",
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

`attributes` holds the member facts the segmentation rules name in `api_field`. Either `age` or
`dateOfBirth` is required for the member and each family member. `company` falls back to `memberTypeCode`.
Unknown properties are ignored.

## Rule tables

| Table | Purpose |
|---|---|
| `league_segmentation.segment` | The segments the response always returns. |
| `league_segmentation.segment_rule` | One condition per row. Same `segment_name` + `company` + `rule_group` = AND; different groups = OR. |
| `family_permission.permission_catalog` | The permission keys (26) and their families. |
| `family_permission.family_permission_rule` | Actor relationship × viewing relationship × age band → action codes, consent and masking flags. |
| `family_permission.relationship_code` | MemberDomain relationship code → Subscriber / Spouse / Ex-Spouse / Child. |
| `family_permission.action_code` | 1 View, 2 Edit, 3 Download, 4 Delete. |
| `family_permission.family_consent` | Consent on file for CONSENT_REQUIRED rules. |

Schema and seed live in `src/main/resources/db/migration` and are applied by Flyway at startup.
`V4__seed_family_permission_rules.sql` is a **partial** seed (profile family plus benefits/claims examples);
replace it with the full export of the "Family Permission Rules" sheet before go-live.

### Changing a rule

Rules are data. A change is a reviewed SQL script applied by the DBA; it is live on the next request.

```sql
-- Example: HPHC online bill pay also for member category B2C
INSERT INTO league_segmentation.segment_rule
  (segment_name, segment_category, league_capability, company, rule_group, evaluation_order,
   logical_operator, api_field, comparison_operator, rule_value, updated_by, change_note)
VALUES
  ('onlineBillPay', 'Premium payment', 'home', 'HPHC', 2, 4, 'AND', 'dependentType',    'EQUALS',     '01',         'jdoe', 'CR-1234'),
  ('onlineBillPay', 'Premium payment', 'home', 'HPHC', 2, 5, 'AND', 'memberCategory',   'EQUALS',     'B2C',        'jdoe', 'CR-1234'),
  ('onlineBillPay', 'Premium payment', 'home', 'HPHC', 2, 6, NULL,  'customerCategory', 'NOT_EQUALS', 'NH_39_WEEK', 'jdoe', 'CR-1234');

-- Retire a condition instead of deleting it
UPDATE league_segmentation.segment_rule SET is_active = FALSE, updated_by = 'jdoe', change_note = 'CR-1235'
 WHERE segment_rule_id = 42;
```

Check the effect with `GET /api/v1/members/{memberId}/profile?explain=true`, which shows every rule group
with pass/fail per condition and every permission row that was selected.

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
`/actuator/info` reports the build version.

**Spring Tool Suite / Eclipse**: File > Import > Gradle > Existing Gradle Project, pick the
`member-profile-service` folder. Gradle version and JDK come from the wrapper and `gradle.properties`.

**Docker**: `docker build -t member-profile-service .` then run it with `MEMBER_PROFILE_DB_URL`,
`MEMBER_PROFILE_DB_USER`, `MEMBER_PROFILE_DB_PASSWORD` and `MEMBER_DOMAIN_BASE_URL` set.

Configuration (`application.yaml`, all overridable by environment variable):

| Property | Env var | Default |
|---|---|---|
| `spring.datasource.url` | `MEMBER_PROFILE_DB_URL` | `jdbc:postgresql://localhost:5432/member_profile` |
| `spring.datasource.username` / `password` | `MEMBER_PROFILE_DB_USER` / `MEMBER_PROFILE_DB_PASSWORD` | `member_profile` |
| `member-profile.member-domain.base-url` | `MEMBER_DOMAIN_BASE_URL` | `http://localhost:8090/api/v1` |
| `member-profile.member-domain.connect-timeout` / `read-timeout` | | `2s` / `3s` |

## Tests

```bash
./gradlew test                                # evaluator and resolver unit tests
MEMBER_PROFILE_TEST_DB=true ./gradlew test    # plus the end-to-end test against the local PostgreSQL
```

The end-to-end test runs the migrations, stubs MemberDomain, and checks an HPHC subscriber with a young
child and a THP Medicare subscriber with a teenager (consent required for claims). The GitHub workflow
`.github/workflows/member-profile-service.yml` runs both against a PostgreSQL service container and
builds the Docker image.

## Not in this version

- Authentication. The member id is a path parameter; put the service behind the gateway that validates the
  League session and restrict it to the portal's service identity, or add a token filter that derives the
  member id from the session and ignores the path.
- Caching of rules or responses. Deliberately omitted: every call reads the current rules.
