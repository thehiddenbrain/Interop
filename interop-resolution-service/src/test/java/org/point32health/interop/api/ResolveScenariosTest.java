package org.point32health.interop.api;

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
    private static final String VENDOR_MAP = "/api/v1/member-ids/vendor-map";
    /** An unmasked 9- or 11-digit run (member ids), or an MM/dd/yyyy value (MMI dates of birth). */
    private static final Pattern PHI = Pattern.compile("(?<!\\d)(?:\\d{9}|\\d{11})(?!\\d)|(?<![A-Za-z0-9])[A-Za-z]\\d{8}(?!\\d)|\\d{2}/\\d{2}/\\d{4}");

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
    @Autowired org.point32health.interop.mmi.MmiClient mmiClient;

    private org.point32health.interop.mmi.StubMmiClient stub;

    @BeforeEach
    void stub() {
        stub = (org.point32health.interop.mmi.StubMmiClient) mmiClient;
    }

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger("org.point32health.interop")).addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger("org.point32health.interop")).detachAppender(logs);
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

    private JsonNode callVendorMap(int expectedStatus, Object body) throws Exception {
        Resp r = send(HttpMethod.POST, VENDOR_MAP, toJson(body), MediaType.APPLICATION_JSON_VALUE);
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
            assertThat(body).as("nothing a caller receives names MMI (owner): no field, code, message or URL").doesNotContain("MMI", "mmi");
        }
        for (ILoggingEvent e : logs.list) {
            StringBuilder text = new StringBuilder(e.getFormattedMessage());
            if (e.getThrowableProxy() != null) {
                text.append(' ').append(e.getThrowableProxy().getMessage());
            }
            // a generated correlation id (UUID) is not PHI but its hex can contain a 9-digit run: blank UUIDs before matching
            String line = text.toString().replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>");
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
        assertThat(r.get("message").asText()).isEqualTo("Member found; coverage active on 2026-10-15");
        assertThat(r.has("sourceMessage")).as("MMI's message only matters when nothing was found").isFalse();
        assertThat(r.at("/memberId/received").asText()).isEqualTo("123456789");
        assertThat(r.at("/memberId/stored").asText()).isEqualTo("123456789   01");
        assertThat(r.at("/memberId/forVendor").asText()).isEqualTo("12345678901");
        assertThat(r.has("vendor")).as("Onyx named the vendor; it is not echoed").isFalse();
        assertThat(r.has("company")).as("lean response: the line of business is what Onyx routes on").isFalse();
        assertThat(r.has("correlationId")).as("the correlation id travels in the X-Correlation-Id header").isFalse();
        assertThat(r.get("lineOfBusiness").asText()).isEqualTo("MCR");
        assertThat(r.at("/coverage/active").asBoolean()).isTrue();
        assertThat(r.get("coverage").properties()).extracting(Map.Entry::getKey).as("flag and period only").containsExactly("active", "span", "coverageId");
        assertThat(r.at("/coverage/coverageId").asText()).as("stored id without spaces, effective and end as yyyyMMdd, open end 99991231")
                .isEqualTo(r.at("/memberId/stored").asText().replaceAll("\\s+", "") + "-20210101-99991231");
        assertThat(r.at("/coverage/span/effectiveDate").asText()).as("two adjacent records are one continuous period").isEqualTo("2021-01-01");
        assertThat(r.at("/coverage/span").has("endDate")).as("open-ended end is an explicit null").isTrue();
        assertThat(r.at("/coverage/span/endDate").isNull()).isTrue();
        assertThat(r.get("dateOfServiceDefaulted").asBoolean()).isFalse();
        assertThat(r.get("traceId").asText()).matches("INTEROP-\\d{13}-\\d{5}");
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
        assertThat(optum.at("/memberId/forVendor").asText()).isEqualTo("123456789");
    }

    @Test
    void scoMemberWhoseCoverageEndedIsInactiveWithTheEndDate() throws Exception {
        JsonNode r = call(200, req("234567890", "2026-10-15", "evicore"));
        assertThat(r.get("outcome").asText()).isEqualTo("INACTIVE");
        assertThat(r.at("/coverage/active").asBoolean()).isFalse();
        assertThat(r.get("message").asText()).isEqualTo("Member found; coverage ended before 2026-10-15");
        assertThat(r.get("coverage").properties()).extracting(Map.Entry::getKey).as("no period when inactive, no neighbouring dates").containsExactly("active");
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
        assertThat(r.get("message").asText()).isEqualTo("Member found; coverage not yet effective on 2026-10-15");
        assertThat(r.at("/coverage/nextEffectiveDate").isMissingNode()).isTrue();
        assertThat(call(200, req("789012345", "2027-01-01", "evicore")).get("outcome").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void voidSpansAreIgnored() throws Exception {
        JsonNode r = call(200, req("678901234", "2026-10-15", "evicore"));
        assertThat(r.get("outcome").asText()).isEqualTo("INACTIVE");
        assertThat(r.get("message").asText()).isEqualTo("Member found; coverage ended before 2026-10-15");
    }

    @Test
    void unreadableSpanIsSkippedAndTheValidSpanDecides() throws Exception {
        JsonNode r = call(200, req("890123456", "2026-10-15", "evicore"));
        assertThat(r.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("marker=UNREADABLE_SPAN"))).isTrue();
    }

    @Test
    void companyMissingOnTheRecordIsInferredForTheVendorFormat() throws Exception {
        JsonNode r = call(200, req("901234567", "2026-10-15", "evicore"));
        assertThat(r.get("outcome").asText()).isEqualTo("ACTIVE");
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
        assertThat(blank.get("outcome").asText()).isEqualTo("INACTIVE");
        assertThat(blank.get("message").asText()).isEqualTo("Member found; coverage not yet effective on 2026-10-03");
    }

    // ---------------------------------------------------------------- populations with dependents

    @Test
    void publicPlansNineDigitWithoutDobIsAmbiguousWithCandidates() throws Exception {
        JsonNode r = call(200, req("345678901", "2026-10-15", "evicore"));
        assertThat(r.get("outcome").asText()).isEqualTo("AMBIGUOUS");
        assertThat(r.has("ambiguity")).isFalse();
        assertThat(r.get("message").asText()).isEqualTo(
                "Several members match this id; add patient.dateOfBirth or resend the member's full id including the suffix, or pick from candidates");
        assertThat(r.get("candidates")).hasSize(3);
        assertThat(r.at("/candidates/0").properties()).extracting(Map.Entry::getKey).containsExactly("storedMemberId", "lineOfBusiness", "coverageActive");
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
        assertThat(r.get("message").asText()).as("twins share the DOB").isEqualTo(
                "Several members match this id and date of birth; resend the member's full id including the suffix, or pick from candidates");
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
        assertThat(r.get("message").asText()).isEqualTo("Member found; no coverage on 2024-08-15 (gap between coverage periods)");
        assertThat(r.at("/coverage/lastEndDate").isMissingNode()).isTrue();
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

    // ---------------------------------------------------------------- TMP id with the real shape: a letter and 8 digits

    @Test
    void tmpIdStartingWithALetterKeepsTheLetterInEveryVendorFormat() throws Exception {
        for (String typed : List.of("S98765432", "s98765432", "S98765432   01", "S9876543201", "S98765432-01")) {
            JsonNode r = call(200, req(typed, "2026-10-15", "evicore"));
            assertThat(r.get("outcome").asText()).as(typed).isEqualTo("ACTIVE");
            assertThat(r.at("/memberId/received").asText()).isEqualTo(typed);
            assertThat(r.at("/memberId/stored").asText()).isEqualTo("S98765432   01");
            assertThat(r.at("/memberId/forVendor").asText()).as("the S is part of the id").isEqualTo("S9876543201");
        }
        assertThat(call(200, req("S98765432", "2026-10-15", "MHK")).at("/memberId/forVendor").asText()).isEqualTo("S98765432   01");
        JsonNode optum = call(200, req("S98765432", "2026-10-15", "OPTUM"));
        assertThat(optum.at("/memberId/forVendor").asText()).isEqualTo("S98765432");

        JsonNode map = callVendorMap(200, Map.of("memberId", "S98765432", "dateOfService", "2026-10-15"));
        Map<String, String> byVendor = new java.util.LinkedHashMap<>();
        map.get("vendorMemberIds").forEach(e -> byVendor.put(e.get("vendor").asText(), e.get("memberId").asText()));
        assertThat(byVendor).containsEntry("CARELON", "S9876543201").containsEntry("EVICORE", "S9876543201")
                .containsEntry("EVOLENT", "S9876543201").containsEntry("MHK", "S98765432   01")
                .containsEntry("ONYX", "S9876543201").containsEntry("OPTUM", "S98765432");
    }

    // ---------------------------------------------------------------- HPHC and converted members

    @Test
    void hphcIdWithCardHyphenOrLowerCaseIsPassedAsStored() throws Exception {
        for (String input : List.of("HP-456789012", "hp456789012", "HP 4567 89012", "HP456789012")) {
            JsonNode r = call(200, req(input, "2026-10-15", "evicore"));
            assertThat(r.get("outcome").asText()).as(input).isEqualTo("ACTIVE");
            assertThat(r.at("/memberId/received").asText()).as("echoed exactly as sent").isEqualTo(input);
            assertThat(stub.lastSearched()).as("no upper-casing or separator stripping before MMI").isEqualTo(input);
            assertThat(r.at("/memberId/stored").asText()).isEqualTo("HP456789012");
            assertThat(r.at("/memberId/forVendor").asText()).isEqualTo("HP456789012");
            assertThat(r.get("lineOfBusiness").asText()).isEqualTo("COM");
            assertThat(r.at("/coverage/span/endDate").isNull()).as("12/31/9999 is open-ended").isTrue();
        }
    }

    @Test
    void convertedMemberGetsTheIdOfTheCompanyThatOwnsTheDateOfService() throws Exception {
        JsonNode after = call(200, req("567890123 01", "2026-10-15", "Optum"));
        assertThat(after.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(after.at("/memberId/stored").asText()).isEqualTo("HP567890123");
        assertThat(after.at("/memberId/forVendor").asText()).isEqualTo("HP567890123");

        JsonNode before = call(200, req("56789012301", "2024-06-01", "Optum"));
        assertThat(before.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(before.at("/memberId/stored").asText()).isEqualTo("567890123   01");
        assertThat(before.at("/memberId/forVendor").asText()).as("Optum gets the 9-character core").isEqualTo("567890123");

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
        assertThat(r.has("lineOfBusiness")).isFalse();
        JsonNode notFound = call(200, req("HP111222333", "2026-10-15", "evicore"));
        assertThat(notFound.get("outcome").asText()).isEqualTo("NOT_FOUND");
        assertThat(notFound.get("message").asText()).isEqualTo("No member found for this id");
        assertThat(notFound.at("/sourceMessage/status").asText()).as("MMI's 404 is a normal answer, surfaced as is").isEqualTo("404");
        assertThat(notFound.at("/sourceMessage/code").asText()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(notFound.at("/sourceMessage/type").asText()).as("an ERROR-typed not-found message is still NOT_FOUND, not 502").isEqualTo("ERROR");
        assertThat(notFound.get("traceId").asText()).isNotBlank();
        assertThat(notFound.has("coverage")).isFalse();
    }

    @Test
    void everyValidationProblemIsReportedTogetherAndMmiIsNotCalled() throws Exception {
        JsonNode r = call(400, Map.of("memberId", "   ", "dateOfService", "10/15/2026", "vendor", "", "patient", Map.of("dateOfBirth", "2099-01-01")));
        assertThat(r.at("/error/code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(r.get("traceId")).isNull();
        List<String> codes = new java.util.ArrayList<>();
        r.at("/error/details").forEach(d -> codes.add(d.get("code").asText()));
        assertThat(codes).containsExactlyInAnyOrder("MEMBER_ID_MISSING", "DATE_OF_SERVICE_INVALID", "VENDOR_MISSING", "DATE_OF_BIRTH_OUT_OF_RANGE");
    }

    @Test
    void dateOfServiceOlderThanTheCoverageHistoryIsRejectedButAFutureDateIsJudged() throws Exception {
        JsonNode r = call(400, req("123456789", "2010-01-01", "evicore"));
        assertThat(r.at("/error/details/0/code").asText()).isEqualTo("DATE_OF_SERVICE_OUT_OF_RANGE");
        assertThat(call(400, req("123456789", "2026-02-30", "evicore")).at("/error/details/0/code").asText()).isEqualTo("DATE_OF_SERVICE_INVALID");
        JsonNode farFuture = call(200, req("123456789", "2036-01-01", "evicore"));
        assertThat(farFuture.get("dateOfService").asText()).as("no upper limit: the date is judged on the coverage on record").isEqualTo("2036-01-01");
        assertThat(farFuture.get("outcome").asText()).as("this member's coverage on record is open-ended").isEqualTo("ACTIVE");
    }

    // ---------------------------------------------------------------- the date of service is the date judged (owner feedback 20)

    @Test
    void aFutureDateOfServiceIsJudgedOnTheCoverageOnRecord() throws Exception {
        // a member whose only record runs 01/01/2026 to 12/31/2026: the owner's PQA case
        JsonNode inYear = call(200, req("T20262026", "2026-10-15", "evicore"));
        assertThat(inYear.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(inYear.get("message").asText()).isEqualTo("Member found; coverage active on 2026-10-15");
        assertThat(inYear.at("/coverage/span/endDate").asText()).isEqualTo("2026-12-31");
        assertThat(inYear.at("/coverage/coverageId").asText()).isEqualTo(inYear.at("/memberId/stored").asText().replaceAll("\\s+", "") + "-20260101-20261231");

        JsonNode nextYear = call(200, req("T20262026", "2027-10-15", "evicore"));
        assertThat(nextYear.get("outcome").asText()).as("no record covers 2027").isEqualTo("INACTIVE");
        assertThat(nextYear.at("/coverage/active").asBoolean()).isFalse();
        assertThat(nextYear.get("message").asText()).isEqualTo("Member found; coverage ended before 2027-10-15");
        assertThat(nextYear.get("dateOfService").asText()).as("the date asked about, never today").isEqualTo("2027-10-15");
        assertThat(nextYear.get("dateOfServiceDefaulted").asBoolean()).isFalse();
        assertThat(nextYear.at("/coverage/coverageId").isMissingNode()).as("no period, no id").isTrue();
        assertThat(nextYear.at("/memberId/forVendor").asText()).as("the id is still returned when inactive").isEqualTo("T2026202601");

        JsonNode map = callVendorMap(200, Map.of("memberId", "T20262026", "dateOfService", "2027-10-15"));
        assertThat(map.get("outcome").asText()).as("same judgement on the vendor map").isEqualTo("INACTIVE");
        assertThat(map.get("dateOfService").asText()).isEqualTo("2027-10-15");
        assertThat(map.get("dateOfServiceDefaulted").asBoolean()).isFalse();
        assertThat(map.get("vendorMemberIds")).hasSize(6);

        JsonNode firstDay = call(200, req("T20262026", "2027-01-01", "evicore"));
        assertThat(firstDay.get("outcome").asText()).as("the day after the record ends is already inactive").isEqualTo("INACTIVE");
        assertThat(call(200, req("T20262026", "2026-12-31", "evicore")).get("outcome").asText()).as("the last covered day").isEqualTo("ACTIVE");
    }

    @Test
    void aPeriodOfServiceMustBeCoveredOnEveryDay() throws Exception {
        // two adjacent plan-year records (2025, 2026) are one continuous period
        JsonNode across = call(200, Map.of("memberId", "U20252026", "dateOfService", "2025-12-20", "dateOfServiceEnd", "2026-01-05", "vendor", "EVICORE"));
        assertThat(across.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(across.get("message").asText()).isEqualTo("Member found; coverage active from 2025-12-20 to 2026-01-05");
        assertThat(across.get("dateOfService").asText()).isEqualTo("2025-12-20");
        assertThat(across.get("dateOfServiceEnd").asText()).as("echoed when a period was asked about").isEqualTo("2026-01-05");
        assertThat(across.at("/coverage/span/effectiveDate").asText()).isEqualTo("2025-01-01");
        assertThat(across.at("/coverage/span/endDate").asText()).isEqualTo("2026-12-31");
        assertThat(across.at("/coverage/coverageId").asText()).isEqualTo(across.at("/memberId/stored").asText().replaceAll("\\s+", "") + "-20250101-20261231");

        JsonNode outlasts = call(200, Map.of("memberId", "U20252026", "dateOfService", "2026-12-20", "dateOfServiceEnd", "2027-01-05", "vendor", "EVICORE"));
        assertThat(outlasts.get("outcome").asText()).as("covered on the first day only").isEqualTo("INACTIVE");
        assertThat(outlasts.at("/coverage/active").asBoolean()).isFalse();
        assertThat(outlasts.get("message").asText()).isEqualTo("Member found; coverage active on 2026-12-20 but ends 2026-12-31, before 2027-01-05");
        assertThat(outlasts.at("/coverage/span/endDate").asText()).as("the period that covers the first day is shown, so intake sees how far coverage goes").isEqualTo("2026-12-31");
        assertThat(outlasts.at("/coverage/coverageId").asText()).as("the period has an id even when the answer is inactive")
                .isEqualTo(outlasts.at("/memberId/stored").asText().replaceAll("\\s+", "") + "-20250101-20261231");

        JsonNode single = call(200, Map.of("memberId", "U20252026", "dateOfService", "2026-10-15", "vendor", "EVICORE"));
        assertThat(single.has("dateOfServiceEnd")).as("absent for a single date").isFalse();
        assertThat(single.get("message").asText()).isEqualTo("Member found; coverage active on 2026-10-15");
        assertThat(single.at("/coverage/span/effectiveDate").asText()).isEqualTo("2025-01-01");

        JsonNode map = callVendorMap(200, Map.of("memberId", "T20262026", "dateOfService", "2026-12-01", "dateOfServiceEnd", "2027-01-31"));
        assertThat(map.get("outcome").asText()).as("the vendor map judges the period the same way").isEqualTo("INACTIVE");
        assertThat(map.get("dateOfServiceEnd").asText()).isEqualTo("2027-01-31");
        assertThat(map.get("vendorMemberIds")).hasSize(6);
        JsonNode mapOk = callVendorMap(200, Map.of("memberId", "T20262026", "dateOfService", "2026-12-01", "dateOfServiceEnd", "2026-12-31"));
        assertThat(mapOk.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(mapOk.get("message").asText()).isEqualTo("Member found; coverage active from 2026-12-01 to 2026-12-31");

        // the end date is validated like the start, on both operations
        assertThat(call(400, Map.of("memberId", "T20262026", "dateOfService", "2026-10-15", "dateOfServiceEnd", "2026-10-14", "vendor", "EVICORE"))
                .at("/error/details/0/code").asText()).isEqualTo("DATE_OF_SERVICE_END_BEFORE_START");
        assertThat(callVendorMap(400, Map.of("memberId", "T20262026", "dateOfService", "2026-10-15", "dateOfServiceEnd", "10/20/2026"))
                .at("/error/details/0/code").asText()).isEqualTo("DATE_OF_SERVICE_END_INVALID");
        assertThat(callVendorMap(400, Map.of("memberId", "T20262026", "dateOfServiceEnd", "2026-10-20"))
                .at("/error/details/0/code").asText()).isEqualTo("DATE_OF_SERVICE_END_WITHOUT_START");
        JsonNode sameDay = callVendorMap(200, Map.of("memberId", "T20262026", "dateOfService", "2026-10-15", "dateOfServiceEnd", "2026-10-15"));
        assertThat(sameDay.get("message").asText()).as("a one-day period reads like a single date").isEqualTo("Member found; coverage active on 2026-10-15");
        assertThat(sameDay.get("dateOfServiceEnd").asText()).isEqualTo("2026-10-15");
    }

    @Test
    void aSentDateOfServiceIsNeverReplacedByToday() throws Exception {
        // an unusable date must not become "active for today" on either operation
        for (String bad : List.of("10/15/2027", "2027-13-01", "next year")) {
            JsonNode map = callVendorMap(400, Map.of("memberId", "T20262026", "dateOfService", bad));
            assertThat(map.at("/error/code").asText()).as(bad).isEqualTo("INVALID_REQUEST");
            assertThat(map.at("/error/details/0/code").asText()).isEqualTo("DATE_OF_SERVICE_INVALID");
            assertThat(call(400, req("T20262026", bad, "evicore")).at("/error/details/0/code").asText()).isEqualTo("DATE_OF_SERVICE_INVALID");
        }
        JsonNode old = callVendorMap(400, Map.of("memberId", "T20262026", "dateOfService", "1999-01-01"));
        assertThat(old.at("/error/details/0/code").asText()).isEqualTo("DATE_OF_SERVICE_OUT_OF_RANGE");
        // only a missing date defaults to today, and the response says so
        JsonNode none = callVendorMap(200, Map.of("memberId", "T20262026"));
        assertThat(none.get("dateOfService").asText()).isEqualTo("2026-10-03");
        assertThat(none.get("dateOfServiceDefaulted").asBoolean()).isTrue();
    }

    @Test
    void unknownVendorListsTheKnownOnesAndNeverEchoesTheValue() throws Exception {
        JsonNode r = call(400, req("123456789", "2026-10-15", "AIMX"));
        assertThat(r.at("/error/code").asText()).isEqualTo("UNKNOWN_VENDOR");
        assertThat(r.at("/error/details/0/message").asText()).isEqualTo("CARELON, EVICORE, EVOLENT, MHK, ONYX, OPTUM");
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
        assertThat(json.readTree(r.body()).has("correlationId")).as("header only on a 200").isFalse();
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
        assertThat(r.at("/error/code").asText()).isEqualTo("MEMBER_LOOKUP_INVALID_RESPONSE");
        assertThat(r.at("/error/details/0/code").asText()).isEqualTo("UNREADABLE_COVERAGE");
        assertThat(r.get("traceId").asText()).startsWith("INTEROP-");
    }

    @Test
    void coverageBlockIsTheFlagAndThePeriodOnly() throws Exception {
        JsonNode active = call(200, req("123456789", "2026-10-15", "evicore"));
        assertThat(active.get("coverage").properties()).extracting(Map.Entry::getKey).containsExactly("active", "span", "coverageId");
        assertThat(active.at("/coverage/coverageId").asText()).as("stored id without spaces, effective and end as yyyyMMdd, open end 99991231")
                .isEqualTo(active.at("/memberId/stored").asText().replaceAll("\\s+", "") + "-20210101-99991231");
        assertThat(active.at("/coverage/span/effectiveDate").asText()).isEqualTo("2021-01-01");

        JsonNode inactive = call(200, req("234567890", null, "evicore"));
        assertThat(inactive.get("outcome").asText()).isEqualTo("INACTIVE");
        assertThat(inactive.get("dateOfServiceDefaulted").asBoolean()).isTrue();
        assertThat(inactive.get("coverage").properties()).extracting(Map.Entry::getKey).containsExactly("active");
        assertThat(inactive.get("message").asText()).isEqualTo("Member found; coverage ended before 2026-10-03");
    }

    @Test
    void dobMismatchCarriesTheMmiRequestId() throws Exception {
        JsonNode r = call(422, reqDob("123456789", "2026-10-15", "evicore", "1999-09-09"));
        assertThat(r.get("traceId").asText()).startsWith("INTEROP-");
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
        call(400, req("   ", "2026-10-15", "evicore"));
        call(400, req("123456789", "2026-10-15", "AIMX"));
        call(400, req("123456789", "13/45/2026", "evicore"));
        assertThat(stub.calls()).isEqualTo(before);
    }

    @Test
    void anyMemberIdIsPassedToMmiExactlyAsTypedAndNeverRejectedForItsShape() throws Exception {
        // 10 digits, 40 digits, letters and punctuation: none of it is this service's business, MMI decides
        for (String odd : List.of("1234567890", "1".repeat(40), "ABC-123/XYZ_9", "HP-000000000", "98765 432 10")) {
            long before = stub.calls();
            JsonNode r = call(200, req(odd, "2026-10-15", "evicore"));
            assertThat(stub.calls()).as("MMI was asked about " + odd).isEqualTo(before + 1);
            assertThat(r.get("outcome").asText()).as(odd).isEqualTo("NOT_FOUND");
            assertThat(r.at("/memberId/received").asText()).isEqualTo(odd);
            assertThat(stub.lastSearched()).as("sent to MMI exactly as typed: " + odd).isEqualTo(odd);
            assertThat(r.get("traceId").asText()).startsWith("INTEROP-");
        }
        // a known member typed with a hyphen or in pieces still resolves: the (lenient) MMI matches it, not this service
        for (String typed : List.of("123456789-01", "123-456-789 01", "  12345678901  ")) {
            JsonNode r = call(200, req(typed, "2026-10-15", "evicore"));
            assertThat(r.get("outcome").asText()).as(typed).isEqualTo("ACTIVE");
            assertThat(r.at("/memberId/received").asText()).isEqualTo(typed.strip());
            assertThat(stub.lastSearched()).as("sent to MMI exactly as typed: " + typed).isEqualTo(typed.strip());
            assertThat(r.at("/memberId/stored").asText()).isEqualTo("123456789   01");
            assertThat(r.at("/memberId/forVendor").asText()).isEqualTo("12345678901");
        }
    }

    // ---------------------------------------------------------------- vendor map (operation 2)

    @Test
    void vendorMapReturnsTheTmpIdInEveryVendorsFormat() throws Exception {
        long before = stub.calls();
        JsonNode r = callVendorMap(200, Map.of("memberId", "123456789", "dateOfService", "2026-10-15"));
        assertThat(stub.calls()).as("one MMI call for all vendors").isEqualTo(before + 1);
        assertThat(r.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(r.at("/memberId/received").asText()).isEqualTo("123456789");
        assertThat(r.at("/memberId/stored").asText()).isEqualTo("123456789   01");
        assertThat(r.has("vendor")).as("no single vendor in this operation").isFalse();
        assertThat(r.at("/memberId/forVendor").isMissingNode()).isTrue();
        assertThat(r.has("company")).isFalse();
        assertThat(r.has("correlationId")).isFalse();
        assertThat(r.get("lineOfBusiness").asText()).isEqualTo("MCR");
        assertThat(r.get("dateOfService").asText()).isEqualTo("2026-10-15");
        assertThat(r.get("dateOfServiceDefaulted").asBoolean()).isFalse();
        assertThat(r.at("/coverage/active").asBoolean()).isTrue();
        assertThat(r.at("/coverage/reason").isMissingNode()).isTrue();
        assertThat(r.get("traceId").asText()).isNotBlank();

        Map<String, JsonNode> byVendor = new java.util.LinkedHashMap<>();
        r.get("vendorMemberIds").forEach(e -> byVendor.put(e.get("vendor").asText(), e));
        assertThat(byVendor.keySet()).as("every configured vendor, sorted by code").containsExactly("CARELON", "EVICORE", "EVOLENT", "MHK", "ONYX", "OPTUM");
        assertThat(byVendor.get("EVICORE").get("memberId").asText()).isEqualTo("12345678901");
        assertThat(byVendor.get("EVOLENT").get("memberId").asText()).isEqualTo("12345678901");
        assertThat(byVendor.get("CARELON").get("memberId").asText()).isEqualTo("12345678901");
        assertThat(byVendor.get("ONYX").get("memberId").asText()).isEqualTo("12345678901");
        assertThat(byVendor.get("MHK").get("memberId").asText()).isEqualTo("123456789   01");
        assertThat(byVendor.get("OPTUM").get("memberId").asText()).isEqualTo("123456789");
    }

    @Test
    void vendorMapPassesNonTmpIdsAsStoredForEveryVendorAndDefaultsTheDate() throws Exception {
        JsonNode hphc = callVendorMap(200, Map.of("memberId", "HP-456789012"));
        assertThat(hphc.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(hphc.get("dateOfServiceDefaulted").asBoolean()).isTrue();
        assertThat(hphc.get("dateOfService").asText()).isEqualTo("2026-10-03");
        assertThat(hphc.at("/memberId/received").asText()).isEqualTo("HP-456789012");
        assertThat(hphc.at("/memberId/stored").asText()).isEqualTo("HP456789012");
        assertThat(hphc.get("vendorMemberIds")).hasSize(6);
        hphc.get("vendorMemberIds").forEach(e -> {
            assertThat(e.get("memberId").asText()).as(e.get("vendor").asText()).isEqualTo("HP456789012");
        });

        JsonNode publicPlans = callVendorMap(200, Map.of("memberId", "34567890102", "dateOfService", "2024-08-15"));
        assertThat(publicPlans.get("outcome").asText()).as("a gap in coverage still identifies the member").isEqualTo("INACTIVE");
        assertThat(publicPlans.at("/coverage/active").asBoolean()).isFalse();
        assertThat(publicPlans.get("vendorMemberIds")).hasSize(6);
        publicPlans.get("vendorMemberIds").forEach(e -> assertThat(e.get("memberId").asText()).isEqualTo("34567890102"));
    }

    @Test
    void vendorMapWithoutAnIdentifiedMemberCarriesNoVendorIds() throws Exception {
        JsonNode notFound = callVendorMap(200, Map.of("memberId", "HP111222333", "dateOfService", "2026-10-15"));
        assertThat(notFound.get("outcome").asText()).isEqualTo("NOT_FOUND");
        assertThat(notFound.get("message").asText()).isEqualTo("No member found for this id");
        assertThat(notFound.at("/sourceMessage/code").asText()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(notFound.has("vendorMemberIds")).isFalse();
        assertThat(notFound.has("coverage")).isFalse();
        assertThat(notFound.at("/memberId/received").asText()).isEqualTo("HP111222333");
        assertThat(notFound.get("traceId").asText()).isNotBlank();

        JsonNode ambiguous = callVendorMap(200, Map.of("memberId", "345678901", "dateOfService", "2026-10-15"));
        assertThat(ambiguous.get("outcome").asText()).isEqualTo("AMBIGUOUS");
        assertThat(ambiguous.has("ambiguity")).isFalse();
        assertThat(ambiguous.get("message").asText()).startsWith("Several members match this id; add patient.dateOfBirth");
        assertThat(ambiguous.get("candidates")).hasSize(3);
        assertThat(ambiguous.has("vendorMemberIds")).isFalse();

        JsonNode withDob = callVendorMap(200, Map.of("memberId", "345678901", "dateOfService", "2026-10-15",
                "patient", Map.of("dateOfBirth", "1985-06-01")));
        assertThat(withDob.get("outcome").asText()).as("DOB picks the subscriber, then every vendor gets the id").isEqualTo("ACTIVE");
        assertThat(withDob.at("/memberId/stored").asText()).isEqualTo("34567890101");
        assertThat(withDob.get("vendorMemberIds")).hasSize(6);
        withDob.get("vendorMemberIds").forEach(e -> assertThat(e.get("memberId").asText()).isEqualTo("34567890101"));
    }

    @Test
    void vendorMapIsLenientAboutEverythingExceptTheMemberId() throws Exception {
        long before = stub.calls();
        JsonNode blank = callVendorMap(400, Map.of("memberId", "   ", "dateOfService", "2026-10-15"));
        assertThat(blank.at("/error/code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(blank.at("/error/details/0/code").asText()).isEqualTo("MEMBER_ID_MISSING");
        assertThat(stub.calls()).as("a missing id never reaches MMI").isEqualTo(before);

        // the /resolve payload, vendor included, is accepted as is: the vendor is simply ignored
        JsonNode withVendor = callVendorMap(200, Map.of("memberId", "123456789", "dateOfService", "2026-10-15", "vendor", "EVICORE"));
        assertThat(withVendor.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(withVendor.get("vendorMemberIds")).hasSize(6);
        // whatever shape the vendor takes (unknown code, object, array, number), it is ignored, never looked up or rejected
        for (Object vendor : List.of("NO_SUCH_VENDOR", Map.of("code", "EVICORE"), List.of("EVICORE"), 5)) {
            JsonNode r = callVendorMap(200, Map.of("memberId", "123456789", "dateOfService", "2026-10-15", "vendor", vendor));
            assertThat(r.get("outcome").asText()).as("vendor=" + vendor).isEqualTo("ACTIVE");
            assertThat(r.get("vendorMemberIds")).hasSize(6);
        }

        // unknown properties, at the top level and inside patient, are ignored
        JsonNode odd = callVendorMap(200, Map.of("memberId", "123456789", "dateOfService", "2026-10-15", "anything", "goes",
                "patient", Map.of("dateOfBirth", "1950-03-15", "name", "ignored too")));
        assertThat(odd.get("outcome").asText()).isEqualTo("ACTIVE");
        assertThat(odd.get("dateOfService").asText()).isEqualTo("2026-10-15");
        assertThat(odd.has("ignoredFields")).as("nothing is silently ignored and reported any more").isFalse();
        assertThat(odd.get("vendorMemberIds")).hasSize(6);

        // a sent value that cannot be used is a 400, never dropped (feedback 20): dates are the same on both operations
        JsonNode badDob = callVendorMap(400, Map.of("memberId", "123456789", "dateOfService", "2026-10-15",
                "patient", Map.of("dateOfBirth", "not-a-date")));
        assertThat(badDob.at("/error/details/0/code").asText()).isEqualTo("DATE_OF_BIRTH_INVALID");
        assertThat(callVendorMap(400, Map.of("memberId", "123456789", "dateOfService", "10/15/2026")).at("/error/details/0/code").asText())
                .isEqualTo("DATE_OF_SERVICE_INVALID");

        // a real date of birth is still used, and still protects against the wrong person
        JsonNode dobMismatch = callVendorMap(422, Map.of("memberId", "123456789", "dateOfService", "2026-10-15",
                "patient", Map.of("dateOfBirth", "1999-12-31")));
        assertThat(dobMismatch.at("/error/code").asText()).isEqualTo("DOB_MISMATCH");
        assertThat(dobMismatch.get("traceId").asText()).isNotBlank();

        // /resolve keeps its strict contract
        JsonNode strict = call(400, Map.of("memberId", "123456789", "dateOfService", "10/15/2026", "vendor", "EVICORE", "anything", "goes"));
        assertThat(strict.at("/error/code").asText()).isEqualTo("INVALID_REQUEST");
    }

    // ---------------------------------------------------------------- MMI failures

    @Test
    void mmiFailuresMapToClearStatuses() throws Exception {
        JsonNode down = call(503, req("500500500", "2026-10-15", "evicore"));
        assertThat(down.at("/error/code").asText()).isEqualTo("MEMBER_LOOKUP_UNAVAILABLE");
        assertThat(down.at("/error/details/0/code").asText()).isEqualTo("HTTP_500");
        assertThat(down.get("traceId").asText()).startsWith("INTEROP-");
        assertThat(post(req("500500500", "2026-10-15", "evicore")).header("Retry-After")).isEqualTo("10");

        JsonNode timeout = call(503, req("503503503", "2026-10-15", "evicore"));
        assertThat(timeout.at("/error/details/0/code").asText()).isEqualTo("READ_TIMEOUT");

        Resp badRequest = post(req("400400400", "2026-10-15", "evicore"));
        assertThat(badRequest.status()).as("MMI's 400 is forwarded as a 400, not hidden behind a 502").isEqualTo(400);
        assertThat(badRequest.header("Retry-After")).isNull();
        JsonNode rejected = json.readTree(badRequest.body());
        assertThat(rejected.at("/error/code").asText()).isEqualTo("MEMBER_LOOKUP_REJECTED");
        assertThat(rejected.at("/error/details/0/code").asText()).isEqualTo("HTTP_400");
        assertThat(rejected.at("/error/message").asText()).startsWith("The member lookup rejected the request: INVALID_REQUEST");
        assertThat(rejected.get("traceId").asText()).isNotBlank();

        JsonNode message = call(502, req("888888888", "2026-10-15", "evicore"));
        assertThat(message.at("/error/code").asText()).isEqualTo("MEMBER_LOOKUP_ERROR");
        assertThat(message.at("/error/details/0/code").asText()).isEqualTo("ERROR_MESSAGE");
        assertThat(message.at("/error/message").asText()).as("the lookup's own code and text are forwarded so the problem can be read")
                .isEqualTo("The member lookup reported an error: ES_TIMEOUT search backend timed out (stub)");

        JsonNode body = call(502, req("202202202", "2026-10-15", "evicore"));
        assertThat(body.at("/error/code").asText()).isEqualTo("MEMBER_LOOKUP_INVALID_RESPONSE");
    }
}
