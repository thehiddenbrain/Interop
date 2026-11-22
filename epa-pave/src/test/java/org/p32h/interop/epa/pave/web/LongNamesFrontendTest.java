package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * A sentence the page writes itself quotes a long name of an uploaded file by its start and its
 * length, as the server's sentences do (dom.js named()). The name of the active environment stays
 * whole wherever it names the environment: in the chooser's options and its title, in the
 * production and UAT lines and in the browser tab's title, because the unlock of production asks
 * for the name exactly as it is shown and two names can differ only at their end. The chooser may
 * cut it on screen; its options and title never do. A long name wraps and scrolls in a box of its
 * own, so it cannot push the page wide.
 */
class LongNamesFrontendTest {

    @Test
    void thePageHoldsOneRuleForALongNameInItsOwnSentences() throws IOException {
        String named = StaticSources.body(StaticSources.script("dom.js"), "export function named(name) {");
        assertThat(named).contains("if (text.length <= 60) {").contains("return text;")
                .contains("if (code >= 0xDC00 && code <= 0xDFFF && before >= 0xD800 && before <= 0xDBFF) {")
                .contains("if (length === 60) {").contains("cut = i;")
                .contains("const suffix = '... (a name of ' + length + ' characters)';")
                .contains("return length <= 60 + suffix.length ? text : text.slice(0, cut) + suffix;");
    }

    @Test
    void theEnvironmentNameIsWholeInTheChooserTheLinesAndTheTabTitle() throws IOException {
        String app = StaticSources.script("app.js");
        assertThat(app).contains("options.push(el('option', {value: each.id, selected: Boolean(environment) && each.name === environment.name}, each.name));")
                .contains("select.title = environment ? environment.name : 'No environment active';")
                .contains("(environment ? environment.name + ' (' + environment.kind + ')' : 'no environment')")
                .contains("el('strong', {class: 'line__kind'}, 'Production'),\n            el('span', {class: 'line__name environment-name'}, environment.name),")
                .contains("el('strong', {class: 'line__kind'}, 'UAT'),\n            el('span', {class: 'line__name environment-name'}, environment.name)")
                .doesNotContain("named(environment.name)")
                .doesNotContain("named(each.name)");
        String css = StaticSources.read("css/pave.css");
        String box = StaticSources.body(css, ".environment-name {");
        assertThat(box).contains("overflow-wrap: anywhere;").contains("max-height: 4.5em;").contains("overflow-y: auto;")
                .contains("max-width: 100%;");
    }
}
