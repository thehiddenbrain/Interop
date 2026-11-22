package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** A badly formed request is refused before anything handles it, with a 4xx that repeats nothing of it. */
class RequestShapeFilterTest {

    private final MockEnvironment environment = new MockEnvironment().withProperty(RequestShapeFilter.MAX_REQUEST_SIZE, "1KB")
            .withProperty(RequestShapeFilter.MAX_FILE_SIZE, "1KB").withProperty(RequestShapeFilter.FILE_SIZE_THRESHOLD, "1KB");

    /** A fixed heap, so that no answer depends on the memory of the machine that runs the test. */
    private static final long HEAP = 1024L * 1024 * 1024;

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
        new RequestShapeFilter(environment, () -> HEAP).doFilter(request, response, new MockFilterChain(counting));
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
                .isEqualTo("Send a smaller body. To accept larger ones, raise spring.servlet.multipart.max-request-size and "
                        + "spring.servlet.multipart.max-file-size together in application.yml.");

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
        RequestShapeFilter unset = new RequestShapeFilter(new MockEnvironment(), () -> HEAP);
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
                            + "\"action\":\"Send a smaller body. To accept larger ones, raise spring.servlet.multipart.max-request-size and "
                            + "spring.servlet.multipart.max-file-size together in application.yml.\",\"fieldErrors\":[],\"issues\":[]}"
                    : "");
        }
    }

    @Test
    void aNegativeLimitIsNoLimitAsTheFrameworkDocumentsIt() throws Exception {
        // With -1 every body was refused as larger than "-1 in all", a GET stating a length of 0 included.
        environment.setProperty(RequestShapeFilter.MAX_REQUEST_SIZE, "-1");
        HttpServlet reader = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
                req.getInputStream().readAllBytes();
                res.setStatus(200);
            }
        };
        MockHttpServletRequest change = new MockHttpServletRequest("POST", "/api/config/writes");
        change.setContentType("application/json");
        change.setContent("{\"action\":\"lock\"}".getBytes(StandardCharsets.UTF_8));
        Result passed = send(change, reader);
        assertThat(passed.passedOn()).isTrue();
        assertThat(passed.response().getStatus()).isEqualTo(200);

        MockHttpServletRequest emptyRead = new MockHttpServletRequest("GET", "/api/mode");
        emptyRead.setContent(new byte[0]);
        assertThat(emptyRead.getContentLengthLong()).isZero();
        assertThat(send(emptyRead, null).passedOn()).isTrue();

        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/api/config/writes") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        chunked.setContent(new byte[64 * 1024]);
        assertThat(send(chunked, reader).response().getStatus()).isEqualTo(200);

        // No size limit is no heap: a body the memory cannot hold is still refused before it is read.
        MockHttpServletRequest large = new MockHttpServletRequest("POST", "/api/crd") {
            @Override
            public long getContentLengthLong() {
                return 100L * 1024 * 1024 * 1024;
            }
        };
        Result refused = send(large, null);
        assertThat(refused.passedOn()).isFalse();
        assertThat(refused.json().get("title").stringValue())
                .isEqualTo("This request is 102400 MB and PAVE runs with 1024 MB of memory, which is too little to read it.");
        // A heap with no stated bound refuses nothing.
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new RequestShapeFilter(environment, () -> Long.MAX_VALUE).doFilter(large, response, chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void aBodyTooLargeForTheHeapIsRefusedBeforeItIsReadWithTheSettingThatHelps() throws Exception {
        environment.setProperty(RequestShapeFilter.MAX_REQUEST_SIZE, "30MB");
        long fiveMb = 5L * 1024 * 1024;
        // 5 MB times twelve is 60 MB, more than a heap of 48 MB: the hunt's -Xmx64m case, with room for the rest of PAVE.
        for (long heapMb : new long[] {48, 64}) {
            MockHttpServletRequest upload = new MockHttpServletRequest("POST", "/api/config/collections") {
                @Override
                public long getContentLengthLong() {
                    return fiveMb;
                }
            };
            upload.setContentType("application/json");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            new RequestShapeFilter(environment, () -> heapMb * 1024 * 1024).doFilter(upload, response, chain);
            if (heapMb == 48) {
                assertThat(chain.getRequest()).as("heap " + heapMb).isNull();
                assertThat(response.getStatus()).isEqualTo(413);
                JsonNode json = JsonSafe.parse(response.getContentAsString()).node();
                assertThat(json.get("title").stringValue())
                        .isEqualTo("This request is 5 MB and PAVE runs with 48 MB of memory, which is too little to read it.");
                assertThat(json.get("action").stringValue()).isEqualTo("Start PAVE with -Xmx512m or more.");
                assertThat(json.get("kind").stringValue()).isEqualTo("VALIDATION");
            } else {
                assertThat(chain.getRequest()).as("heap " + heapMb).isNotNull();
            }
        }
        // The size is rounded up and the heap down, so neither makes the fit look better than it is.
        assertThat(RequestShapeFilter.tooLittleMemory(fiveMb + 1, 48L * 1024 * 1024 + 1023))
                .isEqualTo("This request is 6 MB and PAVE runs with 48 MB of memory, which is too little to read it.");
    }

    @Test
    void aBodyOverTheSizeLimitIsToldAboutThatLimitEvenWhenTheHeapIsSmallerToo() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/config/writes");
        request.setContent(new byte[2048]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequestShapeFilter(environment, () -> 1024L).doFilter(request, response, new MockFilterChain());
        assertThat(JsonSafe.parse(response.getContentAsString()).node().get("title").stringValue())
                .isEqualTo("The request is larger than this tool accepts, which is 1KB in all. Nothing was done.");
    }

    @Test
    void aFormUploadOverTheLimitNamesTheLimitForOneFileBesideTheOneForTheRequestAndBothSettings() throws Exception {
        environment.setProperty(RequestShapeFilter.MAX_REQUEST_SIZE, "30MB");
        environment.setProperty(RequestShapeFilter.MAX_FILE_SIZE, "25MB");
        MockHttpServletRequest upload = new MockHttpServletRequest("POST", "/api/config/collections") {
            @Override
            public long getContentLengthLong() {
                return 31L * 1024 * 1024;
            }
        };
        upload.setContentType("multipart/form-data; boundary=x");
        Result refused = send(upload, null);
        assertThat(refused.response().getStatus()).isEqualTo(413);
        assertThat(refused.json().get("title").stringValue())
                .isEqualTo("The upload is larger than this tool accepts, which is 25MB for one file and 30MB in all. Nothing was done.");
        assertThat(refused.json().get("action").stringValue()).isEqualTo("Send a smaller body. To accept larger ones, raise "
                + "spring.servlet.multipart.max-request-size and spring.servlet.multipart.max-file-size together in application.yml.");

        // The same body sent as JSON, as the screens send a file, has only the request's limit.
        MockHttpServletRequest json = new MockHttpServletRequest("POST", "/api/config/collections") {
            @Override
            public long getContentLengthLong() {
                return 31L * 1024 * 1024;
            }
        };
        json.setContentType("application/json");
        assertThat(send(json, null).json().get("title").stringValue())
                .isEqualTo("The request is larger than this tool accepts, which is 30MB in all. Nothing was done.");

        // With the file limit at or above the request's, the request's is the one that matters.
        environment.setProperty(RequestShapeFilter.MAX_FILE_SIZE, "40MB");
        assertThat(send(upload, null).json().get("title").stringValue())
                .isEqualTo("The request is larger than this tool accepts, which is 30MB in all. Nothing was done.");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theOperatorIsToldWhenAnUploadCanReachTheTemporaryFolder(CapturedOutput output) {
        new RequestShapeFilter(new MockEnvironment().withProperty(RequestShapeFilter.MAX_FILE_SIZE, "25MB")
                .withProperty(RequestShapeFilter.FILE_SIZE_THRESHOLD, "30MB"));
        new RequestShapeFilter(new MockEnvironment().withProperty(RequestShapeFilter.MAX_FILE_SIZE, "30MB")
                .withProperty(RequestShapeFilter.FILE_SIZE_THRESHOLD, "30MB"));
        assertThat(output.getAll()).doesNotContain("temporary folder");

        new RequestShapeFilter(new MockEnvironment().withProperty(RequestShapeFilter.MAX_FILE_SIZE, "-1")
                .withProperty(RequestShapeFilter.FILE_SIZE_THRESHOLD, "30MB"));
        assertThat(output.getAll()).contains("spring.servlet.multipart.max-file-size is -1 and spring.servlet.multipart.file-size-threshold "
                + "is 30MB, so an uploaded file larger than 30MB is written to the temporary folder while it is read, and a hard kill in the "
                + "middle of that upload leaves it there. To keep every upload in memory, set spring.servlet.multipart.max-file-size no "
                + "higher than spring.servlet.multipart.file-size-threshold.");
        new RequestShapeFilter(new MockEnvironment().withProperty(RequestShapeFilter.MAX_FILE_SIZE, "40MB")
                .withProperty(RequestShapeFilter.FILE_SIZE_THRESHOLD, "30MB"));
        assertThat(output.getAll()).contains("spring.servlet.multipart.max-file-size is 40MB and");
    }
}
