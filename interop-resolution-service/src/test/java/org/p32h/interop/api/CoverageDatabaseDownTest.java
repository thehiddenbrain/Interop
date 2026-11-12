package org.p32h.interop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.api.CoverageLookupFailureTest.assertUnavailable;
import static org.p32h.interop.api.CoverageLookupFailureTest.resolve;
import static org.p32h.interop.api.CoverageLookupFailureTest.stubMemberId;

import org.junit.jupiter.api.Test;
import org.p32h.interop.api.CoverageLookupFailureTest.Resp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * The database behind the coverage view is not there at all, and nothing is mocked: the pool, the transaction and the
 * repository are the real ones. The service starts and reports UP all the same, an answer that needs the member's row
 * is 503 {@code COVERAGE_LOOKUP_UNAVAILABLE}, and an answer that needs no row is unaffected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:tcp://localhost:1/none",       // nothing listens there
        "spring.datasource.hikari.connection-timeout=250",
        "spring.datasource.hikari.validation-timeout=250",
        "spring.sql.init.mode=never"})
@ActiveProfiles("test")
class CoverageDatabaseDownTest {

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;

    @Test
    void theServiceIsUpWithoutItsDatabase() {
        String health = RestClient.create("http://localhost:" + port).get().uri("/actuator/health").retrieve().body(String.class);
        assertThat(json.readTree(health).get("status").asText()).isEqualTo("UP");
    }

    @Test
    void anAnswerThatNeedsTheRowIs503() throws Exception {
        assertUnavailable(json, resolve(json, port, stubMemberId(json, "TMP (Tufts Medicare Preferred)")));
    }

    @Test
    void anAnswerThatNeedsNoRowIsUnaffected() {
        Resp r = resolve(json, port, "NOSUCHMEMBER");
        assertThat(r.status()).isEqualTo(200);
        assertThat(json.readTree(r.body()).get("outcome").asText()).isEqualTo("NOT_FOUND");
    }
}
