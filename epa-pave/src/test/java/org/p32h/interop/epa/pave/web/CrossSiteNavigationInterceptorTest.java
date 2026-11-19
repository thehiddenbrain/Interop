package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * A link from another site may open a page of the tool and never an answer of the API. What is an
 * answer is what the framework hands to a controller method; no address is sorted here.
 */
class CrossSiteNavigationInterceptorTest {

    private final CrossSiteNavigationInterceptor interceptor = new CrossSiteNavigationInterceptor(TestMvc.mapper());

    static class SomeController {
        public String export() {
            return "recordType,runId";
        }
    }

    private static HandlerMethod controllerMethod() throws NoSuchMethodException {
        return new HandlerMethod(new SomeController(), SomeController.class.getMethod("export"));
    }

    private static MockHttpServletRequest link(String site) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/runs/export.csv");
        request.addHeader("Host", "localhost:8057");
        request.addHeader("Sec-Fetch-Site", site);
        request.addHeader("Sec-Fetch-Mode", "navigate");
        request.addHeader("Sec-Fetch-Dest", "document");
        request.addHeader("Accept", "text/html,application/xhtml+xml");
        return request;
    }

    @Test
    void aLinkFromAnotherSiteToAControllerMethodIsRefusedWithTheGuardsSentences() throws Exception {
        for (String site : new String[] {"cross-site", "same-site"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(link(site), response, controllerMethod())).as(site).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentType()).startsWith("text/plain");
            assertThat(response.getContentAsString()).isEqualTo(ApiGuardFilter.ANOTHER_SITE_TITLE + "\n" + ApiGuardFilter.ANOTHER_SITE_ACTION + "\n");
        }
    }

    @Test
    void aLinkFromAnotherSiteToAPageOrAStaticFileIsLetThrough() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(link("cross-site"), response, new ResourceHttpRequestHandler())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void theToolsOwnPagesTheAddressBarAndClientsThatAreNotBrowsersAreLetThrough() throws Exception {
        for (String site : new String[] {"same-origin", "none"}) {
            assertThat(interceptor.preHandle(link(site), new MockHttpServletResponse(), controllerMethod())).as(site).isTrue();
        }
        MockHttpServletRequest curl = new MockHttpServletRequest("GET", "/api/runs/export.csv");
        curl.addHeader("Host", "localhost:8057");
        assertThat(interceptor.preHandle(curl, new MockHttpServletResponse(), controllerMethod())).isTrue();
    }

    @Test
    void theErrorDispatchIsNotANewRequest() throws Exception {
        MockHttpServletRequest error = link("cross-site");
        error.setDispatcherType(DispatcherType.ERROR);
        assertThat(interceptor.preHandle(error, new MockHttpServletResponse(), controllerMethod())).isTrue();
    }
}
