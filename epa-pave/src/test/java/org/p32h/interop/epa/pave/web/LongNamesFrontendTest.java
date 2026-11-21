package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A sentence the page writes itself quotes a long name of an uploaded file by its start and its
 * length, as the server's sentences do: "Stored the collection" with a name of 100,000 characters
 * ran off the side of the screen, and the words after the name could not be read. A name shown as
 * a label, in a list, the strip or the banner, stays whole: it wraps there and the page works. The build has
 * no browser, so these read the scripts.
 */
class LongNamesFrontendTest {

    private static String read(String file) throws IOException {
        return Files.readString(Path.of("src/main/resources/static/js/" + file), StandardCharsets.UTF_8);
    }

    @Test
    void thePageHoldsOneRuleForALongNameInTheServersWords() throws IOException {
        String named = CaseCodesFrontendTest.body(read("dom.js"), "export function named(name) {");
        assertThat(named).contains("if (text.length <= 60) {").contains("return text;")
                // Characters are counted as code points: the second unit of a pair is not counted, and the cut falls before the 61st.
                .contains("if (code >= 0xDC00 && code <= 0xDFFF && before >= 0xD800 && before <= 0xDBFF) {")
                .contains("if (length === 60) {").contains("cut = i;")
                .contains("const suffix = '... (a name of ' + length + ' characters)';")
                // Shortened only when the start and the length are shorter than the name.
                .contains("return length <= 60 + suffix.length ? text : text.slice(0, cut) + suffix;");
    }

    @Test
    void theSentencesOfAnUploadAndOfARemovalQuoteANameThroughTheRule() throws IOException {
        String collections = read("config-collections.js");
        assertThat(collections).contains("'Stored the collection \"', named(collection.name), '\": '")
                .contains("unplaced.map((request, index) => [index > 0 ? ', ' : null, named(request.name)])")
                .contains("heading(2, 'One request in full: ' + named(request.name))")
                .contains("el('strong', null, 'Remove the collection \"', named(collection.name), '\"?')")
                .doesNotContain("'Stored the collection \"', collection.name");
        String environments = read("config-environments.js");
        assertThat(environments).contains("'Stored the environment \"', named(environment.name), '\" as '")
                .contains("'\"', named(answer.body.environment.name), '\" is now the active environment.'")
                .contains("'Variables of ', named(environment.name), ' '")
                // The label of the list stays whole.
                .contains("el('strong', null, environment.name)");
        assertThat(read("config-parts.js")).contains("'The tool warns about an address of \"' + named(name) + '\"'");
        String app = read("app.js");
        assertThat(app).contains("'The active environment is ' + named(environment.name) + ' ('");
    }

    /**
     * The unlock of production refuses a name "not typed exactly as the environment strip shows it",
     * so the strip shows it whole, at any length: given by its start and its length, a name of 75
     * characters typed as the strip showed it was refused, and two production names that differ only
     * after the 60th character looked alike. A long one wraps and scrolls in a box of its own
     * instead, so it cannot push the page wide.
     */
    @Test
    void theStripAndTheBannerGiveTheEnvironmentNameWholeAsTheUnlockAsksForIt() throws IOException {
        String app = read("app.js");
        assertThat(app).contains("el('span', {class: 'environment-name'}, environment.name)")
                .contains("'none active', 'strip-item--environment')")
                .contains("banner('prod', 'Production', environment.name, access.reason,")
                .contains("banner('uat', 'UAT', environment.name, access.reason)")
                .contains("el('span', {class: 'banner__name environment-name'}, name)")
                .doesNotContain("named(environment.name))")
                .doesNotContain("'Production', named(")
                .doesNotContain("'UAT', named(");
        String css = Files.readString(Path.of("src/main/resources/static/css/pave.css"), StandardCharsets.UTF_8);
        String box = css.substring(css.indexOf(".environment-name {"), css.indexOf("}", css.indexOf(".environment-name {")));
        assertThat(box).contains("overflow-wrap: anywhere;").contains("max-height: 4.5em;").contains("overflow-y: auto;")
                .contains("max-width: 100%;");
        String item = css.substring(css.indexOf(".strip-item--environment {"), css.indexOf("}", css.indexOf(".strip-item--environment {")));
        assertThat(item).contains("white-space: normal;");
    }
}
