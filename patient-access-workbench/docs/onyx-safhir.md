# Onyx SAFHIR / OnyxOS notes

What is publicly documented about the vendor platform the workbench is aimed at, and how to map it onto
an environment. Verify the tenant-specific values in the Onyx developer portal (`https://portal.safhir.io/`,
*Application Credentials* modal) for each tier; items marked *unverified* were not confirmed against the
vendor documentation.

## Hosts and tiers

* Production hosts follow `https://api-<tenant>-prd.safhir.io`; non-production tiers seen in public
  endpoint directories use suffixes such as `-uat`, `-t31`, `-t32` and `-alpha`
  (e.g. `https://api-ida-uat.safhir.io`). Create one workbench environment per tier.
* The tenant root `https://api-<tenant>-<env>.safhir.io/v1/api` is a landing page; each implementation guide
  is served under its own base with its own CapabilityStatement at `[base]/<ig>/metadata`:
  `/v1/api/carin-bb` (claims and coverage, C4BB), `/v1/api/pdex` (clinical data, PDex incl. prior
  authorization), `/v1/api/provider-directory` (Plan-Net, open API; older tenants use `/v1/api/plannet`),
  `/v1/api/formulary` (US Drug Formulary).
* In the workbench set **FHIR base URL** to the PDex base and fill the **IG-specific base URLs** (CARIN BB,
  PDex, Formulary, Plan-Net). Searches are routed by resource type: EOB claims and C4BB Patient/Coverage go
  to the CARIN BB base, `ExplanationOfBenefit?use=preauthorization` and US Core clinical resources to the
  PDex base, formulary resources to the Formulary base. The *Onyx SAFHIR preset* button in the environment
  editor fills all of this from the tenant host.

## Authorization

* Authorization endpoint `https://api-<tenant>-<env>.safhir.io/v1/authorize`; the documented example passes
  `aud=https://api-<tenant>-<env>.safhir.io/v1` (the `/v1` root, not the IG base). The token endpoint is
  issued with the application credentials (`/v1/token` was observed for one tenant, *unverified* for
  others); the documented token request uses `client_secret_post` style form fields.
* Public documentation shows only `authorization_code` and `refresh_token` grants for member-facing
  (Patient Access) applications: use auth mode **SMART App Launch (member login)** with the test member's
  login (ID.me or the plan's own identity provider depending on the tenant). Scopes:
  `launch/patient openid fhirUser offline_access patient/*.read` (resource-level `patient/<Type>.read` also
  accepted). The token response carries `patient` (the member's Patient id), which the workbench shows in
  the token panel and uses as the search context.
* No public evidence of `/.well-known/smart-configuration`: turn endpoint discovery off and enter the
  endpoints. Whether PKCE and `client_secret_basic` are accepted is *unverified*; the workbench sends PKCE by
  default, disable it if the authorization server rejects `code_challenge`.
* Tokens are sent as `Authorization: Bearer`. No API-key header is documented (*unverified*).

## Behaviour to expect

* Default page size around 10 records (vendor FAQ); set `_count` explicitly and let the workbench follow
  `next` links.
* `next` links may be issued on a different host name than the vanity base URL used for the request:
  enable **Allow next links on another host** for such tenants.
* Patient Access searches are bounded to the member of the token; `Patient?identifier=` and cross-member
  reads are expected to be denied for member tokens.
* IG versions are tenant-specific; recent releases advertise US Core 6.1.0, CARIN BB 2.1, PDex 2.1,
  Plan-Net 1.1 and US Drug Formulary 2.1, older tenants still run C4BB 1.x / PDex 1.x. Compare the
  `metadata` of each base (the workbench's discovery checks list declared profiles and search parameters).
* Rate limits, `429` behaviour and the availability of `_include`, `_revinclude`, `Patient/$everything`
  are not documented; the conformance suite probes them.
* Support: `support@safhir.io`, status board `https://status.safhir.io/`.
