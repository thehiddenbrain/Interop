package org.point32health.memberprofile.memberdomain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.point32health.memberprofile.common.ErrorCode;
import org.point32health.memberprofile.common.MemberIds;
import org.point32health.memberprofile.common.MemberProfileException;
import org.point32health.memberprofile.config.MemberProfileProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link RestMemberDomainClient} against a {@link MockRestServiceServer} bound to the {@code RestClient} builder
 * (catalog section 2):
 * <ul>
 *   <li>{@code GET {baseUrl}/members/{memberId}} with the id substituted into the path;</li>
 *   <li>200 maps the member, facts and roster, ignoring properties this service does not know;</li>
 *   <li>404 is "not found" (empty); every other 4xx/5xx is {@code MEMBER_DOMAIN_ERROR};</li>
 *   <li>connect or read failures are {@code MEMBER_DOMAIN_UNREACHABLE};</li>
 *   <li>an unusable 200 (wrong content type, broken JSON) is {@code MEMBER_DOMAIN_ERROR}.</li>
 * </ul>
 */
class RestMemberDomainClientTest {

    static final String BASE_URL = "http://memberdomain.test/api/v1";
    static final String MEMBER_ID = "HP0000001";

    static final String FULL_MEMBER_JSON = """
            {
              "memberId": "HP0000001",
              "firstName": "Alexa",
              "lastName": "Miller",
              "fullName": "Alexa M Miller",
              "planName": "HMO Blue",
              "relationshipCode": "01",
              "memberTypeCode": "HPHC",
              "userTypeCode": "M",
              "dateOfBirth": "1984-03-15",
              "age": 42,
              "activePolicy": true,
              "accountNumber": "****1234",
              "policyStatus": "ACTIVE",
              "attributes": {
                "sourceSystemId": 2001,
                "planTypeCode": "MR",
                "coverageActive": true,
                "hasActivePdp": false,
                "premium": 12.5
              },
              "familyMembers": [
                { "memberId": "HP0000002", "fullName": "Liam Miller", "relationshipCode": "03", "age": 7, "gender": "M" },
                { "memberId": "HP0000003", "fullName": "Kim Miller", "relationshipCode": "03", "dateOfBirth": "2011-05-04" }
              ],
              "ssn": "***-**-1234",
              "addresses": [ { "line1": "1 Main St", "city": "Canton" } ],
              "metadata": { "source": "test", "nested": { "deep": [1, 2, 3] } }
            }
            """;

    static MemberProfileProperties properties(String baseUrl) {
        return properties(baseUrl, "/members/{memberId}");
    }

    static MemberProfileProperties properties(String baseUrl, String memberPath) {
        return new MemberProfileProperties(
                new MemberProfileProperties.MemberDomain(baseUrl, memberPath, Duration.ofSeconds(2), Duration.ofSeconds(3)),
                new MemberProfileProperties.Security(new MemberProfileProperties.Security.ApiKey(false, "X-Api-Key", "")),
                new MemberProfileProperties.Explain(true),
                new MemberProfileProperties.Http(8192, Duration.ofSeconds(5)),
                "America/New_York");
    }

    MockRestServiceServer server;
    RestMemberDomainClient client;

    @BeforeEach
    void bindMockServer() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestMemberDomainClient(builder.build(), properties(BASE_URL));
    }

    private static MemberProfileException expectFailure(Runnable call, ErrorCode code) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(MemberProfileException.class);
        MemberProfileException e = (MemberProfileException) thrown;
        assertThat(e.getCode()).isEqualTo(code);
        return e;
    }

    // ------------------------------------------------------------------------------- request

    @Nested
    class Request {

        @Test
        void getsTheMemberPathWithTheIdSubstituted() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"memberId\":\"HP0000001\"}", MediaType.APPLICATION_JSON));

            client.findMember(MEMBER_ID);

            server.verify();
        }

        @ParameterizedTest(name = "memberId ''{0}''")
        @ValueSource(strings = {"TH0000001", "abc-DEF_123", "1", "HP0000000000000000000000000001"})
        void anyValidIdIsSubstitutedVerbatim(String memberId) {
            server.expect(requestTo(BASE_URL + "/members/" + memberId)).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"memberId\":\"" + memberId + "\"}", MediaType.APPLICATION_JSON));

            Optional<MemberDomainMember> member = client.findMember(memberId);

            assertThat(member).map(MemberDomainMember::memberId).contains(memberId);
            server.verify();
        }

        @Test
        void aDifferentMemberPathFromPropertiesIsHonoured() {
            RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
            MockRestServiceServer own = MockRestServiceServer.bindTo(builder).build();
            RestMemberDomainClient custom = new RestMemberDomainClient(builder.build(), properties(BASE_URL, "/v2/member/{memberId}/profile"));
            own.expect(requestTo(BASE_URL + "/v2/member/" + MEMBER_ID + "/profile")).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"memberId\":\"HP0000001\"}", MediaType.APPLICATION_JSON));

            custom.findMember(MEMBER_ID);

            own.verify();
        }

        @Test
        void oneHttpCallPerLookup() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID))
                    .andRespond(withSuccess("{\"memberId\":\"HP0000001\"}", MediaType.APPLICATION_JSON));

            client.findMember(MEMBER_ID);

            server.verify();
            // a second lookup with nothing expected fails the mock server: the client does not cache, and does not retry
            assertThat(catchThrowable(() -> client.findMember(MEMBER_ID))).isNotNull();
        }
    }

    // ------------------------------------------------------------------------------- 200

    @Nested
    class Success {

        @Test
        void mapsEveryFieldAndIgnoresUnknownProperties() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID))
                    .andRespond(withSuccess(FULL_MEMBER_JSON, MediaType.APPLICATION_JSON));

            MemberDomainMember member = client.findMember(MEMBER_ID).orElseThrow();

            assertThat(member.memberId()).isEqualTo("HP0000001");
            assertThat(member.firstName()).isEqualTo("Alexa");
            assertThat(member.lastName()).isEqualTo("Miller");
            assertThat(member.fullName()).isEqualTo("Alexa M Miller");
            assertThat(member.planName()).isEqualTo("HMO Blue");
            assertThat(member.relationshipCode()).isEqualTo("01");
            assertThat(member.memberTypeCode()).isEqualTo("HPHC");
            assertThat(member.userTypeCode()).isEqualTo("M");
            assertThat(member.dateOfBirth()).isEqualTo(LocalDate.of(1984, 3, 15));
            assertThat(member.age()).isEqualTo(42);
            assertThat(member.activePolicy()).isTrue();
            assertThat(member.accountNumber()).isEqualTo("****1234");
            assertThat(member.policyStatus()).isEqualTo("ACTIVE");
        }

        @Test
        void attributesKeepTheirJsonTypesForTheRuleFacts() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID))
                    .andRespond(withSuccess(FULL_MEMBER_JSON, MediaType.APPLICATION_JSON));

            Map<String, Object> attributes = client.findMember(MEMBER_ID).orElseThrow().attributes();

            assertThat(attributes.get("sourceSystemId")).isEqualTo(2001);
            assertThat(attributes.get("planTypeCode")).isEqualTo("MR");
            assertThat(attributes.get("coverageActive")).isEqualTo(Boolean.TRUE);
            assertThat(attributes.get("hasActivePdp")).isEqualTo(Boolean.FALSE);
            assertThat(attributes.get("premium")).isInstanceOf(Number.class);
            assertThat(((Number) attributes.get("premium")).doubleValue()).isEqualTo(12.5);
        }

        @Test
        void familyRosterIsMappedInOrderWithAgeOrDateOfBirth() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID))
                    .andRespond(withSuccess(FULL_MEMBER_JSON, MediaType.APPLICATION_JSON));

            var family = client.findMember(MEMBER_ID).orElseThrow().familyMembers();

            assertThat(family).hasSize(2);
            assertThat(family.get(0).memberId()).isEqualTo("HP0000002");
            assertThat(family.get(0).fullName()).isEqualTo("Liam Miller");
            assertThat(family.get(0).relationshipCode()).isEqualTo("03");
            assertThat(family.get(0).age()).isEqualTo(7);
            assertThat(family.get(0).dateOfBirth()).isNull();
            assertThat(family.get(1).memberId()).isEqualTo("HP0000003");
            assertThat(family.get(1).age()).isNull();
            assertThat(family.get(1).dateOfBirth()).isEqualTo(LocalDate.of(2011, 5, 4));
        }

        @Test
        void minimalBodyGivesEmptyAttributesAndRosterNotNull() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID))
                    .andRespond(withSuccess("{\"memberId\":\"HP0000001\",\"memberTypeCode\":\"THP\"}", MediaType.APPLICATION_JSON));

            MemberDomainMember member = client.findMember(MEMBER_ID).orElseThrow();

            assertThat(member.attributes()).isEmpty();
            assertThat(member.familyMembers()).isEmpty();
            assertThat(member.age()).isNull();
            assertThat(member.dateOfBirth()).isNull();
            assertThat(member.memberTypeCode()).isEqualTo("THP");
        }

        /**
         * Catalog section 3: "a missing or null fact never satisfies a condition". MemberDomain may therefore send
         * {@code "planCode": null}; the member must still load, with that fact absent.
         */
        @Test
        void nullValuedAttributeIsToleratedAsAMissingFact() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID))
                    .andRespond(withSuccess("{\"memberId\":\"HP0000001\",\"attributes\":{\"sourceSystemId\":2001,\"planCode\":null}}",
                            MediaType.APPLICATION_JSON));

            MemberDomainMember member = client.findMember(MEMBER_ID).orElseThrow();

            assertThat(member.attributes().get("sourceSystemId")).isEqualTo(2001);
            assertThat(member.attributes().get("planCode")).isNull();
        }

        @Test
        void explicitNullAttributesAndRosterAreNormalizedToEmpty() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID))
                    .andRespond(withSuccess("{\"memberId\":\"HP0000001\",\"attributes\":null,\"familyMembers\":null}", MediaType.APPLICATION_JSON));

            MemberDomainMember member = client.findMember(MEMBER_ID).orElseThrow();

            assertThat(member.attributes()).isEmpty();
            assertThat(member.familyMembers()).isEmpty();
        }

        @Test
        void jsonWithACharsetParameterIsAccepted() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID))
                    .andRespond(withSuccess("{\"memberId\":\"HP0000001\"}", MediaType.parseMediaType("application/json;charset=UTF-8")));

            assertThat(client.findMember(MEMBER_ID)).isPresent();
        }

        /** An empty 200 carries no member; the service then reports the member as not found. */
        @Test
        void emptySuccessBodyIsMemberDomainError() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID))
                    .andRespond(withSuccess());

            expectFailure(() -> client.findMember(MEMBER_ID), ErrorCode.MEMBER_DOMAIN_ERROR);
        }
    }

    // ------------------------------------------------------------------------------- 404 and other statuses

    @Nested
    class Statuses {

        @Test
        void notFoundIsEmptyNotAnError() {
            server.expect(requestTo(BASE_URL + "/members/NOBODY")).andRespond(withResourceNotFound());

            assertThat(client.findMember("NOBODY")).isEmpty();
            server.verify();
        }

        @Test
        void notFoundWithAJsonErrorBodyIsStillEmpty() {
            server.expect(requestTo(BASE_URL + "/members/NOBODY"))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"status\":\"ERROR\",\"message\":\"no such member\"}"));

            assertThat(client.findMember("NOBODY")).isEmpty();
        }

        @Test
        void serverErrorIsMemberDomainError() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID)).andRespond(withServerError());

            MemberProfileException e = expectFailure(() -> client.findMember(MEMBER_ID), ErrorCode.MEMBER_DOMAIN_ERROR);

            assertThat(e.getMessage()).contains("500").contains(MemberIds.forLog(MEMBER_ID)).doesNotContain(MEMBER_ID);
            assertThat(e.getCause()).isInstanceOf(HttpServerErrorException.class);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = HttpStatus.class, names = {"BAD_REQUEST", "UNAUTHORIZED", "FORBIDDEN", "METHOD_NOT_ALLOWED",
                "CONFLICT", "UNPROCESSABLE_CONTENT", "TOO_MANY_REQUESTS", "INTERNAL_SERVER_ERROR", "BAD_GATEWAY",
                "SERVICE_UNAVAILABLE", "GATEWAY_TIMEOUT"})
        void everyOtherErrorStatusIsMemberDomainError(HttpStatus status) {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID)).andRespond(withStatus(status));

            MemberProfileException e = expectFailure(() -> client.findMember(MEMBER_ID), ErrorCode.MEMBER_DOMAIN_ERROR);

            assertThat(e.getMessage()).contains(String.valueOf(status.value())).contains(MemberIds.forLog(MEMBER_ID)).doesNotContain(MEMBER_ID);
            assertThat(e.getCode().status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        }

        @Test
        void clientErrorKeepsTheOriginalExceptionAsCause() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID)).andRespond(withStatus(HttpStatus.FORBIDDEN));

            MemberProfileException e = expectFailure(() -> client.findMember(MEMBER_ID), ErrorCode.MEMBER_DOMAIN_ERROR);

            assertThat(e.getCause()).isInstanceOf(HttpClientErrorException.class);
        }
    }

    // ------------------------------------------------------------------------------- transport failures

    @Nested
    class Unreachable {

        @Test
        void connectionFailureIsMemberDomainUnreachable() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID))
                    .andRespond(withException(new ConnectException("Connection refused")));

            MemberProfileException e = expectFailure(() -> client.findMember(MEMBER_ID), ErrorCode.MEMBER_DOMAIN_UNREACHABLE);

            assertThat(e.getMessage()).contains(MemberIds.forLog(MEMBER_ID)).doesNotContain(MEMBER_ID);
            assertThat(e.getCause()).isInstanceOf(ResourceAccessException.class);
            assertThat(e.getCode().status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        }

        @Test
        void readTimeoutIsMemberDomainUnreachable() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID))
                    .andRespond(withException(new SocketTimeoutException("Read timed out")));

            MemberProfileException e = expectFailure(() -> client.findMember(MEMBER_ID), ErrorCode.MEMBER_DOMAIN_UNREACHABLE);

            assertThat(e.getMessage()).contains("Read timed out");
        }

        @Test
        void anyIoFailureIsMemberDomainUnreachable() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID))
                    .andRespond(withException(new IOException("Unexpected end of file from server")));

            expectFailure(() -> client.findMember(MEMBER_ID), ErrorCode.MEMBER_DOMAIN_UNREACHABLE);
        }

        @Test
        void aRealConnectionRefusedIsMemberDomainUnreachable() throws IOException {
            int closedPort;
            try (ServerSocket socket = new ServerSocket(0)) {
                closedPort = socket.getLocalPort();
            }
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofMillis(500));
            factory.setReadTimeout(Duration.ofMillis(500));
            String baseUrl = "http://127.0.0.1:" + closedPort + "/api/v1";
            RestMemberDomainClient real = new RestMemberDomainClient(
                    RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build(), properties(baseUrl));

            MemberProfileException e = expectFailure(() -> real.findMember(MEMBER_ID), ErrorCode.MEMBER_DOMAIN_UNREACHABLE);

            assertThat(e.getMessage()).contains(MemberIds.forLog(MEMBER_ID)).doesNotContain(MEMBER_ID);
            assertThat(e.getCause()).isInstanceOf(ResourceAccessException.class);
        }
    }

    // ------------------------------------------------------------------------------- unusable 200

    @Nested
    class Unusable200 {

        @Test
        void htmlInsteadOfJsonIsMemberDomainError() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID))
                    .andRespond(withSuccess("<html><body>login</body></html>", MediaType.TEXT_HTML));

            MemberProfileException e = expectFailure(() -> client.findMember(MEMBER_ID), ErrorCode.MEMBER_DOMAIN_ERROR);

            assertThat(e.getMessage()).contains(MemberIds.forLog(MEMBER_ID)).doesNotContain(MEMBER_ID);
            assertThat(e.getCause()).isInstanceOf(RestClientException.class);
        }

        @Test
        void brokenJsonIsMemberDomainError() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID))
                    .andRespond(withSuccess("{\"memberId\":\"HP0000001\",", MediaType.APPLICATION_JSON));

            MemberProfileException e = expectFailure(() -> client.findMember(MEMBER_ID), ErrorCode.MEMBER_DOMAIN_ERROR);

            assertThat(e.getCause()).isInstanceOf(RestClientException.class);
        }

        @Test
        void wrongTypeForAKnownFieldIsMemberDomainError() {
            server.expect(requestTo(BASE_URL + "/members/" + MEMBER_ID))
                    .andRespond(withSuccess("{\"memberId\":\"HP0000001\",\"age\":\"forty-two\"}", MediaType.APPLICATION_JSON));

            expectFailure(() -> client.findMember(MEMBER_ID), ErrorCode.MEMBER_DOMAIN_ERROR);
        }
    }
}
