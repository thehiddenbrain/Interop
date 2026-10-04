package com.thehiddenbrain.interop.memberid.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

/**
 * The scenario matrix, end to end over HTTP against the in-process MMI stub, with "today" fixed at 2026-10-03.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ResolveScenariosTest.FixedClock.class)
class ResolveScenariosTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-03T16:00:00Z"), ZoneId.of("America/New_York"));
        }
    }

    private static final String PATH = "/api/v1/member-ids/resolve";
    /** An unmasked 9- or 11-digit run (member ids), or an MM/dd/yyyy value (MMI dates of birth). */
    private static final Pattern PHI = Pattern.compile("(?<!\\d)(?:\\d{9}|\\d{11})(?!\\d)|\\d{2}/\\d{2}/\\d{4}");

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;

    /** A plain HTTP response: status, headers, body. The client never throws on a status. */
    record Resp(int status, HttpHeaders headers, String body) {
        String header(String name) {
            return headers.getFirst(name);
        }

        MediaType contentType() {
            return headers.getContentType();
        }
    }

    private RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private Resp send(HttpMethod method, String path, String body, String contentType, String... headers) {
        RestClient.RequestBodySpec spec = http().method(method).uri(path);
        if (contentType != null) {
            spec = spec.header(HttpHeaders.CONTENT_TYPE, contentType);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            spec = spec.header(headers[i], headers[i + 1]);
        }
        RestClient.RequestHeadersSpec<?> ready = body == null ? spec : spec.body(body);
        return ready.exchange((req, res) -> new Resp(res.getStatusCode().value(), res.getHeaders(),
                new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private String toJson(Object body) {
        return body instanceof String s ? s : json.writeValueAsString(body);
    }
    @Autowired com.thehiddenbrain.interop.memberid.mmi.MmiClient mmiClient;

    private com.thehiddenbrain.interop.memberid.mmi.StubMmiClient stub;

    @BeforeEach
    void stub() {
        stub = (com.thehiddenbrain.interop.memberid.mmi.StubMmiClient) mmiClient;
    }

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger("com.thehiddenbrain.interop.memberid")).addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger("com.thehiddenbrain.interop.memberid")).detachAppender(logs);
    }

    // ---------------------------------------------------------------- helpers

    private Resp post(Object body, String... headers) {
        return send(HttpMethod.POST, PATH, toJson(body), MediaType.APPLICATION_JSON_VALUE, headers);
    }

    private JsonNode call(int expectedStatus, Object body, String... headers) throws Exception {
        Resp r = post(body, headers);
        assertThat(r.status()).as("status for %s: %s", body, r.body()).isEqualTo(expectedStatus);
        assertThat(r.contentType()).isNotNull();
        assertThat(r.contentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertNoPhi(r.body());
        return json.readTree(r.body());
    }

    private static Map<String, Object> req(String memberId, String dos, String vendor) {
        return dos == null ? Map.of("memberId", memberId, "vendor", vendor) : Map.of("memberId", memberId, "dateOfService", dos, "vendor", vendor);
    }

    private static Map<String, Object> reqDob(String memberId, String dos, String vendor, String dob) {
        return Map.of("memberId", memberId, "dateOfService", dos, "vendor", vendor, "patient", Map.of("dateOfBirth", dob));
    }

    private void assertNoPhi(String body) {
        if (body != null) {
            assertThat(body).doesNotContain("memberFirstName", "memberLastName", "socialSecurityNumber", "memberDob", "Morgan", "Rivera", "***-**");
        }
        for (ILoggingEvent e : logs.list) {
            StringBuilder text = new StringBuilder(e.getFormattedMessage());
            if (e.getThrowableProxy() != null) {
                text.append(' ').append(e.getThrowableProxy().getMessage());
            }
            // the MDC holds only the correlation id, an opaque token Onyx chooses (a generated UUID can contain a 9-digit run)
            String line = text.toString();
            assertThat(PHI.matcher(line).find()).as("unmasked member id or MM/dd/yyyy date in log line: %s", line).isFalse();
            assertThat(line).doesNotContain("1950-03-15", "2012-09-09", "Morgan", "Rivera");
        }
    }

    private void assertNoPhi(Resp r) {
        assertNoPhi(r.body());
    }

    // ---------------------------------------------------------------- TMP: 9 / 11 / 14, one record, vendor formats

    @Test
    void tmpNineDigitCardNumberResolvesToTheOneMember() throws Exception {
        JsonNode r = call(200, req("123456789", "2026-10-15", "evicore"));
        assertThat(r.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(r.at("/memberId/received").asText()).isEqualTo("123456789");
        assertThat(r.at("/memberId/searched").asText()).isEqualTo("123456789");
        assertThat(r.at("/memberId/stored").asText()).isEqualTo("123456789   01");
        assertThat(r.at("/memberId/forVendor").asText()).isEqualTo("12345678901");
        assertThat(r.at("/memberId/forVendorParts").isMissingNode()).isTrue();
        assertThat(r.get("vendor").asText()).isEqualTo("EVICORE");
        assertThat(r.get("company").asText()).isEqualTo("THP");
        assertThat(r.get("lineOfBusiness").asText()).isEqualTo("MCR");
        assertThat(r.at("/coverage/active").asBoolean()).isTrue();
        assertThat(r.at("/coverage/reason").asText()).isEqualTo("COVERED");
        assertThat(r.at("/coverage/span/effectiveDate").asText()).isEqualTo("2024-01-01");
        assertThat(r.at("/coverage/span").has("endDate")).as("open-ended end is an explicit null").isTrue();
        assertThat(r.at("/coverage/span/endDate").isNull()).isTrue();
        assertThat(r.get("dateOfServiceDefaulted").asBoolean()).isFalse();
        assertThat(r.get("mmiRequestId").asText()).matches("MBRIDSVC-\\d{13}-\\d{5}");
    }

    @Test
    void elevenAndFourteenCharacterFormsResolveTheSame() throws Exception {
        for (String input : List.of("12345678901", "123456789   01", "123456789-01", "123-456-789 01")) {
            JsonNode r = call(200, req(input, "2026-10-15", "MHK"));
            assertThat(r.get("outcome").asText()).isEqualTo("ACTIVE");
            assertThat(r.at("/memberId/stored").asText()).isEqualTo("123456789   01");
            assertThat(r.at("/memberId/forVendor").asText()).as(input).isEqualTo("123456789   01");
        }
    }

    @Test
    void vendorFormatsAndAliases() throws Exception {
        assertThat(call(200, req("123456789", "2026-10-15", "EVOLENT")).at("/memberId/forVendor").asText()).isEqualTo("12345678901");
        assertThat(call(200, req("123456789", "2026-10-15", "carelon")).at("/memberId/forVendor").asText()).isEqualTo("12345678901");
        assertThat(call(200, req("123456789", "2026-10-15", "medhok")).at("/memberId/forVendor").asText()).isEqualTo("123456789   01");
        JsonNode optum = call(200, req("123456789", "2026-10-15", "Optum"));
        assertThat(optum.at("/memberId/forVendor").asText()).isEqualTo("12345678901");
        assertThat(optum.at("/memberId/forVendorParts/memberId").asText()).isEqualTo("123456789");
        assertThat(optum.at("/memberId/forVendorParts/suffix").asText()).isEqualTo("01");
    }

    @Test
    void scoMemberWhoseCoverageEndedIsInactiveWithTheEndDate() throws Exception {
        JsonNode r = call(200, req("234567890", "2026-10-15", "evicore"));
        assertThat(r.get("outcome").asText()).isEqualTo("INACTIVE");
        assertThat(r.at("/coverage/active").asBoolean()).isFalse();
        assertThat(r.at("/coverage/reason").asText()).isEqualTo("COVERAGE_ENDED");
        assertThat(r.at("/coverage/lastEndDate").asText()).isEqualTo("2025-12-31");
        assertThat(r.at("/memberId/forVendor").asText()).as("the id is still returned when inactive").isEqualTo("23456789001");
    }

    @Test
    void dateOfServiceInsideAnEarlierSpanIsActiveOnThatDate() throws Exception {
        JsonNode r = call(200, req("234567890   01", "2025-06-15", "evicore"));
        assertThat(r.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(r.at("/coverage/span/endDate").asText()).isEqualTo("2025-12-31");
    }

    @Test
    void futureEffectiveDateIsNotYetEffective() throws Exception {
        JsonNode r = call(200, req("789012345", "2026-10-15", "evicore"));
        assertThat(r.get("outcome").asText()).isEqualTo("INACTIVE");
        assertThat(r.at("/coverage/reason").asText()).isEqualTo("NOT_YET_EFFECTIVE");
        assertThat(r.at("/coverage/nextEffectiveDate").asText()).isEqualTo("2027-01-01");
        assertThat(call(200, req("789012345", "2027-01-01", "evicore")).get("outcome").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void voidSpansAreIgnored() throws Exception {
        JsonNode r = call(200, req("678901234", "2026-10-15", "evicore"));
        assertThat(r.get("outcome").asText()).isEqualTo("INACTIVE");
        assertThat(r.at("/coverage/reason").asText()).isEqualTo("COVERAGE_ENDED");
        assertThat(r.at("/coverage/lastEndDate").asText()).isEqualTo("2025-06-30");
    }

    @Test
    void unreadableSpanIsSkippedAndTheValidSpanDecides() throws Exception {
        JsonNode r = call(200, req("890123456", "2026-10-15", "evicore"));
        assertThat(r.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("marker=UNREADABLE_SPAN"))).isTrue();
    }

    @Test
    void companyMissingOnTheRecordIsInferred() throws Exception {
        JsonNode r = call(200, req("901234567", "2026-10-15", "evicore"));
        assertThat(r.get("company").asText()).isEqualTo("THP");
        assertThat(r.at("/memberId/forVendor").asText()).isEqualTo("90123456701");
    }

    // ---------------------------------------------------------------- no date of service

    @Test
    void missingDateOfServiceDefaultsToTodayAndSaysSo() throws Exception {
        JsonNode r = call(200, req("123456789", null, "evicore"));
        assertThat(r.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(r.get("dateOfService").asText()).isEqualTo("2026-10-03");
        assertThat(r.get("dateOfServiceDefaulted").asBoolean()).isTrue();
        JsonNode blank = call(200, Map.of("memberId", "789012345", "dateOfService", "", "vendor", "evicore"));
        assertThat(blank.get("dateOfServiceDefaulted").asBoolean()).isTrue();
        assertThat(blank.at("/coverage/nextEffectiveDate").asText()).as("future coverage is still reported").isEqualTo("2027-01-01");
    }

    // ---------------------------------------------------------------- populations with dependents

    @Test
    void publicPlansNineDigitWithoutDobIsAmbiguousWithCandidates() throws Exception {
        JsonNode r = call(200, req("345678901", "2026-10-15", "evicore"));
        assertThat(r.get("outcome").asText()).isEqualTo("AMBIGUOUS");
        assertThat(r.at("/ambiguity/reason").asText()).isEqualTo("MULTIPLE_PERSONS");
        assertThat(r.at("/ambiguity/hint").asText()).contains("11-character");
        assertThat(r.get("candidates")).hasSize(3);
        assertThat(r.at("/candidates/0/storedMemberId").asText()).isEqualTo("34567890101");
        assertThat(r.at("/candidates/0/coverageActive").asBoolean()).isTrue();
        assertThat(r.at("/candidates/2/storedMemberId").asText()).isEqualTo("34567890103");
        assertThat(r.at("/candidates/2/coverageActive").asBoolean()).isFalse();
        assertThat(r.at("/candidates/0").has("dateOfBirth")).isFalse();
        assertThat(r.has("memberId")).isTrue();
        assertThat(r.at("/memberId/stored").isMissingNode()).isTrue();
    }

    @Test
    void publicPlansNineDigitWithDobResolvesTheDependent() throws Exception {
        JsonNode r = call(200, reqDob("345678901", "2026-10-15", "evicore", "2012-09-09"));
        assertThat(r.get("outcome").asText()).isEqualTo("AMBIGUOUS");
        assertThat(r.at("/ambiguity/reason").asText()).as("twins share the DOB").isEqualTo("DOB_NOT_DISCRIMINATING");
        assertThat(r.get("candidates")).hasSize(2);

        JsonNode sub = call(200, reqDob("345678901", "2026-10-15", "evicore", "1985-06-01"));
        assertThat(sub.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(sub.at("/memberId/stored").asText()).isEqualTo("34567890101");
        assertThat(sub.at("/memberId/forVendor").asText()).as("Public Plans ids are never reshaped").isEqualTo("34567890101");
        assertThat(sub.get("lineOfBusiness").asText()).isEqualTo("PP");
    }

    @Test
    void publicPlansElevenCharacterIdIsDirectAndReportsAGap() throws Exception {
        JsonNode r = call(200, req("34567890102", "2024-08-15", "MHK"));
        assertThat(r.get("outcome").asText()).isEqualTo("INACTIVE");
        assertThat(r.at("/coverage/reason").asText()).isEqualTo("COVERAGE_GAP");
        assertThat(r.at("/coverage/lastEndDate").asText()).isEqualTo("2024-05-31");
        assertThat(r.at("/coverage/nextEffectiveDate").asText()).isEqualTo("2025-01-01");
        assertThat(r.at("/memberId/forVendor").asText()).as("MHK's 14-character format does not apply to an 11-character id").isEqualTo("34567890102");
        assertThat(call(200, req("34567890102", "2024-05-31", "MHK")).get("outcome").asText()).as("inclusive end date").isEqualTo("ACTIVE");
    }

    @Test
    void dobThatMatchesNoRecordIsAMismatch() throws Exception {
        JsonNode r = call(422, reqDob("345678901", "2026-10-15", "evicore", "1999-09-09"));
        assertThat(r.at("/error/code").asText()).isEqualTo("DOB_MISMATCH");
        assertThat(r.at("/error/details/0/field").asText()).isEqualTo("patient.dateOfBirth");
        JsonNode single = call(422, reqDob("123456789", "2026-10-15", "evicore", "1999-09-09"));
        assertThat(single.at("/error/code").asText()).as("a single TMP record is still verified when a DOB is sent").isEqualTo("DOB_MISMATCH");
        assertThat(call(200, reqDob("123456789", "2026-10-15", "evicore", "1950-03-15")).get("outcome").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void blankOrNullPatientMeansNoDob() throws Exception {
        assertThat(call(200, Map.of("memberId", "123456789", "dateOfService", "2026-10-15", "vendor", "evicore",
                "patient", Map.of("dateOfBirth", ""))).get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(call(200, Map.of("memberId", "123456789", "dateOfService", "2026-10-15", "vendor", "evicore",
                "patient", Map.of())).get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(call(200, "{\"memberId\":\"123456789\",\"dateOfService\":\"2026-10-15\",\"vendor\":\"evicore\",\"patient\":null}")
                .get("outcome").asText()).isEqualTo("ACTIVE");
    }

    // ---------------------------------------------------------------- HPHC and converted members

    @Test
    void hphcIdWithCardHyphenOrLowerCaseIsPassedAsStored() throws Exception {
        for (String input : List.of("HP-456789012", "hp456789012", "HP 4567 89012", "HP456789012")) {
            JsonNode r = call(200, req(input, "2026-10-15", "evicore"));
            assertThat(r.get("outcome").asText()).as(input).isEqualTo("ACTIVE");
            assertThat(r.at("/memberId/searched").asText()).isEqualTo("HP456789012");
            assertThat(r.at("/memberId/stored").asText()).isEqualTo("HP456789012");
            assertThat(r.at("/memberId/forVendor").asText()).isEqualTo("HP456789012");
            assertThat(r.get("company").asText()).isEqualTo("HPHC");
            assertThat(r.at("/coverage/span/endDate").isNull()).as("12/31/9999 is open-ended").isTrue();
        }
    }

    @Test
    void convertedMemberGetsTheIdOfTheCompanyThatOwnsTheDateOfService() throws Exception {
        JsonNode after = call(200, req("567890123 01", "2026-10-15", "Optum"));
        assertThat(after.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(after.at("/memberId/stored").asText()).isEqualTo("HP567890123");
        assertThat(after.at("/memberId/forVendor").asText()).isEqualTo("HP567890123");
        assertThat(after.at("/memberId/forVendorParts").isMissingNode()).as("no split for an HPHC id").isTrue();
        assertThat(after.get("company").asText()).isEqualTo("HPHC");

        JsonNode before = call(200, req("56789012301", "2024-06-01", "Optum"));
        assertThat(before.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(before.at("/memberId/stored").asText()).isEqualTo("567890123   01");
        assertThat(before.at("/memberId/forVendorParts/suffix").asText()).isEqualTo("01");
        assertThat(before.get("company").asText()).isEqualTo("THP");

        JsonNode newIdPreMigration = call(200, req("HP567890123", "2024-06-01", "evicore"));
        assertThat(newIdPreMigration.at("/memberId/stored").asText()).as("reverse legacy link").isEqualTo("567890123   01");

        JsonNode nineDigits = call(200, req("567890123", "2026-10-15", "evicore"));
        assertThat(nineDigits.get("outcome").asText()).as("9-digit old id, both records, one person").isEqualTo("ACTIVE");
        assertThat(nineDigits.at("/memberId/stored").asText()).isEqualTo("HP567890123");
    }

    // ---------------------------------------------------------------- not found, validation, routing

    @Test
    void unknownMemberIsNotFound() throws Exception {
        JsonNode r = call(200, req("111222333", "2026-10-15", "evicore"));
        assertThat(r.get("outcome").asText()).isEqualTo("NOT_FOUND");
        assertThat(r.at("/memberId/received").asText()).isEqualTo("111222333");
        assertThat(r.has("coverage")).isFalse();
        assertThat(r.has("company")).isFalse();
        assertThat(call(200, req("HP111222333", "2026-10-15", "evicore")).get("outcome").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void everyValidationProblemIsReportedTogetherAndMmiIsNotCalled() throws Exception {
        JsonNode r = call(400, Map.of("memberId", "1234567890", "dateOfService", "10/15/2026", "vendor", "", "patient", Map.of("dateOfBirth", "2099-01-01")));
        assertThat(r.at("/error/code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(r.get("mmiRequestId")).isNull();
        List<String> codes = new java.util.ArrayList<>();
        r.at("/error/details").forEach(d -> codes.add(d.get("code").asText()));
        assertThat(codes).containsExactlyInAnyOrder("MEMBER_ID_UNRECOGNIZED_SHAPE", "DATE_OF_SERVICE_INVALID", "VENDOR_MISSING", "DATE_OF_BIRTH_OUT_OF_RANGE");
        assertThat(r.toString()).doesNotContain("1234567890");
    }

    @Test
    void dateOfServiceOutsideTheWindowIsRejected() throws Exception {
        JsonNode r = call(400, req("123456789", "2010-01-01", "evicore"));
        assertThat(r.at("/error/details/0/code").asText()).isEqualTo("DATE_OF_SERVICE_OUT_OF_RANGE");
        assertThat(call(400, req("123456789", "2026-02-30", "evicore")).at("/error/details/0/code").asText()).isEqualTo("DATE_OF_SERVICE_INVALID");
    }

    @Test
    void unknownVendorListsTheKnownOnesAndNeverEchoesTheValue() throws Exception {
        JsonNode r = call(400, req("123456789", "2026-10-15", "AIMX"));
        assertThat(r.at("/error/code").asText()).isEqualTo("UNKNOWN_VENDOR");
        assertThat(r.at("/error/details/0/message").asText()).isEqualTo("CARELON, EVICORE, EVOLENT, MHK, OPTUM");
        assertThat(r.toString()).doesNotContain("AIMX");
        JsonNode phi = call(400, req("123456789", "2026-10-15", "Smith, John 123456789 01"));
        assertThat(phi.at("/error/details/0/code").asText()).isEqualTo("VENDOR_INVALID");
        assertThat(phi.toString()).doesNotContain("Smith");
    }

    @Test
    void malformedJsonUnknownPropertyAndWrongTypeAre400() throws Exception {
        assertThat(call(400, "{not json").at("/error/details/0/code").asText()).isEqualTo("MALFORMED_JSON");
        JsonNode unknown = call(400, Map.of("memberID", "123456789", "vendor", "evicore"));
        assertThat(unknown.at("/error/details/0/code").asText()).isEqualTo("UNKNOWN_PROPERTY");
        assertThat(unknown.at("/error/details/0/field").asText()).isEqualTo("memberID");
        JsonNode wrongType = call(400, "{\"memberId\":[1,2],\"vendor\":\"evicore\"}");
        assertThat(wrongType.at("/error/details/0/code").asText()).isEqualTo("WRONG_JSON_TYPE");
        assertThat(wrongType.at("/error/details/0/field").asText()).isEqualTo("memberId");
    }

    @Test
    void wrongMethodRouteAndMediaTypeCarryTheEnvelope() throws Exception {
        Resp get = send(HttpMethod.GET, PATH, null, null);
        assertThat(get.status()).isEqualTo(405);
        assertThat(get.header("Allow")).isEqualTo("POST");
        assertThat(json.readTree(get.body()).at("/error/details/0/code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
        assertNoPhi(get);

        Resp route = send(HttpMethod.POST, "/api/v1/nope", "{}", MediaType.APPLICATION_JSON_VALUE);
        assertThat(route.status()).isEqualTo(404);
        assertThat(json.readTree(route.body()).at("/error/details/0/code").asText()).isEqualTo("ROUTE_NOT_FOUND");
        assertNoPhi(route);

        Resp media = send(HttpMethod.POST, PATH, "x", MediaType.TEXT_PLAIN_VALUE);
        assertThat(media.status()).isEqualTo(415);
        assertNoPhi(media);

        Resp yaml = send(HttpMethod.GET, "/api-docs.yaml", null, null);
        assertThat(yaml.status()).as("a representation we do not serve is 406, not 500").isEqualTo(406);
        assertThat(json.readTree(yaml.body()).at("/error/details/0/code").asText()).isEqualTo("NOT_ACCEPTABLE");
    }

    @Test
    void answersJsonWhateverAcceptHeaderIsSent() throws Exception {
        Resp r = post(req("123456789", "2026-10-15", "evicore"), "Accept", "application/xml");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.contentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertNoPhi(r);
    }

    @Test
    void correlationIdIsEchoedOrGenerated() throws Exception {
        Resp r = post(req("123456789", "2026-10-15", "evicore"), "X-Correlation-Id", "ONYX-PA-1");
        assertThat(r.header("X-Correlation-Id")).isEqualTo("ONYX-PA-1");
        assertThat(json.readTree(r.body()).get("correlationId").asText()).isEqualTo("ONYX-PA-1");
        assertThat(r.header("Cache-Control")).isEqualTo("no-store");
        assertNoPhi(r);
        Resp generated = post(req("123456789", "2026-10-15", "evicore"), "X-Correlation-Id", "{bad id}");
        assertThat(generated.header("X-Correlation-Id")).matches("[0-9a-f-]{36}");
        assertNoPhi(generated);
    }

    @Test
    void unknownNestedPropertyReportsTheFullPath() throws Exception {
        JsonNode r = call(400, "{\"memberId\":\"123456789\",\"vendor\":\"evicore\",\"patient\":{\"dob\":\"1950-03-15\"}}");
        assertThat(r.at("/error/details/0/code").asText()).isEqualTo("UNKNOWN_PROPERTY");
        assertThat(r.at("/error/details/0/field").asText()).isEqualTo("patient.dob");
    }

    @Test
    void memberWhoseOnlySpanIsUnreadableIsRefusedNotInactive() throws Exception {
        JsonNode r = call(502, req("880000000", "2026-10-15", "evicore"));
        assertThat(r.at("/error/code").asText()).isEqualTo("MMI_INVALID_RESPONSE");
        assertThat(r.at("/error/details/0/code").asText()).isEqualTo("UNREADABLE_COVERAGE");
        assertThat(r.get("mmiRequestId").asText()).startsWith("MBRIDSVC-");
    }

    @Test
    void activeAnswerCarriesThePreviousSpanEndAndDefaultedDateWorksForInactive() throws Exception {
        JsonNode active = call(200, req("123456789", "2026-10-15", "evicore"));
        assertThat(active.at("/coverage/lastEndDate").asText()).as("end of the span before the covering one").isEqualTo("2023-12-31");
        assertThat(active.at("/coverage/nextEffectiveDate").isMissingNode()).isTrue();

        JsonNode inactive = call(200, req("234567890", null, "evicore"));
        assertThat(inactive.get("outcome").asText()).isEqualTo("INACTIVE");
        assertThat(inactive.get("dateOfServiceDefaulted").asBoolean()).isTrue();
        assertThat(inactive.at("/coverage/lastEndDate").asText()).isEqualTo("2025-12-31");
    }

    @Test
    void dobMismatchCarriesTheMmiRequestId() throws Exception {
        JsonNode r = call(422, reqDob("123456789", "2026-10-15", "evicore", "1999-09-09"));
        assertThat(r.get("mmiRequestId").asText()).startsWith("MBRIDSVC-");
    }

    @Test
    void errorMessageBesideMembersIsAWarningNotAFailure() throws Exception {
        JsonNode r = call(200, req("887777777", "2026-10-15", "evicore"));
        assertThat(r.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("marker=MMI_ERROR_MESSAGE_WITH_MEMBERS"))).isTrue();
    }

    @Test
    void recordsWithoutAMemberIdAreAContractError() throws Exception {
        JsonNode r = call(502, req("886666666", "2026-10-15", "evicore"));
        assertThat(r.at("/error/details/0/code").asText()).isEqualTo("NO_MEMBER_ID");
    }

    @Test
    void invalidRequestsNeverReachMmi() throws Exception {
        long before = stub.calls();
        call(400, req("1234567890", "2026-10-15", "evicore"));
        call(400, req("123456789", "2026-10-15", "AIMX"));
        call(400, req("1".repeat(41), "2026-10-15", "evicore"));
        assertThat(stub.calls()).isEqualTo(before);
        JsonNode tooLong = call(400, req("1".repeat(41), "2026-10-15", "evicore"));
        assertThat(tooLong.at("/error/details/0/code").asText()).isEqualTo("MEMBER_ID_TOO_LONG");
    }

    // ---------------------------------------------------------------- MMI failures

    @Test
    void mmiFailuresMapToClearStatuses() throws Exception {
        JsonNode down = call(503, req("500500500", "2026-10-15", "evicore"));
        assertThat(down.at("/error/code").asText()).isEqualTo("MMI_UNAVAILABLE");
        assertThat(down.at("/error/details/0/code").asText()).isEqualTo("HTTP_500");
        assertThat(down.get("mmiRequestId").asText()).startsWith("MBRIDSVC-");
        assertThat(post(req("500500500", "2026-10-15", "evicore")).header("Retry-After")).isEqualTo("10");

        JsonNode timeout = call(503, req("503503503", "2026-10-15", "evicore"));
        assertThat(timeout.at("/error/details/0/code").asText()).isEqualTo("READ_TIMEOUT");

        JsonNode rejected = call(502, req("400400400", "2026-10-15", "evicore"));
        assertThat(rejected.at("/error/code").asText()).isEqualTo("MMI_ERROR");

        JsonNode message = call(502, req("888888888", "2026-10-15", "evicore"));
        assertThat(message.at("/error/code").asText()).isEqualTo("MMI_ERROR");
        assertThat(message.at("/error/details/0/code").asText()).isEqualTo("ERROR_MESSAGE");
        assertThat(message.toString()).doesNotContain("search backend timed out");

        JsonNode body = call(502, req("202202202", "2026-10-15", "evicore"));
        assertThat(body.at("/error/code").asText()).isEqualTo("MMI_INVALID_RESPONSE");
    }
}
