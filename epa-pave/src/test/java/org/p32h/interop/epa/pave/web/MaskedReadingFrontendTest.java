package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Every screen that draws a conclusion from an answer draws the server's line about a masked
 * reading under that conclusion, above its reasons and its next step, with the heavy rule the
 * screens give what must be read before acting. The build has no browser, so these read the
 * scripts; the screens were driven in a real browser at 1440 by 900.
 */
class MaskedReadingFrontendTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    private static String read(String file) throws IOException {
        return Files.readString(STATIC.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    void theLineIsDrawnOnceInOneShapeWithTheRuleOfWhatMustBeReadFirst() throws IOException {
        String result = read("js/result.js");
        assertThat(CaseCodesFrontendTest.body(result, "export function maskedReading(line) {"))
                .contains("el('p', {class: 'masked-reading'}, line)");
        String css = read("css/pave.css");
        assertThat(css).contains(".masked-reading {");
        assertThat(CaseCodesFrontendTest.body(css, ".masked-reading {")).contains("border-left: 4px solid currentColor;")
                .contains("font-weight: 600;");
    }

    @Test
    void theVerdictOfEveryRunCarriesItUnderTheHeadlineAndAboveItsReasons() throws IOException {
        String strip = CaseCodesFrontendTest.body(read("js/result.js"), "function verdictStrip(envelope) {");
        int headline = strip.indexOf("strip.append(el('h2', {class: 'verdict__headline' + size}, verdict.headline));");
        int line = strip.indexOf("const masked = maskedReading(verdict.maskedReading);");
        int reasons = strip.indexOf("const supporting = verdict.supporting || [];");
        assertThat(headline).isPositive();
        assertThat(line).isGreaterThan(headline);
        assertThat(reasons).isGreaterThan(line);
    }

    @Test
    void bothLookupsCrdDiscoveryANotificationAndAReplayCarryItUnderWhatTheyConclude() throws IOException {
        String lookup = CaseCodesFrontendTest.body(read("js/lookup.js"), "function draw() {");
        assertThat(lookup.indexOf("const masked = maskedReading(answer.maskedReading);")).isPositive()
                .isLessThan(lookup.indexOf("if (before) {"));
        assertThat(read("js/lookup.js")).contains("el('p', null, answer.message),\n            maskedReading(answer.maskedReading),");

        String discovery = CaseCodesFrontendTest.body(read("js/tab-crd.js"), "async function discover() {");
        assertThat(discovery.indexOf("maskedReading(found.maskedReading),")).isPositive()
                .isLessThan(discovery.indexOf("services.length === 0 ? el('p', null, 'The answer listed no services.')"));

        String notification = CaseCodesFrontendTest.body(read("js/pas-notifications.js"), "function rowFor(item, fresh) {");
        assertThat(notification).contains("line, maskedReading(verdict.maskedReading), meta, decided, sampleNotes,");

        assertThat(read("js/history-replay.js")).contains("entry.verdict ? maskedReading(entry.verdict.maskedReading) : null,");
    }

    @Test
    void aNextStepMarkedAsRestingOnTheMaskIsStillKnownForTheErrorPanelsOwnAction() throws IOException {
        assertThat(read("js/result.js")).contains("step.headline.endsWith(': ' + error.action.replace(/^Next: /, ''))");
    }
}
