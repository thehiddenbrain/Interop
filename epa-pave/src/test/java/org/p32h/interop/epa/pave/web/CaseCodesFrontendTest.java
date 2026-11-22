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
 * What the page sends as the procedure codes of the case is what the code lines show, and nothing
 * else: text left in the code box is never moved into the case when the request is read, the same
 * code is refused by what each entry holds on every way in, and a send with text left in the box is
 * refused before anything is asked of the server. The decision and the picker are run with Node.
 */
class CaseCodesFrontendTest {

    @Test
    void textLeftInTheCodeBoxIsNeverMovedIntoTheCaseWhenTheRequestIsRead() throws IOException {
        String values = StaticSources.body(StaticSources.script("code-picker.js"), "values() {");
        assertThat(values).doesNotContain("commitTyped").doesNotContain("input.value");
    }

    @Test
    void theSameCodeIsRefusedByWhatEachEntryHoldsOnEveryWayIn() throws IOException {
        String picker = StaticSources.script("code-picker.js");
        String addEntry = StaticSources.body(picker, "function addEntry(");
        assertThat(addEntry).contains("existing.input.code === String(candidate.code)").doesNotContain("existing.code ===");
        Matcher appends = Pattern.compile("entries\\s*=\\s*entries\\.concat").matcher(picker);
        int count = 0;
        while (appends.find()) {
            count++;
        }
        assertThat(count).isEqualTo(1);
        assertThat(addEntry).contains("entries = entries.concat");
    }

    @Test
    void aSendWithTextLeftInTheCodeBoxIsRefusedBeforeAnythingIsAsked() throws IOException {
        String send = StaticSources.body(StaticSources.script("wb-run.js"), "export async function sendRun(spec) {");
        int refusal = send.indexOf("const typed = textLeftToRefuse({typed: spec.typed, caseShown: true, sendsCase: spec.sendsCase});\n    if (typed !== '') {");
        int sending = send.indexOf("await call(");
        assertThat(refusal).isGreaterThanOrEqualTo(0);
        assertThat(sending).isGreaterThan(refusal);
        assertThat(send.substring(refusal, sending)).contains("return null;").contains("field: 'procedureCodes'");
        assertThat(StaticSources.script("wb-run.js")).contains("was typed in the procedure code box and not added to the case.");
        assertThat(StaticSources.script("wb-crd.js")).contains("typed: ctx.typedCode()");
    }

    /** The decision and the picker, run: the same code twice is one line, and typed text is not a code of the case. */
    @Test
    void theRefusalHoldsExactlyWhenTheCallSendsTheCaseAndTheCodesAreWhatTheLinesShow(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the code picker", """
                import './browser.js';
                import {textLeftToRefuse} from './js/wb-run.js';
                import {createCodePicker, splitCodes, cutDescription} from './js/code-picker.js';
                const say = value => console.log(JSON.stringify(value));
                globalThis.fetch = async () => ({ok: true, status: 200, text: async () => '{"codes": []}'});
                say([{typed: 'MRI', caseShown: true}, {typed: '  MRI  ', caseShown: true, sendsCase: true}, {typed: '', caseShown: true},
                    {typed: '   ', caseShown: true}, {typed: 'MRI', caseShown: true, sendsCase: false}, {typed: 'MRI', caseShown: false},
                    {typed: 'MRI', caseShown: true, ownRequest: true}, {typed: undefined, caseShown: true}].map(textLeftToRefuse));
                let changes = 0;
                const picker = createCodePicker({lineOfBusiness: () => '', changed: () => { changes++; }});
                picker.replace(['E0470', 'E0470', {code: '95810'}]);
                picker.input.value = 'G0399';
                say([picker.values(), picker.typed(), changes > 0]);
                say([splitCodes('E0470, 95810;G0399 | 81162'), cutDescription('x'.repeat(41)).length, cutDescription('short')]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"MRI\",\"MRI\",\"\",\"\",\"\",\"\",\"\",\"\"]",
                "[[{\"code\":\"E0470\"},{\"code\":\"95810\"}],\"G0399\",true]",
                "[[\"E0470\",\"95810\",\"G0399\",\"81162\"],43,\"short\"]");
    }
}
