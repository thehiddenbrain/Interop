package org.p32h.interop.epa.pave.postman;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;

/**
 * A collection in Postman's older format v2.0 is read (D-307). It was refused whole, so nothing
 * at all was taken from a vendor's older export. The shapes v2.0 writes differently from v2.1 (a
 * url as text, a request as a bare url, a header as text, a helper as an object) are read; a part
 * that cannot be read is said in a line and the rest is still read. Placeholder values only.
 */
class PostmanParserV20Test {

    private static final String V20 = "https://schema.getpostman.com/json/collection/v2.0.0/collection.json";

    @Test
    void theRepositorysV20SampleIsReadAndSaysItsFormat() {
        PostmanCollection collection = PostmanParser.collection(PostmanFiles.text(PostmanFiles.V2_0_COLLECTION));
        assertThat(collection.requests()).hasSize(1);
        PostmanRequest request = collection.requests().get(0);
        assertThat(request.url()).isEqualTo("{{crdUrl}}/cds-services/{{crdOrderSignServiceId}}");
        assertThat(OperationDetector.detect(request.name(), request.method(), request.url(), request.folderPath()).operation())
                .isEqualTo(EpaOperation.CRD_ORDER_SIGN);
        assertThat(collection.notes()).containsExactly("This collection is in Postman's older format v2.0, which this tool reads as it reads "
                + "v2.1.");
    }

    @Test
    void eachShapeV20WritesDifferentlyIsReadAndAnEntryThatIsNeitherIsSaidWhileTheRestIsRead() {
        Map<String, Object> root = Map.of(
                "info", Map.of("name", "Placeholder older collection", "schema", V20),
                "auth", Map.of("type", "bearer", "bearer", Map.of("token", "{{accessToken}}")),
                "item", List.of(
                        Map.of("name", "PAS", "item", List.of(
                                Map.of("name", "Claim Submit", "request", Map.of("method", "POST", "url", "{{base}}/Claim/$submit",
                                        "header", "Content-Type: application/fhir+json\nX-Placeholder: one",
                                        "body", Map.of("mode", "raw", "raw", "{\"resourceType\": \"Bundle\"}"))),
                                Map.of("name", "Capability", "request", "{{base}}/metadata"))),
                        Map.of("name", "A note that is not a request", "description", "text only")));
        PostmanCollection collection = PostmanParser.collection(JsonSafe.mapper().writeValueAsString(root));

        assertThat(collection.requests()).hasSize(2);
        PostmanRequest submit = collection.requests().get(0);
        assertThat(submit.url()).isEqualTo("{{base}}/Claim/$submit");
        assertThat(submit.folderPath()).containsExactly("PAS");
        assertThat(submit.headers().stream().map(header -> header.name() + "=" + header.value()).toList())
                .containsExactly("Content-Type=application/fhir+json", "X-Placeholder=one");
        assertThat(submit.body()).isEqualTo("{\"resourceType\": \"Bundle\"}");
        assertThat(submit.auth().type()).isEqualTo("bearer");
        assertThat(submit.auth().variables()).containsExactly("accessToken");

        PostmanRequest bare = collection.requests().get(1);
        assertThat(bare.method()).isEqualTo("GET");
        assertThat(bare.url()).isEqualTo("{{base}}/metadata");

        assertThat(collection.notes()).containsExactly(
                "This collection is in Postman's older format v2.0, which this tool reads as it reads v2.1.",
                "1 entry is neither a folder nor a request and is not listed.");
    }
}
