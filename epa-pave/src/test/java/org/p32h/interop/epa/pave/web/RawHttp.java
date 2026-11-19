package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import org.p32h.interop.epa.pave.model.JsonSafe;
import tools.jackson.databind.JsonNode;

/**
 * Speaks to the running server over a socket on the loopback interface, with each request written
 * byte for byte, so that nothing between the test and the server rewrites it. Used where what is
 * tested is what the server sends: its status line, its headers and its body.
 */
final class RawHttp {

    private RawHttp() {
    }

    /** One answer, with its head read as written and its body decoded from chunks when it was sent in chunks. */
    record Answer(int status, String head, Map<String, String> headers, String body) {

        JsonNode json() {
            return JsonSafe.parse(body).node();
        }

        String title() {
            return JsonSafe.text(json(), "title");
        }

        String header(String name) {
            return headers.get(name);
        }

        /** The five headers every answer of this tool carries. */
        void assertTheFiveHeaders() {
            assertThat(header("Cache-Control")).as("Cache-Control").isEqualTo("no-store");
            assertThat(header("X-Content-Type-Options")).as("X-Content-Type-Options").isEqualTo("nosniff");
            assertThat(header("X-Frame-Options")).as("X-Frame-Options").isEqualTo("DENY");
            assertThat(header("Referrer-Policy")).as("Referrer-Policy").isEqualTo("same-origin");
            assertThat(header("Content-Security-Policy")).as("Content-Security-Policy").startsWith("default-src 'self';");
        }
    }

    /** A request with no body: the request line, then the given header lines, then Connection: close. */
    static Answer get(int port, String requestLine, String... headers) throws IOException {
        return send(port, head(requestLine, headers), new byte[0]);
    }

    static byte[] head(String requestLine, String... headers) {
        StringBuilder out = new StringBuilder(requestLine).append("\r\n");
        for (String header : headers) {
            out.append(header).append("\r\n");
        }
        return out.append("Connection: close\r\n\r\n").toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    static Answer send(int port, byte[] head, byte[] body) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(60000);
            OutputStream out = socket.getOutputStream();
            out.write(head);
            try {
                out.write(body);
                out.flush();
            } catch (IOException closedEarly) {
                // The server may answer and close before reading a body it refuses. The answer is still there to read.
            }
            return read(socket.getInputStream());
        }
    }

    static Answer read(InputStream in) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        try {
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                all.write(buffer, 0, read);
            }
        } catch (IOException reset) {
            // A connection reset after the answer was sent; what was read is the answer.
        }
        // One character per byte, so a place found in the text is the same place in the bytes.
        String raw = all.toString(StandardCharsets.ISO_8859_1);
        int endOfHead = raw.indexOf("\r\n\r\n");
        assertThat(endOfHead).as("a complete response head").isPositive();
        String head = raw.substring(0, endOfHead);
        String[] lines = head.split("\r\n");
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.put(lines[i].substring(0, colon).strip(), lines[i].substring(colon + 1).strip());
        }
        String body = raw.substring(endOfHead + 4);
        if ("chunked".equalsIgnoreCase(headers.get("Transfer-Encoding"))) {
            StringBuilder whole = new StringBuilder();
            int at = 0;
            while (true) {
                int endOfSize = body.indexOf("\r\n", at);
                int size = Integer.parseInt(body.substring(at, endOfSize).strip(), 16);
                if (size == 0) {
                    break;
                }
                whole.append(body, endOfSize + 2, endOfSize + 2 + size);
                at = endOfSize + 2 + size + 2;
            }
            body = whole.toString();
        }
        return new Answer(Integer.parseInt(lines[0].split(" ")[1]), head, headers,
                new String(body.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
    }

    /** A multipart body of the given parts, each a name, a file name or null for a plain field, and the bytes. */
    static byte[] multipart(String boundary, Object... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < parts.length; i += 3) {
            String disposition = "Content-Disposition: form-data; name=\"" + parts[i] + "\""
                    + (parts[i + 1] == null ? "" : "; filename=\"" + parts[i + 1] + "\"");
            out.writeBytes(("--" + boundary + "\r\n" + disposition + "\r\n" + (parts[i + 1] == null ? "" : "Content-Type: text/csv\r\n")
                    + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes((byte[]) parts[i + 2]);
            out.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    /** The head of a multipart POST to the address, for a body of the given length. */
    static byte[] multipartHead(String address, int port, String boundary, long length, String... headers) {
        String[] all = new String[headers.length + 3];
        all[0] = "Host: localhost:" + port;
        all[1] = "Content-Type: multipart/form-data; boundary=" + boundary;
        all[2] = "Content-Length: " + length;
        System.arraycopy(headers, 0, all, 3, headers.length);
        return head("POST " + address + " HTTP/1.1", all);
    }
}
