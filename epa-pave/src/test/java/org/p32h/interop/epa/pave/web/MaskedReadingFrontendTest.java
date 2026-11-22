package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Every Workbench screen that draws a conclusion from an answer draws the server's line about a
 * masked reading under that conclusion, above its facts and its next step, with the heavy rule the
 * screens give what must be read before acting. The order in a drawn card is held by
 * ResultFrontendTest, which runs it; these read the scripts for the one shape and every place.
 */
class MaskedReadingFrontendTest {

    @Test
    void theLineIsDrawnOnceInOneShapeWithTheRuleOfWhatMustBeReadFirst() throws IOException {
        assertThat(StaticSources.body(StaticSources.script("result.js"), "export function maskedReading(line) {"))
                .contains("el('p', {class: 'masked-reading'}, line)");
        String css = StaticSources.read("css/workbench.css");
        assertThat(StaticSources.body(css, ".masked-reading,")).contains("border-left: 4px solid currentColor;").contains("font-weight: 600;");
    }

    @Test
    void theResultCardCarriesItUnderTheHeadlineAndAboveItsFacts() throws IOException {
        String card = StaticSources.body(StaticSources.script("result.js"), "export function resultCard(envelope, options) {");
        int headline = card.indexOf("card.append(el('p', {class: 'verdict__headline'}, verdict.headline));");
        int line = card.indexOf("const masked = maskedReading(verdict.maskedReading);");
        int facts = card.indexOf("const facts = factRows(envelope);");
        assertThat(headline).isPositive();
        assertThat(line).isGreaterThan(headline);
        assertThat(facts).isGreaterThan(line);
    }

    @Test
    void bothLookupsCarryItAboveWhatTheyReturned() throws IOException {
        String fold = StaticSources.body(StaticSources.script("wb-lookup.js"), "export function returnedFold(answer) {");
        assertThat(fold.indexOf("maskedReading(answer.maskedReading),")).isPositive().isLessThan(fold.indexOf("answer.fields.map"));
        assertThat(StaticSources.script("wb-member.js")).contains("returnedFold(answer)");
        assertThat(StaticSources.script("wb-provider.js")).contains("returnedFold(answer)");
    }
}
