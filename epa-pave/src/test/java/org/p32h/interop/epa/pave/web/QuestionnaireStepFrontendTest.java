package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.service.dtr.DtrNextQuestionRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrPackageRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrResponseRequest;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * Step 2 of the Workbench, the questionnaire (wb-dtr.js and wb-dtr-items.js), run with Node against
 * the step module's contract (plan 9.1): a fake ctx and answers of the server's own shape. It holds
 * that Enter in a field of the form never submits anything; that a question that appears or
 * disappears gets one visible line; that the turn count follows the turns the server answered; that
 * "Continue to Submit" is withdrawn, and the hand-over to Submit taken back, while typed answers differ
 * from the completed response; that a payer's prefilled answer starts in its field, marked, and is
 * not sent back as the tester's; and that a request carries only the properties the server's DTR
 * request records have, since those are read strictly.
 */
class QuestionnaireStepFrontendTest {

    @Test
    void everyPropertyAStepPostsIsOneTheRequestRecordHas() throws IOException {
        String script = StaticSources.script("wb-dtr.js");
        Set<String> common = Set.of("member", "memberOverrides", "provider", "providerOverrides", "procedureCodes", "chainId",
                "configEpoch", "dateOfService", "testerRef", "note", "expected", "carry");
        for (Class<?> record : List.of(DtrPackageRequest.class, DtrNextQuestionRequest.class, DtrResponseRequest.class)) {
            assertThat(components(record)).as(record.getSimpleName()).containsAll(common);
        }
        assertThat(components(DtrPackageRequest.class)).contains("questionnaires", "coverageAssertionId", "scenarioId");
        assertThat(components(DtrNextQuestionRequest.class)).contains("questionnaire", "questionnaireResponse", "answers",
                "wrapInParameter", "scenarioId");
        assertThat(components(DtrResponseRequest.class)).contains("questionnaire", "questionnaireResponse", "answers")
                .doesNotContain("scenarioId");
        assertThat(script).contains("body.wrapInParameter", "body.scenarioId", "body.questionnaires", "body.coverageAssertionId");
    }

    private static Set<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
    }

    @Test
    void theFormStepsTurnsWithdrawsSubmitAndNeverSubmitsOnEnter(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the questionnaire step", """
                import './browser.js';
                const v = value => ({presence: 'VALUE', value});
                const absent = {presence: 'ABSENT'};
                const item = (linkId, text, code, extra) => Object.assign({path: 'item', linkId: v(linkId), text: v(text),
                    prefix: absent, type: {presence: 'VALUE', code, recognised: true}, question: true, required: v('true'),
                    repeats: absent, readOnly: absent, answerValueSet: absent, answerOptions: [], initial: [], prepopulated: [],
                    enableBehavior: absent, enableWhen: [], enabled: 'ENABLED', extensions: [], items: [], lists: {}}, extra || {});
                const responseOf = (foundAt, items) => ({foundAt, items: items.map(([linkId, answer, source]) => ({path: 'item',
                    linkId: v(linkId), text: v(linkId), answers: answer === null ? [] : [v(answer)],
                    origins: answer === null ? [] : [{source: v(source), author: absent, roles: []}], answerExtensions: [], items: []})),
                    status: {presence: 'VALUE', code: 'in-progress', display: 'In progress'}, answeredCount: items.filter(each => each[1] !== null).length,
                    json: '{"resourceType":"QuestionnaireResponse"}'});
                const envelope = (operation, outcome, action, detail, carry) => ({runId: 'run-' + Math.random(), protocol: 'DTR', operation,
                    answeredBy: 'SCENARIO', verdict: {headline: outcome + '.', outcomeCode: outcome}, ledger: {recorded: true},
                    nextStep: {action, headline: 'Next.', carry: carry || {runId: ['run-1'], questionnaire: ['https://example.org/Q|1']}}, detail});
                const staticPackage = envelope('DTR_QUESTIONNAIRE_PACKAGE', 'PACKAGE_LOADED', 'SUBMIT_RESPONSE', {
                    questionnaires: [{position: 1, subject: 'Placeholder criteria', kind: 'STATIC', kindShort: 'static form', questionCount: 2,
                        requiredCount: 2, title: v('Placeholder criteria'), arrivedWith: 'entry[1]', json: '{"resourceType":"Questionnaire"}',
                        problems: [], notApplied: [], items: [item('q-1', 'Specialty', 'string'),
                            item('q-2', 'Counselled?', 'choice', {answerOptions: [{label: 'Yes', answerAs: 'yes', value: v('Yes (s|yes)')},
                                {label: 'No', answerAs: 'no', value: v('No (s|no)')}]})]}],
                    questionnaireResponses: [responseOf('entry[1]', [['q-1', 'Medical genetics', 'auto'], ['q-2', null, null]])]});
                const completed = envelope('DTR_QUESTIONNAIRE_PACKAGE', 'RESPONSE_COMPLETED', 'START_PAS', {
                    form: {questionCount: 2, answeredCount: 2, complete: true, status: {presence: 'VALUE', code: 'completed'},
                        requiredUnanswered: [], undetermined: [], asked: [], answers: []},
                    questionnaires: staticPackage.detail.questionnaires, questionnaireResponses: []},
                    {runId: ['run-2'], questionnaireResponse: ['{"resourceType":"QuestionnaireResponse","status":"completed"}']});
                const adaptivePackage = envelope('DTR_QUESTIONNAIRE_PACKAGE', 'PACKAGE_LOADED', 'ANSWER_NEXT_QUESTION', {
                    questionnaires: [{position: 1, subject: 'Adaptive', kind: 'ADAPTIVE', questionCount: 0, requiredCount: 0,
                        arrivedWith: 'entry[1]', json: '{}', problems: [], notApplied: [], items: []}],
                    questionnaireResponses: [responseOf('entry[1]', [])]});
                const turn = (asked, appeared, disappeared) => envelope('DTR_NEXT_QUESTION', 'NEXT_QUESTION', 'ANSWER_NEXT_QUESTION', {
                    form: {questionCount: asked.length, answeredCount: 0, complete: false, status: {presence: 'VALUE', code: 'in-progress', display: 'In progress'},
                        requiredUnanswered: [], undetermined: [], asked: [], answers: [],
                        changes: {compared: true, summary: 'Changed.', appeared, disappeared, answersDropped: []}},
                    questionnaires: [{position: 1, subject: 'Adaptive', kind: 'ADAPTIVE', questionCount: asked.length, requiredCount: 0,
                        json: '{}', problems: [], notApplied: [], items: asked.map(([id, text]) => item(id, text, 'integer'))}],
                    questionnaireResponses: [Object.assign(responseOf('qr', []), {json: '{"turn":' + asked.length + '}'})]});

                const answers = [];
                const posted = [];
                globalThis.fetch = async (address, init) => {
                    const path = String(address);
                    const body = init && init.body ? JSON.parse(init.body) : null;
                    if (body) {
                        posted.push({path, body});
                    }
                    const data = path === '/api/vocabulary' ? {outcomes: {DTR: []}, nextActions: [{code: 'START_PAS', button: 'Continue to Submit'},
                        {code: 'SUBMIT_RESPONSE', button: 'Complete the response'}, {code: 'ANSWER_NEXT_QUESTION', button: 'Send answers and get the next question'}], answeredBy: []}
                        : answers.shift();
                    return {ok: true, status: 200, text: async () => JSON.stringify(data)};
                };
                const handed = [];
                const ctx = {
                    request: () => ({member: {memberId: 'S123456789'}, memberOverrides: [], provider: {npi: '1234567893'}, providerOverrides: [],
                        servicingProvider: {npi: '1234567893'}, procedureCodes: [{code: '81162'}], placeOfService: '11', diagnosisCodes: 'Z80.3',
                        levelOfService: 'U', chainId: 'new', configEpoch: 3}),
                    carry: () => null, handOn: (step, carry) => handed.push([step, carry ? Object.keys(carry).sort().join(',') : null]),
                    option: () => '', optionsRow: () => null, optionsToggle: () => null, resulted: () => null, showField: () => false,
                    placeErrors: () => [], go: () => null, isMock: () => false, visible: () => true, typedCode: () => '', blocked: () => null,
                    readinessLine: () => null, latest: () => null, refused: () => null, handedLine: () => null,
                    card: envelope => {
                        const {el} = globalThis.domForTest;
                        return el('div', {class: 'result'}, el('div', {class: 'verdict__foot'},
                            envelope.nextStep.action === 'START_PAS' ? el('button', {class: 'btn btn--primary'}, 'Continue to Submit') : null),
                            globalThis.domForTest.fold({summary: 'Why', body: () => el('p', null, 'The card says why.')}));
                    }
                };
                globalThis.domForTest = await import('./js/dom.js');
                const {mount} = await import('./js/wb-dtr.js');
                const root = globalThis.document.createElement('div');
                const step = mount(root, ctx);
                const settle = () => new Promise(done => setTimeout(done, 30));
                const say = value => console.log(JSON.stringify(value));
                const button = words => root.querySelectorAll('button').find(each => each.textContent === words);

                // A static form: the payer's prefilled answer starts in its field, marked.
                answers.push(staticPackage);
                await button('Fetch questionnaire').click();
                await settle();
                const specialty = root.querySelector('[data-link-id=q-1] input');
                say([specialty.value, root.querySelector('[data-link-id=q-1] .mark').textContent, root.querySelectorAll('form').length]);
                // Enter in a field submits nothing.
                let prevented = false;
                root.querySelector('[data-link-id=q-1]').dispatchEvent({type: 'keydown', key: 'Enter', target: specialty, preventDefault() { prevented = true; }});
                // The one post so far is the package itself.
                say([prevented, posted.length]);
                // Only the tester's own answer goes; the prefilled one is not sent back as theirs.
                const yes = root.querySelectorAll('[data-link-id=q-2] input').find(each => each.value === 'yes');
                yes.checked = true;
                yes.dispatchEvent({type: 'change'});
                answers.push(completed);
                await button('Complete the response').click();
                await settle();
                const sent = posted[posted.length - 1];
                say([sent.path, JSON.stringify(sent.body.answers), Object.keys(sent.body).sort().join(',')]);
                const cont = () => root.querySelector('.dtr-step-result .btn--primary');
                // One filled button and one Why fold once Continue is offered (I-6, look-10).
                const sendFilled = () => root.querySelector('.dtr-send').classList.contains('btn--primary');
                const whys = root.querySelectorAll('summary').filter(each => each.textContent.startsWith('Why'));
                whys[0].parentNode.open = true;
                whys[0].parentNode.dispatchEvent({type: 'toggle'});
                say([cont().disabled, root.querySelector('.dtr-withdrawn').hidden, handed[handed.length - 1], sendFilled(), whys.length,
                  whys[0].parentNode.textContent.includes('The card says why.') && whys[0].parentNode.textContent.includes('no SMART launch')]);
                // A typed answer that differs from the completed response withdraws Continue and the hand-over.
                const no = root.querySelectorAll('[data-link-id=q-2] input').find(each => each.value === 'no');
                yes.checked = false;
                no.checked = true;
                no.dispatchEvent({type: 'change'});
                await settle();
                say([cont().disabled, cont().title, root.querySelector('.dtr-withdrawn').hidden, handed[handed.length - 1], sendFilled()]);
                // Put back as completed, it is offered again.
                no.checked = false;
                yes.checked = true;
                yes.dispatchEvent({type: 'change'});
                await settle();
                say([cont().disabled, handed[handed.length - 1]]);

                // An adaptive questionnaire: one visible line per question that appears or disappears, and the turn count.
                step.reset();
                answers.push(adaptivePackage);
                await button('Fetch questionnaire').click();
                await settle();
                say(root.querySelector('.dtr-count').textContent);
                answers.push(turn([['a-1', 'How many weeks?']], [{linkId: 'a-1', text: 'How many weeks?'}], []));
                await button('Send answers and get the next question').click();
                await settle();
                say([root.querySelector('.dtr-count').textContent, root.querySelectorAll('.dtr-change').map(each => each.textContent)]);
                answers.push(turn([['a-2', 'Which side']], [{linkId: 'a-2', text: 'Which side'}], [{linkId: 'a-1', text: 'How many weeks?'}]));
                await button('Send answers and get the next question').click();
                await settle();
                say([root.querySelector('.dtr-count').textContent, root.querySelectorAll('.dtr-change').map(each => each.textContent)]);
                const loop = posted[posted.length - 1].body;
                say([loop.questionnaireResponse, 'servicingProvider' in loop, 'placeOfService' in loop, 'scenarioId' in loop]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"Medical genetics\",\"payer prefilled\",0]",
                "[true,1]",
                "[\"/api/dtr/response\",\"[{\\\"linkId\\\":\\\"q-2\\\",\\\"values\\\":[\\\"yes\\\"]}]\","
                        + "\"answers,carry,chainId,configEpoch,member,memberOverrides,procedureCodes,provider,providerOverrides,questionnaire,questionnaireResponse\"]",
                "[false,true,[3,\"questionnaireResponse,runId\"],false,1,true]",
                "[true,\"Complete the response again before Submit.\",false,[3,null],true]",
                "[false,[3,\"questionnaireResponse,runId\"]]",
                "\"0 of 0 answered \u00b7 turn 0 \u00b7 in progress\"",
                "[\"0 of 1 answered \u00b7 turn 1 \u00b7 in progress\",[\"Now asked: How many weeks?\"]]",
                "[\"0 of 1 answered \u00b7 turn 2 \u00b7 in progress\",[\"Now asked: Which side.\",\"No longer asked: How many weeks?\"]]",
                "[\"{\\\"turn\\\":1}\",false,false,false]");
    }
}
