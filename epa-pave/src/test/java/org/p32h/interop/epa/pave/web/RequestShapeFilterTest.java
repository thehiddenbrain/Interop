package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** A badly formed request is refused before anything handles it, with a 4xx that repeats nothing of it. */
class RequestShapeFilterTest {

    private final MockEnvironment environment = new MockEnvironment().withProperty(RequestShapeFilter.MAX_REQUEST_SIZE, "1KB");

    private record Result(MockHttpServletResponse response, boolean passedOn) {

        JsonNode json() throws Exception {
            return JsonSafe.parse(response.getContentAsString()).node();
        }
    }

    private Result send(MockHttpServletRequest request, HttpServlet servlet) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        HttpServlet counting = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) throws java.io.IOException, jakarta.servlet.ServletException {
                reached.set(true);
                if (servlet != null) {
                    servlet.service(req, res);
                }
            }
        };
        new RequestShapeFilter(environment).doFilter(request, response, new MockFilterChain(counting));
        return new Result(response, reached.get());
    }

    private Result send(String method, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        return send(request, null);
    }

    @Test
    void anAddressWithASemicolonIsRefusedWhereverItIsBecauseThisToolTakesNoPathParameters() throws Exception {
        for (String uri : new String[] {"/api/mode;x=%zz", "/api;x=1/mode", "/api/members/S123456789;x=1", "/api/mode;PAVESESSION=ABC",
            "/api/members/S123456789;", "/;"}) {
            Result result = send("GET", uri);
            assertThat(result.passedOn()).as(uri).isFalse();
            assertThat(result.response().getStatus()).as(uri).isEqualTo(400);
            assertThat(result.json().get("title").stringValue())
                    .isEqualTo("The address holds a semicolon that is not percent-encoded. This tool takes none, so nothing was done.");
            assertThat(result.response().getContentAsString()).doesNotContain("S123456789").doesNotContain("PAVESESSION").doesNotContain("%zz");
            assertThat(result.response().getHeader("Cache-Control")).isEqualTo("no-store");
        }
        // Percent-encoded, a semicolon is part of a value.
        assertThat(send("GET", "/api/members/S123456789%3Bx=1").passedOn()).isTrue();
    }

    @Test
    void anAddressWithAPercentSignThatStartsNoEscapeIsRefused() throws Exception {
        for (String uri : new String[] {"/api/%zz", "/api/members/S12%", "/api/members/S12%4", "/api/members/S12%G1"}) {
            Result result = send("GET", uri);
            assertThat(result.passedOn()).as(uri).isFalse();
            assertThat(result.response().getStatus()).as(uri).isEqualTo(400);
            assertThat(result.json().get("title").stringValue())
                    .isEqualTo("The address holds a percent sign that does not start a valid escape, so nothing was done.");
        }
        assertThat(send("GET", "/api/members/S12%2534%20x%aF").passedOn()).isTrue();
        assertThat(RequestShapeFilter.wellEscaped("/a%20b%2f")).isTrue();
        assertThat(RequestShapeFilter.wellEscaped("/a%2")).isFalse();
    }

    @Test
    void aContentTypeThatIsAWildcardIsA415() throws Exception {
        for (String type : new String[] {"*/*", "application/*", "text/*", "*/*; charset=UTF-8"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/crd");
            request.setContentType(type);
            Result result = send(request, null);
            assertThat(result.passedOn()).as(type).isFalse();
            assertThat(result.response().getStatus()).as(type).isEqualTo(415);
        }
        for (String type : new String[] {"application/json", "application/fhir+json; charset=UTF-8", "multipart/form-data; boundary=x", "garbage"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/crd");
            request.setContentType(type);
            assertThat(send(request, null).passedOn()).as(type).isTrue();
        }
    }

    @Test
    void aBodyThatStatesALengthOverTheLimitIsRefusedBeforeItIsRead() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/config/writes");
        request.setContentType("application/json");
        request.setContent(new byte[1025]);
        Result result = send(request, null);
        assertThat(result.passedOn()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo(413);
        assertThat(result.json().get("title").stringValue())
                .isEqualTo("The request is larger than this tool accepts, which is 1KB in all. Nothing was done.");
        assertThat(result.json().get("action").stringValue())
                .isEqualTo("Send a smaller body. To accept larger ones, raise spring.servlet.multipart.max-request-size in application.yml.");

        MockHttpServletRequest atLimit = new MockHttpServletRequest("POST", "/api/config/writes");
        atLimit.setContent(new byte[1024]);
        assertThat(send(atLimit, null).passedOn()).isTrue();
    }

    @Test
    void aBodyWithNoStatedLengthIsCountedAsItIsReadAndRefusedPastTheLimit() throws Exception {
        HttpServlet reader = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
                req.getInputStream().readAllBytes();
                res.setStatus(200);
            }
        };
        MockHttpServletRequest over = new MockHttpServletRequest("POST", "/api/config/writes") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        over.setContent(new byte[1025]);
        Result refused = send(over, reader);
        assertThat(refused.response().getStatus()).isEqualTo(413);
        assertThat(refused.json().get("title").stringValue()).startsWith("The request is larger than this tool accepts");

        MockHttpServletRequest under = new MockHttpServletRequest("POST", "/api/config/writes") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        under.setContent("{\"action\":\"lock\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(send(under, reader).response().getStatus()).isEqualTo(200);
    }

    @Test
    void withTheLimitUnsetTheFilterHoldsToTheValueApplicationYmlShipsWith() throws Exception {
        RequestShapeFilter unset = new RequestShapeFilter(new MockEnvironment());
        for (long length : new long[] {30L * 1024 * 1024, 30L * 1024 * 1024 + 1}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/crd") {
                @Override
                public long getContentLengthLong() {
                    return length;
                }
            };
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            unset.doFilter(request, response, chain);
            assertThat(response.getStatus()).as("length " + length).isEqualTo(length > 30L * 1024 * 1024 ? 413 : 200);
            assertThat(response.getContentAsString()).as("length " + length).isEqualTo(length > 30L * 1024 * 1024
                    ? "{\"kind\":\"VALIDATION\",\"title\":\"The request is larger than this tool accepts, which is 30MB in all. Nothing was done.\","
                            + "\"action\":\"Send a smaller body. To accept larger ones, raise spring.servlet.multipart.max-request-size in "
                            + "application.yml.\",\"fieldErrors\":[],\"issues\":[]}"
                    : "");
        }
    }
}
