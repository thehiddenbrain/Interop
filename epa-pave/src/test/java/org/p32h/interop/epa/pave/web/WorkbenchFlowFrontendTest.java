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
 * is chosen on mock. After a reload, or from
 * Audit's "Load into the Workbench", the case and each step's result are read back from the chain,
 * the trail and, for a run the trail no longer keeps, the ledger, and nothing of it goes to browser
 * storage. The rules the operator's earlier tool taught: the latest choice wins over a slower earlier
 * answer, and a failed lookup clears what the lookup before it filled.
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

    /** The reload read back from a chain, the trail and the ledger, the case cards taking the posted case, and storage watched. */
    @Test
    void aReloadRedrawsTheCaseAndEachStepFromTheChainAndTheTrailAndKeepsNothingInStorage(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the reload", """
                import './browser.js';
                import {readBack, runsToRedraw, caseFromLedger, NOT_KEPT_LINE} from './js/wb-journey.js';
                import {createMemberCard} from './js/wb-member.js';
                import {createProviderCard} from './js/wb-provider.js';
                import {createServiceCard} from './js/wb-service.js';
                import {ledgerCard, downloadLink} from './js/result.js';
                const say = value => console.log(JSON.stringify(value));
                const tick = () => new Promise(resolve => setTimeout(resolve, 20));
                // Browser storage is watched: a reload keeps nothing in it, so any use of it is counted.
                let stored = 0;
                const watched = {getItem: () => { stored++; return null; }, setItem: () => { stored++; }, removeItem: () => { stored++; }, clear: () => { stored++; }};
                globalThis.localStorage = watched;
                globalThis.sessionStorage = watched;
                globalThis.window = {localStorage: watched, sessionStorage: watched};
                globalThis.fetch = async () => ({ok: true, status: 200, text: async () => JSON.stringify({codes: []})});
                // The chain the server gives: steps with their latest runs; an inquiry in the PAS stage and subscription calls among the other runs.
                const chain = {chainId: 'chain-1',
                  steps: [{step: 'COVERAGE', latestRunId: 'run-1'}, {step: 'QUESTIONNAIRE', latestRunId: 'run-2'}, {step: 'SUBMIT'}, {step: 'STATUS', latestRunId: 'run-9'}],
                  stages: [{protocol: 'CRD', runs: [{runId: 'run-0', operation: 'CRD_ORDER_SIGN', timestamp: '2026-11-22T10:00:00Z'},
                    {runId: 'run-1', operation: 'CRD_ORDER_SIGN', timestamp: '2026-11-22T10:01:00Z'}]},
                    {protocol: 'DTR', runs: [{runId: 'run-2', operation: 'DTR_QUESTIONNAIRE_PACKAGE', timestamp: '2026-11-22T10:02:00Z'}]},
                    {protocol: 'PAS', runs: [{runId: 'run-4', operation: 'PAS_INQUIRE', timestamp: '2026-11-22T10:04:00Z'},
                      {runId: 'run-5', operation: 'PAS_INQUIRE', timestamp: '2026-11-22T10:05:00Z'}]}],
                  otherRuns: [{runId: 'run-6', operation: 'SUBSCRIPTION_CREATE', timestamp: '2026-11-22T10:06:00Z'},
                    {runId: 'run-7', operation: 'SUBSCRIPTION_GET', timestamp: '2026-11-22T10:07:00Z'},
                    {runId: 'run-9', operation: 'SUBSCRIPTION_NOTIFICATION', timestamp: '2026-11-22T10:09:00Z'}]};
                say(runsToRedraw(chain));
                const posted = {member: {memberId: 'S123456789', firstName: 'Pat', lastName: 'Placeholder', coverageStart: '2026-01-01'},
                  memberOverrides: ['lastName'], provider: {npi: '1234567893', phone: '555-0100'}, providerOverrides: [],
                  procedureCodes: [{code: 'E0470', quantity: 2, unit: 'UN'}], dateOfService: '2026-12-01', diagnosisCodes: ['G47.33'], testerRef: 'T-1'};
                const kept = (runId, operation) => ({runId, kept: true, keptAt: '2026-11-22T10:0' + runId.slice(-1) + ':30Z',
                  entry: {runId, chainId: 'chain-1', operation, case: posted, envelope: {runId, chainId: 'chain-1', operation, verdict: {headline: 'H ' + runId}}}});
                const trail = {
                  'run-1': kept('run-1', 'CRD_ORDER_SIGN'),
                  'run-2': {runId: 'run-2', kept: false, line: 'Not kept: the trail holds the last 500 runs, and never a write that was refused.'},
                  'run-5': kept('run-5', 'PAS_INQUIRE'),
                  'run-7': {...kept('run-7', 'SUBSCRIPTION_GET'), entry: {...kept('run-7', 'SUBSCRIPTION_GET').entry, case: {chainId: 'chain-1'}}}
                };
                const ledger = {'run-2': {runId: 'run-2', protocol: 'DTR', outcome: 'QUESTIONNAIRE_RECEIVED', memberToken: 'tok-1', memberIdLast4: '6789',
                  npi: '1234567893', procedureCodes: ['E0470'], chainId: 'chain-1', timestamp: '2026-11-22T10:02:00Z', httpStatus: 200}};
                const asked = [];
                const ask = async (method, path) => {
                  asked.push(method + ' ' + path);
                  const id = decodeURIComponent(path.split('/').pop());
                  if (path.startsWith('/api/chains/')) {
                    return id === 'chain-1' ? {ok: true, status: 200, data: chain} : {ok: false, status: 404, error: {kind: 'NOT_FOUND', title: 'No journey chain-x.'}};
                  }
                  if (path.startsWith('/api/trail/')) {
                    return trail[id] ? {ok: true, status: 200, data: trail[id]} : {ok: true, status: 200, data: {runId: id, kept: false, line: 'Not kept: the trail holds the last 500 runs, and never a write that was refused.'}};
                  }
                  if (path.startsWith('/api/runs/')) {
                    return ledger[id] ? {ok: true, status: 200, data: ledger[id]} : {ok: false, status: 404, error: {kind: 'NOT_FOUND', title: 'No run ' + id + '.'}};
                  }
                  return {ok: false, status: 404, error: {kind: 'NOT_FOUND'}};
                };
                // After a reload: the chain, then each run to redraw from the trail; the one it no longer keeps from the ledger.
                const back = await readBack({chainId: 'chain-1'}, ask);
                say(asked);
                say([back.ok, back.chainId, back.notKept, back.theCase.from, back.theCase.entry.runId]);
                say(Object.entries(back.runs).map(([slot, run]) => [slot, run.step, run.runId, run.envelope ? 'trail' : 'ledger', run.line || null, run.at]));
                // "Load into the Workbench" names a run: its trail entry names the chain.
                asked.length = 0;
                const loaded = await readBack({runId: 'run-5'}, ask);
                say([loaded.ok, loaded.chainId, asked[0], asked[1], asked.filter(line => line === 'GET /api/trail/run-5').length]);
                // A run the trail no longer keeps leads to its chain through its ledger line; a run of no journey and a journey not found are said.
                asked.length = 0;
                say([(await readBack({runId: 'run-2'}, ask)).chainId, asked.slice(0, 3)]);
                ledger['run-x'] = {runId: 'run-x', protocol: 'RAW'};
                say(await readBack({runId: 'run-x'}, ask));
                say(await readBack({chainId: 'chain-x'}, ask));
                // With nothing kept, the case is what the ledger kept: never the member token, never a guess.
                for (const id of Object.keys(trail)) {
                  delete trail[id];
                }
                const fromLedger = await readBack({chainId: 'chain-1'}, ask);
                say([fromLedger.notKept, fromLedger.theCase.from, caseFromLedger(fromLedger.theCase.record)]);
                say(caseFromLedger({memberId: 'S123456789', lineOfBusiness: 'Commercial', npi: '1234567893', procedureCodes: ['E0470', ''], dateOfService: '2026-12-01'}));
                say([caseFromLedger(null), NOT_KEPT_LINE]);
                // The case cards take the posted case back, marked "from the trail"; a field the tester had changed is theirs again.
                const member = createMemberCard({isMock: () => false, mockLists: () => null, savedMembers: () => [], dateOfService: () => '', useSample: () => null});
                const provider = createProviderCard({savedProviders: () => [], placeOfService: () => null});
                const service = createServiceCard({lineOfBusiness: () => '', options: () => null});
                member.restore(posted.member, posted.memberOverrides, 'from the trail');
                provider.restore(posted.provider, posted.providerOverrides, null, 'from the trail');
                service.restore(posted, 'from the trail');
                await tick();
                say(member.request());
                say(provider.request());
                say(service.request());
                say(member.node.querySelectorAll('.field__mark').map(mark => mark.textContent).filter(Boolean));
                // The ledger card says what the line holds, and the download link names the run's file.
                const words = {outcomes: {DTR: [{code: 'QUESTIONNAIRE_RECEIVED', label: 'The questionnaire package arrived.', short: 'Package received', tone: 'PASS'}]},
                  answeredBy: []};
                say(ledgerCard(ledger['run-2'], words).textContent);
                say(ledgerCard({runId: 'run-3', protocol: 'PAS'}, null).textContent);
                const link = downloadLink('run-5');
                say([link.href, link.getAttribute('download'), link.textContent]);
                // Nothing of it went to browser storage.
                say(stored);

                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "{\"1\":{\"runId\":\"run-1\",\"at\":\"2026-11-22T10:01:00Z\"},\"2\":{\"runId\":\"run-2\",\"at\":\"2026-11-22T10:02:00Z\"},\"inquiry\":{\"runId\":\"run-5\",\"at\":\"2026-11-22T10:05:00Z\"},\"subscription\":{\"runId\":\"run-7\",\"at\":\"2026-11-22T10:07:00Z\"}}",
                "[\"GET /api/chains/chain-1\",\"GET /api/trail/run-1\",\"GET /api/trail/run-2\",\"GET /api/runs/run-2\",\"GET /api/trail/run-5\",\"GET /api/trail/run-7\"]",
                "[true,\"chain-1\",true,\"trail\",\"run-5\"]",
                "[[\"1\",1,\"run-1\",\"trail\",null,\"2026-11-22T10:01:00Z\"],[\"2\",2,\"run-2\",\"ledger\",\"Not kept: the trail holds the last 500 runs, and never a write that was refused.\",\"2026-11-22T10:02:00Z\"],[\"inquiry\",4,\"run-5\",\"trail\",null,\"2026-11-22T10:05:00Z\"],[\"subscription\",4,\"run-7\",\"trail\",null,\"2026-11-22T10:07:00Z\"]]",
                "[true,\"chain-1\",\"GET /api/trail/run-5\",\"GET /api/chains/chain-1\",1]",
                "[\"chain-1\",[\"GET /api/trail/run-2\",\"GET /api/runs/run-2\",\"GET /api/chains/chain-1\"]]",
                "{\"ok\":false,\"error\":{\"kind\":\"NOT_FOUND\",\"title\":\"This run belongs to no journey, so there is no case to load.\"}}",
                "{\"ok\":false,\"error\":{\"kind\":\"NOT_FOUND\",\"title\":\"No journey chain-x.\"}}",
                "[true,\"ledger\",{\"member\":{},\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":[{\"code\":\"E0470\"}]}]",
                "{\"member\":{\"memberId\":\"S123456789\",\"lineOfBusiness\":\"Commercial\"},\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":[{\"code\":\"E0470\"}],\"dateOfService\":\"2026-12-01\"}",
                "[{\"member\":{},\"provider\":{},\"procedureCodes\":[]},\"Not kept in the trail: the case shows what the ledger kept.\"]",
                "{\"member\":{\"memberId\":\"S123456789\",\"firstName\":\"Pat\",\"lastName\":\"Placeholder\",\"coverageStart\":\"2026-01-01\"},\"memberOverrides\":[\"lastName\"]}",
                "{\"provider\":{\"npi\":\"1234567893\",\"phone\":\"555-0100\"},\"providerOverrides\":[],\"servicingProvider\":null}",
                "{\"procedureCodes\":[{\"code\":\"E0470\",\"quantity\":2,\"unit\":\"UN\"}],\"dateOfService\":\"2026-12-01\",\"diagnosisCodes\":[\"G47.33\"],\"testerRef\":\"T-1\"}",
                "[\"from the trail\",\"from the trail\",\"by hand\"]",
                "\"HTTP 200Open in AuditThe questionnaire package arrived.OutcomePackage receivedCodeE0470ExpectedNot set\"",
                "\"Open in AuditNo outcome recordedOutcomenot recordedCodenot recordedExpectedNot set\"",
                "[\"/api/trail/run-5/download\",\"run-5.json\",\"Download request and answer\"]",
                "0");
    }

    /** Two lookups racing, and a failed lookup after a found one. */
    /**
     * A tester whose questionnaire request failed saw no way to Submit, though the stepper opens any
     * step. A questionnaire result that offers no way on says that Submit is open and offers it, never
     * as the filled button; a result with a way on, or of another step, does not (D-317).
     */
    @Test
    void aQuestionnaireResultWithNoWayOnStillOffersSubmitPlainly(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the way on to Submit", """
                import './browser.js';
                import {submitWithoutQuestionnaire} from './js/result.js';
                const say = value => console.log(JSON.stringify(value));
                let opened = 0;
                const go = () => { opened++; };
                // Step 2 with no way on: the line, and a plain button that opens Submit.
                const aside = submitWithoutQuestionnaire(2, null, null, go);
                const button = aside.querySelectorAll('button')[0];
                say([aside.querySelector('p').textContent, button.textContent, button.className.includes('btn--primary'), button.disabled === true]);
                button.click();
                say(opened);
                // Submit cannot be sent now: the button is there, off, with the server's sentence as its title.
                const off = submitWithoutQuestionnaire(2, null, 'No address for Submit.', go).querySelectorAll('button')[0];
                say([off.disabled === true, off.title]);
                // A result that leads on, one that stays in its own step, and results of other steps offer nothing here.
                say([submitWithoutQuestionnaire(2, 3, null, go), submitWithoutQuestionnaire(2, 2, null, go),
                    submitWithoutQuestionnaire(1, null, null, go), submitWithoutQuestionnaire(3, null, null, go)]);
                """);

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"Submit does not wait for the questionnaire. Sent now, it carries no completed response.\","
                        + "\"Continue to Submit without it\",false,false]",
                "1",
                "[true,\"No address for Submit.\"]",
                "[null,null,null,null]");
        // The Workbench puts it under the verdict of the card it draws for a step.
        assertThat(StaticSources.script("workbench.js"))
                .contains("submitWithoutQuestionnaire(own, leadsTo, blocked(OPERATION_OF_STEP[3]), () => openStep(3, true))");
    }

    @Test
    void theLatestLookupWinsAndAFailedLookupClearsWhatTheOneBeforeFilled(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the lookup rules", """
                import './browser.js';
                import {createMemberCard} from './js/wb-member.js';
                import {latestOnly} from './js/wb-lookup.js';
                const say = value => console.log(JSON.stringify(value));
                const tick = () => new Promise(resolve => setTimeout(resolve, 30));
                // The latest choice wins over a slower earlier answer.
                const run = latestOnly();
                const took = [];
                const slow = run(() => new Promise(resolve => setTimeout(() => resolve('first'), 40)), answer => took.push(answer));
                const fast = run(async () => 'second', answer => took.push(answer));
                await Promise.all([slow, fast]);
                say(took);
                // A failed lookup clears the fields the previous lookup filled; a field the tester typed stays.
                let reply = {status: 200, body: {status: 'FOUND', member: {firstName: 'Pat', lastName: 'Placeholder', dateOfBirth: '1980-01-01'}}};
                globalThis.fetch = async () => ({ok: reply.status < 300, status: reply.status, text: async () => JSON.stringify(reply.body)});
                const card = createMemberCard({isMock: () => false, mockLists: () => null, savedMembers: () => [], dateOfService: () => '', useSample: () => null});
                document.body.append(card.node);
                const input = name => card.node.querySelectorAll('input').find(each => each.name === name);
                input('memberId').value = 'S123456789';
                input('memberId').dispatchEvent({type: 'change'});
                await tick();
                say(card.request().member);
                input('planName').value = 'Typed plan';
                input('planName').dispatchEvent({type: 'input'});
                input('memberId').value = 'S123456788';
                reply = {status: 503, body: {kind: 'UNREACHABLE', title: 'The member lookup is not answering.'}};
                input('memberId').dispatchEvent({type: 'change'});
                await tick();
                say(card.request().member);

                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"second\"]",
                "{\"memberId\":\"S123456789\",\"firstName\":\"Pat\",\"lastName\":\"Placeholder\",\"dateOfBirth\":\"1980-01-01\"}",
                "{\"memberId\":\"S123456788\",\"planName\":\"Typed plan\"}");
    }
}
