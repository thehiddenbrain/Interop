package com.thehiddenbrain.interop.patientaccess.demo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.thehiddenbrain.interop.patientaccess.auth.Pkce;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The demo Patient Access API through the real Spring MVC stack: discovery, security, OAuth flows, search semantics, errors. */
@SpringBootTest
@AutoConfigureMockMvc
class DemoServerTest {

    static final String FHIR = "/demo/fhir";
    static final String AUTH = "/demo/auth";
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("paw.data-dir", () -> dataDir.toString());
        registry.add("paw.demo.enabled", () -> "true");
        registry.add("paw.history.persist", () -> "false");
    }

    @Autowired
    MockMvc mvc;

    // ------------------------------------------------------------------ discovery

    @Test
    void metadataDescribesAConformantPatientAccessServer() throws Exception {
        MvcResult r = mvc.perform(get(FHIR + "/metadata")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/fhir+json"))).andReturn();
        JsonNode cs = JSON.readTree(r.getResponse().getContentAsString());
        assertEquals("CapabilityStatement", cs.path("resourceType").asString(""));
        assertEquals("4.0.1", cs.path("fhirVersion").asString(""));
        assertEquals("instance", cs.path("kind").asString(""));
        assertTrue(texts(cs.path("format")).contains("application/fhir+json"));
        assertTrue(texts(cs.path("instantiates")).contains(DemoCapabilityStatement.C4BB_CAPABILITY));
        assertTrue(texts(cs.path("implementationGuide")).contains("http://hl7.org/fhir/us/carin-bb|2.1.0"), cs.path("implementationGuide").toString());
        assertTrue(texts(cs.path("implementationGuide")).contains("http://hl7.org/fhir/us/core|6.1.0"));
        assertEquals(DemoCapabilityStatement.SOFTWARE_NAME, cs.path("software").path("name").asString(""));
        JsonNode security = cs.path("rest").path(0).path("security");
        assertEquals("SMART-on-FHIR", security.path("service").path(0).path("coding").path(0).path("code").asString(""));
        JsonNode oauth = security.path("extension").path(0);
        assertEquals("http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris", oauth.path("url").asString(""));
        Set<String> uris = new HashSet<>();
        for (JsonNode e : oauth.path("extension")) {
            uris.add(e.path("url").asString("") + "=" + e.path("valueUri").asString(""));
        }
        assertTrue(uris.contains("token=http://localhost/demo/auth/token"), uris.toString());
        assertTrue(uris.contains("authorize=http://localhost/demo/auth/authorize"));
        JsonNode eob = null;
        for (JsonNode res : cs.path("rest").path(0).path("resource")) {
            if ("ExplanationOfBenefit".equals(res.path("type").asString(""))) {
                eob = res;
            }
        }
        assertNotNull(eob, "ExplanationOfBenefit resource");
        Set<String> params = new HashSet<>();
        for (JsonNode p : eob.path("searchParam")) {
            params.add(p.path("name").asString(""));
        }
        assertTrue(params.containsAll(List.of("_id", "_lastUpdated", "patient", "identifier", "type", "use", "service-date", "billable-period-start")), params.toString());
        assertTrue(texts(eob.path("supportedProfile")).contains("http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/pdex-priorauthorization"));
        assertTrue(texts(eob.path("searchInclude")).contains("ExplanationOfBenefit:*"));
        Set<String> interactions = new HashSet<>();
        for (JsonNode i : eob.path("interaction")) {
            interactions.add(i.path("code").asString(""));
        }
        assertEquals(Set.of("read", "vread", "search-type"), interactions);
    }

    @Test
    void smartConfigurationHasTheRequiredFields() throws Exception {
        JsonNode doc = json(mvc.perform(get(FHIR + "/.well-known/smart-configuration")).andExpect(status().isOk()).andReturn());
        assertEquals("http://localhost/demo/auth/authorize", doc.path("authorization_endpoint").asString(""));
        assertEquals("http://localhost/demo/auth/token", doc.path("token_endpoint").asString(""));
        assertEquals("http://localhost/demo/auth/jwks", doc.path("jwks_uri").asString(""));
        assertTrue(doc.hasNonNull("issuer") && doc.hasNonNull("registration_endpoint"));
        assertTrue(texts(doc.path("capabilities")).containsAll(List.of("launch-standalone", "client-confidential-asymmetric", "permission-v2", "context-standalone-patient")));
        assertEquals(List.of("S256"), texts(doc.path("code_challenge_methods_supported")));
        assertTrue(texts(doc.path("grant_types_supported")).containsAll(List.of("authorization_code", "client_credentials", "refresh_token")));
        assertTrue(texts(doc.path("scopes_supported")).containsAll(List.of("openid", "fhirUser", "offline_access", "launch/patient", "patient/*.rs", "system/*.rs", "patient/ExplanationOfBenefit.rs")));
        assertEquals(List.of("code"), texts(doc.path("response_types_supported")));
        assertTrue(texts(doc.path("token_endpoint_auth_methods_supported")).containsAll(List.of("client_secret_basic", "client_secret_post", "private_key_jwt")));
    }

    // ------------------------------------------------------------------ security and tokens

    @Test
    void fhirEndpointsRequireABearerToken() throws Exception {
        MvcResult r = mvc.perform(get(FHIR + "/Patient")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"demo\"")).andReturn();
        JsonNode outcome = json(r);
        assertEquals("OperationOutcome", outcome.path("resourceType").asString(""));
        assertEquals("login", outcome.path("issue").path(0).path("code").asString(""));
        assertEquals("missing or invalid bearer token", outcome.path("issue").path(0).path("diagnostics").asString(""));
        mvc.perform(get(FHIR + "/Patient").header("Authorization", "Bearer not-a-token")).andExpect(status().isUnauthorized());
        mvc.perform(get(FHIR + "/Patient/Patient1").header("Authorization", "Bearer " + DemoAuthService.STATIC_TOKEN)).andExpect(status().isOk());

        JsonNode bundle = search("/Patient", systemToken());
        assertEquals("searchset", bundle.path("type").asString(""));
        assertEquals(4, bundle.path("total").asInt());
        assertEquals("http://localhost/demo/fhir/Patient", bundle.path("link").path(0).path("url").asString(""));
        assertEquals("http://localhost/demo/fhir/Patient/Patient1", bundle.path("entry").path(0).path("fullUrl").asString(""));
        assertEquals("match", bundle.path("entry").path(0).path("search").path("mode").asString(""));
    }

    @Test
    void clientCredentialsAcceptBasicPostAndJwtAssertionAndRejectBadSecrets() throws Exception {
        JsonNode basic = json(mvc.perform(tokenRequest().param("grant_type", "client_credentials")
                .header("Authorization", basic("demo-client", "demo-secret"))).andExpect(status().isOk()).andReturn());
        assertEquals("Bearer", basic.path("token_type").asString(""));
        assertEquals(3600, basic.path("expires_in").asInt());
        assertEquals("system/*.rs", basic.path("scope").asString(""));
        assertFalse(basic.has("patient"));

        JsonNode post = json(mvc.perform(tokenRequest().param("grant_type", "client_credentials").param("client_id", "demo-client")
                .param("client_secret", "demo-secret").param("scope", "system/Patient.rs")).andExpect(status().isOk()).andReturn());
        assertEquals("system/Patient.rs", post.path("scope").asString(""));

        JsonNode wrong = json(mvc.perform(tokenRequest().param("grant_type", "client_credentials")
                .header("Authorization", basic("demo-client", "nope"))).andExpect(status().isUnauthorized()).andReturn());
        assertEquals("invalid_client", wrong.path("error").asString(""));

        JsonNode noAuth = json(mvc.perform(tokenRequest().param("grant_type", "client_credentials").param("client_id", "demo-client"))
                .andExpect(status().isUnauthorized()).andReturn());
        assertEquals("invalid_client", noAuth.path("error").asString(""));

        JsonNode badGrant = json(mvc.perform(tokenRequest().param("grant_type", "password").header("Authorization", basic("demo-client", "demo-secret")))
                .andExpect(status().isBadRequest()).andReturn());
        assertEquals("unsupported_grant_type", badGrant.path("error").asString(""));

        SignedJWT assertion = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().issuer("demo-client").subject("demo-client")
                .audience("http://localhost/demo/auth/token").jwtID(UUID.randomUUID().toString()).expirationTime(Date.from(Instant.now().plusSeconds(300))).build());
        assertion.sign(new MACSigner(new byte[32]));
        JsonNode jwt = json(mvc.perform(tokenRequest().param("grant_type", "client_credentials")
                .param("client_assertion_type", DemoAuthController.JWT_BEARER).param("client_assertion", assertion.serialize())
                .param("scope", "system/*.rs")).andExpect(status().isOk()).andReturn());
        assertTrue(jwt.hasNonNull("access_token"));
        search("/Coverage", jwt.path("access_token").asString(""));

        SignedJWT other = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().issuer("someone-else").audience("x")
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).build());
        other.sign(new MACSigner(new byte[32]));
        mvc.perform(tokenRequest().param("grant_type", "client_credentials").param("client_assertion_type", DemoAuthController.JWT_BEARER)
                .param("client_assertion", other.serialize())).andExpect(status().isUnauthorized());
    }

    @Test
    void authorizationCodeWithPkceBindsTheTokenToThePatient() throws Exception {
        String verifier = Pkce.verifier();
        String redirect = "http://localhost:9999/oauth/callback";
        MockHttpServletRequestBuilder authorize = get(AUTH + "/authorize").param("response_type", "code").param("client_id", "demo-client")
                .param("redirect_uri", redirect).param("scope", "launch/patient openid fhirUser offline_access patient/*.rs").param("state", "xyz-123")
                .param("aud", "http://localhost/demo/fhir").param("code_challenge", Pkce.challenge(verifier)).param("code_challenge_method", "S256");
        String page = mvc.perform(authorize).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("Sign in as Johnny Appleseed"), page);
        assertTrue(page.contains("Patient/Patient1") && page.contains("name=\"code_challenge\""));

        MvcResult decision = mvc.perform(post(AUTH + "/authorize").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("response_type", "code")
                .param("client_id", "demo-client").param("redirect_uri", redirect).param("scope", "launch/patient openid fhirUser offline_access patient/*.rs")
                .param("state", "xyz-123").param("code_challenge", Pkce.challenge(verifier)).param("code_challenge_method", "S256").param("patient", "1"))
                .andExpect(status().isFound()).andReturn();
        String location = decision.getResponse().getHeader("Location");
        assertTrue(location.startsWith(redirect + "?code="), location);
        assertTrue(location.endsWith("&state=xyz-123"), location);
        String code = location.substring(location.indexOf("code=") + 5, location.indexOf("&state"));

        JsonNode wrongVerifier = json(mvc.perform(tokenRequest().param("grant_type", "authorization_code").param("code", code)
                .param("redirect_uri", redirect).param("code_verifier", "not-the-verifier").param("client_id", "demo-client"))
                .andExpect(status().isBadRequest()).andReturn());
        assertEquals("invalid_grant", wrongVerifier.path("error").asString(""));

        code = newCode(verifier, redirect, "1");
        JsonNode token = json(mvc.perform(tokenRequest().param("grant_type", "authorization_code").param("code", code)
                .param("redirect_uri", redirect).param("code_verifier", verifier).param("client_id", "demo-client")).andExpect(status().isOk()).andReturn());
        assertEquals("1", token.path("patient").asString(""));
        assertTrue(token.hasNonNull("refresh_token"));
        assertTrue(token.path("scope").asString("").contains("patient/*.rs"));
        String[] idToken = token.path("id_token").asString("").split("\\.");
        assertEquals(3, idToken.length, "compact JWT");
        JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(idToken[1]));
        assertEquals("demo-client", claims.path("aud").asString(""));
        assertEquals("http://localhost/demo/auth", claims.path("iss").asString(""));
        assertEquals("http://localhost/demo/fhir/Patient/1", claims.path("fhirUser").asString(""));

        String bearer = token.path("access_token").asString("");
        mvc.perform(tokenRequest().param("grant_type", "authorization_code").param("code", code).param("redirect_uri", redirect)
                .param("code_verifier", verifier).param("client_id", "demo-client")).andExpect(status().isBadRequest());

        JsonNode own = search("/ExplanationOfBenefit?patient=1", bearer);
        assertEquals(4, own.path("total").asInt());
        JsonNode forbidden = json(mvc.perform(get(FHIR + "/ExplanationOfBenefit").param("patient", "Patient/Patient2").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isForbidden()).andReturn());
        assertEquals("forbidden", forbidden.path("issue").path(0).path("code").asString(""));
        mvc.perform(get(FHIR + "/Patient/Patient1").header("Authorization", "Bearer " + bearer)).andExpect(status().isForbidden());
        mvc.perform(get(FHIR + "/Coverage/Coverage1").header("Authorization", "Bearer " + bearer)).andExpect(status().isForbidden());
        mvc.perform(get(FHIR + "/Organization/Payer2").header("Authorization", "Bearer " + bearer)).andExpect(status().isOk());
        JsonNode patients = search("/Patient", bearer);
        assertEquals(1, patients.path("total").asInt());
        assertEquals("1", patients.path("entry").path(0).path("resource").path("id").asString(""));
        assertEquals(4, search("/ExplanationOfBenefit", bearer).path("total").asInt(), "unscoped search is limited to the token's patient");

        JsonNode refreshed = json(mvc.perform(tokenRequest().param("grant_type", "refresh_token").param("refresh_token", token.path("refresh_token").asString(""))
                .param("client_id", "demo-client")).andExpect(status().isOk()).andReturn());
        assertEquals("1", refreshed.path("patient").asString(""));
        assertFalse(refreshed.path("access_token").asString("").equals(bearer));
        mvc.perform(get(FHIR + "/Patient/1").header("Authorization", "Bearer " + refreshed.path("access_token").asString(""))).andExpect(status().isOk());
    }

    @Test
    void authorizeRejectsBadClientsWithoutRedirecting() throws Exception {
        mvc.perform(get(AUTH + "/authorize").param("response_type", "code").param("client_id", "other").param("redirect_uri", "http://localhost/cb"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(AUTH + "/authorize").param("response_type", "code").param("client_id", "demo-client").param("redirect_uri", "/relative"))
                .andExpect(status().isBadRequest());
        MvcResult r = mvc.perform(get(AUTH + "/authorize").param("response_type", "token").param("client_id", "demo-client").param("redirect_uri", "http://localhost/cb").param("state", "s"))
                .andExpect(status().isFound()).andReturn();
        assertTrue(r.getResponse().getHeader("Location").startsWith("http://localhost/cb?error=unsupported_response_type"));
        mvc.perform(get(AUTH + "/jwks")).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("{\"keys\":[]}"));
        mvc.perform(get(AUTH + "/register")).andExpect(status().isMethodNotAllowed());
    }

    // ------------------------------------------------------------------ search semantics

    @Test
    void patientSearchByIdentifierNameAndDemographics() throws Exception {
        String token = systemToken();
        assertEquals(List.of("Patient2"), ids(search("/Patient?identifier=https://www.upmchealthplan.com/fhir/memberidentifier%7C88800933501", token)));
        assertEquals(List.of("Patient2"), ids(search("/Patient?identifier=88800933501", token)));
        assertEquals(List.of("Patient1"), ids(search("/Patient?identifier=https://www.xxxhealthplan.com/fhir/memberidentifier%7C1234-234-1243-12345678901", token)));
        assertEquals(List.of(), ids(search("/Patient?identifier=http://other.example%7C88800933501", token)));
        assertEquals(List.of("1", "100"), ids(search("/Patient?name=apple", token)));
        assertEquals(List.of("Patient1"), ids(search("/Patient?name=EXAMPLE1", token)));
        assertEquals(List.of("Patient2"), ids(search("/Patient?family=Test&given=Member", token)));
        assertEquals(List.of("1", "100", "Patient1"), ids(search("/Patient?birthdate=1986-01-01", token)));
        assertEquals(List.of("Patient2"), ids(search("/Patient?birthdate=lt1950", token)));
        assertEquals(List.of("1", "100", "Patient1"), ids(search("/Patient?birthdate=ge1986&gender=male", token)));
        assertEquals(List.of(), ids(search("/Patient?gender=female", token)));
        assertEquals(List.of("Patient1", "Patient2"), ids(search("/Patient?_id=Patient1,Patient2", token)));
        assertEquals(List.of("Patient1", "Patient2"), ids(search("/Patient?_profile=http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-Patient", token)));
    }

    @Test
    void eobSearchByPatientUseTypeAndDates() throws Exception {
        String token = systemToken();
        JsonNode byRelative = search("/ExplanationOfBenefit?patient=Patient/1", token);
        assertEquals(4, byRelative.path("total").asInt());
        assertEquals(ids(byRelative), ids(search("/ExplanationOfBenefit?patient=1", token)));
        assertEquals(ids(byRelative), ids(search("/ExplanationOfBenefit?patient=http://localhost/demo/fhir/Patient/1", token)));
        assertEquals(ids(byRelative), ids(search("/ExplanationOfBenefit?patient:Patient=1", token)));

        JsonNode pa = search("/ExplanationOfBenefit?patient=1&use=preauthorization", token);
        assertEquals(4, pa.path("total").asInt());
        for (JsonNode e : pa.path("entry")) {
            assertEquals("preauthorization", e.path("resource").path("use").asString(""));
        }
        assertEquals(List.of("PriorAuthApproved1"), ids(search("/ExplanationOfBenefit?patient=Patient1&use=preauthorization", token)));
        assertEquals(10, search("/ExplanationOfBenefit?use=claim", token).path("total").asInt());
        assertEquals(15, search("/ExplanationOfBenefit", token).path("total").asInt());

        List<String> institutional = ids(search("/ExplanationOfBenefit?type=institutional", token));
        assertTrue(institutional.containsAll(List.of("EOBInpatient1", "EOBOutpatient2", "PDexPriorAuth1", "PriorAuthApproved1")), institutional.toString());
        assertFalse(institutional.contains("EOBProfessional1"));
        assertEquals(institutional, ids(search("/ExplanationOfBenefit?type=http://terminology.hl7.org/CodeSystem/claim-type%7Cinstitutional", token)));
        assertEquals(List.of("EOBInpatient1", "EOBOutpatient1"), ids(search("/ExplanationOfBenefit?patient=Patient2&type=institutional", token)));
        assertEquals(List.of("EOBPharmacy1"), ids(search("/ExplanationOfBenefit?identifier=AW123412341234123412341234123412&type=pharmacy", token)));

        List<String> recent = ids(search("/ExplanationOfBenefit?_lastUpdated=ge2026-01-01", token));
        assertEquals(List.of("PriorAuthApproved1", "PriorAuthDenied1", "PriorAuthPartial1", "PriorAuthPended1"), recent);
        List<String> old = ids(search("/ExplanationOfBenefit?_lastUpdated=lt2020-01-01", token));
        assertEquals(List.of("EOBInpatient1", "EOBOutpatient1", "EOBPharmacy1", "EOBProfessional1"), old);
        assertEquals(old, ids(search("/ExplanationOfBenefit?_lastUpdated=eq2019", token)));
        assertEquals(old, ids(search("/ExplanationOfBenefit?_lastUpdated=le2019-12-31T23:59:59Z", token)));
        assertEquals(15 - old.size(), search("/ExplanationOfBenefit?_lastUpdated=gt2019-12-31", token).path("total").asInt());

        List<String> served2021 = ids(search("/ExplanationOfBenefit?service-date=ge2021-01-01&service-date=le2021-12-31", token));
        assertTrue(served2021.containsAll(List.of("EOBOral1", "EOBOral2", "PDexPriorAuth1")), served2021.toString());
        assertFalse(served2021.contains("EOBInpatient1"));
        assertEquals(List.of("EOBOral1"), ids(search("/ExplanationOfBenefit?billable-period-start=2021-03-01", token)));
        assertEquals(List.of("EOBOral1"), ids(search("/ExplanationOfBenefit?service-start-date=2021-03-18&patient=Patient2", token)));
        assertEquals(List.of("PriorAuthDenied1", "PriorAuthPended1"), ids(search("/ExplanationOfBenefit?created=ge2026-06-01&provider=Organization/ProviderOrg1,ProviderOrg2", token)));
        assertEquals(List.of("PriorAuthApproved1"), ids(search("/ExplanationOfBenefit?insurer=Payer2&coverage=Coverage/Coverage1&use=preauthorization", token)));
        assertEquals(List.of("PriorAuthApproved1"), ids(search("/ExplanationOfBenefit?care-team=Practitioner/Practitioner1&use=preauthorization", token)));
    }

    @Test
    void clinicalSearchesUsePatientCodeCategoryStatusAndDates() throws Exception {
        String token = systemToken();
        assertEquals(List.of("p1-condition-1", "p1-condition-2"), ids(search("/Condition?patient=Patient1", token)));
        assertEquals(List.of("p1-condition-1"), ids(search("/Condition?patient=Patient1&category=problem-list-item", token)));
        assertEquals(List.of("p1-condition-2"), ids(search("/Condition?patient=Patient1&code=http://snomed.info/sct%7C279039007", token)));
        assertEquals(List.of("p1-condition-1", "p1-condition-2"), ids(search("/Condition?patient=Patient1&clinical-status=active", token)));
        assertEquals(List.of("p1-condition-2"), ids(search("/Condition?patient=Patient1&onset-date=ge2026-01-01", token)));
        assertEquals(List.of("p1-observation-lab-1"), ids(search("/Observation?patient=Patient1&category=laboratory", token)));
        assertEquals(List.of("p1-observation-bp-1"), ids(search("/Observation?patient=Patient1&code=85354-9", token)));
        assertEquals(List.of("p1-observation-bp-1", "p1-observation-smoking-1"), ids(search("/Observation?patient=Patient1&date=2026-03-10", token)));
        assertEquals(List.of("p1-medicationrequest-1"), ids(search("/MedicationRequest?patient=Patient1&intent=order&status=active", token)));
        assertEquals(List.of("p1-encounter-1"), ids(search("/Encounter?patient=Patient1&class=AMB", token)));
        assertEquals(List.of("6", "7"), ids(search("/Encounter?patient=1&status=finished", token)));
        assertEquals(List.of("p1-goal-1"), ids(search("/Goal?patient=Patient1&lifecycle-status=active&target-date=le2026-12-31", token)));
        assertEquals(List.of("p1-documentreference-1"), ids(search("/DocumentReference?patient=Patient1&type=11506-3&period=ge2026-03-01", token)));
        assertEquals(List.of("p1-careplan-1"), ids(search("/CarePlan?patient=Patient1&category=assess-plan", token)));
        assertEquals(List.of("p1-immunization-1"), ids(search("/Immunization?patient=Patient1&date=2025", token)));
        assertEquals(List.of("p1-procedure-1"), ids(search("/Procedure?patient=Patient1&code=45378", token)));
        assertEquals(List.of("p1-allergyintolerance-1"), ids(search("/AllergyIntolerance?patient=Patient1&clinical-status=active", token)));
        assertEquals(List.of("1000001"), ids(search("/MedicationDispense?patient=1", token)));
        assertEquals(List.of("Coverage1", "Coverage2"), ids(search("/Coverage?patient=Patient1&status=active", token)));
        assertEquals(List.of("Coverage1", "Coverage2"), ids(search("/Coverage?beneficiary=Patient/Patient1&payor=Organization/Payer2", token)));
        assertEquals(List.of("FormularyItem-D1002-1000091", "FormularyItem-D1002-1049640"), ids(search("/Basic?formulary=InsurancePlan/FormularyD1002&status=active", token)));
        assertEquals(List.of("FormularyItem-D1002-1049640"), ids(search("/Basic?drug-tier=brand", token)));
        assertEquals(List.of("FormularyDrug-1049640"), ids(search("/MedicationKnowledge?drug-name=percocet", token)));
        assertEquals(List.of("PayerInsurancePlanA1002"), ids(search("/InsurancePlan?formulary-coverage=InsurancePlan/FormularyD1002", token)));
        assertEquals(List.of("ProviderOrganization1"), ids(search("/Organization?name=orange", token)));
        assertEquals(0, search("/ServiceRequest?patient=Patient1", token).path("total").asInt(), "catalog type without data answers an empty bundle");
    }

    @Test
    void pagingWithCountAndNextLinksVisitsEveryResourceOnce() throws Exception {
        String token = systemToken();
        JsonNode first = search("/ExplanationOfBenefit?_count=4", token);
        assertEquals(15, first.path("total").asInt());
        assertEquals(4, first.path("entry").size());
        assertNull(link(first, "previous"));
        String next = link(first, "next");
        assertEquals("http://localhost/demo/fhir/ExplanationOfBenefit?_count=4&page=2", next);
        Set<String> seen = new LinkedHashSet<>(ids(first));
        int pages = 1;
        while (next != null) {
            JsonNode page = search(URI.create(next), token);
            assertNotNull(link(page, "previous"));
            for (String id : ids(page)) {
                assertTrue(seen.add(id), "duplicate " + id + " on page " + (pages + 1));
            }
            next = link(page, "next");
            pages++;
        }
        assertEquals(4, pages);
        assertEquals(15, seen.size());
        JsonNode capped = search("/Observation?_count=1000", token);
        assertTrue(capped.path("entry").size() <= DemoSearchEngine.MAX_COUNT);
        JsonNode countOnly = search("/Patient?_count=0", token);
        assertEquals(4, countOnly.path("total").asInt());
        assertEquals(0, countOnly.path("entry").size());
        assertTrue(search("/Observation", token).path("entry").size() <= DemoSearchEngine.DEFAULT_COUNT, "default page size");
    }

    @Test
    void includesAndRevIncludes() throws Exception {
        String token = systemToken();
        JsonNode bundle = search("/ExplanationOfBenefit?_id=PriorAuthApproved1&_include=ExplanationOfBenefit:*", token);
        assertEquals(1, bundle.path("total").asInt());
        Set<String> included = new HashSet<>();
        for (JsonNode e : bundle.path("entry")) {
            if ("include".equals(e.path("search").path("mode").asString(""))) {
                included.add(e.path("resource").path("resourceType").asString("") + "/" + e.path("resource").path("id").asString(""));
                assertTrue(e.path("fullUrl").asString("").startsWith("http://localhost/demo/fhir/"));
            }
        }
        assertTrue(included.containsAll(List.of("Patient/Patient1", "Organization/Payer2", "Organization/ProviderOrganization1", "Coverage/Coverage1", "Practitioner/Practitioner1")), included.toString());

        JsonNode patientOnly = search("/ExplanationOfBenefit?patient=Patient1&_include=ExplanationOfBenefit:patient&_include=ExplanationOfBenefit:insurer", token);
        Set<String> types = new HashSet<>();
        for (JsonNode e : patientOnly.path("entry")) {
            if ("include".equals(e.path("search").path("mode").asString(""))) {
                types.add(e.path("resource").path("resourceType").asString("") + "/" + e.path("resource").path("id").asString(""));
            }
        }
        assertEquals(Set.of("Patient/Patient1", "Organization/Payer2"), types);

        JsonNode coverage = search("/Coverage?patient=Patient1&_include=Coverage:payor", token);
        assertTrue(ids(coverage).containsAll(List.of("Coverage1", "Coverage2")));
        assertEquals(1, coverage.path("entry").size() - coverage.path("total").asInt(), "one payer organization included once");

        JsonNode prov = search("/Condition?patient=Patient1&_revinclude=Provenance:target", token);
        assertEquals(2, prov.path("total").asInt());
        List<String> provenances = new ArrayList<>();
        for (JsonNode e : prov.path("entry")) {
            if ("Provenance".equals(e.path("resource").path("resourceType").asString(""))) {
                assertEquals("include", e.path("search").path("mode").asString(""));
                provenances.add(e.path("resource").path("id").asString(""));
            }
        }
        assertEquals(List.of("p1-provenance-1"), provenances);
        JsonNode enc = search("/Encounter?_id=6&_revinclude=Provenance:target", token);
        assertEquals(2, enc.path("entry").size());
        assertEquals("1000002", enc.path("entry").path(1).path("resource").path("id").asString(""));
    }

    // ------------------------------------------------------------------ errors, read, vread

    @Test
    void errorsAreOperationOutcomes() throws Exception {
        String token = systemToken();
        JsonNode unknownType = json(mvc.perform(get(FHIR + "/Foo").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound()).andReturn());
        assertEquals("not-found", unknownType.path("issue").path(0).path("code").asString(""));
        JsonNode unknownId = json(mvc.perform(get(FHIR + "/Patient/nope").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound()).andReturn());
        assertEquals("OperationOutcome", unknownId.path("resourceType").asString(""));
        assertEquals("not-found", unknownId.path("issue").path(0).path("code").asString(""));

        mvc.perform(get(FHIR + "/Patient").param("bogus", "1").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        JsonNode strict = json(mvc.perform(get(FHIR + "/Patient").param("bogus", "1").header("Prefer", "handling=strict").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()).andReturn());
        assertEquals("not-supported", strict.path("issue").path(0).path("code").asString(""));
        assertTrue(strict.path("issue").path(0).path("diagnostics").asString("").contains("bogus"));
        mvc.perform(get(FHIR + "/Patient").param("name", "x").header("Prefer", "handling=strict").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        JsonNode badDate = json(mvc.perform(get(FHIR + "/Patient").param("birthdate", "not-a-date").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()).andReturn());
        assertEquals("invalid", badDate.path("issue").path(0).path("code").asString(""));
        mvc.perform(get(FHIR + "/ExplanationOfBenefit").param("_lastUpdated", "ge2026-13-01").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mvc.perform(get(FHIR + "/Patient").param("_count", "abc").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
    }

    @Test
    void readAndVread() throws Exception {
        String token = systemToken();
        MvcResult read = mvc.perform(get(FHIR + "/Patient/Patient1").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(header().string("ETag", "W/\"1\"")).andReturn();
        JsonNode patient = json(read);
        assertEquals("Patient1", patient.path("id").asString(""));
        assertEquals("1", patient.path("meta").path("versionId").asString(""));
        JsonNode vread = json(mvc.perform(get(FHIR + "/Patient/Patient1/_history/1").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn());
        assertEquals("Patient1", vread.path("id").asString(""));
        JsonNode missing = json(mvc.perform(get(FHIR + "/Patient/Patient1/_history/2").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound()).andReturn());
        assertEquals("not-found", missing.path("issue").path(0).path("code").asString(""));
        JsonNode doc = json(mvc.perform(get(FHIR + "/DocumentReference/123456").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn());
        assertEquals(DemoDataStore.DEFAULT_LAST_UPDATED, doc.path("meta").path("lastUpdated").asString(""), "example without lastUpdated gets the fixed instant");
    }

    // ------------------------------------------------------------------ workbench helpers

    @Test
    void demoMembersAndEnvironment() throws Exception {
        JsonNode members = json(mvc.perform(get("/api/v1/demo/members")).andExpect(status().isOk()).andReturn());
        assertEquals(4, members.size());
        JsonNode c4bb = null;
        JsonNode pdex = null;
        for (JsonNode m : members) {
            if ("Patient1".equals(m.path("patientId").asString(""))) {
                c4bb = m;
            } else if ("1".equals(m.path("patientId").asString(""))) {
                pdex = m;
            }
        }
        assertNotNull(c4bb);
        assertNotNull(pdex);
        assertEquals("Johnny Example1", c4bb.path("name").asString(""));
        assertTrue(c4bb.path("hasCoverage").asBoolean() && c4bb.path("hasClaims").asBoolean() && c4bb.path("hasPriorAuths").asBoolean());
        assertEquals("1234-234-1243-12345678901", c4bb.path("identifiers").path(0).path("value").asString(""));
        assertEquals("MB", c4bb.path("identifiers").path(0).path("type").asString(""));
        assertEquals(4, pdex.path("priorAuths").asInt());

        MvcResult created = mvc.perform(post("/api/v1/demo/environment")).andExpect(status().isCreated()).andReturn();
        JsonNode env = json(created);
        assertEquals(DemoController.ENVIRONMENT_NAME, env.path("name").asString(""));
        assertEquals("http://localhost/demo/fhir", env.path("fhirBaseUrl").asString(""));
        assertEquals("CLIENT_CREDENTIALS", env.path("auth").path("mode").asString(""));
        assertEquals("demo-client", env.path("auth").path("clientId").asString(""));
        assertTrue(env.path("auth").path("clientSecret").path("set").asBoolean());
        assertTrue(env.path("auth").path("discoverEndpoints").asBoolean());
        assertEquals("SANDBOX", env.path("tier").asString(""));
        boolean memberDefault = false;
        for (JsonNode s : env.path("identifierSystems")) {
            if ("https://www.xxxhealthplan.com/fhir/memberidentifier".equals(s.path("system").asString("")) && "MB".equals(s.path("typeCode").asString(""))) {
                memberDefault = s.path("defaultForMemberId").asBoolean();
            }
        }
        assertTrue(memberDefault, env.path("identifierSystems").toString());
        JsonNode again = json(mvc.perform(post("/api/v1/demo/environment")).andExpect(status().isOk()).andReturn());
        assertEquals(env.path("id").asString(""), again.path("id").asString(""));
        assertEquals(1, json(mvc.perform(get("/api/v1/environments")).andReturn()).size());
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder tokenRequest() {
        return post(AUTH + "/token").contentType(MediaType.APPLICATION_FORM_URLENCODED);
    }

    private String systemToken() throws Exception {
        JsonNode t = json(mvc.perform(tokenRequest().param("grant_type", "client_credentials").header("Authorization", basic("demo-client", "demo-secret")))
                .andExpect(status().isOk()).andReturn());
        return t.path("access_token").asString("");
    }

    private String newCode(String verifier, String redirect, String patient) throws Exception {
        MvcResult r = mvc.perform(post(AUTH + "/authorize").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("response_type", "code")
                .param("client_id", "demo-client").param("redirect_uri", redirect).param("scope", "launch/patient openid fhirUser offline_access patient/*.rs")
                .param("code_challenge", Pkce.challenge(verifier)).param("code_challenge_method", "S256").param("patient", patient))
                .andExpect(status().isFound()).andReturn();
        String location = r.getResponse().getHeader("Location");
        return location.substring(location.indexOf("code=") + 5);
    }

    private JsonNode search(String pathAndQuery, String token) throws Exception {
        return search(URI.create("http://localhost" + FHIR + pathAndQuery), token);
    }

    private JsonNode search(URI uri, String token) throws Exception {
        MvcResult r = mvc.perform(get(uri).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/fhir+json"))).andReturn();
        JsonNode bundle = json(r);
        assertEquals("Bundle", bundle.path("resourceType").asString(""));
        assertEquals("searchset", bundle.path("type").asString(""));
        return bundle;
    }

    private static JsonNode json(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Ids of the match entries, sorted. */
    private static List<String> ids(JsonNode bundle) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : bundle.path("entry")) {
            if (!"include".equals(e.path("search").path("mode").asString(""))) {
                out.add(e.path("resource").path("id").asString(""));
            }
        }
        return out.stream().sorted().toList();
    }

    private static String link(JsonNode bundle, String relation) {
        for (JsonNode l : bundle.path("link")) {
            if (relation.equals(l.path("relation").asString(""))) {
                return l.path("url").asString("");
            }
        }
        return null;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString("")));
        return out;
    }

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }
}
