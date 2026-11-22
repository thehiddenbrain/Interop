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
import org.p32h.interop.epa.pave.service.dtr.CaseAnswers;
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
 * not sent back as the tester's; that answers from the case (plan R-18) go only into empty fields,
 * marked, never over the payer's answer or a typed one, and are never put back into a field the tester
 * cleared; and that a request carries only the properties the server's request records have, since
 * those are read strictly.
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

    @Test
    void theCaseAnswersPostCarriesExactlyTheCaseFieldsTheRequestRecordReads() throws IOException {
        // The request is read strictly, so a property the record lacks would refuse every post.
        assertThat(components(CaseAnswers.Request.class)).containsExactlyInAnyOrder("questionnaire", "member", "provider",
                "procedureCodes", "dateOfService", "placeOfService", "levelOfService");
        assertThat(StaticSources.script("wb-dtr.js")).contains("'/api/dtr/case-answers'",
                "['dateOfService', 'placeOfService', 'levelOfService']", "questionnaire:", "member: request.member",
                "provider: request.provider", "procedureCodes: request.procedureCodes");
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
                    // The case answers nothing here; that step is held by the test of answers from the case.
                    if (path === '/api/dtr/case-answers') {
                        return {ok: true, status: 200, text: async () => JSON.stringify({answers: []})};
                    }
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
                            envelope.nextStep.action === 'START_PAS' ? el('button', {class: 'btn btn--primary'}, 'Continue to Submit') : null));
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
                say([cont().disabled, root.querySelector('.dtr-withdrawn').hidden, handed[handed.length - 1]]);
                // A typed answer that differs from the completed response withdraws Continue and the hand-over.
                const no = root.querySelectorAll('[data-link-id=q-2] input').find(each => each.value === 'no');
                yes.checked = false;
                no.checked = true;
                no.dispatchEvent({type: 'change'});
                await settle();
                say([cont().disabled, cont().title, root.querySelector('.dtr-withdrawn').hidden, handed[handed.length - 1]]);
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
                "[false,true,[3,\"questionnaireResponse,runId\"]]",
                "[true,\"Complete the response again before Submit.\",false,[3,null]]",
                "[false,[3,\"questionnaireResponse,runId\"]]",
                "\"0 of 0 answered \u00b7 turn 0 \u00b7 in progress\"",
                "[\"0 of 1 answered \u00b7 turn 1 \u00b7 in progress\",[\"Now asked: How many weeks?\"]]",
                "[\"0 of 1 answered \u00b7 turn 2 \u00b7 in progress\",[\"Now asked: Which side.\",\"No longer asked: How many weeks?\"]]",
                "[\"{\\\"turn\\\":1}\",false,false,false]");
    }

    @Test
    void answersFromTheCaseGoOnlyIntoEmptyFieldsMarkedAndNeverOverThePayersOrTheTesters(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "answers from the case", """
                import './browser.js';
                const v = value => ({presence: 'VALUE', value});
                const absent = {presence: 'ABSENT'};
                const item = (linkId, text, code, extra) => Object.assign({path: 'item', linkId: v(linkId), text: v(text),
                    prefix: absent, type: {presence: 'VALUE', code, recognised: true}, question: true, required: absent,
                    repeats: absent, readOnly: absent, answerValueSet: absent, answerOptions: [], initial: [], prepopulated: [],
                    enableBehavior: absent, enableWhen: [], enabled: 'ENABLED', extensions: [], items: [], lists: {}}, extra || {});
                const held = (linkId, answer) => ({path: 'item', linkId: v(linkId), text: v(linkId), answers: [v(answer)],
                    origins: [{source: v('auto'), author: absent, roles: []}], answerExtensions: [], items: []});
                const pkg = {runId: 'run-1', protocol: 'DTR', operation: 'DTR_QUESTIONNAIRE_PACKAGE', answeredBy: 'SCENARIO',
                    verdict: {headline: 'Loaded.', outcomeCode: 'PACKAGE_LOADED'}, ledger: {recorded: true},
                    nextStep: {action: 'SUBMIT_RESPONSE', headline: 'Next.', carry: {runId: ['run-1']}},
                    detail: {questionnaires: [{position: 1, subject: 'Placeholder criteria', kind: 'STATIC', kindShort: 'static form',
                        questionCount: 6, requiredCount: 0, title: v('Placeholder criteria'), arrivedWith: 'entry[1]',
                        json: '{"resourceType":"Questionnaire","id":"placeholder"}', problems: [], notApplied: [], items: [
                            item('q-dob', 'Date of birth', 'date'),
                            item('q-spec', 'Specialty', 'string'),
                            item('q-npi', 'Ordering provider NPI', 'string'),
                            item('q-lob', 'Line of business', 'choice', {answerOptions: [{label: 'Medicare', answerAs: 'medicare', value: v('Medicare')},
                                {label: 'Commercial', answerAs: 'commercial', value: v('Commercial')}]}),
                            item('q-when', 'When was the last test done', 'date'),
                            item('q-off', 'Date of the earlier test', 'date', {enabled: 'DISABLED', enabledReason: 'Disabled: its condition does not hold.'})]}],
                        questionnaireResponses: [{foundAt: 'entry[1]', items: [held('q-spec', 'Medical genetics')],
                            status: {presence: 'VALUE', code: 'in-progress', display: 'In progress'}, answeredCount: 1,
                            json: '{"resourceType":"QuestionnaireResponse"}'}]}};
                const done = Object.assign({}, pkg, {runId: 'run-2', verdict: {headline: 'Completed.', outcomeCode: 'RESPONSE_COMPLETED'},
                    nextStep: {action: 'START_PAS', headline: 'Next.', carry: {runId: ['run-2'], questionnaireResponse: ['{}']}},
                    detail: Object.assign({}, pkg.detail, {form: {questionCount: 6, answeredCount: 4, complete: true,
                        status: {presence: 'VALUE', code: 'completed'}, requiredUnanswered: [], undetermined: [], asked: [], answers: []}})});
                // What the server answers: every question it can, the payer's and a disabled one too, so the page is what keeps them.
                const fromCase = date => ({answers: [
                    {linkId: 'q-dob', values: ['1960-01-01'], field: 'member.dateOfBirth', mark: 'from the case'},
                    {linkId: 'q-spec', values: ['Placeholder specialty'], field: 'provider.specialty', mark: 'from the case'},
                    {linkId: 'q-npi', values: ['1234567893'], field: 'provider.npi', mark: 'from the case'},
                    {linkId: 'q-lob', values: ['commercial'], field: 'member.lineOfBusiness', mark: 'from the case'},
                    {linkId: 'q-off', values: ['1960-01-01'], field: 'member.dateOfBirth', mark: 'from the case'}]
                    .concat(date ? [{linkId: 'q-when', values: [date], field: 'dateOfService', mark: 'default, change if needed'}] : [])});
                const runs = [];
                const posted = [];
                const asked = [];
                let release = null;
                globalThis.fetch = async (address, init) => {
                    const path = String(address);
                    const body = init && init.body ? JSON.parse(init.body) : null;
                    let data;
                    if (path === '/api/dtr/case-answers') {
                        asked.push(body);
                        // The answer is held back until the test lets it go, so the tester can type first.
                        data = await new Promise(go => { release = () => go(fromCase(body.dateOfService)); });
                    } else if (path === '/api/vocabulary') {
                        data = {outcomes: {DTR: []}, nextActions: [{code: 'SUBMIT_RESPONSE', button: 'Complete the response'}], answeredBy: []};
                    } else {
                        posted.push({path, body});
                        data = runs.shift();
                    }
                    return {ok: true, status: 200, text: async () => JSON.stringify(data)};
                };
                let dateOfService = '';
                const shown = [];
                const ctx = {
                    request: () => Object.assign({member: {memberId: 'S123456789', dateOfBirth: '1960-01-01', lineOfBusiness: 'commercial'},
                        memberOverrides: [], provider: {npi: '1234567893', specialty: 'Placeholder specialty'}, providerOverrides: [],
                        servicingProvider: {npi: '1234567893'}, procedureCodes: [{code: '81162'}], placeOfService: '', diagnosisCodes: 'Z80.3',
                        levelOfService: 'U', certificationType: 'I', chainId: 'new', configEpoch: 3}, dateOfService ? {dateOfService} : {}),
                    carry: () => null, handOn: () => null, option: () => '', optionsRow: () => null, optionsToggle: () => null,
                    resulted: () => null, showField: name => { shown.push(name); return true; }, placeErrors: () => [], go: () => null,
                    isMock: () => false, visible: () => true, typedCode: () => '', blocked: () => null, readinessLine: () => null,
                    latest: () => null, refused: () => null, handedLine: () => null,
                    card: () => globalThis.document.createElement('div')
                };
                const {mount} = await import('./js/wb-dtr.js');
                const root = globalThis.document.createElement('div');
                const step = mount(root, ctx);
                const settle = () => new Promise(go => setTimeout(go, 30));
                const say = value => console.log(JSON.stringify(value));
                const button = words => root.querySelectorAll('button').find(each => each.textContent === words);
                const input = linkId => root.querySelector('[data-link-id=' + linkId + '] input');
                const mark = linkId => root.querySelector('[data-link-id=' + linkId + '] .mark').textContent;
                const hints = linkId => root.querySelectorAll('[data-link-id=' + linkId + '] .field__hint').map(each => each.textContent);

                runs.push(pkg);
                await button('Fetch questionnaire').click();
                await settle();
                // Asked once, with the questionnaire on screen and only the case fields R-18 names; a blank one is not sent.
                say([asked.length, Object.keys(asked[0]).sort().join(','), asked[0].questionnaire]);
                // With no date of service on the Service card the date line shows; its button shows the field.
                const line = root.querySelector('.dtr-date-line');
                say([line.hidden, line.textContent]);
                line.querySelector('button').click();
                say(shown);
                // The tester types an NPI before the answers from the case arrive.
                input('q-npi').value = '1000000004';
                input('q-npi').dispatchEvent({type: 'input'});
                release();
                await settle();
                // The payer's answer and the typed one stay; the empty fields take the case's, marked; the disabled
                // question takes nothing and says nothing of why.
                say([input('q-dob').value, mark('q-dob'), input('q-spec').value, mark('q-spec'), input('q-npi').value, mark('q-npi'),
                    root.querySelectorAll('[data-link-id=q-lob] input').filter(each => each.checked).map(each => each.value), mark('q-lob'),
                    input('q-off').value, hints('q-off')]);
                say([root.querySelector('.dtr-case-chip').hidden, root.querySelector('.dtr-case-chip').textContent, line.hidden]);
                // The tester clears the date of birth: the mark goes, nothing is sent for it, and it is never filled again.
                input('q-dob').value = '';
                input('q-dob').dispatchEvent({type: 'input'});
                say([mark('q-dob'), root.querySelector('.dtr-case-chip').textContent]);
                // A date of service typed on the Service card: the answers are asked for again after the quiet time,
                // and the date question takes it as a default; the cleared one stays empty.
                dateOfService = '2026-01-10';
                step.caseChanged();
                await new Promise(go => setTimeout(go, 560));
                release();
                await settle();
                say([asked.length, asked[1].dateOfService, input('q-when').value, mark('q-when'), input('q-dob').value, line.hidden]);
                // Completing sends the answers from the case as the tester's own, beside the typed one; never the payer's.
                runs.push(done);
                await button('Complete the response').click();
                await settle();
                say(posted[posted.length - 1].body.answers.map(each => each.linkId + '=' + each.values.join('|')).sort());
                // Drawn again after the step, each answer from the case keeps its mark.
                say([mark('q-lob'), mark('q-when'), mark('q-spec'), mark('q-npi'), asked.length]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[1,\"levelOfService,member,procedureCodes,provider,questionnaire\",\"{\\\"resourceType\\\":\\\"Questionnaire\\\",\\\"id\\\":\\\"placeholder\\\"}\"]",
                "[false,\"Date questions take the date of service from the Service card. Show the field\"]",
                "[\"dateOfService\"]",
                "[\"1960-01-01\",\"from the case\",\"Medical genetics\",\"payer prefilled\",\"1000000004\",\"\",[\"commercial\"],"
                        + "\"from the case\",\"\",[]]",
                "[false,\"2 from the case\",false]",
                "[\"\",\"1 from the case\"]",
                "[2,\"2026-01-10\",\"2026-01-10\",\"default, change if needed\",\"\",true]",
                "[\"q-lob=commercial\",\"q-npi=1000000004\",\"q-when=2026-01-10\"]",
                "[\"from the case\",\"default, change if needed\",\"payer prefilled\",\"\",2]");
    }
}
