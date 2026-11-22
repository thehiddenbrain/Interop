package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * The result card (plan 3.2, "The result"), run with Node and a stand-in for the document: an error,
 * its fault report and a run NOT recorded are never inside a closed fold; the address called after a
 * failure never shows its query, which can carry a member ID; a long JSON string is cut on screen and
 * copied whole; the chips, the next step's button and its action come from the server's words; the
 * line about a masked reading stands under the headline and above the facts. A service fault is drawn
 * inside its card (prototype 05a): its headline and what the service said once each, every issue on
 * its own line, no second framed box; a CDS refusal's summary and detail are what the service said,
 * never from an answer the wire says was masked.
 */
class ResultFrontendTest {

    /** A failed run and a run that went well, drawn. */
    @Test
    void aFailureIsNeverFoldedAwayAndTheAddressCalledShowsNoQuery(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the result card", """
                import './browser.js';
                import {resultCard, addressOnly} from './js/result.js';
                const say = value => console.log(JSON.stringify(value));
                const words = {outcomes: {CRD: [], PAS: [{code: 'APPROVED', short: 'Approved', tone: 'CLEAR'}]},
                  nextActions: [{code: 'START_DTR', button: 'Continue to the questionnaire'}],
                  testResults: [{code: 'UNVERIFIED', label: 'Unverified'}], answeredBy: [{code: 'SERVICE'}, {code: 'SCENARIO', chip: 'MOCK'}]};
                globalThis.fetch = async path => ({ok: true, status: 200, text: async () => JSON.stringify(String(path).includes('vocabulary') ? words : {})});
                const tick = () => new Promise(resolve => setTimeout(resolve, 20));
                const inClosedFold = node => { for (let at = node.parentNode; at; at = at.parentNode) { if (at.localName === 'details' && !at.open) return true; } return false; };
                const longBody = JSON.stringify({resourceType: 'Bundle', note: 'x'.repeat(2500)});
                const failed = {runId: 'run-9', protocol: 'CRD', operation: 'CRD_ORDER_SIGN', profile: 'live', answeredBy: 'SERVICE',
                  verdict: {headline: 'The service could not be reached.', tone: 'FAULT', outcomeCode: 'UNREACHABLE'},
                  expectation: {result: 'unverified'}, determination: [{label: 'Covered', presence: 'ABSENT'}],
                  error: {kind: 'UNREACHABLE', heading: 'A fault in the environment', title: 'The service could not be reached.', action: 'Check the address. Then run it again.',
                    reference: 'ref-1', cause: 'java.net.ConnectException'},
                  wire: {request: {method: 'POST', url: 'https://vendor.example.invalid/crd/order-sign?member=S123456789#x', headers: [], body: longBody, sent: true}},
                  ledger: {recorded: false, message: 'The ledger could not write this run: it is NOT recorded.'}, timing: {elapsedMillis: 5, slow: false}};
                const card = resultCard(failed, {context: 'workbench', onNext: () => null});
                document.body.append(card);
                await tick();
                // No error and no NOT recorded run inside a fold. A fault of the service or its environment is drawn inside the card itself (prototype 05a).
                const panel = card.querySelector('.error-panel') || card.querySelector('.verdict');
                const ledger = card.querySelector('.result__not-recorded');
                say([Boolean(panel), inClosedFold(panel), Boolean(ledger), inClosedFold(ledger), ledger.textContent]);
                say(panel.textContent.includes('Cause, as the server log names it:') && panel.textContent.includes('ref-1'));
                // The address after a failure never shows its query.
                const wire = card.querySelectorAll('details').find(each => each.querySelector('summary').textContent.startsWith('Wire traffic'));
                wire.open = true;
                say([wire.textContent.includes('S123456789'), wire.textContent.includes('https://vendor.example.invalid/crd/order-sign')]);
                say([addressOnly('https://h.example.invalid/p?a=1'), addressOnly('https://h.example.invalid/p#f'), addressOnly('https://h.example.invalid/p')]);
                // A long JSON string is cut on screen and copied whole.
                const cut = wire.querySelector('.json__cut');
                say([Boolean(cut), cut ? cut.textContent : null]);
                await wire.querySelector('.json-view button').click();
                say(globalThis.standIn.clipboard.includes('x'.repeat(2500)));
                // Not returned rows are counted in their own fold; Expected says Not set.
                say(card.querySelectorAll('summary').map(each => each.textContent));
                // A run that went well: chips from the vocabulary, the next step a button, the MOCK chip, Open in Audit.
                const fine = {runId: 'run-1', protocol: 'CRD', operation: 'CRD_ORDER_SIGN', profile: 'mock', answeredBy: 'SCENARIO',
                  verdict: {headline: 'Documentation is required.', tone: 'ACTION', maskedReading: 'Read from a mask.'}, expectation: {result: 'unverified'},
                  nextStep: {action: 'START_DTR', headline: 'Next: complete the questionnaire.'},
                  determination: [{label: 'Covered', code: 'covered', display: 'Covered', presence: 'VALUE', recognised: true, tone: 'CLEAR'}],
                  wire: {response: {status: 200}, memberId: {typed: 'S1234567 89', sent: 'S123456789', changed: true, rule: 'packed'}},
                  ledger: {recorded: true}, timing: {elapsedMillis: 150, slow: false}};
                let pressed = null;
                const good = resultCard(fine, {context: 'workbench', onNext: action => { pressed = action; }});
                await tick();
                say(good.querySelector('.result__meta').textContent);
                const headline = good.textContent.indexOf('Documentation is required.');
                const masked = good.textContent.indexOf('Read from a mask.');
                const covered = good.textContent.indexOf('Covered');
                say([headline >= 0, masked > headline, covered > masked]);
                await good.querySelector('.verdict__foot button').click();
                say([good.querySelector('.verdict__foot button').textContent, pressed]);
                say(resultCard(fine, {context: 'audit'}).textContent.includes('Open in Audit'));

                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[true,false,true,false,\"NOT recorded The ledger could not write this run: it is NOT recorded.\"]",
                "true",
                "[false,true]",
                "[\"https://h.example.invalid/p\",\"https://h.example.invalid/p\",\"https://h.example.invalid/p\"]",
                "[true,\" (2500 characters; Copy copies them whole)\"]",
                "true",
                "[\"Not returned (1)\",\"Why\",\"Wire trafficrequest and response JSON\"]",
                "\"HTTP 200MOCK150 msUnverifiedSent as S123456789, rule: packedOpen in Audit\"",
                "[true,true,true]",
                "[\"Continue to the questionnaire\",\"START_DTR\"]",
                "false");
    }

    /** A played HTTP 500, a CDS refusal with summary and detail, the same masked, and a fault of this tool, run. */
    @Test
    void aServiceFaultIsOneCardAndACdsRefusalSaysWhatTheServiceSaid(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "a service fault in its card", """
                import './browser.js';
                import {resultCard, faultInCard} from './js/result.js';
                const say = value => console.log(JSON.stringify(value));
                globalThis.fetch = async () => ({ok: true, status: 200, text: async () => JSON.stringify({})});
                const value = text => ({presence: 'VALUE', value: text});
                // A service fault is one card: the headline once, the service's sentence once, each issue on its line, no second framed box.
                const err500 = {runId: 'run-1', protocol: 'CRD', operation: 'CRD_ORDER_SIGN', answeredBy: 'SCENARIO',
                  verdict: {headline: 'The service failed (HTTP 500).', tone: 'FAULT'},
                  nextStep: {action: 'CHECK_ENVIRONMENT', headline: 'Next, had this been a real service: check the environment, then send again.'},
                  error: {kind: 'VENDOR_ERROR', heading: 'A failure played by a mock scenario, not a fault in the environment', title: 'The service failed with HTTP 500.',
                    action: 'Nothing left this machine.', httpStatus: 500,
                    issues: [{severity: value('error'), code: value('exception'), diagnostics: value('An unexpected error occurred.'), detailsText: value('An unexpected error occurred.')},
                      {severity: value('warning'), code: value('processing'), diagnostics: value('Second issue.'), expression: [value('Claim.item[0]')]}]}};
                const card = resultCard(err500, {context: 'workbench'});
                const text = card.textContent;
                say([faultInCard(err500), card.querySelectorAll('.verdict').length, card.querySelectorAll('.error-panel').length,
                  text.split('The service failed').length - 1, text.split('An unexpected error occurred.').length - 1, card.querySelectorAll('.issue-line').length]);
                say(card.querySelectorAll('.issue-line').map(line => line.textContent));
                // A CDS service's refusal: its summary and detail, left among the fields returned, are what the service said.
                const refused = {runId: 'run-2', protocol: 'CRD', operation: 'CRD_ORDER_SIGN', answeredBy: 'SERVICE',
                  verdict: {headline: 'The service did not accept the request as sent (HTTP 400).', tone: 'STOP'},
                  error: {kind: 'VENDOR_REJECTED', title: 'Refused.', issues: []},
                  additionalFields: [{path: 'summary', presence: 'VALUE', value: 'invalid hook request', kind: 'FIELD'},
                    {path: 'detail', presence: 'VALUE', value: 'context.draftOrders.type is not allowed', kind: 'FIELD'}],
                  wire: {response: {status: 400}}};
                say(resultCard(refused, {context: 'workbench'}).querySelectorAll('.service-said').map(line => line.textContent));
                // Never from an answer the wire says was masked; and a fault of this tool keeps its own panel and report.
                refused.wire.response.maskedPlaces = 1;
                say(resultCard(refused, {context: 'workbench'}).querySelectorAll('.service-said').length);
                const tool = {runId: 'run-3', protocol: 'CRD', operation: 'CRD_ORDER_SIGN', verdict: {headline: 'This tool failed.'},
                  error: {kind: 'INTERNAL', title: 'This tool hit an internal error.', reference: 'ref-1', cause: 'java.lang.IllegalStateException'}};
                say([faultInCard(tool), resultCard(tool, {context: 'workbench'}).querySelectorAll('.error-panel').length]);

                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[true,1,0,1,1,2]",
                "[\"error exception \",\"warning processing Second issue. Claim.item[0]\"]",
                "[\"Service said: invalid hook request: context.draftOrders.type is not allowed\"]",
                "0",
                "[false,1]");
    }
}
