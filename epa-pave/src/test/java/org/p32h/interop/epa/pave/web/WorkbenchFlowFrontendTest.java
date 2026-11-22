package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * The journey of a case through its four steps (plan section 4), run with Node and a stand-in for the
 * document: the journey changes only when a result arrives, never on a refused send, so a step not
 * sent is never counted as sent; a hand-over goes only with the case it was made for, and the same
 * case typed another way keeps it; a masked value is named and never handed on; a refusal for a mask
 * alone withdraws the Continue that led to it; the words of a hand-over not made are the page's own;
 * and the page never works out which scenario an ID plays: the body carries a scenario only when one
 * is chosen on mock.
 */
class WorkbenchFlowFrontendTest {

    /** The journey, its hand-overs and the CRD body, run. */
    @Test
    void theJourneyMovesOnlyWithAResultAndAHandOverGoesOnlyWithItsCase(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the journey", """
                import './browser.js';
                import {createJourney, refusedForAMask, stepOfAction} from './js/wb-journey.js';
                import {caseKey} from './js/wb-case.js';
                import {crdBody} from './js/wb-crd.js';
                const say = value => console.log(JSON.stringify(value));
                const request = (memberId, codes) => ({member: {memberId}, provider: {npi: '1234567893'}, procedureCodes: codes.map(code => ({code}))});
                const key = caseKey(request('S123456789', ['E0470']));
                const journey = createJourney();
                // Nothing has happened yet: every step unsent, and the first run starts a new chain.
                say([journey.chainId(), journey.result(1), journey.carry(2, key)]);
                // A refusal is not a result: the journey does not move.
                journey.refused(1, {kind: 'VALIDATION', fieldErrors: [{field: 'member.memberId', message: 'Enter a member ID.'}]});
                say([journey.result(1), journey.knownChain(), journey.offered(2)]);
                const envelope = {runId: 'run-1', chainId: 'chain-1', operation: 'CRD_ORDER_SIGN', protocol: 'CRD',
                  nextStep: {action: 'START_DTR', carry: {questionnaire: ['https://example.org/Questionnaire/q'], coverageAssertionId: ['[secret clientSecret]'], runId: ['run-1']},
                    carryLabels: {coverageAssertionId: 'Coverage assertion id'}}};
                journey.resulted(1, envelope, key, 7);
                say([journey.chainId(), journey.result(1).envelope.runId, journey.result(2)]);
                // The carry goes with its case only, without its masked values.
                say(journey.carry(2, key).carry);
                say(journey.carry(2, caseKey(request('S123456788', ['E0470']))));
                say(journey.carry(2, caseKey(request('S123456789', ['E0470', '95810']))));
                // The same case typed another way keeps its journey.
                say(journey.carry(2, caseKey(request('S1234567 89', ['E0470']))) !== null);
                // The masked value is named, never sent; and the words for a case that changed and a step run alone are the page's.
                say(journey.handedLine(2, key));
                say(journey.handedLine(2, caseKey(request('S123456788', ['E0470']))));
                say(journey.handedLine(3, key));
                // A refusal for a mask alone withdraws the Continue into that step; another refusal does not.
                journey.refused(2, {kind: 'VALIDATION', fieldErrors: [{field: 'questionnaire', message: 'The value [secret clientSecret] is a mask.'}]});
                say([journey.offered(2), refusedForAMask({title: 'Refused', fieldErrors: [{field: 'x', message: 'Enter a value.'}]}), refusedForAMask(null)]);
                journey.resulted(1, envelope, key, 8);
                say(journey.offered(2));
                // A step handed on by a module.
                journey.handOn(4, {claimId: ['claim-1'], runId: ['run-3']}, key);
                say([journey.carry(4, key).carry, journey.carry(4, 'other')]);
                // Start a new case: everything forgotten.
                journey.reset();
                say([journey.chainId(), journey.result(1), journey.carry(2, key), journey.carry(4, key)]);
                say(['START_DTR', 'START_PAS', 'CHECK_STATUS', 'OPEN_CONFIG'].map(stepOfAction));
                // The page never works out a scenario from an ID: the body carries the member ID as typed and a scenario only when chosen on mock.
                const base = {member: {memberId: 'P32H-DOC-DTR'}, memberOverrides: [], provider: {npi: '1234567893'}, providerOverrides: [],
                  procedureCodes: [{code: 'E0470'}], chainId: 'new', configEpoch: 5};
                say(Object.keys(crdBody(base, {hook: 'order-sign', includePrefetch: true, expected: '', mock: true, scenarioId: ''})));
                say(crdBody(base, {hook: 'order-sign', includePrefetch: true, expected: '', mock: false, scenarioId: 'crd-04-doc-dtr'}).scenarioId);
                say(crdBody(base, {hook: 'order-sign', includePrefetch: false, expected: 'AUTH_REQUIRED', mock: true, scenarioId: 'crd-04-doc-dtr'}).scenarioId);

                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"new\",null,null]",
                "[null,null,true]",
                "[\"chain-1\",\"run-1\",null]",
                "{\"questionnaire\":[\"https://example.org/Questionnaire/q\"],\"runId\":[\"run-1\"]}",
                "null",
                "null",
                "true",
                "{\"kind\":\"handed\",\"masked\":[\"Coverage assertion id: masked, not handed on\"]}",
                "{\"kind\":\"changed\",\"words\":\"Not handed on: the case changed since step 1.\"}",
                "{\"kind\":\"none\",\"words\":\"Not handed on: nothing came from step 2. Sent from the case and the options.\"}",
                "[false,false,false]",
                "true",
                "[{\"claimId\":[\"claim-1\"],\"runId\":[\"run-3\"]},null]",
                "[\"new\",null,null,null]",
                "[2,3,4,null]",
                "[\"hook\",\"member\",\"memberOverrides\",\"provider\",\"providerOverrides\",\"procedureCodes\",\"includePrefetch\",\"chainId\",\"configEpoch\"]",
                "undefined",
                "\"crd-04-doc-dtr\"");
    }

    /** Sending a run, and the stepper's chips before and after a result. */
    @Test
    void aSendRefusedInThePageAsksNothingAndAStepNotSentIsNotCountedAsSent(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the sending of a run", """
                import './browser.js';
                import {sendRun, textLeftToRefuse, busyLine} from './js/wb-run.js';
                import {createStepper} from './js/wb-stepper.js';
                import {el} from './js/dom.js';
                const say = value => console.log(JSON.stringify(value));
                let asked = 0;
                let answer = {status: 200, body: {runId: 'run-1', operation: 'CRD_ORDER_SIGN'}};
                globalThis.fetch = async () => { asked++; return {ok: answer.status < 300, status: answer.status, text: async () => JSON.stringify(answer.body)}; };
                const seen = [];
                const ctx = {placeErrors: errors => errors.map(each => each.field)};
                const spec = typed => ({path: '/api/crd', body: {}, busy: busyLine(), busyWords: 'Running the check...', buttons: [el('button')], typed, ctx,
                  onResult: envelope => seen.push('result ' + envelope.runId), onRefused: (error, placed) => seen.push('refused ' + placed.join(',')),
                  onFailed: error => seen.push('failed ' + (error && error.kind))});
                // Text left in the code box: refused in the page, nothing asked of the server.
                await sendRun(spec('MRI'));
                say([asked, seen]);
                await sendRun(spec(''));
                answer = {status: 422, body: {kind: 'VALIDATION', title: 'Refused', fieldErrors: [{field: 'provider.npi', message: 'The NPI fails its check digit.'}]}};
                await sendRun(spec(''));
                answer = {status: 403, body: {runId: 'run-2', operation: 'PAS_SUBMIT', error: {kind: 'BLOCKED', title: 'Refused by the production guardrail'}}};
                await sendRun(spec(''));
                answer = {status: 500, body: {kind: 'INTERNAL', title: 'x', reference: 'r-1', cause: 'java.lang.IllegalStateException'}};
                await sendRun(spec(''));
                say([asked, seen]);
                say(['MRI', '  MRI ', ''].map(typed => textLeftToRefuse({typed, caseShown: true})).concat([textLeftToRefuse({typed: 'MRI', caseShown: true, sendsCase: false}),
                  textLeftToRefuse({typed: 'MRI', caseShown: true, ownRequest: true}), textLeftToRefuse({typed: undefined, caseShown: true})]));
                // A step not sent is not counted as sent: before a result every chip is the vocabulary's NOT_RUN.
                const words = {steps: [{step: 'COVERAGE', name: 'Coverage check'}, {step: 'QUESTIONNAIRE', name: 'Questionnaire'}, {step: 'SUBMIT', name: 'Submit'},
                  {step: 'STATUS', name: 'Status'}], stepStates: {NOT_RUN: {text: 'Not run'}},
                  outcomes: {CRD: [{code: 'DOCUMENTATION_REQUIRED', short: 'Documentation required', tone: 'ACTION'}]}};
                const stepper = createStepper(() => null);
                stepper.draw(words, null, () => null);
                say(stepper.node.textContent);
                stepper.draw(words, null, n => (n === 1 ? {protocol: 'CRD', verdict: {outcomeCode: 'DOCUMENTATION_REQUIRED'}} : null));
                say(stepper.node.textContent);
                stepper.draw(words, {steps: [{step: 'QUESTIONNAIRE', chip: {text: 'Not needed'}, stateLine: 'Not needed: no documentation.'}]}, () => null);
                say(stepper.node.textContent);
                stepper.draw(null, null, () => null);
                say(stepper.node.textContent);

                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[0,[\"refused procedureCodes\"]]",
                "[4,[\"refused procedureCodes\",\"result run-1\",\"refused provider.npi\",\"result run-2\",\"failed INTERNAL\"]]",
                "[\"MRI\",\"MRI\",\"\",\"\",\"\",\"\"]",
                "\"1Coverage checkNot run2QuestionnaireNot run3SubmitNot run4StatusNot run\"",
                "\"1Coverage checkDocumentation required2QuestionnaireNot run3SubmitNot run4StatusNot run\"",
                "\"1Coverage checkNot run2QuestionnaireNot needed3SubmitNot run4StatusNot run\"",
                "\"1234\"");
    }
}
