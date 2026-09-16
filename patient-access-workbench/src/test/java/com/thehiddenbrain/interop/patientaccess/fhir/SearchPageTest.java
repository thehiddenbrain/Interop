package com.thehiddenbrain.interop.patientaccess.fhir;

import tools.jackson.databind.node.ObjectNode;
import com.thehiddenbrain.interop.patientaccess.support.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchPageTest {

    private static HttpResult result(int status, String contentType, String body) {
        return new HttpResult("https://fhir.example.org/r4/Patient", status, Map.of("Content-Type", List.of(contentType)), body, 12, "req1");
    }

    @Test
    void separatesMatchesFromIncludesAndReadsTotalAndLinks() {
        ObjectNode bundle = Fixtures.bundle(List.of(Fixtures.resource("Coverage", "c1"), Fixtures.resource("Coverage", "c2")),
                List.of(Fixtures.resource("Organization", "payer")), 17, "https://fhir.example.org/r4/Coverage?page=2");
        // an entry without search.mode counts as a match, an entry without a resource is ignored
        bundle.withArray("entry").addObject().set("resource", Fixtures.resource("Coverage", "c3"));
        bundle.withArray("entry").addObject().put("fullUrl", "urn:uuid:no-resource");

        SearchPage page = SearchPage.of(result(200, Fixtures.FHIR_JSON, Fixtures.json(bundle)), List.of("w1"));
        assertThat(page.isBundle()).isTrue();
        assertThat(page.resources()).extracting(r -> r.path("id").asString("")).containsExactly("c1", "c2", "c3");
        assertThat(page.included()).extracting(r -> r.path("id").asString("")).containsExactly("payer");
        assertThat(page.count()).isEqualTo(3);
        assertThat(page.total()).isEqualTo(17);
        assertThat(page.selfUrl()).isEqualTo("http://example.org/fhir/self");
        assertThat(page.nextUrl()).isEqualTo("https://fhir.example.org/r4/Coverage?page=2");
        assertThat(page.warnings()).containsExactly("w1");
        assertThat(page.response().requestId()).isEqualTo("req1");
    }

    @Test
    void bundleWithoutTotalOrNextLink() {
        ObjectNode bundle = Fixtures.bundle(List.of(Fixtures.resource("Patient", "p")), List.of(), null, null);
        SearchPage page = SearchPage.of(result(200, Fixtures.FHIR_JSON, Fixtures.json(bundle)), null);
        assertThat(page.total()).isNull();
        assertThat(page.nextUrl()).isNull();
        assertThat(page.count()).isEqualTo(1);
        assertThat(page.warnings()).isEmpty();
    }

    @Test
    void nonBundleAndNonJsonBodiesAreNotBundles() {
        SearchPage outcome = SearchPage.of(result(400, Fixtures.FHIR_JSON, Fixtures.json(Fixtures.operationOutcome("error", "invalid", "bad"))), List.of());
        assertThat(outcome.isBundle()).isFalse();
        assertThat(outcome.bundle().path("resourceType").asString("")).isEqualTo("OperationOutcome");
        assertThat(outcome.resources()).isEmpty();

        SearchPage html = SearchPage.of(result(200, "text/html", "<html>login</html>"), List.of());
        assertThat(html.isBundle()).isFalse();
        assertThat(html.bundle()).isNull();
        assertThat(html.resources()).isEmpty();
        assertThat(html.included()).isEmpty();
        assertThat(html.total()).isNull();
    }
}
