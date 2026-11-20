package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(JsonSafe.parse(refused.getContentAsString()).node().get("action").stringValue())
                .isEqualTo("Open this tool from its own address, http://localhost and its port, and do it from there.");
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
        // Over plain http, an https origin is another origin, whatever its host and port.
        assertThat(passes("POST", "/api/crd", "Host", "localhost:443", "Origin", "https://localhost")).isFalse();
        assertThat(passes("POST", "/api/crd", "Host", "[::1]:8057", "Origin", "http://[::1]:8057")).isTrue();
        assertThat(passes("POST", "/api/crd", "Host", "localhost:x", "Origin", "http://localhost:8057")).isFalse();
    }

    @Test
    void aReadAnotherSitesPageCausesIsRefusedLikeAChangeBecauseAReadActsToo() throws Exception {
        // Another site's image or fetch: on live a lookup reaches the payer with this tool's
        // credentials, and the export is saved to the tester's disk. That the browser hides the
        // answer from the other page undoes neither.
        String host = "localhost:8057";
        for (String method : new String[] {"GET", "HEAD", "OPTIONS"}) {
            for (String site : new String[] {"cross-site", "same-site", "something-new"}) {
                assertThat(passes(method, "/api/members/S123456789", "Host", host, "Sec-Fetch-Site", site, "Sec-Fetch-Mode", "no-cors",
                        "Sec-Fetch-Dest", "image")).as(method + " " + site).isFalse();
            }
            // A browser too old to send Sec-Fetch-Site still states the origin of a request a script makes.
            assertThat(passes(method, "/api/runs", "Host", host, "Origin", "https://attacker.example")).as(method).isFalse();
            assertThat(passes(method, "/api/runs", "Host", host)).as(method).isTrue();
        }
        MockHttpServletResponse refused = send("GET", "/api/runs/export.csv", "Host", host, "Sec-Fetch-Site", "cross-site");
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(refused.getContentAsString()).doesNotContain("recordType");
        // The host check still applies to a read.
        assertThat(passes("GET", "/api/runs", "Host", "attacker.example", "Origin", "http://attacker.example")).isFalse();
    }

    @Test
    void onlyATopLevelNavigationByGetFromAnotherSiteIsLetPastTheFilterForTheInterceptorToDecide() throws Exception {
        String host = "localhost:8057";
        // A link on a wiki page, followed in the window. The interceptor refuses it when a controller method would answer.
        assertThat(passes("GET", "/", "Host", host, "Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "navigate", "Sec-Fetch-Dest", "document"))
                .isTrue();
        assertThat(passes("GET", "/api/runs/export.csv", "Host", host, "Sec-Fetch-Site", "same-site", "Sec-Fetch-Mode", "navigate",
                "Sec-Fetch-Dest", "document")).isTrue();
        // Anything else that is a navigation is refused here: a form post, a frame, a HEAD.
        assertThat(passes("POST", "/api/crd", "Host", host, "Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "navigate", "Sec-Fetch-Dest",
                "document")).isFalse();
        assertThat(passes("GET", "/api/runs/export.csv", "Host", host, "Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "navigate",
                "Sec-Fetch-Dest", "iframe")).isFalse();
        assertThat(passes("HEAD", "/api/runs/export.csv", "Host", host, "Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "navigate",
                "Sec-Fetch-Dest", "document")).isFalse();
        assertThat(passes("GET", "/api/runs/export.csv", "Host", host, "Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "no-cors",
                "Sec-Fetch-Dest", "document")).isFalse();
    }

    @Test
    void secFetchSiteDecidesWhenPresentAndOriginIsReadOnlyWhenItIsAbsent() throws Exception {
        String host = "localhost:8057";
        // What Chrome sent for a plain form post on the tool's own page under a no-referrer policy.
        assertThat(passes("POST", "/api/suites/preview", "Host", host, "Origin", "null", "Sec-Fetch-Site", "same-origin", "Sec-Fetch-Mode",
                "navigate", "Sec-Fetch-Dest", "document")).isTrue();
        // The address bar, whatever Origin says.
        assertThat(passes("GET", "/api/mode", "Host", host, "Origin", "https://attacker.example", "Sec-Fetch-Site", "none")).isTrue();
        // A browser that says another site is not believed over an Origin that names this tool.
        assertThat(passes("POST", "/api/crd", "Host", host, "Origin", "http://localhost:8057", "Sec-Fetch-Site", "cross-site")).isFalse();
        // No Sec-Fetch-Site: Origin decides, and null is never this tool's page.
        assertThat(passes("POST", "/api/crd", "Host", host, "Origin", "null")).isFalse();
    }

    @Test
    void anOriginIsThisToolsOnlyWithItsSchemeHostAndPortAndNothingElse() throws Exception {
        String host = "localhost:8057";
        for (String origin : new String[] {"https://localhost:8057", "ws://localhost:8057", "ftp://localhost:8057", "chrome-extension://localhost:8057",
            "//localhost:8057", "http://attacker.example@localhost:8057", "http://user:pass@localhost:8057", "http://localhost:8057/",
            "http://localhost:8057/x", "http://localhost:8057?x", "http://localhost:8057#x"}) {
            assertThat(passes("POST", "/api/config/writes", "Host", host, "Origin", origin)).as(origin).isFalse();
        }
        assertThat(passes("POST", "/api/config/writes", "Host", host, "Origin", "http://localhost:8057")).isTrue();
        assertThat(passes("POST", "/api/config/writes", "Host", host, "Origin", "HTTP://LOCALHOST:8057")).isTrue();
        // Over TLS the tool's own origin is https.
        MockHttpServletRequest secure = new MockHttpServletRequest("POST", "/api/config/writes");
        secure.setSecure(true);
        secure.addHeader("Host", "localhost:8443");
        secure.addHeader("Origin", "https://localhost:8443");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new ApiGuardFilter(app, environment, TestMvc.mapper()).doFilter(secure, response, chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void theFullyQualifiedSpellingOfANameIsTheName() throws Exception {
        assertThat(passes("GET", "/api/mode", "Host", "localhost.:8057")).isTrue();
        assertThat(passes("GET", "/api/mode", "Host", "localhost.")).isTrue();
        assertThat(passes("POST", "/api/crd", "Host", "localhost.:8057", "Origin", "http://localhost.:8057")).isTrue();
        assertThat(passes("GET", "/api/mode", "Host", "localhost..:8057")).isFalse();
        assertThat(passes("GET", "/api/mode", "Host", "attacker.example.:8057")).isFalse();
        assertThat(passes("GET", "/api/mode", "Host", ".")).isFalse();
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void anAllowedHostWrittenWithAPortIsReadAsTheHostAndTheOperatorIsToldOnce(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        app = AppProperties.of(Map.of("allowed-hosts", "qa-laptop.example:8062, fd00::5, [fd00::6]:8062, qa-other.example"));
        assertThat(passes("GET", "/api/mode", "Host", "qa-laptop.example:8062")).isTrue();
        assertThat(passes("GET", "/api/mode", "Host", "qa-laptop.example:9000")).isTrue();
        assertThat(passes("GET", "/api/mode", "Host", "[fd00::5]:8062")).isTrue();
        assertThat(passes("GET", "/api/mode", "Host", "[fd00::6]:8062")).isTrue();
        assertThat(passes("GET", "/api/mode", "Host", "qa-other.example:8062")).isTrue();
        assertThat(output.getAll()).contains("The entry qa-laptop.example:8062 under app.allowed-hosts names a port. A host name is "
                + "matched whatever port it is used with, so the port is ignored and qa-laptop.example is used.")
                .contains("The entry [fd00::6]:8062 under app.allowed-hosts names a port.")
                .doesNotContain("The entry fd00::5").doesNotContain("The entry qa-other.example");
        assertThat(ApiGuardFilter.entryHost("QA-Laptop.example:8062")).isEqualTo("qa-laptop.example");
        assertThat(ApiGuardFilter.entryHost("fd00::5")).isEqualTo("fd00::5");
        assertThat(ApiGuardFilter.entryHost("[fd00::5]")).isEqualTo("fd00::5");
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void theOperatorIsToldOfAPortOnlyWhenAnEntryNamesOne(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        // The fully qualified spelling names no port: it is the name, accepted without a word.
        app = AppProperties.of(Map.of("allowed-hosts", "QA-Laptop.example., qa-other.example.:8062"));
        assertThat(passes("GET", "/api/mode", "Host", "qa-laptop.example:8057")).isTrue();
        assertThat(passes("GET", "/api/mode", "Host", "qa-other.example:8057")).isTrue();
        assertThat(output.getAll()).doesNotContain("The entry QA-Laptop.example.")
                .contains("The entry qa-other.example.:8062 under app.allowed-hosts names a port. A host name is matched whatever port it "
                        + "is used with, so the port is ignored and qa-other.example is used.");
        assertThat(ApiGuardFilter.entryHost("localhost.")).isEqualTo("localhost");
        assertThat(ApiGuardFilter.entryNamesPort("localhost.")).isFalse();
        assertThat(ApiGuardFilter.entryNamesPort("fd00::5")).isFalse();
        assertThat(ApiGuardFilter.entryNamesPort("[fd00::5]")).isFalse();
        assertThat(ApiGuardFilter.entryNamesPort("[fd00::5]:8062")).isTrue();
        assertThat(ApiGuardFilter.entryNamesPort("qa-laptop.example:8062")).isTrue();
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void anEntryThatIsNotAHostNameStopsTheStartAndIsNamed(org.springframework.boot.test.system.CapturedOutput output) {
        // Read as a host, http://qa-other.example was the host "http": a name nobody meant let in,
        // the name meant still refused, and a start-up line saying a port had been ignored.
        for (String entry : new String[] {"http://qa-other.example", "https://qa-other.example:8062", "qa-other.example/pave",
                "user@qa-other.example", "qa-other.example:", "qa-other.example:http", "[fd00::5", "[fd00::5]x", ":8062", "."}) {
            app = AppProperties.of(Map.of("allowed-hosts", entry));
            assertThatThrownBy(() -> new ApiGuardFilter(app, environment, TestMvc.mapper())).as(entry)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("The entry " + entry + " under app.allowed-hosts is not a host name, so the tool did not start. Write the "
                            + "name alone, such as qa-laptop.example, with no scheme such as http://, no path and no user name.");
        }
        assertThat(output.getAll()).doesNotContain("names a port");
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
        assertThat(ApiGuardFilter.hostOf("localhost.:8057")).isEqualTo("localhost");
        assertThat(ApiGuardFilter.hostOf("")).isNull();
        assertThat(ApiGuardFilter.hostOf(null)).isNull();
    }
}
