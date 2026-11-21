package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A value an answer gave masked fills no field and goes with no hand-over the screens post, and
 * every place that would have used it draws the line that says why. The build has no browser, so
 * these read the scripts; the screens were driven in a real browser at 1440 by 900.
 */
class CarriedMaskFrontendTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    private static String read(String file) throws IOException {
        return Files.readString(STATIC.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    void theWordsAndTheLineAreWrittenOnceAndSayWhatTheTesterCanDo() throws IOException {
        String masks = read("js/masks.js");
        assertThat(masks).contains("const OPEN = '[secret ';");
        assertThat(CaseCodesFrontendTest.body(masks, "export function notFilledWords(masks) {"))
                .contains("'Not filled in: the answer gave this value masked, as ' + masks + ', because it repeats a secret this tool holds. '")
                .contains("'Type the value, or settle the secret in the Config tab.'");
        assertThat(CaseCodesFrontendTest.body(masks, "export function notHandedOnWords(masks) {"))
                .contains("'Not handed on: the answer gave this value masked, as ' + masks + ', because it repeats a secret this tool holds, so it is '")
                .contains("'not sent. Type the value on the form, or settle the secret in the Config tab.'");
        // A field holding a mask is left empty, never filled with the mask, and the line goes beside it.
        String fillFrom = CaseCodesFrontendTest.body(masks, "export function fillFromAnswer(control, value) {");
        assertThat(fillFrom).contains("fill(slot, masks ? maskedLine(notFilledWords(masks)) : null);").contains("control.set(masks ? '' : value);");
        assertThat(CaseCodesFrontendTest.body(read("css/pave.css"), ".masked-carry {")).contains("border-left: 4px solid currentColor;");
    }

    @Test
    void everyHandOverIsTakenWithoutItsMaskedValuesWhichAreKeptApartToBeNamed() throws IOException {
        String carryTo = CaseCodesFrontendTest.body(read("js/app.js"), "carryTo(tabId, payload) {");
        assertThat(carryTo).contains("const split = withoutMasks(payload ? payload.carry : null);")
                .contains("{...payload, carry: split.carry, masked: split.masked}");
        // The DTR tab names each masked value among what was handed on.
        assertThat(CaseCodesFrontendTest.body(read("js/tab-dtr.js"), "function drawCarried() {"))
                .contains("masked.map(one => [carriedName(handed, one.key), maskedLine(notHandedOnWords(one.masks))])");
        // So does the PAS tab's hand-over panel.
        assertThat(read("js/pas-handover.js")).contains("handed.masked.map(one => [carriedName(one.key), maskedLine(notHandedOnWords(one.masks))])");
        // The DTR steps post the latest hand-over back: without its masked values, which are named beside the next step.
        String dtr = read("js/dtr-questionnaire.js");
        assertThat(dtr).contains("carry: withoutMasks(packageEnvelope.nextStep && packageEnvelope.nextStep.carry).carry,")
                .contains("const split = withoutMasks(carried);\n            session.carry = split.carry;");
        assertThat(CaseCodesFrontendTest.body(dtr, "function nextStepLine() {")).contains("notHandedOnWords(one.masks)");
        assertThat(dtr).doesNotContain("session.carry = carried;");
    }

    @Test
    void aStepOfTheQuestionnaireWhoseBodyWouldHoldAMaskSaysSoBesideItsButtons() throws IOException {
        assertThat(CaseCodesFrontendTest.body(read("js/masks.js"), "export function stepRefusedWords(masks) {"))
                .contains("'What the next step would post holds a mask, ' + masks + ': an answer this tool read repeats a secret this tool holds. '")
                .contains("'A mask is never sent, so the next step is refused and nothing goes out. Settle the secret in the Config tab, then fetch the '")
                .contains("'questionnaire package again.'");
        String dtr = read("js/dtr-questionnaire.js");
        // The text looked at is the one the step posts, chosen as loopBody and responseBody choose it.
        assertThat(CaseCodesFrontendTest.body(dtr, "function postedMasks(entry, loop, respond) {"))
                .contains("texts.push(entry.loopJson || (entry.startResponse ? entry.startResponse.json : entry.view.json));")
                .contains("if (entry.view.kind === 'ADAPTIVE' && entry.loopJson) {\n                texts.push(entry.loopJson);")
                .contains("texts.push(entry.view.json, entry.startResponse ? entry.startResponse.json : null);");
        assertThat(CaseCodesFrontendTest.body(dtr, "function loopBody(entry) {"))
                .contains("if (entry.loopJson) {\n            body.questionnaireResponse = entry.loopJson;\n        } else if (entry.startResponse) {\n"
                        + "            body.questionnaireResponse = entry.startResponse.json;\n        } else {\n            body.questionnaire = entry.view.json;");
        assertThat(CaseCodesFrontendTest.body(dtr, "function actions(entry, countNote) {"))
                .contains("const masks = postedMasks(entry, loop, respond);\n        if (masks) {\n"
                        + "            parts.push(maskedLine(stepRefusedWords(masks)));\n        }\n        const row = el('div', {class: 'button-row'});");
    }

    @Test
    void theSubscriptionIdAndBothClaimIdFieldsAreFilledOnlyThroughTheCheckForAMask() throws IOException {
        String pas = read("js/tab-pas.js");
        assertThat(pas).contains("fillFromAnswer(controls.subscriptionId, envelope.detail.subscriptionId);")
                .contains("if (!fillFromAnswer(controls.inquiryClaimId, following.claimId)) {")
                .contains("if (!fillFromAnswer(controls.inquiryClaimId, source.claimId || '')) {\n        return;\n    }")
                .contains("fillFromAnswer(controls.claimId, claimId);");
        // None of the three fields is set straight from a value an answer handed on any more.
        Matcher direct = Pattern.compile("controls\\.(subscriptionId|inquiryClaimId|claimId)\\.set\\(").matcher(pas);
        assertThat(direct.find()).as("a field set directly").isFalse();
    }
}
