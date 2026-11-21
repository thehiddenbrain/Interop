package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ResponseHeadersFilterTest {

    private static MockHttpServletResponse get(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new ResponseHeadersFilter().doFilter(new MockHttpServletRequest("GET", path), response, chain);
        assertThat(chain.getRequest()).isNotNull();
        return response;
    }

    @Test
    void nothingThisToolServesIsStoredByTheBrowserWhateverTheAddress() throws Exception {
        // A lookup answers with a member's name and date of birth. The browser's disk cache is storage too.
        // The rule is not "an API response", because the framework also finds an API controller for the
        // spellings at the end of this list, and a test on the address as written would miss them.
        for (String path : new String[] {"/api/members/S123456789", "/api/runs", "/api/mode", "/api/crd", "/", "/index.html", "/js/app.js",
                "/css/pave.css", "/%61pi/members/S123456789", "/ap%69/runs", "/api;x=1/members/S123456789"}) {
            assertThat(get(path).getHeader("Cache-Control")).as(path).isEqualTo("no-store");
        }
    }

    @Test
    void everyResponseCarriesTheHeadersThatKeepUntrustedTextFromRunningOrCallingOut() throws Exception {
        for (String path : new String[] {"/", "/api/mode"}) {
            MockHttpServletResponse response = get(path);
            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            // same-origin: no address of this tool goes to another site, and the tool's own form
            // post still states its origin instead of null.
            assertThat(response.getHeader("Referrer-Policy")).isEqualTo("same-origin");
            assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
            String policy = response.getHeader("Content-Security-Policy");
            assertThat(policy).contains("default-src 'self'").contains("script-src 'self'").contains("style-src 'self'")
                    .contains("connect-src 'self'").contains("object-src 'none'").contains("base-uri 'none'")
                    .contains("frame-ancestors 'none'");
            // No escape hatch that would let injected markup run or load from elsewhere.
            assertThat(policy).doesNotContain("unsafe-inline").doesNotContain("unsafe-eval").doesNotContain("*").doesNotContain("http");
        }
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void anythingThrownBeneathIsAnsweredInTheSharedShapeWithAReferenceAndNoMessage(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        for (Exception thrown : new Exception[] {new IllegalArgumentException("Invalid encoded sequence \"%S123456789\""),
            new jakarta.servlet.ServletException("Request processing failed: Zzlastname 1961-07-23"), new java.io.IOException("S123456789")}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setHeader("Content-Disposition", "attachment; filename=\"pave-runs-all.csv\"");
            new ResponseHeadersFilter().doFilter(new MockHttpServletRequest("GET", "/api/members/S123456789"), response, (req, res) -> {
                sneaky(thrown);
            });
            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(response.getContentType()).startsWith("application/json");
            tools.jackson.databind.JsonNode body = org.p32h.interop.epa.pave.model.JsonSafe.parse(response.getContentAsString()).node();
            assertThat(body.get("kind").stringValue()).isEqualTo("INTERNAL");
            assertThat(body.get("title").stringValue()).isEqualTo("This tool hit an internal error and could not complete the request.");
            String reference = body.get("reference").stringValue();
            assertThat(reference).isNotBlank();
            // The cause is named by type, as the first line of the log entry names it, and by no message:
            // these exceptions are not among those whose message may be given.
            assertThat(body.get("cause").stringValue()).isEqualTo(thrown.getClass().getName());
            assertThat(response.getContentAsString()).doesNotContain("S123456789").doesNotContain("Zzlastname").doesNotContain("\tat ")
                    .doesNotContain("    at ");
            // The answer is a new one: what the handler had set for its own answer is gone, and the five headers are back.
            assertThat(response.getHeader("Content-Disposition")).isNull();
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getHeader("Content-Security-Policy")).isEqualTo(ResponseHeadersFilter.CONTENT_SECURITY_POLICY);
            assertThat(output.getAll()).contains(reference).contains(thrown.getClass().getName());
        }
        assertThat(output.getAll()).doesNotContain("S123456789").doesNotContain("Zzlastname").doesNotContain("1961-07-23");
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void aBrowserThatWentAwayIsNotAFaultOfThisTool(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ResponseHeadersFilter().doFilter(new MockHttpServletRequest("GET", "/api/runs"), response, (req, res) -> {
            sneaky(new org.springframework.web.context.request.async.AsyncRequestNotUsableException("gone",
                    new org.apache.catalina.connector.ClientAbortException()));
        });
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(output.getAll()).doesNotContain("Unhandled").doesNotContain("ERROR");
    }

    @Test
    void theErrorDispatchCarriesTheHeadersToo() throws Exception {
        MockHttpServletRequest error = new MockHttpServletRequest("GET", "/error");
        error.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new ResponseHeadersFilter().doFilter(error, response, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("same-origin");
    }

    /** What the tests below throw from beneath the filter: the two kinds, each with a message that names the placeholder member. */
    private static Throwable[] thrownWithAMemberId() {
        return new Throwable[] {new ExceptionInInitializerError(new IllegalStateException("no plan for S123456789")),
            new RuntimeException("no plan for S123456789")};
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void anErrorDispatchInsideAnotherGetsTheSameLastWord(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        // The filter has already run for this request, so the framework hands the error dispatch to
        // the nested path. What is thrown there would otherwise go on to the server, which logs it
        // with its message ("Exception Processing ErrorPage").
        ResponseHeadersFilter filter = new ResponseHeadersFilter();
        for (Throwable thrown : thrownWithAMemberId()) {
            MockHttpServletRequest error = new MockHttpServletRequest("GET", "/error");
            error.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
            error.setAttribute(ResponseHeadersFilter.class.getName() + org.springframework.web.filter.OncePerRequestFilter.ALREADY_FILTERED_SUFFIX,
                    Boolean.TRUE);
            MockHttpServletResponse response = new MockHttpServletResponse();
            org.assertj.core.api.Assertions.assertThatCode(() -> filter.doFilter(error, response, (req, res) -> sneaky(thrown)))
                    .doesNotThrowAnyException();
            assertThat(response.getStatus()).isEqualTo(500);
            tools.jackson.databind.JsonNode body = org.p32h.interop.epa.pave.model.JsonSafe.parse(response.getContentAsString()).node();
            String reference = body.get("reference").stringValue();
            String cause = org.p32h.interop.epa.pave.service.error.Faults.cause(thrown);
            assertThat(body.get("cause").stringValue()).isEqualTo(cause);
            assertThat(output.getAll()).contains("Unhandled exception at the edge, reference " + reference + ": " + cause);
            assertThat(output.getAll().split("reference " + reference, -1)).hasSize(2);
        }
        assertThat(output.getAll()).doesNotContain("S123456789").doesNotContain("no plan").doesNotContain("Caused by:");
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void anAnswerThatCannotBeWrittenIsNamedByItsTypesAndNothingGoesOnToTheServer(org.springframework.boot.test.system.CapturedOutput output) {
        // The answer itself fails. Thrown on, that would reach the server, which logs it with its
        // message ("Servlet.service() ... threw exception").
        for (Throwable thrown : thrownWithAMemberId()) {
            MockHttpServletResponse response = new MockHttpServletResponse() {
                @Override
                public java.io.PrintWriter getWriter() {
                    throw new IllegalStateException("writer for S123456789");
                }
            };
            org.assertj.core.api.Assertions.assertThatCode(() -> new ResponseHeadersFilter().doFilter(
                    new MockHttpServletRequest("GET", "/api/members/S123456789"), response, (req, res) -> sneaky(thrown)))
                    .doesNotThrowAnyException();
        }
        String all = output.getAll();
        java.util.regex.Matcher entry = java.util.regex.Pattern.compile(
                "Unhandled exception at the edge, reference ([0-9a-f-]{36}): ").matcher(all);
        int entries = 0;
        while (entry.find()) {
            entries++;
            assertThat(all).contains("The answer under reference " + entry.group(1) + " could not be written: java.lang.IllegalStateException");
        }
        assertThat(entries).isEqualTo(2);
        assertThat(all).doesNotContain("S123456789").doesNotContain("no plan").doesNotContain("writer for").doesNotContain("Caused by:");
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneaky(Throwable error) throws T {
        throw (T) error;
    }
}
