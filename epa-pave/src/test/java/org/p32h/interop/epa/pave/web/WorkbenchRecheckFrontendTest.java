package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * What the recheck of the release candidate found in the Workbench, run with Node and the stand-in
 * for the document: a field's refusal goes when its value is replaced by a pick, a lookup or a new
 * case, so it never shows under a value the server did not refuse; and a question that waits on
 * another answer is drawn muted, not required, and takes an answer (I-36).
 */
class WorkbenchRecheckFrontendTest {

    private static final String PRELUDE = """
            import './browser.js';
            import fs from 'node:fs';
            fs.writeFileSync('./js/app.js', [
                'export const app = {',
                '  mode: () => globalThis.fake.mode, isMock: () => true,',
                '  epoch: () => 7, on: () => () => true, go: hash => globalThis.fake.went.push(hash), notice() {}',
                '};'].join(String.fromCharCode(10)));
            globalThis.fake = {mode: {mode: 'mock', epoch: 7}, went: []};
            globalThis.fetch = async () => ({ok: true, status: 200, text: async () => '{}'});
            const say = value => console.log(JSON.stringify(value));
            """;

    @Test
    void aRefusedValueLosesItsRefusalWhenItIsReplaced(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "a refusal that outlives its value", PRELUDE + """
                const {caseField} = await import('./js/wb-case.js');
                const {createProviderCard} = await import('./js/wb-provider.js');
                const shown = control => !control.node.querySelector('.field__error').hidden;
                const field = caseField({name: 'phone', label: 'Phone'});
                field.setError('Not a phone number.');
                field.set('555-0100', 'from lookup');
                const afterSet = shown(field);
                field.setError('Not a phone number.');
                field.clear();
                const afterClear = shown(field);
                field.setError('Not a phone number.');
                field.masked();
                say([afterSet, afterClear, shown(field)]);
                const card = createProviderCard({savedProviders: () => [], placeOfService() {}});
                document.body.append(card.node);
                const npiError = () => card.node.querySelector('.field__error');
                card.show('npi', 'The last digit of this NPI does not match the other nine.');
                const refused = !npiError().hidden;
                card.clear();
                card.useNpi('1234567893');
                say([refused, !npiError().hidden, npiError().textContent]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly("[false,false,false]", "[true,false,\"\"]");
    }

    @Test
    void aQuestionThatWaitsOnAnotherAnswerTakesAnAnswer(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "a question that waits on another answer", PRELUDE + """
                const {questionField} = await import('./js/wb-dtr-items.js');
                const v = value => ({presence: 'VALUE', value});
                const absent = {presence: 'ABSENT'};
                const item = (linkId, enabled, extra) => Object.assign({path: 'item', linkId: v(linkId), text: v('Number of affected relatives'),
                    prefix: absent, type: {presence: 'VALUE', code: 'integer', recognised: true}, question: true, required: v('true'),
                    repeats: absent, readOnly: absent, answerValueSet: absent, answerOptions: [], initial: [], prepopulated: [],
                    enableBehavior: absent, enableWhen: [], enabled, extensions: [], items: [], lists: {}}, extra || {});
                const given = [];
                const off = questionField({item: item('q-2', 'DISABLED', {enabledReason: 'Not asked until q-1 is Yes.'}), depth: 0, held: null,
                    onChange: (id, values) => given.push([id, values])});
                off.input.value = '2';
                off.input.dispatchEvent({type: 'input'});
                say([off.node.className.includes('question--off'), off.input.readOnly === true, Boolean(off.node.querySelector('.required')),
                  off.node.querySelector('.enabled-reason').textContent, given]);
                const fixed = questionField({item: item('q-3', 'ENABLED', {readOnly: v('true')}), depth: 0, held: null, onChange() {}});
                say([fixed.input.readOnly === true, Boolean(fixed.node.querySelector('.required'))]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[true,false,false,\"Not asked until q-1 is Yes.\",[[\"q-2\",[\"2\"]]]]",
                "[true,true]");
    }

    @Test
    void aResponseTableWrapsItsCellsInsideTheFold() throws Exception {
        // Layout is not run here; the rule that lets a cell wrap is what a browser needs to fit it (critic-2).
        String css = StaticSources.withoutComments(StaticSources.read("css/workbench.css"));
        // Wrapping at spaces and hyphens only keeps a column as wide as its longest word, so a code
        // name such as questionnaire is never cut in its middle at 1024 (critic-k3-1).
        assertThat(StaticSources.body(css, ".detail .table td {"))
                .contains("white-space: normal;", "overflow-wrap: break-word;", "word-break: normal;")
                .doesNotContain("anywhere");
    }

    @Test
    void aRefusedMemberIdOrNpiIsNamedByItsLabel(@TempDir Path folder) throws Exception {
        // The ID and NPI rows replace their case field's node; the row must still carry the field's
        // name, or the refusal panel lists the sentence without the label on screen (I-3).
        NodeRun run = StaticSources.runModule(folder, "a refused member ID named by its label", PRELUDE + """
                const {createMemberCard} = await import('./js/wb-member.js');
                const {createProviderCard} = await import('./js/wb-provider.js');
                const member = createMemberCard({isMock: () => true, mockLists: () => null, savedMembers: () => [],
                    dateOfService: () => '', useSample() {}});
                const provider = createProviderCard({savedProviders: () => [], placeOfService() {}});
                const labelIn = (card, name) => {
                    const row = card.node.querySelector('[data-case-field="' + name + '"]');
                    const label = row ? row.querySelector('.field__label') : null;
                    return label && label.childNodes.length > 0 ? String(label.childNodes[0].textContent).trim() : null;
                };
                say([labelIn(member, 'memberId'), labelIn(provider, 'npi')]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly("[\"Member ID\",\"NPI\"]");
    }

    @Test
    void aFailureSaysWhatToChangeAboveItsButton(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the next step of a failure", PRELUDE + """
                const vocabulary = {nextActions: [{code: 'OPEN_CONFIG', button: 'Open Configuration'}, {code: 'START_DTR', button: 'Continue to the questionnaire'}]};
                globalThis.fetch = async () => ({ok: true, status: 200, text: async () => JSON.stringify(vocabulary)});
                const {resultCard} = await import('./js/result.js');
                const headline = 'Set draftOrders type to Left out for Sample Sandbox in Configuration > Environments, then run the check again.';
                const refused = {runId: 'run-7', protocol: 'CRD', operation: 'CRD_ORDER_SIGN', answeredBy: 'VENDOR',
                  verdict: {headline: 'The service did not accept the request as sent (HTTP 400).', tone: 'FAULT'}, expectation: {result: 'unverified'},
                  error: {kind: 'VENDOR_REJECTED', httpStatus: 400, title: 'The service did not accept the request as sent.', issues: []},
                  nextStep: {action: 'OPEN_CONFIG', headline}, ledger: {recorded: true}, timing: {elapsedMillis: 5}};
                const settings = {context: 'workbench', onNext() {}};
                const card = resultCard(refused, settings);
                const passed = resultCard({...refused, error: undefined, verdict: {headline: 'Documentation is required.'},
                  nextStep: {action: 'START_DTR', headline: 'Fetch the questionnaire the answer names.'}}, settings);
                await new Promise(done => setTimeout(done, 30));
                const foot = node => Array.from(node.querySelectorAll('.verdict__next, button')).map(each => each.textContent).filter(text => text !== '');
                say(foot(card).filter(text => text === headline || text === 'Open Configuration'));
                say(foot(passed).filter(text => text.startsWith('Fetch') || text.startsWith('Continue')));
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"Set draftOrders type to Left out for Sample Sandbox in Configuration > Environments, then run the check again.\",\"Open Configuration\"]",
                "[\"Continue to the questionnaire\"]");
    }

    @Test
    void theSignInChipGivesWayBeforeTheEnvironmentChooser() throws Exception {
        // At 1100 px and under the chooser keeps the room to name the environment in force (live-config-7).
        // The chip may now go down to 48 px: the switch between mock and live, in place of the profile
        // badge, is some 60 px wider, and without that the chooser and its kind badge overlapped it at 1024 px.
        String css = StaticSources.withoutComments(StaticSources.read("css/pave.css"));
        String narrow = css.substring(css.indexOf("@media (max-width: 1100px)"));
        assertThat(StaticSources.body(narrow, ".topbar__signin {")).contains("flex-shrink: 8;", "min-width: 48px;", "max-width: 96px;");
    }
}
