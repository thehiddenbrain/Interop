package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * Configuration > Advanced > Send as written, and the vendor's sample it offers (plan 10.9, I3;
 * T25). The page runs with Node against answers of the shapes ConfigController and RawController
 * send, with a stand-in for the frame (app.js) written in place of the real one. What it holds:
 * the sample is read from POST /api/config/resolve when its button is pressed, once a press, by
 * one function every page shares, and put in the box as the collection holds it; the button is
 * named in one place; a body that is not JSON is said, never refused; a secret typed for a send
 * goes once and its field is emptied; a refusal is shown at its field.
 */
class VendorSampleFrontendTest {

    private static final String PRELUDE = """
            import './browser.js';
            import fs from 'node:fs';
            fs.writeFileSync('./js/app.js', [
                'export const app = {',
                '  mode: () => globalThis.fake.mode, isMock: () => globalThis.fake.mode.mode === "mock",',
                '  epoch: () => 7, on: () => () => true, go: hash => globalThis.fake.went.push(hash), notice() {}',
                '};'].join(String.fromCharCode(10)));
            globalThis.fake = {mode: {mode: 'live', port: 8057, epoch: 7, environment: {name: 'Sample Sandbox', kind: 'sandbox', active: true}, writeAccess: {production: false, reason: 'Not production.'}}, went: [], sent: [], answers: {}};
            globalThis.fetch = async (address, init) => {
                const key = init.method + ' ' + address;
                fake.sent.push({key, body: init.body === undefined ? null : JSON.parse(init.body)});
                let answer = fake.answers[key] || {status: 404, body: {kind: 'NOT_FOUND', title: 'Not found.'}};
                if (Array.isArray(answer)) {
                    answer = answer.length > 1 ? answer.shift() : answer[0];
                }
                return {ok: answer.status < 300, status: answer.status, text: async () => JSON.stringify(answer.body)};
            };
            const settle = () => new Promise(resolve => setTimeout(resolve, 20));
            const say = value => console.log(JSON.stringify(value));
            const text = node => node.textContent;
            const press = async (scope, words) => {
                await scope.querySelectorAll('button').find(each => text(each) === words).click();
                await settle();
            };
            """;

    /** A request chosen, its placeholders listed, the sample put in the box, refused at a field, then sent. */
    @Test
    void sendAsWrittenListsThePlaceholdersPutsTheSampleInTheBoxAndSendsOnce(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "Send as written", PRELUDE + """
            const request = {id: 'r-1', name: 'order-sign Happy Path', method: 'POST', urlTemplate: '{{baseUrl}}/cds-services/{{serviceId}}',
                effectiveOperation: 'CRD_ORDER_SIGN', effectiveLabel: 'CRD order-sign', scripted: true};
            fake.answers['GET /api/config/collections'] = {status: 200, body: {collections: [{id: 'c-1', name: 'Sample ePA', requests: [request]}]}};
            const resolved = {operation: 'CRD_ORDER_SIGN', label: 'CRD order-sign', requestName: 'order-sign Happy Path', scripted: true,
                bodyTemplate: '{"hook": "order-sign", "fhirServer": "{{fhirServer}}"}',
                variables: [{name: 'baseUrl', resolved: true, display: 'https://sandbox.example.invalid', sourceLabel: 'The active environment'},
                    {name: 'clientSecret', secret: true, resolved: true, display: '{{clientSecret}} (masked)', sourceLabel: 'The active environment'}],
                urlTokens: []};
            fake.answers['POST /api/config/resolve'] = {status: 200, body: resolved};
            const {mount} = await import('./js/adv-raw.js');
            const root = document.createElement('div');
            document.body.append(root);
            const page = mount(root, ['advanced', 'raw']);
            await settle();
            const select = root.querySelector('select');
            say(['options', select.querySelectorAll('option').map(text)]);
            select.value = 'c-1 r-1';
            select.dispatchEvent({type: 'change'});
            await settle();
            const inputs = () => root.querySelectorAll('input').filter(each => each.getAttribute('data-variable'));
            say(['placeholders', inputs().map(each => [each.getAttribute('data-variable'), each.type, each.value]),
                root.querySelectorAll('.field__hint').map(text).filter(line => line.startsWith('Now:'))]);
            const box = root.querySelector('textarea');
            say(['box', box.value, fake.sent.filter(each => each.key === 'POST /api/config/resolve').length]);

            // The vendor's sample is read from resolve when the button is pressed, once a press, and put in the box as the collection holds it.
            await press(root, 'Put the vendor\\'s sample in the box');
            const resolves = fake.sent.filter(each => each.key === 'POST /api/config/resolve').map(each => each.body);
            say(['sample', box.value, resolves.length, resolves[resolves.length - 1],
                root.querySelectorAll('p').map(text).filter(line => line.startsWith('The sample of'))]);

            // A body that is not JSON is said; the send button's words say so; nothing is refused for it.
            box.value = '{"hook": ';
            box.dispatchEvent({type: 'input'});
            say(['not json', root.querySelectorAll('button').map(text).filter(words => words.startsWith('Send as written'))]);
            box.value = '{"hook":"order-sign","n":1.0}';
            box.dispatchEvent({type: 'input'});
            await press(root, 'Format the JSON');
            say(['formatted', box.value]);

            // A refusal at a placeholder is shown at its field; the typed secret is sent once and cleared.
            inputs()[1].value = 'typed-secret-value';
            fake.answers['POST /api/raw'] = [{status: 422, body: {kind: 'VALIDATION', title: 'The request cannot be sent. Nothing was sent.',
                fieldErrors: [{field: 'variables.baseUrl', message: 'This value is not an address.'}]}},
                {status: 200, body: {runId: 'run-1', protocol: 'RAW', verdict: {headline: 'The service answered (HTTP 200).', tone: 'NEUTRAL'}}}];
            await press(root, 'Send as written');
            const raws = () => fake.sent.filter(each => each.key === 'POST /api/raw').map(each => each.body);
            say(['refused', raws().length, raws()[0].variables, raws()[0].request, raws()[0].configEpoch, inputs()[1].value,
                root.querySelectorAll('.field--error').map(text)]);
            await press(root, 'Send as written');
            say(['sent', raws().length, text(root).includes('The result'), text(root).includes('typed-secret-value')]);

            // Nothing uploaded: said, with the way to Collections.
            page.unmount();
            fake.answers['GET /api/config/collections'] = {status: 200, body: {collections: []}};
            const empty = document.createElement('div');
            mount(empty, ['advanced', 'raw']);
            await settle();
            await press(empty, 'Open Collections');
            say(['empty', text(empty).includes('No collection is uploaded.'), fake.went, root.childNodes.length]);
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"options\",[\"Choose a request\",\"order-sign Happy Path (POST, CRD order-sign)\"]]",
                "[\"placeholders\",[[\"baseUrl\",\"text\",\"\"],[\"clientSecret\",\"password\",\"\"]],[\"Now: https://sandbox.example.invalid (The active environment)\",\"Now: {{clientSecret}} (masked) (The active environment)\"]]",
                "[\"box\",\"\",1]",
                "[\"sample\",\"{\\\"hook\\\": \\\"order-sign\\\", \\\"fhirServer\\\": \\\"{{fhirServer}}\\\"}\",2,{\"operation\":\"CRD_ORDER_SIGN\",\"request\":{\"collectionId\":\"c-1\",\"requestId\":\"r-1\"}},[\"The sample of order-sign Happy Path is in the box, as the collection holds it. It carries a pre-request script, which PAVE never runs.\"]]",
                "[\"not json\",[\"Send as written anyway, as it is not valid JSON\"]]",
                "[\"formatted\",\"{\\n  \\\"hook\\\": \\\"order-sign\\\",\\n  \\\"n\\\": 1.0\\n}\"]",
                "[\"refused\",1,{\"clientSecret\":\"typed-secret-value\"},{\"collectionId\":\"c-1\",\"requestId\":\"r-1\"},7,\"\",[\"{{baseUrl}}Now: https://sandbox.example.invalid (The active environment)This value is not an address.\"]]",
                "[\"sent\",2,true,false]",
                "[\"empty\",true,[\"#configuration/collections\"],0]");
    }

    @Test
    void theSampleIsReadByOneFunctionFromResolveAndItsButtonIsNamedOnce() throws IOException {
        String raw = StaticSources.withoutComments(StaticSources.script("adv-raw.js"));
        String compare = StaticSources.withoutComments(StaticSources.script("adv-compare.js"));
        assertThat(StaticSources.body(raw, "export async function readSample(operation, request) {"))
                .contains("call('POST', '/api/config/resolve', {body})");
        assertThat(compare).contains("import {bodyBox, sampleButton} from './adv-raw.js';").doesNotContain("bodyTemplate")
                .doesNotContain("readSample(");
        String label = "'Put the vendor\\'s sample in the box'";
        for (StaticSources.Source script : StaticSources.sources(".js")) {
            String text = StaticSources.withoutComments(script.text());
            int count = 0;
            for (int at = text.indexOf(label); at >= 0; at = text.indexOf(label, at + 1)) {
                count++;
            }
            assertThat(count).as(script.name())
                    .isEqualTo(script.name().endsWith("adv-raw.js") ? 1 : 0);
        }
    }
}
