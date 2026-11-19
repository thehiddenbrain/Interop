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
            assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
            String policy = response.getHeader("Content-Security-Policy");
            assertThat(policy).contains("default-src 'self'").contains("script-src 'self'").contains("style-src 'self'")
                    .contains("connect-src 'self'").contains("object-src 'none'").contains("base-uri 'none'")
                    .contains("frame-ancestors 'none'");
            // No escape hatch that would let injected markup run or load from elsewhere.
            assertThat(policy).doesNotContain("unsafe-inline").doesNotContain("unsafe-eval").doesNotContain("*").doesNotContain("http");
        }
    }
}
