package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * A value an answer gave masked fills no field and goes with no hand-over the Workbench posts, and
 * every place that would have used it says why. masks.js is run with Node; the places that fill a
 * field from an answer are read, so a field set straight from a handed-on value fails the build.
 */
class CarriedMaskFrontendTest {

    @Test
    void aMaskedValueFillsNoFieldAndGoesWithNoHandOver(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the masks", """
                import './browser.js';
                import {masksIn, withoutMasks, fillFromAnswer, notFilledWords, notHandedOnWords} from './js/masks.js';
                import {el} from './js/dom.js';
                const say = value => console.log(JSON.stringify(value));
                say([masksIn('a [secret clientSecret] and [secret clientSecret] then [secret apiKey]'), masksIn('plain'), masksIn(null)]);
                say(withoutMasks({questionnaire: ['https://example.org/q', '[secret clientSecret]'], claimId: '[secret apiKey]', runId: ['run-1']}));
                const control = {input: el('input'), slot: el('div')};
                say([fillFromAnswer(control, 'claim-1'), control.input.value, control.slot.textContent]);
                say([fillFromAnswer(control, 'claim [secret apiKey]'), control.input.value, control.slot.textContent]);
                say(notHandedOnWords('Coverage assertion id'));
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"[secret clientSecret] and [secret apiKey]\",null,null]",
                "{\"carry\":{\"questionnaire\":[\"https://example.org/q\"],\"runId\":[\"run-1\"]},\"masked\":["
                        + "{\"key\":\"questionnaire\",\"value\":\"[secret clientSecret]\",\"masks\":\"[secret clientSecret]\"},"
                        + "{\"key\":\"claimId\",\"value\":\"[secret apiKey]\",\"masks\":\"[secret apiKey]\"}]}",
                "[true,\"claim-1\",\"\"]",
                "[false,\"\",\"Not filled in: the answer gave this value masked, as [secret apiKey], because it repeats a secret this tool "
                        + "holds. Type the value, or settle the secret in Configuration.\"]",
                "\"Coverage assertion id: masked, not handed on\"");
    }

    @Test
    void everyFieldAnAnswerFillsGoesThroughTheCheckForAMask() throws IOException {
        String workbench = StaticSources.script("workbench.js");
        assertThat(workbench).contains("if (fillFromAnswer(claim, Array.isArray(carry.claimId) ? carry.claimId[0] : carry.claimId)) {")
                .contains("if (fillFromAnswer(control, value)) {");
        // No option field of a step is set straight from a value an answer handed on.
        Matcher direct = Pattern.compile("options\\[\\d]\\.\\w+\\.set\\(|claim\\.set\\((?!claim\\.input\\.value)").matcher(workbench);
        assertThat(direct.find()).as("a field set directly from an answer").isFalse();
        // Every hand-over the journey keeps is taken without its masked values.
        String journey = StaticSources.script("wb-journey.js");
        assertThat(journey).contains("const split = withoutMasks(envelope.nextStep.carry);").contains("const split = withoutMasks(carry);")
                .doesNotContain("carries[step] = {carry,").doesNotContain("carry: envelope.nextStep.carry");
        // A lookup's masked field is left empty, with its mark.
        assertThat(StaticSources.script("wb-lookup.js")).contains("if (masked.has(control.name)) {\n            control.masked();");
        assertThat(StaticSources.body(StaticSources.read("css/workbench.css"), ".masked-reading,")).contains("border-left: 4px solid currentColor;");
    }
}
