# CMS-1500 Claim Bundle Service

Spring Boot service that fills the CMS-1500 (NUCC 02/12) health insurance claim form from a
claim sent by TIBCO, appends the claim's attachments from the shared drive and writes one PDF,
`<claimNumber>.pdf`, back to the shared drive. The same operation is exposed twice, over
**JSON/REST** and over **SOAP**, from one contract (`src/main/resources/xsd/cms1500-claim.xsd`).

## What it does

1. Validates the claim (required items, formats, cross-field rules such as accident state,
   date ranges, diagnosis pointer references, item 28 total, NPI check digit).
2. Fills the fillable NUCC form. More than six service lines continue on additional form pages
   with the header repeated; item 28 (total charge) and 29 (amount paid) print on the last page only.
3. Finds attachments named `<claimNumber>_1.<ext>`, `<claimNumber>_2.<ext>`, ... in the
   attachments folder (case-insensitive, ordered by the number, an optional description may follow
   the number, e.g. `CLM123_2_EOB.pdf`).
4. Writes `<claimNumber>.pdf` to the output folder: form page(s) first, then every attachment in
   order. PDF attachments are merged page for page; JPG, PNG, TIFF (multi-page), BMP and GIF become
   one page per image, fitted to letter size.
5. Answers with where the bundle is (or an error); a `GET` endpoint also streams it for download.

## Quick start

Only Java 17 or newer is required (`java -version` to check). Maven is not needed: the Maven
wrapper (`mvnw` / `mvnw.cmd`) downloads it on first use.

```bash
./run.sh          # Mac / Linux
run.cmd           # Windows
```

The script uses the prebuilt jar in `target/` when present, otherwise builds it, creates
`data/attachments` and `data/bundles` next to the project, and starts the service on port 8080
(dev profile). Then, from another terminal:

```bash
curl -X POST http://localhost:8080/api/v1/claims/cms1500 \
     -H 'Content-Type: application/json' --data-binary @samples/claim-full.json
```

Drop files named `CLM-2026-000123_1.pdf`, `CLM-2026-000123_2.png`, ... into `data/attachments`
first and they are bundled behind the form; the result lands in `data/bundles/CLM-2026-000123.pdf`.
Swagger UI: `http://localhost:8080/swagger-ui.html`. WSDL: `http://localhost:8080/ws/cms1500.wsdl`.

## Build and run manually

```bash
./mvnw verify                                                    # builds and runs 96 tests
java -jar target/cms1500-claim-service-0.1.0-SNAPSHOT.jar        # dev profile, ./data/...
SPRING_PROFILES_ACTIVE=prod java -jar target/cms1500-claim-service-0.1.0-SNAPSHOT.jar
java -jar target/cms1500-claim-service-0.1.0-SNAPSHOT.jar --server.port=9090   # any Spring option
```

The attachments folder must already exist in prod (it is the shared drive); the output folder is
created if missing. Effective folders and policies are logged at startup.

## Configuration per environment

`application.yaml` holds the defaults; `application-dev.yaml` and `application-prod.yaml` override
the folders per profile. Every value can also be set with an environment variable
(`CMS1500_ATTACHMENTS_DIR`, `CMS1500_OUTPUT_DIR`, or any Spring relaxed-binding name such as
`CMS1500_OUTPUT_OVERWRITE=false`).

| Property | Default | Meaning |
|---|---|---|
| `cms1500.template` | `classpath:forms/cms1500-02-12.pdf` | Fillable NUCC 02/12 template (`file:` URLs allowed). Verified at startup. |
| `cms1500.attachments.root` | `./data/attachments` (dev), `/mnt/claims/attachments` (prod) | Shared-drive folder holding `<claimNumber>_<n>.<ext>` |
| `cms1500.attachments.allowed-extensions` | `pdf,jpg,jpeg,png,tif,tiff,bmp,gif` | Types that are bundled |
| `cms1500.attachments.unsupported` | `FAIL` | `FAIL` the request or `SKIP` (with a warning) a matching file of another type |
| `cms1500.attachments.when-none` | `WARN` | `WARN` (bundle holds the form only) or `FAIL` when no attachment matches |
| `cms1500.output.root` | `./data/bundles` (dev), `/mnt/claims/bundles` (prod) | Where `<claimNumber>.pdf` is written |
| `cms1500.output.overwrite` | `true` | Replace an existing bundle for the same claim number, or reject with `BUNDLE_EXISTS` |
| `cms1500.form.uppercase` | `true` | Print text in upper case |
| `cms1500.form.strip-diagnosis-periods` | `true` | `S82.101A` prints as `S82101A` (NUCC instruction) |
| `cms1500.form.continuation-marker` | `""` | Text for item 28 on all but the last page of a multi-page claim |

## REST API

OpenAPI UI: `http://host:8080/swagger-ui.html` (spec at `/api-docs`; disabled in the prod profile).

```bash
curl -X POST http://localhost:8080/api/v1/claims/cms1500 \
     -H 'Content-Type: application/json' --data-binary @samples/claim-full.json
```

```json
{
  "status": "GENERATED",
  "claimNumber": "CLM-2026-000123",
  "fileName": "CLM-2026-000123.pdf",
  "bundlePath": "/mnt/claims/bundles/CLM-2026-000123.pdf",
  "totalPages": 6,
  "formPages": 1,
  "attachmentCount": 4,
  "attachments": [
    { "index": 1, "fileName": "CLM-2026-000123_1.pdf", "type": "PDF", "pages": 2 },
    { "index": 3, "fileName": "CLM-2026-000123_3.png", "type": "IMAGE", "pages": 1 }
  ],
  "warnings": [],
  "replacedExisting": false,
  "generatedAt": "2026-09-09T15:24:20.906Z"
}
```

Download: `GET /api/v1/claims/{claimNumber}/bundle` returns `application/pdf` with a
`Content-Disposition: attachment` header.

Errors always have the same shape:

```json
{
  "status": "ERROR",
  "claimNumber": "CLM-BAD",
  "code": "VALIDATION_ERROR",
  "message": "Claim failed validation with 2 error(s): [...]",
  "details": [
    { "field": "patient.sex", "message": "is required" },
    { "field": "billingProvider.npi", "message": "'1234567890' fails the NPI check digit" }
  ]
}
```

| Code | HTTP | When |
|---|---|---|
| `MALFORMED_REQUEST` | 400 | Body is not valid JSON for the contract (unknown property, bad enum, bad date); `details[0].field` names the path |
| `VALIDATION_ERROR` | 400 | Claim content breaks a CMS-1500 rule; one detail per violation |
| `BUNDLE_NOT_FOUND` | 404 | Download requested for a claim with no bundle yet |
| `BUNDLE_EXISTS` | 409 | Bundle exists and `overwrite` is off |
| `NO_ATTACHMENTS` | 422 | No matching files and `when-none` is `FAIL` |
| `UNSUPPORTED_ATTACHMENT` | 422 | A matching file has a disallowed type and `unsupported` is `FAIL` |
| `ATTACHMENT_UNREADABLE` | 422 | Corrupt or password-protected attachment |
| `TEMPLATE_ERROR`, `STORAGE_ERROR`, `INTERNAL_ERROR` | 500 | Template mismatch, folder or disk problem, unexpected failure |

## SOAP API

* Endpoint: `POST http://host:8080/ws` (SOAP 1.1, `Content-Type: text/xml`)
* WSDL: `http://host:8080/ws/cms1500.wsdl` (operation `generateClaimBundle`, port `Cms1500ClaimPort`)
* Requests are validated against the XSD before reaching the service.

```bash
curl -X POST http://localhost:8080/ws -H 'Content-Type: text/xml;charset=UTF-8' \
     --data-binary @samples/claim-soap-request.xml
```

The response body is `generateClaimBundleResponse/result` with the same fields as the JSON result.
Failures are SOAP faults (`Client` for caller problems, `Server` for service problems) whose
`detail` carries a `generateClaimBundleFault` element with the same `code`, `message` and
`details` as the JSON error.

## The claim contract

The JSON body and the SOAP `claim` element have the identical structure, defined once in the XSD
and documented item by item there. Key points:

* `claimNumber` names the bundle and selects attachments; it is not printed on the form.
  Allowed characters: letters, digits, `.`, `_`, `-` (max 64).
* Dates are ISO (`2026-07-16`); money is decimal with at most two places; booleans map to the
  YES/NO boxes and are left blank when omitted.
* `patient.relationshipToInsured = SELF` lets you omit `insured`; items 4, 7 and 11a are then
  copied from the patient.
* `diagnoses` holds up to 12 codes (items 21 A-L); each line's `diagnosisPointers` (e.g. `AB`)
  must reference them.
* `totalCharge` is optional; it is computed from the lines and rejected if it does not match.
* Signature items 12, 13 and 31 accept `signatureOnFile`/`onFile` and print SIGNATURE ON FILE.
* Text is reduced to printable ASCII (accents folded) so the template font can render it.

Samples: `samples/claim-full.json` (every item populated) and `samples/claim-soap-request.xml`
(self-insured Medicare-style claim). Both are exercised by the test suite.

## Project layout

```
src/main/resources/xsd/cms1500-claim.xsd     contract (JAXB classes generated at build time)
src/main/resources/forms/cms1500-02-12.pdf   NUCC 02/12 fillable template (see NOTICE.md)
config/      Cms1500Properties, WebServiceConfig (Spring-WS), JacksonConfig, StartupReport
domain/      ClaimValidator, Patterns (incl. NPI Luhn), ClaimException
pdf/         Cms1500Fields (field names per item), Cms1500FormFiller (fill + paginate + flatten),
             FormText (NUCC formatting), PdfBundler, ImagePages, TemplateSource
attachments/ AttachmentLocator (naming convention)
service/     ClaimBundleService (validate -> fill -> locate -> bundle -> write)
api/rest     ClaimRestController, RestExceptionHandler
api/soap     ClaimSoapEndpoint, ClaimSoapFaultResolver
```

## Tests

`mvn test` runs unit tests for every layer plus Spring tests: field-by-field checks of the filled
form, pagination, all validation rules, attachment discovery, merging of PDF/JPG/PNG/multi-page
TIFF/BMP/GIF, encrypted and corrupt attachments, every error code over REST, SOAP faults, schema
validation, the WSDL, and a real-HTTP round trip including the download.
`Cms1500FormFillerTest` also writes `target/visual/cms1500-full.pdf` and `.png` for eyeballing.
