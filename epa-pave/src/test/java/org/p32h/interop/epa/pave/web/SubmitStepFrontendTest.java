package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * Step 3 of the Workbench, Submit (wb-submit.js), run with Node against the step module's contract
 * (plan 9.1) and answers of the server's own shape: "What goes" is three lines of chips within the
 * word budget of M22; each gap the server names is a chip that opens its field (a value of the
 * environment opens the environment's Values); "Include it" unticked sends no questionnaire response;
 * opening the step sends nothing; the diagnosis codes go as codes, which is how PAS reads them; and
 * the result goes to the frame alone, which hands step 4 its claim, so the step hands nothing on itself.
 */
class SubmitStepFrontendTest {

    @Test
    void whatGoesIsShortEachGapOpensItsFieldAndAnUntickedResponseIsNotSent(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the Submit step", """
                import './browser.js';
                const posted = [];
                const previewAnswer = {operation: 'PAS_SUBMIT', memberId: {typed: 'S123 456789', sent: 'S123456789', rule: 'packed'},
                    toComplete: [{field: 'provider.phone', line: 'Provider phone or fax: none. PAS rule pd-1 needs one: add it in Edit provider fields.'},
                        {field: 'values.payerName', line: 'Insurer: none. PAS 2.2.1 requires the payer: add Payer name and Payer identifier under Values.'}]};
                const submitted = {runId: 'run-9', protocol: 'PAS', operation: 'PAS_SUBMIT', verdict: {headline: 'Approved.'},
                    nextStep: {action: 'CHECK_STATUS', carry: {claimId: ['P32H-CLAIM-1'], runId: ['run-9']}}};
                globalThis.fetch = async (address, init) => {
                    const path = String(address);
                    if (init && init.body) {
                        posted.push({path, body: JSON.parse(init.body)});
                    }
                    const data = path === '/api/pas/submit/preview' ? previewAnswer : path === '/api/pas/submit' ? submitted
                        : path === '/api/pas/options' ? {attachments: {kinds: [{extension: 'pdf', contentType: 'application/pdf', label: 'PDF'}],
                            perFileBytes: 5242880, totalBytes: 10485760}}
                        : path === '/api/vocabulary' ? {outcomes: {PAS: []}}
                        : path === '/api/mode' ? {mode: 'live', environment: {active: true, name: 'Sandbox'}, writeAccess: {production: false}} : {};
                    return {ok: true, status: 200, text: async () => JSON.stringify(data)};
                };
                const shown = [];
                const handed = [];
                const ctx = {
                    request: () => ({member: {memberId: 'S123 456789'}, memberOverrides: [], provider: {npi: '1234567893', phone: '555-0100'},
                        providerOverrides: [], procedureCodes: [{code: '81162', quantity: 2, unit: 'FL'}], dateOfService: '2026-11-23',
                        placeOfService: '11', diagnosisCodes: ['Z80.3'], chainId: 'chain-1', configEpoch: 4}),
                    carry: step => (step === 3 ? {runId: ['run-2'], questionnaireResponse: [JSON.stringify({resourceType: 'QuestionnaireResponse',
                        item: [{linkId: 'a', answer: [{valueString: 'x'}]}, {linkId: 'g', item: [{linkId: 'b', answer: [{valueBoolean: true}]}]}, {linkId: 'c'}]})]} : null),
                    handOn: (step, carry) => handed.push([step, Object.keys(carry).sort().join(',')]),
                    option: () => '', optionsRow: () => null, optionsToggle: () => null, resulted: envelope => handed.push(['resulted', envelope.runId]),
                    showField: name => { shown.push(name); return true; }, placeErrors: () => [], go: () => null, isMock: () => false,
                    visible: () => true, typedCode: () => '', blocked: () => null, readinessLine: () => null, latest: () => null,
                    refused: () => null, handedLine: () => null, card: () => globalThis.document.createElement('div')
                };
                globalThis.location = {hash: ''};
                const {mount} = await import('./js/wb-submit.js');
                const root = globalThis.document.createElement('div');
                const step = mount(root, ctx);
                const settle = () => new Promise(done => setTimeout(done, 30));
                const say = value => console.log(JSON.stringify(value));
                await settle();
                step.show();
                await settle();
                say(posted.map(each => each.path));
                const lines = root.querySelectorAll('.goes .goes__line');
                const words = lines.map(line => line.textContent).join(' ').split(' ').filter(Boolean).length;
                say([lines.length, words <= 45, lines[0].textContent, lines[1].textContent, lines[2].textContent]);
                const gaps = root.querySelectorAll('.gaps button');
                say(gaps.map(each => [each.textContent, each.title.length > each.textContent.length]));
                await gaps[0].click();
                await gaps[1].click();
                say([shown, globalThis.location.hash]);
                const send = root.querySelectorAll('button').find(each => each.textContent === 'Send PAS request');
                await send.click();
                await settle();
                let body = posted[posted.length - 1];
                say([body.path, Object.keys(body.body.carry).sort(), body.body.diagnosisCodes, 'attachments' in body.body, handed]);
                const include = root.querySelector('#pas-include');
                include.checked = false;
                await send.click();
                await settle();
                body = posted[posted.length - 1];
                say(Object.keys(body.body.carry).sort());
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"/api/pas/submit/preview\"]",
                "[3,true,\"member S123456789 rule: packed NPI 1234567893 phone\",\"81162 x2 FL 2026-11-23 place 11\","
                        + "\"questionnaire response, 2 answers  Include it no files dx Z80.3\"]",
                "[[\"Provider phone or fax\",true],[\"Insurer\",true]]",
                "[[\"provider.phone\"],\"#configuration/environments/Sandbox/values\"]",
                "[\"/api/pas/submit\",[\"questionnaireResponse\",\"runId\"],[{\"code\":\"Z80.3\"}],false,[[\"resulted\",\"run-9\"]]]",
                "[\"runId\"]");
    }
}
