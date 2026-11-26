package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * The screens of D-321, run with Node and a stand-in for the document: the servicing provider is a
 * part of the Provider card in its own right, one line while none is named and open once one is,
 * with the words "Submit only" gone; the Service card says where the diagnosis codes go; the
 * questionnaire request sends both and the later questionnaire requests neither; and a reload reads
 * both back whichever run came last. Placeholders only.
 */
class DiagnosisAndServicingScreensFrontendTest {

    @Test
    void theServicingProviderIsAPartOfTheProviderCardSeenWithoutOpeningAnything(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the Provider card's servicing part", """
                import './browser.js';
                import {createProviderCard} from './js/wb-provider.js';
                const say = value => console.log(JSON.stringify(value));
                globalThis.fetch = async () => ({ok: true, status: 200, text: async () => '{}'});
                const card = createProviderCard({savedProviders: () => [{id: 's-1', label: 'Placeholder Sleep Laboratory', npi: '9999999995',
                  organization: 'Placeholder Sleep Laboratory'}], placeOfService: () => null});
                document.body.append(card.node);
                const part = card.node.querySelector('[data-servicing]');
                const closed = part.querySelector('.servicing__closed');
                const body = part.querySelector('.servicing__body');
                // Not inside "Edit provider fields": no fold holds it.
                let inside = false;
                for (let node = part.parentNode; node; node = node.parentNode) {
                  inside = inside || node.tagName === 'DETAILS';
                }
                say([inside, closed.hidden, body.hidden, closed.textContent, card.request().servicingProvider]);
                say(card.node.textContent.includes('Submit only'));
                // "Add one" opens the part, with its line.
                closed.querySelector('button').dispatchEvent({type: 'click'});
                say([closed.hidden, body.hidden, part.querySelector('.servicing__hint').textContent]);
                // A pick from the saved providers names one.
                const picker = part.querySelector('#servicing-picker');
                picker.value = 's-1';
                picker.dispatchEvent({type: 'change'});
                say([card.request().servicingProvider, card.servicingLabel()]);
                // Removed: one line again, and nothing sent.
                Array.from(part.querySelectorAll('button')).find(each => each.textContent === 'Remove the servicing provider')
                  .dispatchEvent({type: 'click'});
                say([closed.hidden, body.hidden, card.request().servicingProvider, card.servicingLabel()]);
                // Read back after a reload: open, with the provider named.
                card.restore({npi: '1234567893'}, [], {npi: '9999999995', lastName: 'Servicing'}, 'from the trail');
                say([closed.hidden, body.hidden, card.request().servicingProvider, card.servicingLabel()]);
                // A refusal of its NPI opens it at the field.
                card.clear();
                say([closed.hidden, card.show('servicingProvider.npi', 'The NPI fails its check digit.'), closed.hidden]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[false,false,true,\"Servicing provider: none. Add one\",null]",
                "false",
                "[true,false,\"Who performs the service. Sent as the order's performer in the coverage check and the questionnaire "
                        + "request, and on the care team in Submit.\"]",
                "[{\"npi\":\"9999999995\",\"organizationName\":\"Placeholder Sleep Laboratory\"},\"Placeholder Sleep Laboratory\"]",
                "[false,true,null,\"\"]",
                "[true,false,{\"npi\":\"9999999995\",\"lastName\":\"Servicing\"},\"Servicing\"]",
                "[false,true,true]");
    }

    @Test
    void theServiceCardSaysWhereTheDiagnosisCodesGo(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the Service card's diagnosis line", """
                import './browser.js';
                import {createServiceCard} from './js/wb-service.js';
                const say = value => console.log(JSON.stringify(value));
                globalThis.fetch = async () => ({ok: true, status: 200, text: async () => '{"codes": []}'});
                const card = createServiceCard({lineOfBusiness: () => '', options: () => null});
                document.body.append(card.node);
                say(card.node.querySelector('[data-case-field="diagnosisCodes"] .field__hint').textContent);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly("\"Sent as the order's reason in the coverage check and the questionnaire request, and as "
                + "the claim's diagnosis in Submit.\"");
    }

    @Test
    void theQuestionnaireRequestSendsBothAndTheLaterRequestsNeither(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the questionnaire bodies", """
                import './browser.js';
                import {packageBody, caseBody} from './js/wb-dtr.js';
                const say = value => console.log(JSON.stringify(value));
                const theCase = {member: {memberId: 'S123456789'}, provider: {npi: '1234567893'}, procedureCodes: [{code: 'E0601'}],
                  diagnosisCodes: ['G47.33'], servicingProvider: {npi: '9999999995'}, chainId: 'new', configEpoch: 2};
                const own = {expected: '', questionnaireUrl: '', coverageAssertionId: '', mock: false, scenarioId: ''};
                const sent = packageBody(theCase, own, null);
                say([sent.diagnosisCodes, sent.servicingProvider]);
                say(['diagnosisCodes' in caseBody(theCase, own), 'servicingProvider' in caseBody(theCase, own)]);
                say(['diagnosisCodes' in packageBody({...theCase, diagnosisCodes: [], servicingProvider: null}, own, null),
                  'servicingProvider' in packageBody({...theCase, diagnosisCodes: [], servicingProvider: null}, own, null)]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[[{\"code\":\"G47.33\"}],{\"npi\":\"9999999995\"}]",
                "[false,false]",
                "[false,false]");
    }

    @Test
    void aReloadAndALoadIntoTheWorkbenchKeepBothWhicheverRunCameLast(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the case read back", """
                import './browser.js';
                import {casePosted} from './js/wb-journey.js';
                const say = value => console.log(JSON.stringify(value));
                const kept = (operation, at, theCase) => ({at, entry: {envelope: {operation}, case: theCase}});
                const crd = kept('CRD_ORDER_SIGN', '2026-11-23T10:00:00Z', {member: {memberId: 'S123456789'}, diagnosisCodes: [{code: 'G47.33'}],
                  servicingProvider: {npi: '9999999995'}});
                const next = kept('DTR_NEXT_QUESTION', '2026-11-23T10:05:00Z', {member: {memberId: 'S123456789'}});
                const inquiry = kept('PAS_INQUIRE', '2026-11-23T10:09:00Z', {member: {memberId: 'S123456789'}});
                // The newest run sends neither: both are read from the newest run of a step that sends them.
                say(casePosted(inquiry.entry, {1: crd, 2: next, inquiry}));
                say(casePosted(next.entry, {1: crd, 2: next}));
                // The newest run sends them and named none: none is put back from an earlier run.
                const submit = kept('PAS_SUBMIT', '2026-11-23T10:07:00Z', {member: {memberId: 'S123456789'}});
                say(casePosted(submit.entry, {1: crd, 3: submit}));
                // Nothing to read from: the case as posted.
                say(casePosted(inquiry.entry, {inquiry}));
                say(casePosted(null, {}));
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "{\"member\":{\"memberId\":\"S123456789\"},\"diagnosisCodes\":[{\"code\":\"G47.33\"}],\"servicingProvider\":{\"npi\":\"9999999995\"}}",
                "{\"member\":{\"memberId\":\"S123456789\"},\"diagnosisCodes\":[{\"code\":\"G47.33\"}],\"servicingProvider\":{\"npi\":\"9999999995\"}}",
                "{\"member\":{\"memberId\":\"S123456789\"}}",
                "{\"member\":{\"memberId\":\"S123456789\"}}",
                "{}");
    }
}
