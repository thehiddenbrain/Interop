package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * Step 4 of the Workbench, Status (plan 3.2), run with Node and a stand-in for the document: on live no
 * "Deliver a sample" is built, on mock it is built with the vocabulary's words and the sample is about the
 * submission's own run; with neither a submission nor a scenario it is not offered; the inquiry carries the
 * claim and the submission's run, a subscription read sends parameters, and a scenario goes on mock only; no
 * inquiry is handed on after a submission that cannot have reached the service; the notification poll asks only
 * while the step is on screen and only for what arrived since the newest stamp, and stops on an error; and the
 * Claim id is filled from step 3 only through the check for a mask.
 */
class StatusFrontendTest {

    /** The step mounted on live and on mock, its bodies, its poll and its Claim id, run. */
    @Test
    void theStatusStepSendsWhatThePlanNamesAndBuildsTheSampleOnMockOnly(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the Status step", """
                import './browser.js';
                import * as status from './js/wb-status.js';
                import {offersInquiryWay, statusCarry} from './js/wb-journey.js';
                import {caseField} from './js/wb-case.js';
                const say = value => console.log(JSON.stringify(value));
                const tick = () => new Promise(resolve => setTimeout(resolve, 0));
                // The server's answers, by method and path; each request is kept.
                const asked = [];
                let answers = {};
                globalThis.fetch = async (address, init) => {
                  asked.push(init.method + ' ' + address + (init.body ? ' ' + init.body : ''));
                  const key = init.method + ' ' + address.split('?')[0];
                  const found = answers[key] || {status: 200, body: {}};
                  return {ok: found.status < 300, status: found.status, text: async () => JSON.stringify(found.body)};
                };
                const words = {stepStates: {NOT_RUN: {text: 'Not run'}},
                  outcomes: {PAS: [{code: 'PENDED', short: 'Pended', tone: 'PENDING'}], SUBSCRIPTION: [{code: 'CREATED', short: 'Created'}],
                    NOTIFICATION: [{code: 'NOTIFICATION_RECEIVED', short: 'Notification'}]},
                  nextActions: [{code: 'DELIVER_SAMPLE_NOTIFICATION', label: 'x', button: 'Deliver a sample'}, {code: 'CHECK_STATUS', label: 'x', button: 'Send inquiry'}]};
                answers['GET /api/vocabulary'] = {status: 200, body: words};
                answers['GET /api/mode'] = {status: 200, body: {mode: 'mock'}};
                const submitted = {runId: 'run-11111111-1111-1111-1111-111111111111', operation: 'PAS_SUBMIT', protocol: 'PAS',
                  nextStep: {action: 'CHECK_STATUS', carry: {claimId: ['claim-1'], runId: ['run-dtr'], procedureCodes: ['E0470']}}};
                function ctxFor(mock, carry) {
                  return {
                    request: () => ({member: {memberId: 'S123456789'}, memberOverrides: [], provider: {npi: '1234567893'}, providerOverrides: [],
                      procedureCodes: [{code: 'E0470'}], dateOfService: '', placeOfService: '22', chainId: 'new', configEpoch: 1}),
                    carry: () => carry, option: name => '', isMock: () => mock, visible: () => true, typedCode: () => '',
                    latest: step => (step === 3 && carry ? submitted : null), resulted: () => {}, refused: () => {}, card: () => document.createElement('div'),
                    placeErrors: () => [], blocked: () => null, handedLine: () => null
                  };
                }
                function texts(root) {
                  return root.querySelectorAll('button').map(one => one.textContent);
                }
                // On live no "Deliver a sample" is built, though the vocabulary names it.
                const live = document.createElement('div');
                document.body.append(live);
                const liveStep = status.mount(live, ctxFor(false, submitted.nextStep.carry));
                await tick(); await tick();
                say(['live', texts(live)]);
                liveStep.unmount();
                // On mock it is built with the vocabulary's words, and the sample is about the submission's own run.
                const mock = document.createElement('div');
                document.body.append(mock);
                const mockStep = status.mount(mock, ctxFor(true, submitted.nextStep.carry));
                await tick(); await tick();
                say(['mock', texts(mock)]);
                answers['POST /api/pas/notifications/sample'] = {status: 200, body: {id: 'n-1'}};
                asked.length = 0;
                await mock.querySelectorAll('button').find(one => one.textContent === 'Deliver a sample').click();
                await tick();
                say(asked.filter(line => line.startsWith('POST')));
                mockStep.unmount();
                // Without the vocabulary's words, nothing is built for it.
                answers['GET /api/vocabulary'] = {status: 500, body: {kind: 'INTERNAL', title: 'x'}};
                say(['no words', status.buttonOf(null, 'DELIVER_SAMPLE_NOTIFICATION'), status.shortOf(null, 'PAS', 'PENDED')]);
                answers['GET /api/vocabulary'] = {status: 200, body: words};
                // With no submission handed on, the sample has nothing to be about until a scenario is chosen.
                const alone = document.createElement('div');
                document.body.append(alone);
                const aloneStep = status.mount(alone, ctxFor(true, null));
                await tick(); await tick();
                const deliver = alone.querySelectorAll('button').find(one => one.textContent === 'Deliver a sample');
                say(['alone', deliver.disabled, deliver.title]);
                aloneStep.unmount();
                // The bodies: the inquiry carries the claim, the submission's own run and its codes; a read sends parameters.
                const request = ctxFor(true, null).request();
                say(status.inquiryBody(request, {claimId: '', mock: true, scenarioId: 'pas-05', expectInquiry: 'PENDED'}, submitted.nextStep.carry, submitted.runId));
                say(status.inquiryBody(request, {claimId: 'typed-claim', mock: false, scenarioId: 'pas-05'}, null, null));
                say(status.subscriptionCall('read', request, {subscriptionId: 'sub 1', mock: false}, submitted.runId));
                say(status.subscriptionCall('create', request, {subscriptionId: 'x', mock: true, scenarioId: 'pas-10'}, null));
                say(status.sampleBody({mock: false, scenarioId: 'pas-06'}, submitted.runId));
                // No inquiry after a submission that cannot have reached the service: it hands on no claim identifier.
                say([
                  offersInquiryWay({operation: 'PAS_SUBMIT', nextStep: {action: 'CHECK_ENVIRONMENT', carry: {runId: ['r'], procedureCodes: []}}}),
                  offersInquiryWay({operation: 'PAS_SUBMIT', nextStep: {action: 'CHECK_ENVIRONMENT', carry: {claimId: ['c'], runId: ['r']}}}),
                  offersInquiryWay({operation: 'PAS_SUBMIT', nextStep: {action: 'FLOW_ENDS', carry: {claimId: ['c']}}}),
                  offersInquiryWay({operation: 'PAS_SUBMIT', nextStep: {action: 'CHECK_STATUS', carry: {claimId: []}}}),
                  offersInquiryWay({operation: 'PAS_INQUIRE', nextStep: {action: 'CHECK_STATUS', carry: {claimId: ['c']}}}),
                  offersInquiryWay({operation: 'PAS_SUBMIT'})]);
                say([statusCarry(submitted), statusCarry({operation: 'PAS_SUBMIT', runId: 'r', nextStep: {carry: {runId: ['r']}}})]);
                // The poll: only while on screen, only what arrived since the newest stamp, never on after an error.
                let onScreen = true;
                const polled = [];
                let reply = {ok: true, data: {notifications: [{id: 'n-2'}], latest: '2026-11-22T09:00:00Z'}};
                const poll = status.createPoll({ask: async since => { polled.push(since); return reply; }, onScreen: () => onScreen, onAnswer: () => {}, every: 100000});
                await poll.start();
                say(['first', polled, poll.running()]);
                reply = {ok: true, data: {notifications: []}};
                await poll.start();
                say(['since', polled]);
                onScreen = false;
                await poll.start();
                say(['hidden', polled, poll.running()]);
                onScreen = true;
                reply = {ok: false, status: 0, error: {kind: 'UNREACHABLE'}};
                await poll.start();
                say(['error', polled.length, poll.running(), poll.stopped()]);
                // The claim id fills only through the check for a mask.
                const claim = caseField({name: 'claimId', label: 'Claim id', mono: true});
                status.fillClaimFrom(claim, {claimId: ['claim-7']});
                say([claim.value(), claim.mark()]);
                status.fillClaimFrom(claim, {claimId: ['[secret clientSecret]']});
                say([claim.value(), claim.mark(), claim.slot.textContent.length > 0]);
                status.fillClaimFrom(claim, {claimId: ['claim-8']});
                status.fillClaimFrom(claim, null);
                say([claim.value(), claim.mark()]);
                claim.set('typed', null);
                status.fillClaimFrom(claim, null);
                say([claim.value(), claim.mark()]);
                process.exit(0);

                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"live\",[\"Preview\",\"Send inquiry\",\"Preview\",\"Send subscription call\",\"Check now\"]]",
                "[\"mock\",[\"Preview\",\"Send inquiry\",\"Preview\",\"Send subscription call\",\"Check now\",\"Deliver a sample\"]]",
                "[\"POST /api/pas/notifications/sample {\\\"runId\\\":\\\"run-11111111-1111-1111-1111-111111111111\\\"}\"]",
                "[\"no words\",null,null]",
                "[\"alone\",true,\"Send a submission in step 3 first, or choose a Mock scenario in Options.\"]",
                "{\"member\":{\"memberId\":\"S123456789\"},\"memberOverrides\":[],\"provider\":{\"npi\":\"1234567893\"},\"providerOverrides\":[],\"procedureCodes\":[{\"code\":\"E0470\"}],\"chainId\":\"new\",\"configEpoch\":1,\"carry\":{\"claimId\":[\"claim-1\"],\"runId\":[\"run-11111111-1111-1111-1111-111111111111\"],\"procedureCodes\":[\"E0470\"]},\"expected\":\"PENDED\",\"scenarioId\":\"pas-05\"}",
                "{\"member\":{\"memberId\":\"S123456789\"},\"memberOverrides\":[],\"provider\":{\"npi\":\"1234567893\"},\"providerOverrides\":[],\"procedureCodes\":[{\"code\":\"E0470\"}],\"chainId\":\"new\",\"configEpoch\":1,\"claimId\":\"typed-claim\"}",
                "{\"method\":\"GET\",\"path\":\"/api/pas/subscriptions/sub%201\",\"query\":{\"chainId\":\"new\",\"configEpoch\":1,\"carry.runId\":\"run-11111111-1111-1111-1111-111111111111\"},\"chosen\":{\"value\":\"read\",\"words\":\"Read\",\"method\":\"GET\",\"operation\":\"SUBSCRIPTION_GET\",\"body\":false,\"needsId\":true}}",
                "{\"method\":\"POST\",\"path\":\"/api/pas/subscriptions\",\"body\":{\"chainId\":\"new\",\"configEpoch\":1,\"scenarioId\":\"pas-10\"},\"chosen\":{\"value\":\"create\",\"words\":\"Create\",\"method\":\"POST\",\"operation\":\"SUBSCRIPTION_CREATE\",\"body\":true,\"needsId\":false}}",
                "{\"runId\":\"run-11111111-1111-1111-1111-111111111111\"}",
                "[false,true,true,false,false,false]",
                "[{\"claimId\":[\"claim-1\"],\"runId\":[\"run-11111111-1111-1111-1111-111111111111\"],\"procedureCodes\":[\"E0470\"]},null]",
                "[\"first\",[null],true]",
                "[\"since\",[null,\"2026-11-22T09:00:00Z\"]]",
                "[\"hidden\",[null,\"2026-11-22T09:00:00Z\"],false]",
                "[\"error\",3,false,true]",
                "[\"claim-7\",\"from step 3\"]",
                "[\"\",\"masked, not taken\",true]",
                "[\"\",null]",
                "[\"typed\",\"by hand\"]");
    }
}
