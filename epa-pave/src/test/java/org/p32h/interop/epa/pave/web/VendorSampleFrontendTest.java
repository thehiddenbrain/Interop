package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The vendor's sample body can be put in the box of a request of the tester's own on DTR and PAS,
 * as on CRD, from one shared reading of the collection. The build has no browser, so these read
 * the scripts; the block was driven in a real browser on live with a collection uploaded.
 */
class VendorSampleFrontendTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    private static String read(String file) throws IOException {
        return Files.readString(JS.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    void theSampleIsReadOnceForEveryTabFromTheResolveOfTheOperation() throws IOException {
        String sample = read("vendor-sample.js");
        assertThat(CaseCodesFrontendTest.body(sample, "export async function readSample(operation) {"))
                .contains("api.postJson('/api/config/resolve', {operation})");
        String compare = read("crd-compare.js");
        assertThat(compare).contains("import {readSample} from './vendor-sample.js';").doesNotContain("async function readSample");
    }

    @Test
    void onlyDtrAndPasAddTheBlockEachForItsOwnOperation() throws IOException {
        String own = read("own-request.js");
        int branch = own.indexOf("if (spec.protocol === 'DTR' || spec.protocol === 'PAS') {");
        assertThat(branch).isPositive();
        assertThat(own.indexOf("sampleBlock(", branch)).isPositive();
        assertThat(own.indexOf("sampleBlock(")).isGreaterThan(branch);
        assertThat(own).contains("spec.protocol === 'DTR' ? 'DTR_QUESTIONNAIRE_PACKAGE' : spec.protocol === 'PAS' ? 'PAS_SUBMIT' : null");
    }

    @Test
    void theButtonIsNamedOnceAndTheWordsSayToUse() throws IOException {
        String sample = read("vendor-sample.js");
        String label = "'Put the vendor\\'s sample in the box'";
        assertThat(sample.indexOf(label)).isPositive();
        assertThat(sample.indexOf(label)).isEqualTo(sample.lastIndexOf(label));
        assertThat(sample).contains("there is no vendor\\'s sample to use. ").contains("has no sample body to use.")
                .doesNotContain("to compare");
    }
}
