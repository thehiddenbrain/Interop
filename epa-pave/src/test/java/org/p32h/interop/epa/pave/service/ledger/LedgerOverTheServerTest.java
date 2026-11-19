package org.p32h.interop.epa.pave.service.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * What reaches the ledger file when a run is sent to the running server over a socket, on the
 * mock profile the tool starts on. The findings of the member data audit that are about the file
 * on disk are shown here end to end: what the browser sends, what the server answers, and the
 * bytes and permissions of the file that results.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerOverTheServerTest {

    @TempDir
    static Path home;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("tracking.directory", () -> home.resolve("run-history").toString());
        registry.add("config-store.directory", () -> home.resolve("config-store").toString());
        registry.add("app.mock-latency-enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    private record Answer(int status, String body) {

        JsonNode json() {
            return JsonSafe.parse(body).node();
        }
    }

    private Answer post(String address, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        byte[] received;
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(20000);
            socket.getOutputStream().write(("POST " + address + " HTTP/1.1\r\nHost: localhost:" + port + "\r\nContent-Type: application/json\r\n"
                    + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
            received = socket.getInputStream().readAllBytes();
        }
        String raw = new String(received, StandardCharsets.ISO_8859_1);
        int endOfHead = raw.indexOf("\r\n\r\n");
        assertThat(endOfHead).as("a complete response head").isPositive();
        String head = raw.substring(0, endOfHead);
        String rest = raw.substring(endOfHead + 4);
        if (head.toLowerCase(java.util.Locale.ROOT).contains("transfer-encoding: chunked")) {
            StringBuilder whole = new StringBuilder();
            int at = 0;
            while (true) {
                int endOfSize = rest.indexOf("\r\n", at);
                int size = Integer.parseInt(rest.substring(at, endOfSize).strip(), 16);
                if (size == 0) {
                    break;
                }
                whole.append(rest, endOfSize + 2, endOfSize + 2 + size);
                at = endOfSize + 2 + size + 2;
            }
            rest = whole.toString();
        }
        return new Answer(Integer.parseInt(head.split(" ")[1]), new String(rest.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
    }

    private static String run(String memberId, String lineOfBusiness, String extra) {
        return "{\"member\":{\"memberId\":\"" + memberId + "\",\"lineOfBusiness\":\"" + lineOfBusiness + "\"},"
                + "\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":[{\"code\":\"95810\"}]" + extra + "}";
    }

    private static String ledgerText() throws IOException {
        Path folder = home.resolve("run-history");
        if (!Files.isDirectory(folder)) {
            return "";
        }
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.sorted().toList()) {
                all.append(Files.readString(file));
            }
        }
        return all.toString();
    }

    @Test
    void aRunWhoseNoteTesterReferenceOrExpectationIsTooLongIsRefusedAndNothingIsWritten() throws IOException {
        String pasted = "{\\\"resourceType\\\":\\\"Patient\\\",\\\"name\\\":[{\\\"family\\\":\\\"Zzlastname-Placeholder\\\"}],"
                + "\\\"birthDate\\\":\\\"1961-07-23\\\",\\\"pad\\\":\\\"" + "z".repeat(20000) + "\\\"}";
        String before = ledgerText();

        Answer note = post("/api/crd", run("S123456789", "Medicare", ",\"note\":\"" + pasted + "\""));
        Answer testerRef = post("/api/crd", run("S123456789", "Medicare", ",\"testerRef\":\"Zzlastname 1961-07-23 " + "z".repeat(5000) + "\""));
        Answer expected = post("/api/crd", run("S123456789", "Medicare",
                ",\"expected\":\"Zzlastname Zzfirstname 1961-07-23 zzz: pa-needed=auth-needed\""));

        for (Answer answer : List.of(note, testerRef, expected)) {
            assertThat(answer.status()).isEqualTo(422);
            assertThat(answer.body()).doesNotContain("Zzlastname").doesNotContain("1961-07-23");
        }
        assertThat(note.json().at("/fieldErrors/0/field").asString()).isEqualTo("note");
        assertThat(testerRef.json().at("/fieldErrors/0/field").asString()).isEqualTo("testerRef");
        assertThat(expected.json().at("/fieldErrors/0/field").asString()).isEqualTo("expected");
        assertThat(ledgerText()).isEqualTo(before);
    }

    @Test
    void aLineOfBusinessTheToolDoesNotKnowAndAnIdThatIsNotAPlaceholderReachTheFileOnlyAsTheirLengthAndAToken() throws IOException {
        Answer person = post("/api/crd", run("Zzlastname Zzfirstname 1961-07-23 77 Zzaddress Lane", "Zzlastname, Zzfirstname", ""));
        Answer known = post("/api/crd", run("S12345678   01", " medicare ", ",\"testerRef\":\"QA-17\""));

        assertThat(person.status()).isEqualTo(200);
        assertThat(known.status()).isEqualTo(200);
        String file = ledgerText();
        assertThat(file).doesNotContain("Zzlastname").doesNotContain("Zzfirstname").doesNotContain("1961-07-23").doesNotContain("Zzaddress");
        assertThat(file).contains("\"lineOfBusiness\":\"(line of business not on the known list, 23 characters)\"")
                .contains("\"lineOfBusiness\":\"medicare\"").contains("\"memberId\":\"S1234567801\"").contains("\"memberToken\":\"");
        // The screen is told what the line holds, which is the same record.
        assertThat(person.json().at("/ledger/record/lineOfBusiness").asString()).startsWith("(line of business not on the known list");
        assertThat(person.json().at("/ledger/record/memberId").isMissingNode()).isTrue();
    }

    @Test
    void theRunHistoryFolderAndItsFileAreForTheirOwnerOnly() throws IOException {
        assumeTrue(OwnerOnly.supported(), "this file system has no owner permissions to check");
        assertThat(post("/api/crd", run("P32H-NO-AUTH", "Medicare", "")).status()).isEqualTo(200);

        Path folder = home.resolve("run-history");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder))).isEqualTo("rwx------");
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.toList()) {
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).as(file.getFileName().toString())
                        .isEqualTo("rw-------");
            }
        }
    }
}
