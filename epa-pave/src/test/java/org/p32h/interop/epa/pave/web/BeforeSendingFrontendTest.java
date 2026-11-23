package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * The line before sending on steps 1, 2 and 3 (D-321), run with Node and a stand-in for the
 * document: the page asks the server with the step, the request chosen for it and the case's two
 * parts, shows the server's line as a plain line where the step is sent, and shows nothing when the
 * server says nothing, when the step sends the collection's body as written, or when the ask fails.
 * Nothing goes to browser storage.
 */
class BeforeSendingFrontendTest {

    private static final String BOTH = "The collection's request for this step carries a diagnosis and a servicing provider. This case "
            + "names neither. Add Diagnosis codes on the Service card and a Servicing provider on the Provider card, or send as it is.";

    @Test
    void theLineIsTheServersAndIsDrawnOnlyWhenItSaysOne(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the line before sending", """
                import './browser.js';
                import {createBeforeLine, beforeBody} from './js/wb-before.js';
                import {choose, writeAsWritten, forgetLines} from './js/wb-request.js';
                const say = value => console.log(JSON.stringify(value));
                const asked = [];
                let reply = {status: 200, body: {line: 'LINE', sampleDiagnosis: true, sampleServicing: true}};
                globalThis.fetch = async (url, init) => { asked.push([url, JSON.parse(init.body)]);
                  return {ok: reply.status < 300, status: reply.status, text: async () => JSON.stringify(reply.body)}; };
                let theCase = {diagnosisCodes: [], servicingProvider: null};
                const line = createBeforeLine({operation: () => 'CRD_ORDER_SIGN', request: () => theCase});
                document.body.append(line.node);
                say([line.node.hidden, line.node.textContent]);
                await line.ask();
                say([line.node.hidden, line.node.textContent, line.node.getAttribute('role')]);
                say(asked[0]);
                // The case's parts go as typed; a chosen request goes with them.
                theCase = {diagnosisCodes: ['G47.33'], servicingProvider: {npi: '9999999995'}};
                choose('CRD_ORDER_SIGN', {collectionId: 'c-1', requestId: 'r-1'});
                await new Promise(resolve => setTimeout(resolve, 10));
                say(asked[asked.length - 1][1]);
                // The server says nothing: nothing is drawn.
                reply = {status: 200, body: {sampleDiagnosis: true, sampleServicing: true}};
                await line.ask();
                say([line.node.hidden, line.node.textContent]);
                // A failed ask draws nothing either: the line is advice.
                reply = {status: 500, body: {kind: 'INTERNAL', title: 'Internal error'}};
                await line.ask();
                say([line.node.hidden, line.node.textContent]);
                // A body as the collection writes it carries the collection's own: nothing is asked, nothing drawn.
                reply = {status: 200, body: {line: 'LINE'}};
                const before = asked.length;
                writeAsWritten('CRD_ORDER_SIGN', true);
                await new Promise(resolve => setTimeout(resolve, 10));
                await line.ask();
                say([asked.length - before, line.node.hidden]);
                writeAsWritten('CRD_ORDER_SIGN', false);
                line.stop();
                forgetLines();
                say(beforeBody('PAS_SUBMIT', {diagnosisCodes: [{code: 'G47.33', system: 'urn:placeholder:dx'}]}));
                say(beforeBody('PAS_SUBMIT', null));
                say(Object.keys(globalThis.localStorage ? globalThis.localStorage : {}).filter(key => key !== 'pave.tab'));
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[true,\"\"]",
                "[false,\"LINE\",\"status\"]",
                "[\"/api/workbench/before-sending\",{\"operation\":\"CRD_ORDER_SIGN\",\"diagnosisCodes\":[]}]",
                "{\"operation\":\"CRD_ORDER_SIGN\",\"diagnosisCodes\":[{\"code\":\"G47.33\"}],\"servicingProvider\":{\"npi\":\"9999999995\"},"
                        + "\"request\":{\"collectionId\":\"c-1\",\"requestId\":\"r-1\"}}",
                "[true,\"\"]",
                "[true,\"\"]",
                "[0,true]",
                "{\"operation\":\"PAS_SUBMIT\",\"diagnosisCodes\":[{\"code\":\"G47.33\",\"system\":\"urn:placeholder:dx\"}]}",
                "{\"operation\":\"PAS_SUBMIT\",\"diagnosisCodes\":[]}",
                "[]");
    }

    @Test
    void theCoverageCheckShowsTheLineUnderItsHeadAndSendsTheServicingProvider(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the coverage check's line", """
                import './browser.js';
                import {mount, crdBody} from './js/wb-crd.js';
                import {forgetLines} from './js/wb-request.js';
                const say = value => console.log(JSON.stringify(value));
                const asked = [];
                globalThis.fetch = async (url, init) => { asked.push(url);
                  const body = url.endsWith('/before-sending') ? {line: %s} : {};
                  return {ok: true, status: 200, text: async () => JSON.stringify(body)}; };
                const theCase = {member: {memberId: 'S123456789'}, provider: {npi: '1234567893'}, procedureCodes: [{code: 'E0601'}],
                  diagnosisCodes: [], servicingProvider: null, chainId: 'new', configEpoch: 1};
                const ctx = {environmentName: () => null, request: () => theCase, isMock: () => false, crdScenarios: () => null,
                  blocked: () => null, readinessLine: () => '', latest: () => null, card: () => '', typedCode: () => '', resulted: () => {},
                  refused: () => {}, placeErrors: () => [], labelOf: () => null};
                const root = document.createElement('section');
                document.body.append(root);
                const step = mount(root, ctx);
                step.show();
                await new Promise(resolve => setTimeout(resolve, 20));
                const line = root.querySelector('[data-before-line]');
                say([asked.includes('/api/workbench/before-sending'), line.hidden, line.textContent]);
                // The line stands above the step's options, where the check is sent.
                const order = Array.from(root.children).map(each => each.className || each.tagName);
                say(order.indexOf('small before-line') < order.indexOf('panel__options'));
                step.unmount();
                forgetLines();
                const own = {hook: 'order-sign', includePrefetch: true, expected: '', mock: false, scenarioId: ''};
                say(crdBody({...theCase, servicingProvider: {npi: '9999999995', lastName: 'Servicing'}}, own).servicingProvider);
                say('servicingProvider' in crdBody(theCase, own));
                """.formatted("\"" + BOTH.replace("'", "\\'") + "\""));
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[true,false,\"" + BOTH + "\"]",
                "true",
                "{\"npi\":\"9999999995\",\"lastName\":\"Servicing\"}",
                "false");
    }

    @Test
    void theQuestionnaireAndSubmitStepsMountTheLineForTheirOwnStep() throws Exception {
        String dtr = StaticSources.script("wb-dtr.js");
        String submit = StaticSources.script("wb-submit.js");
        assertThat(dtr).contains("createBeforeLine({operation: () => 'DTR_QUESTIONNAIRE_PACKAGE', request: () => ctx.request()})")
                .contains("before.node, optionsRow");
        assertThat(submit).contains("createBeforeLine({operation: () => 'PAS_SUBMIT', request: () => ctx.request()})")
                .contains("before.node, optionsRow");
    }
}
