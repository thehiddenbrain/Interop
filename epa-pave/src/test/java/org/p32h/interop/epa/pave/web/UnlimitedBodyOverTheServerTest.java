package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.web.RawHttp.Answer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The framework documents -1 for {@code spring.servlet.multipart.max-request-size} as no limit. The
 * edge's own limit on a body reads it the same way: before, it read -1 as a limit of -1 bytes and
 * refused every change, and a read that stated a length of 0, as larger than "-1 in all".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.servlet.multipart.max-request-size=-1")
class UnlimitedBodyOverTheServerTest {

    private static final int MB = 1024 * 1024;

    @LocalServerPort
    int port;

    private Answer post(String address, byte[] body) throws IOException {
        return RawHttp.send(port, RawHttp.head("POST " + address + " HTTP/1.1", "Host: localhost:" + port, "Content-Type: application/json",
                "Content-Length: " + body.length), body);
    }

    @Test
    void aChangeIsCarriedOut() throws IOException {
        Answer lock = post("/api/config/writes", "{\"action\":\"lock\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(lock.status()).as(lock.body()).isEqualTo(200);
    }

    @Test
    void aReadThatStatesALengthOfZeroIsAnswered() throws IOException {
        Answer read = RawHttp.get(port, "GET /api/mode HTTP/1.1", "Host: localhost:" + port, "Content-Length: 0");
        assertThat(read.status()).as(read.body()).isEqualTo(200);
    }

    @Test
    void aBodyLargerThanTheShippedLimitIsRead() throws IOException {
        // 31MB of blanks before the JSON: over the 30MB that application.yml ships with.
        byte[] lead = new byte[31 * MB];
        Arrays.fill(lead, (byte) ' ');
        byte[] json = "{\"action\":\"lock\"}".getBytes(StandardCharsets.UTF_8);
        byte[] body = Arrays.copyOf(lead, lead.length + json.length);
        System.arraycopy(json, 0, body, lead.length, json.length);
        Answer lock = post("/api/config/writes", body);
        assertThat(lock.status()).as(lock.body()).isEqualTo(200);
    }
}
