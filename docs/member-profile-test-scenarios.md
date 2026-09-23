# Member Profile Service – scenario catalog

Everything the tests must cover, taken from the design workbooks
(`League_Segmentation_Table_Design.xlsx`, `Family_Permission_Table_Design.xlsx`) and the API design notes.
The seeded rules are in `member-profile-service/src/main/resources/db/migration/V2__seed_segmentation.sql`
and `V4__seed_family_permission_rules.sql`.

## 1. API contract

- `POST /api/v1/member-profile`, JSON body `{ "memberId": "...", "impersonating": false, "explain": false }`.
  Only POST. GET is 405 `METHOD_NOT_ALLOWED`. Wrong media type is 415 `UNSUPPORTED_MEDIA_TYPE`. Accept without
  JSON is 406 `NOT_ACCEPTABLE`. Unknown path is 404 `NOT_FOUND`. Bodies above 8 KB are 413 `PAYLOAD_TOO_LARGE`.
  Bad JSON is 400 `MALFORMED_REQUEST`; `details[0].field` names the property for a type mismatch and is
  `body` when the JSON itself cannot be parsed. Missing or invalid member id
  (blank, > 30 chars, characters outside `[A-Za-z0-9_-]`) is 400 `VALIDATION_ERROR` with `details[].field =
  memberId`. With the API key enabled, a missing or wrong `X-Api-Key` is 401 `UNAUTHORIZED` before anything
  else. `"explain": true` where explain is disabled is 403 `EXPLAIN_DISABLED`.
- Success body (the agreed payload):

```json
{
  "member": {
    "memberId": "HPxxxxxxx", "fullName": "Alxxx M Mxxx", "firstName": "Alxxxa", "lastName": "Mxxxx",
    "planName": null, "relationshipCode": "01", "memberTypeCode": "HPHC", "userTypeCode": "M",
    "isImpersonating": false, "age": 42, "activePolicy": false, "accountNumber": "****1234", "policyStatus": "ACTIVE",
    "segmentation": { "onlineBillPay": true, "optumRxCoverage": false, "allPublicPlansMa": false,
                      "allTuftsMedicarePreferred": false, "tmpOtcMa": false, "planOfCare": false, "interoperability": false },
    "permissions": { "benefits": [1], "benefits.idCard": [1, 3] },
    "familyPermissions": [
      { "memberId": "HPxxxx02", "fullName": "Lxxxxx", "relationshipCode": "03", "age": 7,
        "permissions": { "benefits": [1], "benefits.coverage": [1], "benefits.idCard": [1, 3] },
        "consentRequired": ["claims.claim"], "masked": ["claims.claim"] }
    ]
  },
  "actionCodeDescriptions": { "1": "View", "2": "Edit", "3": "Download", "4": "Delete" },
  "explain": { "...only when requested..." }
}
```

- `segmentation` always has exactly the seven keys, true or false, in this order: onlineBillPay,
  optumRxCoverage, allPublicPlansMa, allTuftsMedicarePreferred, tmpOtcMa, planOfCare, interoperability.
- `permissions` maps lower camelCase permission keys to sorted action code arrays. Parent keys
  (`benefits`, `claims`, `demographic`, `documents`, `forms`, `profile`) carry `[1]` when at least one child
  key has an action. Keys with no allowed action are absent. `consentRequired` and `masked` are absent when empty.
- `Cache-Control: no-store` on success and on every error.
- `member` always writes all its properties, null included (`"planName": null`); `permissions` is always
  present on the member and on every family entry, `{}` when empty; `consentRequired` and `masked` are
  absent when empty; `explain` is absent unless requested.
- Errors: `{ "status": "ERROR", "code": "...", "message": "...", "details": [ { "field", "message" } ] }`.
  `message` is fixed per code (see `ErrorCode`) and never contains ids, hosts, table names or rule ids;
  the internal detail is logged only, with the member id hashed. 404 `MEMBER_NOT_FOUND`; 422
  `MEMBER_DATA_INCOMPLETE` with `details[0].field` = memberTypeCode / age / familyMembers.age /
  relationshipCode; 502 `MEMBER_DOMAIN_ERROR` (error status, empty or unreadable body); 504
  `MEMBER_DOMAIN_UNREACHABLE`; 500 `RULE_DATA_INVALID` (unknown operator or relationship label in a rule row).

## 2. MemberDomain input

`GET {baseUrl}/members/{memberId}` → `MemberDomainMember`: identity fields, `memberTypeCode` (HPHC or THP,
used as the company), `age` or `dateOfBirth`, `attributes` (the rule facts, values may be string, number or
boolean), `familyMembers[]` (memberId, fullName, relationshipCode, age or dateOfBirth). Unknown JSON
properties are ignored. A 404 from MemberDomain is "member not found"; other 4xx/5xx are
`MEMBER_DOMAIN_ERROR`; connect/read timeouts are `MEMBER_DOMAIN_UNREACHABLE`. The roster may include the
member themselves; that entry is skipped.

## 3. Segmentation rules (V2 seed, 75 condition rows)

Evaluation: rows grouped by (segment_name, company, rule_group); AND inside a group, OR across groups; a
segment with no active group for the company is false; a missing, null or blank fact never satisfies a
condition, not even NOT_EQUALS / NOT_IN / NOT_CONTAINS. Text compares are case-insensitive and trimmed.
Numbers are compared in plain form (`2001`, `2001.0` and `2.001E3` all read as `2001`; `18.50` as `18.5`).
Booleans accept true/false, Y/N, YES/NO, 1/0 (any case, also as numbers) and JSON booleans. A
`memberTypeCode` other than HPHC or THP is 422, never a silent "all false".

Operators (Operators tab): EQUALS, NOT_EQUALS, IN (comma list), NOT_IN, CONTAINS (substring), NOT_CONTAINS,
IS_TRUE, IS_FALSE (no rule value), GREATER_THAN (numeric; non-numeric fact is false).

| Segment | Company | Group | Conditions (all ANDed) |
|---|---|---|---|
| onlineBillPay | HPHC | 1 | dependentType = 01; memberCategory = B2I; customerCategory != NH_39_WEEK |
| onlineBillPay | THP | 1 | sourceSystemId = 2001; planTypeCode NOT_IN SCO,PDP; coverageGroupTypeCode = 2; hasActivePdp IS_FALSE |
| onlineBillPay | THP | 2 | sourceSystemId = 2001; planTypeCode = CTH |
| optumRxCoverage | HPHC | 1 | basicMedicalDrugCoverageIndicator IS_TRUE |
| optumRxCoverage | THP | 1 | sourceSystemId = 2001; hasPharmacyRider IS_TRUE; product NOT_IN NPDP,RPDP |
| optumRxCoverage | THP | 2 | hasTmpMedicalCoverage IS_TRUE; hasActivePdp IS_TRUE; product NOT_IN NPDP,RPDP |
| optumRxCoverage | THP | 3 | sourceSystemId = 2001; product = PDP |
| allPublicPlansMa | THP | 1 | sourceSystemId = 2026; subsidiary = THPPMA |
| allTuftsMedicarePreferred | THP | 1 | sourceSystemId = 2001; planCode CONTAINS EG; planCode NOT_CONTAINS PDP |
| tmpOtcMa | THP | 1 | sourceSystemId = 2001; planTypeCode = SCO; coverageActive IS_TRUE |
| tmpOtcMa | THP | 2 | sourceSystemId = 2001; planTypeCode = MAP; coverageActive IS_TRUE |
| tmpOtcMa | THP | 3 | sourceSystemId = 2001; planCode = STD SAVER; coverageActive IS_TRUE |
| tmpOtcMa | THP | 4 | sourceSystemId = 2001; planCode CONTAINS SMV; coverageActive IS_TRUE |
| tmpOtcMa | THP | 5 | sourceSystemId = 2064; product = DMA; coverageActive IS_TRUE |
| planOfCare | THP | 1 | subsidiary = THPPMA; sourceSystemId = 2026; product = MC; planOfCareCoverageEligible IS_TRUE |
| planOfCare | THP | 2 | subsidiary = THPPMA; sourceSystemId = 2026; product IN PL,GT; planCode IN 10GT100000,10GT100001,10GT100002,10PL100001; planOfCareCoverageEligible IS_TRUE |
| planOfCare | THP | 3 | subsidiary = THPPMA; sourceSystemId = 2026; product IN PL,GT; planCode IN 10GT100004 … 10GT100018 (20 codes); planOfCareCoverageEligible IS_TRUE |
| planOfCare | THP | 4 | subsidiary = THPPMA; sourceSystemId = 2064; product = DMA; planOfCareCoverageEligible IS_TRUE |
| planOfCare | THP | 5 | sourceSystemId = 2001; planTypeCode = SCO; planOfCareCoverageEligible IS_TRUE |
| interoperability | THP | 1 | subsidiary = THPPRI; sourceSystemId = 2048; coverageStarted IS_TRUE; groupProductEffectiveForCoverage IS_TRUE |
| interoperability | THP | 2 | subsidiary = THPPMA; sourceSystemId = 2026; product IN PL,GT,MC; coverageStarted IS_TRUE; groupProductEffectiveForCoverage IS_TRUE |
| interoperability | THP | 3 | sourceSystemId = 2064; product = DMA; coverageActive IS_TRUE |
| interoperability | THP | 4 | sourceSystemId = 2001; planTypeCode IN MR,SCO,MAP; coverageStarted IS_TRUE; groupProductEffectiveForCoverage IS_TRUE |

HPHC has no rules for allPublicPlansMa, allTuftsMedicarePreferred, tmpOtcMa, planOfCare, interoperability:
those are always false for HPHC members. For every group above, tests need one fact set that matches and,
for each condition, one that fails only that condition. Cross-checks: a THP member with planTypeCode SCO
and coverageGroupTypeCode 2 fails onlineBillPay group 1 (NOT_IN) but tmpOtcMa group 1 and interoperability
group 4 can be true; a THP member with product NPDP or RPDP fails optumRxCoverage groups 1 and 2 (NOT_IN); a stand-alone
PDP member (no pharmacy rider, no TMP medical coverage) gets optumRxCoverage only through group 3.
The seed has 23 rule groups in total: 2 for HPHC and 21 for THP.

## 4. Family permissions

Table `family_permission.family_permission_rule` (Column Name sheet): permission_family, permission_key,
actor_relationship, viewing_relationship, age_description, minimum_age, maximum_age, action_codes
SMALLINT[], access_status, consent_required, administrative_consent, revocable, masked_data, is_active,
source_sheet, source_row, source_access_text, notes.

- Actor relationships: Subscriber, Spouse, Ex-Spouse, Adult Child (18+), Child (teenager) (13–17),
  Child (minor) (0–12). Derived from `relationship_code` (01 Subscriber, 02 Spouse, 03 Child, 04 Ex-Spouse)
  plus age.
- Viewing relationships: Self, Subscriber, Spouse, Child (under 18), Adult dependent (any relationship)
  (18+ dependent, incl. adult child or ex-spouse), All other family members (catch-all).
- Age bands: Any (null/null), 0–11, 0–12, 13–17, 18 or older (18/null).
- Access statuses: FULL_ACCESS, NO_ACCESS, CONSENT_REQUIRED, CONSENT_REQUIRED_ADMIN, REVOCABLE_ACCESS,
  MASKED_ACCESS, NOT_APPLICABLE, REVIEW_REQUIRED. NOT_APPLICABLE rows are inactive and never load.
- Action codes: 1 View, 2 Edit, 3 Download, 4 Delete.
- Permission catalog (26 keys): benefits{.accumulator,.activePolicy,.coverage,.idCard,.spendingAccount},
  claims{.authorization,.claim,.referral}, demographic{.contract,.memberOther,.memberSelf},
  documents{.letter,.planDocument,.taxDocument}, forms{.capeCodHealthcare,.coordinationOfBenefits,
  .designationOfRepresentative,.medicalReimbursement}, profile{.raceEthnicityLanguage,.sexualOrientationGenderIdentity}.

Evaluation rules:
1. Only rows whose age band contains the viewed member's age apply.
2. Rows for the exact viewing relationship win; the catch-all row for a key applies only when that key has
   no exact row (the precedence the workbook flagged as missing from its SQL). The catch-all is about other
   family members and never applies to Self.
3. `consent_required` rows grant only when `family_consent` has an unrevoked row for (actor, viewed member,
   permission family); otherwise the key is listed under `consentRequired` with no actions. With consent the
   row grants its `action_codes`, or View when it lists none.
4. `masked_data` rows add the key to `masked`, both when they grant and while consent is still pending, so
   the UI knows the data will be masked either way.
5. Parent keys are computed from children (View [1] when any child has an action); parent rows in the table
   ("Derived from child permissions") are ignored.
6. Self permissions use viewing relationship Self with the actor's own age.
7. `access_status` is descriptive except that NOT_APPLICABLE and REVIEW_REQUIRED rows never grant, whatever
   `is_active` says. CONSENT_REQUIRED_ADMIN behaves like CONSENT_REQUIRED (the consent row's `granted_by`
   records the staff member); REVOCABLE_ACCESS grants like FULL_ACCESS (a revocation is `revoked_at` on the
   consent row).

Seeded scenarios (V4) that tests must reproduce end to end:
- Subscriber viewing Self, any age: benefits.* all View, idCard View+Download, claims.claim View+Download,
  claims.authorization View, claims.referral View. Parents benefits [1], claims [1].
- Subscriber viewing Spouse: benefits children as above.
- Subscriber viewing Child 0–12: benefits children, claims.claim [1,3], claims.authorization [1], claims.referral [1].
- Subscriber viewing Child 13–17: benefits coverage/idCard/accumulator/activePolicy (no spendingAccount);
  claims.claim CONSENT_REQUIRED + masked; claims.authorization CONSENT_REQUIRED. Without consent:
  `consentRequired = [claims.authorization, claims.claim]`, `masked = [claims.claim]`, no claims keys, no
  `claims` parent. With consent on file for family `claims`: claims.claim [1] and claims.authorization [1],
  `claims` [1], `masked = [claims.claim]`.
- Subscriber viewing Adult dependent 18+: benefits.coverage [1], benefits.idCard [1].
- Subscriber viewing All other family members: claims.claim NO_ACCESS (empty) → key absent.
- Profile family, REL_SOGI rows 9–19 (exact values read from the workbook):
  - Spouse → Child 0–11: profile.raceEthnicityLanguage [1,2]; sexualOrientationGenderIdentity NOT_APPLICABLE (inactive).
  - Spouse → Child 13–17: raceEthnicityLanguage NO_ACCESS; SOGI NOT_APPLICABLE.
  - Ex-Spouse → Self: both [1,2]; parent profile [1].
  - Ex-Spouse → Subscriber: both NO_ACCESS. Ex-Spouse → Child 13–17 and 0–12: REL NO_ACCESS, SOGI NOT_APPLICABLE.
  - Adult Child → Self 18+: both [1,2]. Adult Child → All other: REL NO_ACCESS, SOGI NOT_APPLICABLE.
  - Child (teenager) → Self 13–17: raceEthnicityLanguage [1,2], SOGI NOT_APPLICABLE ("Not collected for
    members under age 18"). Child (teenager) → All other: NO_ACCESS / NOT_APPLICABLE.
  - Child (minor) → Self 0–12: everything NOT_APPLICABLE ("can not create online accounts") → empty permissions.

## 5. Relationship derivation

| MemberDomain code + age | Actor | As viewed by another member |
|---|---|---|
| 01 any | Subscriber | Subscriber |
| 02 any | Spouse | Spouse |
| 04 any | Ex-Spouse | Adult dependent (18+) or All other family members |
| 03 age 0–12 | Child (minor) | Child |
| 03 age 13–17 | Child (teenager) | Child |
| 03 age 18+ | Adult Child | Adult dependent (any relationship) |
| unknown code | error 422 (actor) | Adult dependent (18+) or All other family members (viewed) |

Age is `age` when MemberDomain sends it, else full years between `dateOfBirth` and today (Clock).
Boundaries: 12 → minor, 13 → teenager, 17 → teenager, 18 → adult.

## 6. Efficiency expectations

- Exactly one MemberDomain call per request, made on the request thread. Rule reads: consents (one query) and reference
  data (one UNION query) run on the executor during the MemberDomain call; the permission rules run on the
  executor while the request thread reads the segment rules. No caching. Waits on the executor are bounded
  by `member-profile.http.fan-out-timeout`; JDBC has connect, socket and query timeouts.
- Without `explain` no trace objects are allocated; with `explain` every condition and rule row is reported.
- Rule values are parsed once per row when read (IN sets, numbers); member facts are normalized once per request.
