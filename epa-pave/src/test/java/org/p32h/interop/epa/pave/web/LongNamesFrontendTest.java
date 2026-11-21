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
 * a label, in a list or the strip, stays whole: it wraps there and the page works. The build has
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
                .contains("text.slice(0, cut) + '... (a name of ' + text.length + ' characters)'")
                // A surrogate pair is not cut in half, as on the server.
                .contains("code >= 0xD800 && code <= 0xDBFF ? 59 : 60");
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
        assertThat(app).contains("'The active environment is ' + named(environment.name) + ' ('")
                // A long name in the strip pushed every other fact of it off the screen.
                .contains("el('span', {title: environment.name}, named(environment.name))")
                .contains("banner('prod', 'Production', named(environment.name), access.reason,");
    }
}
