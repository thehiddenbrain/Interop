package org.p32h.interop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.coverage.MemberCoverageRowRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The database behind the coverage view is down or slow: an answer that needs the member's row is 503
 * {@code COVERAGE_LOOKUP_UNAVAILABLE} with {@code Retry-After}, an answer that needs no row is unaffected, and nothing
 * the caller receives names the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CoverageLookupFailureTest {

    @MockitoBean MemberCoverageRowRepository coverageRows;
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;

    record Resp(int status, HttpHeaders headers, String body) {
    }

    /** The stored id of the stub member whose comment starts with the given words. */
    private String stubMemberId(String commentStart) throws Exception {
        try (var in = getClass().getResourceAsStream("/mmi-stub/members.json")) {
            for (JsonNode m : json.readTree(in)) {
                if (m.path("_comment").asText().startsWith(commentStart)) {
                    return m.path("memberId").asText();
                }
            }
        }
        throw new AssertionError("no stub member whose comment starts with: " + commentStart);
    }

    private Resp resolve(String memberId) {
        String body = json.writeValueAsString(Map.of("clientId", "ONYX", "clientType", "EXT", "requestId", "TEST-1",
                "memberId", memberId, "dateOfService", "2026-10-15"));
        return RestClient.builder().baseUrl("http://localhost:" + port).build().post().uri("/v1/interop/resolve")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> new Resp(res.getStatusCode().value(), res.getHeaders(),
                        new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private void assertUnavailable(Resp r) {
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.headers().getFirst("Retry-After")).isEqualTo("10");
        JsonNode body = json.readTree(r.body());
        assertThat(body.at("/error/code").asText()).isEqualTo("COVERAGE_LOOKUP_UNAVAILABLE");
        assertThat(body.at("/error/message").asText()).isEqualTo("the coverage lookup is unavailable");
        assertThat(body.at("/error/details/0/code").asText()).isEqualTo("UNAVAILABLE");
        assertThat(body.get("requestId").asText()).isEqualTo("TEST-1");
        assertThat(r.body().toLowerCase()).as("nothing names the database or carries the failure text")
                .doesNotContain("database", "oracle", "jdbc", "jpa", "hibernate", "transaction", "ods", "mmi", "connection refused");
    }

    @Test
    void aDatabaseThatCannotBeReachedIs503() throws Exception {
        when(coverageRows.findByMemberId(anyString()))
                .thenThrow(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction: connection refused"));
        assertUnavailable(resolve(stubMemberId("TMP (Tufts Medicare Preferred)")));
    }

    @Test
    void aQueryThatDoesNotAnswerInTimeIs503() throws Exception {
        when(coverageRows.findByMemberId(anyString())).thenThrow(new QueryTimeoutException("ORA-01013: connection refused by timeout"));
        assertUnavailable(resolve(stubMemberId("TMP (Tufts Medicare Preferred)")));
    }

    @Test
    void anAnswerThatNeedsNoRowIsUnaffected() {
        when(coverageRows.findByMemberId(anyString())).thenThrow(new CannotCreateTransactionException("down"));
        Resp r = resolve("NOSUCHMEMBER");
        assertThat(r.status()).isEqualTo(200);
        assertThat(json.readTree(r.body()).get("outcome").asText()).isEqualTo("NOT_FOUND");
        verify(coverageRows, never()).findByMemberId(anyString());
    }
}
