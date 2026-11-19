package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Other web pages are kept away from the tool: by the host a request names, and for a change by
 * the origin a browser states. Both hold for every address.
 */
class ApiGuardFilterTest {

    private final MockEnvironment environment = new MockEnvironment();
    private AppProperties app = AppProperties.defaults();

    /** Sends one request through the filter. Returns the response, with status 200 when the filter let it through. */
    private MockHttpServletResponse send(String method, String path, String... headers) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        for (int i = 0; i < headers.length; i += 2) {
            request.addHeader(headers[i], headers[i + 1]);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new ApiGuardFilter(app, environment, TestMvc.mapper()).doFilter(request, response, chain);
        // The chain holds the request only when the filter passed it on.
        assertThat(chain.getRequest() != null).as("passed on").isEqualTo(response.getStatus() == 200);
        return response;
    }

    private boolean passes(String method, String path, String... headers) throws Exception {
        return send(method, path, headers).getStatus() == 200;
    }

    @Test
    void anApiRequestMustBeAddressedToThisMachineByAKnownName() throws Exception {
        for (String host : new String[] {"localhost:8057", "LOCALHOST:8057", "localhost", "127.0.0.1:8057", "[::1]:8057", "[::1]"}) {
            assertThat(passes("GET", "/api/mode", "Host", host)).as(host).isTrue();
        }
        // A page served from another name that resolves to this machine sends that other name.
        for (String host : new String[] {"attacker.example:8057", "localhost.attacker.example", "127.0.0.1.attacker.example:8057",
                "10.1.2.3:8057", "[::2]:8057", "[::1"}) {
            assertThat(passes("GET", "/api/mode", "Host", host)).as(host).isFalse();
        }
        assertThat(passes("GET", "/api/mode")).as("no Host header").isFalse();
    }

    @Test
    void aRefusalIsInTheSharedErrorShapeAndSaysWhatToDo() throws Exception {
        MockHttpServletResponse response = send("GET", "/api/members/S123456789", "Host", "attacker.example:8057");
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode body = JsonSafe.parse(response.getContentAsString()).node();
        assertThat(body.get("kind").stringValue()).isEqualTo("VALIDATION");
        assertThat(body.get("title").stringValue()).isEqualTo("This request was addressed to a host name this tool does not answer to.");
        assertThat(body.get("action").stringValue()).contains("http://localhost").contains("app.allowed-hosts");
        // Nothing of the request is repeated back: the path here holds a member ID.
        assertThat(response.getContentAsString()).doesNotContain("S123456789").doesNotContain("attacker");
    }

    @Test
    void theOperatorCanNameThisMachineOrBindItToAnAddress() throws Exception {
        app = AppProperties.of(Map.of("allowed-hosts", "qa-laptop.example, [fd00::5]"));
        assertThat(passes("GET", "/api/mode", "Host", "qa-laptop.example:8057")).isTrue();
        assertThat(passes("GET", "/api/mode", "Host", "QA-Laptop.Example")).isTrue();
        assertThat(passes("GET", "/api/mode", "Host", "[fd00::5]:8057")).isTrue();
        assertThat(passes("GET", "/api/mode", "Host", "other.example:8057")).isFalse();
        assertThat(passes("GET", "/api/mode", "Host", "localhost:8057")).isTrue();

        app = AppProperties.defaults();
        environment.setProperty("server.address", "10.1.2.3");
        assertThat(passes("GET", "/api/mode", "Host", "10.1.2.3:8057")).isTrue();
        // Bound to every address: that is not a name anyone can type, so it allows nothing by itself.
        environment.setProperty("server.address", "0.0.0.0");
        assertThat(passes("GET", "/api/mode", "Host", "10.1.2.3:8057")).isFalse();
        assertThat(passes("GET", "/api/mode", "Host", "0.0.0.0:8057")).isFalse();

        // The check is switched off only by asking for exactly that.
        app = AppProperties.of(Map.of("allowed-hosts", "*"));
        assertThat(passes("GET", "/api/mode", "Host", "anything.example")).isTrue();
        assertThat(passes("POST", "/api/crd", "Host", "anything.example", "Origin", "http://other.example")).isFalse();
    }

    @Test
    void aChangeMustComeFromThisToolsOwnPages() throws Exception {
        String host = "localhost:8057";
        for (String method : new String[] {"POST", "PUT", "DELETE", "PATCH"}) {
            assertThat(passes(method, "/api/crd", "Host", host, "Origin", "http://localhost:8057")).as(method).isTrue();
            assertThat(passes(method, "/api/crd", "Host", host, "Origin", "http://localhost:8057", "Sec-Fetch-Site", "same-origin")).isTrue();
            // Another tool on another port of this machine is another origin.
            assertThat(passes(method, "/api/crd", "Host", host, "Origin", "http://localhost:8080")).as(method).isFalse();
            assertThat(passes(method, "/api/crd", "Host", host, "Origin", "https://attacker.example")).as(method).isFalse();
            assertThat(passes(method, "/api/crd", "Host", host, "Origin", "http://127.0.0.1:8057")).as(method).isFalse();
            // A sandboxed frame or a local file.
            assertThat(passes(method, "/api/crd", "Host", host, "Origin", "null")).as(method).isFalse();
            assertThat(passes(method, "/api/crd", "Host", host, "Origin", "not a url")).as(method).isFalse();
            assertThat(passes(method, "/api/crd", "Host", host, "Sec-Fetch-Site", "cross-site")).as(method).isFalse();
            assertThat(passes(method, "/api/crd", "Host", host, "Sec-Fetch-Site", "same-site")).as(method).isFalse();
            assertThat(passes(method, "/api/crd", "Host", host, "Sec-Fetch-Site", "none")).as(method).isTrue();
        }
        MockHttpServletResponse refused = send("POST", "/api/pas/submit", "Host", host, "Origin", "https://attacker.example");
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(JsonSafe.parse(refused.getContentAsString()).node().get("title").stringValue())
                .isEqualTo("This request came from a page that is not part of this tool, so it was not carried out.");
    }

    @Test
    void aClientThatIsNotABrowserStatesNoOriginAndIsLetThrough() throws Exception {
        // curl, a script, a vendor's server: no page is involved, so there is no other site to come from.
        assertThat(passes("POST", "/api/crd", "Host", "localhost:8057")).isTrue();
        assertThat(passes("DELETE", "/api/pas/subscriptions/sub-1", "Host", "127.0.0.1:8057")).isTrue();
    }

    @Test
    void theOriginIsComparedWithItsDefaultPortFilledIn() throws Exception {
        assertThat(passes("POST", "/api/crd", "Host", "localhost", "Origin", "http://localhost")).isTrue();
        assertThat(passes("POST", "/api/crd", "Host", "localhost:80", "Origin", "http://localhost")).isTrue();
        assertThat(passes("POST", "/api/crd", "Host", "localhost:8057", "Origin", "http://localhost")).isFalse();
        assertThat(passes("POST", "/api/crd", "Host", "localhost:443", "Origin", "https://localhost")).isTrue();
        assertThat(passes("POST", "/api/crd", "Host", "[::1]:8057", "Origin", "http://[::1]:8057")).isTrue();
        assertThat(passes("POST", "/api/crd", "Host", "localhost:x", "Origin", "http://localhost:8057")).isFalse();
    }

    @Test
    void aReadIsNotHeldToTheOriginCheckBecauseABrowserWillNotShowItsAnswerToAnotherSite() throws Exception {
        // No cross-origin header is ever sent, so another site's script cannot read the response.
        assertThat(passes("GET", "/api/runs", "Host", "localhost:8057", "Origin", "https://attacker.example")).isTrue();
        assertThat(passes("HEAD", "/api/runs", "Host", "localhost:8057", "Sec-Fetch-Site", "cross-site")).isTrue();
        // The host check still applies to it.
        assertThat(passes("GET", "/api/runs", "Host", "attacker.example", "Origin", "http://attacker.example")).isFalse();
    }

    @Test
    void theNotificationReceiverIsReachedUnderWhateverNameTheNetworkGivesThisMachine() throws Exception {
        assertThat(passes("POST", "/api/pas/notifications", "Host", "callbacks.qa.example")).isTrue();
        assertThat(passes("POST", "/api/pas/notifications")).isTrue();
        // A web page still cannot post to it from another site.
        assertThat(passes("POST", "/api/pas/notifications", "Host", "callbacks.qa.example", "Origin", "https://attacker.example")).isFalse();
        // Reading what was received is not a delivery. It can hold an authorization number, and is held to the host check.
        assertThat(passes("GET", "/api/pas/notifications", "Host", "callbacks.qa.example")).isFalse();
        assertThat(passes("GET", "/api/pas/notifications", "Host", "localhost:8057")).isTrue();
        assertThat(passes("POST", "/api/pas/notifications/1", "Host", "callbacks.qa.example")).isFalse();
    }

    @Test
    void everyAddressIsHeldToBothChecksBecauseNoAddressIsSortedIntoApiAndNotApi() throws Exception {
        // The framework finds an API controller for the last four spellings as well. A check that
        // first decided which addresses are the API let them past, so the filter decides nothing.
        for (String path : new String[] {"/", "/index.html", "/css/pave.css", "/apidocs", "/%61pi/mode", "/ap%69/mode", "/%61%70%69/mode",
                "/api;x=1/mode"}) {
            assertThat(passes("GET", path, "Host", "anything.example")).as(path).isFalse();
            assertThat(passes("GET", path, "Host", "localhost:8057")).as(path).isTrue();
            assertThat(passes("POST", path, "Host", "localhost:8057", "Origin", "https://attacker.example")).as(path).isFalse();
            assertThat(passes("POST", path, "Host", "localhost:8057", "Origin", "http://localhost:8057")).as(path).isTrue();
        }
    }

    @Test
    void onlyTheReceiversAddressWrittenExactlyIsExemptFromTheHostCheck() throws Exception {
        for (String path : new String[] {"/%61pi/pas/notifications", "/api;x=1/pas/notifications", "/api/pas/notifications;x=1",
                "/api/pas/notifications/", "/api/pas/%6Eotifications", "/api/pas//notifications", "/api/pas/notifications/sample"}) {
            assertThat(passes("POST", path, "Host", "callbacks.qa.example")).as(path).isFalse();
        }
    }

    @Test
    void aBrowserOpeningAPageUnderAnotherNameIsToldWhatToDoInPlainText() throws Exception {
        MockHttpServletResponse response = send("GET", "/", "Host", "qa-laptop.example:8057", "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("text/plain");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).isEqualTo("This request was addressed to a host name this tool does not answer to.\n"
                + "Open the tool as http://localhost and its port. To use another name for this machine, list the name under "
                + "app.allowed-hosts and start the tool again.\n");
        // A script of the tool asks for JSON and gets the shared shape.
        assertThat(send("GET", "/api/mode", "Host", "qa-laptop.example:8057", "Accept", "application/json").getContentType())
                .startsWith("application/json");
    }

    @Test
    void theHostOfAHeaderIsReadWithoutItsPort() {
        assertThat(ApiGuardFilter.hostOf("LocalHost:8057")).isEqualTo("localhost");
        assertThat(ApiGuardFilter.hostOf(" 127.0.0.1 ")).isEqualTo("127.0.0.1");
        assertThat(ApiGuardFilter.hostOf("[::1]:8057")).isEqualTo("::1");
        assertThat(ApiGuardFilter.hostOf("[FD00::5]")).isEqualTo("fd00::5");
        assertThat(ApiGuardFilter.hostOf("[::1")).isNull();
        assertThat(ApiGuardFilter.hostOf("")).isNull();
        assertThat(ApiGuardFilter.hostOf(null)).isNull();
    }
}
