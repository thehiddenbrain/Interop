# Vendor Extract Builder

A self-service application for the vendor data exchange team. A business or technical analyst
signs in, picks data elements from the catalog (Member, Coverage, Claim, Provider), arranges and
renames them to match the vendor's layout, applies transformations from a closed rule set, builds
a masked sample from real backend data, gets it approved, and productionalizes it into a scheduled
feed delivered through the Axway MFT drop folders. No developer in the loop, no code generated.

This is a fully working demo build: Spring Boot 3, Java 17, Gradle, a static browser UI, and
JSON files on disk instead of a database. Everything you see runs locally.

## Quick start

Only a JDK 17 or newer is required (`java -version` to check). Gradle is downloaded by the
wrapper on first use; nothing else to install.

```
run.bat        Windows
./run.sh       Mac / Linux
```

The script builds on first start (a few minutes), starts the app on port 8090 and opens
http://localhost:8090/ui/ in your browser. Stop it with Ctrl+C.

Other useful addresses:

| Address | What |
|---|---|
| http://localhost:8090/ui/ | The application |
| http://localhost:8090/swagger-ui.html | The REST API, browsable |
| http://localhost:8090/api/v1/health | Health, seed status, file locations |

### Running from Spring Tool Suite or Eclipse

1. File → Import → Gradle → **Existing Gradle Project**, pick the `extract-builder` folder, Finish.
2. Right-click `ExtractBuilderApplication` → Run As → **Spring Boot App** (or Java Application).
3. Open http://localhost:8090/ui/.

The working directory must be the project folder (the default in STS), because the data lives in
`data/` next to `build.gradle`. IntelliJ IDEA and VS Code open the folder as a Gradle project the
same way.

### Command line

```
./gradlew bootRun     start
./gradlew test        run the automated tests
./gradlew bootJar     build build/libs/extract-builder-0.1.0-SNAPSHOT.jar (run it from this folder)
```

## The five-minute walkthrough

The demo data is seeded on first start: six vendors, six definitions in every lifecycle state, a
month of run history, a pending approval and a file held by a quality gate. Use the user switcher
in the top-right corner to change roles.

1. **Dashboard** (Jordan Rivera, analyst). Live feeds, next deliveries, a held file, the hours the
   team no longer spends hand-building extracts.
2. **Definitions → New definition.** Pick Northwind Vision, name it, Create draft. Click elements
   in the catalog to add fields. Drag rows to reorder, type over a header to rename it.
3. **Click a field row** to open the editor. Add a second input (First name) and the layout
   combines them; pick a template like `{1}, {2}`. Add rules: format the date `MM/dd/yyyy`, map
   `M/F` to `1/2`, look up the vendor's own member id through a crosswalk, and so on. "Preview 6
   values" shows the field on real rows, masked.
4. **Preview 20 rows**, then **Generate sample**. The sample is built from the real backend data
   with format-preserving masking: pseudonyms for names, digits swapped in identifiers, dates
   shifted. Widths and code sets stay real, so the vendor can validate the file.
5. **Send to vendor test route** drops it in the vendor's test folder. **Request approval** with the
   vendor's acceptance note.
6. Switch to **Dev Patel (approver) → Approvals.** The card shows the diff against the live
   version, warnings, PHI elements and the vendor's BAA status. Approve.
7. **Productionalize.** The wizard sets the file name pattern, schedule (presets or cron, partner
   timezone, holiday calendar, misfire policy), delivery route and retries, quality gates, alerts
   and SLA. The **dry run** checks the layout, the partner, the drop folder, the contract, the BAA,
   PGP keys and the first run dates. **Go live** retires the previous version.
8. **Runs → Simulate tonight** fires the production run: the file lands in the drop folder with a
   control file, the Axway simulator picks it up a few seconds later and writes an acknowledgement.
9. **Runs** also shows the Bluebird file **held** by the quality gate (lookup misses above the
   limit). An approver releases it, or the layout is fixed and the run retried.
10. **Import a vendor spec**: paste a vendor's layout document and the app proposes the definition,
    matching aliases, date patterns, lengths and crosswalks with confidence scores.
11. **Compliance**: the minimum-necessary report per vendor (which PHI elements, which feeds, BAA,
    contract end, sample downloads, unmasked access), exportable as CSV. **Catalog** shows the
    impact of changing any element. **Audit log** records everything. **Feed API** serves the same
    definition as JSON to a keyed caller.
12. **Settings** (Morgan Chen, admin): Axway simulator, scheduler, self-approval, the hours
    assumption behind the dashboard, and **Reset demo data**.

## Demo users

| Switch to | Role | Can |
|---|---|---|
| Jordan Rivera | Analyst | Create, edit, sample, request approval |
| Priya Okafor | Analyst with PHI privilege | The same, plus unmasked samples when the partner allows |
| Dev Patel | Approver | Approve or reject, release held files, productionalize, retire |
| Morgan Chen | Admin | Partners, settings, API keys, reset |

Users come from the switcher (header `X-Demo-User` on the API). In production this is the identity
provider; the roles and the separate PHI privilege stay the same.

## What is in the box

**Layout builder.** Catalog browsing by subject area and entity, search with aliases, click to add,
drag to reorder, inline rename, per-field description for the vendor spec, constant fields,
run-time tokens (run date, business date, vendor code, file sequence, version, row number),
default values, max length with truncate-or-fail, fixed-width widths and padding, live per-field
preview, and a validation strip that explains every problem and shows the SQL the engine would run.

**Closed rule set (14 rules), each with a schema-driven form.** CONSTANT, CONCAT (separator or
template), COALESCE, LOOKUP (crosswalks, effective-dated, composite keys, miss policy), MAP_VALUES
(codes and ranges), CONDITIONAL (if / else-if on this or other elements), FORMAT_DATE, DATE_MATH
(add, month bounds, age, days between), ARITHMETIC, FORMAT_NUMBER (decimals, implied decimal,
padding, negatives, booleans), CLEAN_TEXT (case, strip, ASCII fold, replace), SUBSTRING (position
or token), SEQUENCE (file or group), MASK. Inputs can be shaped before they are combined. Rules
are type-checked in the chain.

**Joins, filters, sort and scope.** Join paths from the catalog with selectors for one-to-many
relationships (current as of the run date, latest by date, flagged row). Filters on any filterable
element with run-date tokens, plus catalog filter templates such as "active as of". Sorting.
Full or incremental scope with watermark columns, lag and initial watermark.

**File format.** Delimited (any delimiter, quoting policy, header row, line ending, encoding, null
text, delimiter-in-value policy) or fixed width, header and trailer records with `RECORD_COUNT`,
`SUM(field)` and date tokens, file name patterns with vendor, subject, date, time, sequence and
version tokens.

**Samples.** Masked by default with deterministic, format-preserving pseudonyms; unmasked only for
users with the PHI privilege on partners that allow it, and audited. Synthetic mode, cohort of
specific ids, row limits, retention.

**Lifecycle.** DRAFT → SAMPLED → PENDING_APPROVAL → APPROVED → PRODUCTION → RETIRED, with reject,
withdraw, versions cloned from the live layout, a diff of what changed, frozen production layouts
and a spec hash on every run for traceability.

**Productionalize wizard and scheduler.** Presets or cron in the partner's timezone, holiday
calendar with policies, misfire handling, pause and resume, next-run preview, dry run with nine
checks, zero-row policy, retries with backoff, control files, PGP by Axway or by the app,
compression, SLA deadline with alerts.

**Runtime.** Production runs with quality gates (row-count variance against history, null rate,
lookup misses, truncation) that hold a file instead of sending it, release, retry, re-deliver,
re-run as of a business date, watermark commit, drop-folder delivery with `.done` control files,
a simulated Axway pick-up and acknowledgement, contract-end awareness, notifications and a full
audit trail.

**Governance and productivity.** Compliance report and CSV export, change impact analysis per
element, vendor spec import, feed API with per-partner keys, templates and cloning, dashboard
with hours saved.

## How it works

```
browser (static UI, no framework)
   |  REST /api/v1
Spring Boot
   |-- catalog      catalog.json: subject areas, entities, elements, join paths, lookups, filter templates
   |-- definition   definitions and versions, lifecycle, approval, diff, dry run
   |-- engine       Compiler -> QueryPlanner -> Pipeline (rules) -> FileWriter -> Runner
   |-- transform    the 14 rules, masking, param schemas served to the UI
   |-- runtime      RunService, quality gates, Deliverer, Scheduler, AxwaySimulator, calendars
   |-- store        JsonStore: one JSON file per collection, atomic writes
   `-- data/
        catalog.json, calendars.json      maintained by the integration team
        source/*.json                     stands in for the backend database
        seed/                             first-start state
        state/                            definitions, runs, partners, audit (created at run time)
        samples/, staging/, mft/          files: samples, .part files, drop folders and sent folders
```

The compiler turns a definition into a plan: which columns to read, which joins to make, the
filter clauses and their bound parameters, the rule chain per field, and a SQL rendering of the
same plan. The planner executes the plan against the JSON source rows in memory; against a real
database it would run the rendered SQL. The pipeline applies the rules row by row with masking as
an overlay, and the file writer streams the records, computes the trailer and the SHA-256, and
renames the `.part` file into place.

Everything mutable is a JSON file under `data/state`, written atomically. Delete the folder (or
use **Reset demo data**) to start over. The `data/` files that the demo needs are committed; the
run-time folders are ignored by git.

## What is simulated

* **The backend database.** `data/source/*.json` holds a few thousand members, coverage spans,
  claims, claim lines, providers and lookups. The catalog maps elements to those columns exactly as
  it would to database columns. Swap the planner's data access for JDBC and the rest is unchanged.
* **Axway MFT.** Delivery writes into `data/mft/<partner>/out`. The simulator plays the Axway
  poller: it moves the file to `sent/`, writes a `.ack`, and marks the run transferred. In
  production the app writes to the real drop folder and reads the real acknowledgement.
* **Users.** The switcher stands in for single sign-on.
* **PGP.** Recorded and checked (key on file, expiry), not performed.

## Tests

```
./gradlew test
```

* `RulesTest`: every rule and the masking functions, pinned to the behaviour their help text
  promises.
* `ExtractBuilderIntegrationTest`: boots the application against a temporary copy of `data/` and
  walks the scenarios through the REST API: catalog, layout building, joins, masked preview and
  sample, test route, approval and its role checks, dry run, go-live, run-now delivery with control
  file, versioning and diff, validation errors, held-file release, compliance and partner changes,
  spec import, the feed API and schedule previews.

A browser walkthrough (Playwright) of the same story was used during development to check the UI
end to end, including the dark theme.

## Folder layout

```
extract-builder/
  build.gradle, settings.gradle, gradlew, gradlew.bat, run.bat, run.sh
  data/                       catalog, calendars, source data, seed
  src/main/java/com/thehiddenbrain/interop/extract/
    api/          REST controllers
    audit/        audit events
    catalog/      catalog model and source data
    compliance/   minimum-necessary reporting and impact analysis
    config/       properties, web config, error handling
    definition/   definitions, versions, lifecycle
    engine/       compiler, planner, pipeline, writer, runner
    partners/     MFT partners and routes
    runtime/      runs, quality gates, delivery, scheduler, Axway simulator
    seed/         demo seed
    specimport/   vendor spec import
    store/        JSON stores
    transform/    rules and masking
    users/        demo users and roles
  src/main/resources/
    application.yaml
    ui/           index.html, app.css, app.js, pages/*.js
  src/test/java/  tests
```

## Configuration

`src/main/resources/application.yaml`:

| Property | Default | Meaning |
|---|---|---|
| `server.port` | 8090 | HTTP port |
| `extract.data-dir` | `data` | Where everything lives, relative to the working directory |
| `extract.axway-simulator` | true | Run the simulated Axway poller |
| `extract.axway-pickup-seconds` | 8 | Seconds before the simulator picks a file up |
| `extract.hours-per-hand-built-extract` | 40 | Assumption behind the dashboard's hours-saved tile |

The same switches, plus self-approval and sample retention, can be changed at run time on the
Settings page by an admin.
