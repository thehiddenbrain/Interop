package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * Configuration > Advanced > Try a value, Check this installation and CDS discovery (plan 10.9,
 * I3). The pages run with Node against answers of the shapes the server sends. What they hold: a
 * secret typed for a try is a password field, sent once and emptied, while any other typed value
 * stays for the next try; only operations that leave this machine are offered; every part of the
 * installation check is drawn, and a part whose answer did not come says so in its place with the
 * fault report; discovery asks nothing until its button is pressed and shows each value with its
 * presence.
 */
class AdvancedPagesFrontendTest {

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

    /** Try a value twice, the checks with one part failing, and discovery asked once. */
    @Test
    void tryChecksAndDiscoveryEachSayWhatTheServerAnswered(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the Advanced pages", PRELUDE + """
            // Try a value: a secret typed is sent once and its field emptied; a value typed for anything else stays for the next try.
            fake.answers['GET /api/config/effective'] = {status: 200, body: {operations: [
                {operation: 'CRD_ORDER_SIGN', label: 'CRD order-sign', outbound: true, requests: [{collectionId: 'c-1', collectionName: 'Sample ePA',
                    usedByDefault: true, request: {id: 'r-1', name: 'order-sign Happy Path'}}]},
                {operation: 'DTR_RESPONSE', label: 'DTR response', outbound: false},
                {operation: 'UNMAPPED', label: 'Unmapped', outbound: true}]}};
            fake.answers['POST /api/config/resolve'] = {status: 200, body: {operation: 'CRD_ORDER_SIGN', label: 'CRD order-sign', method: 'POST',
                url: {template: '{{baseUrl}}/cds-services', display: 'https://sandbox.example.invalid/cds-services'},
                variables: [{name: 'baseUrl', resolved: true, display: 'https://sandbox.example.invalid', sourceLabel: 'The active environment', usedIn: ['the url']},
                    {name: 'apiKey', secret: true, resolved: true, display: '{{apiKey}} (masked)', sourceLabel: 'The active environment'}]}};
            const tryPage = await import('./js/adv-try.js');
            const one = document.createElement('div');
            document.body.append(one);
            const handle = tryPage.mount(one, ['advanced', 'try']);
            await settle();
            one.querySelectorAll('select').find(each => each.name === 'operation').value = 'CRD_ORDER_SIGN';
            say(['operations', one.querySelectorAll('select')[0].querySelectorAll('option').map(text), one.querySelectorAll('select')[1].querySelectorAll('option').map(text)]);
            await press(one, 'Show how it resolves');
            const typed = () => one.querySelectorAll('input');
            say(['fields', typed().map(each => [each.getAttribute('data-variable'), each.type, each.value])]);
            typed()[0].value = 'https://typed.example.invalid';
            typed()[1].value = 'typed-key-value';
            await press(one, 'Show how it resolves');
            const resolves = fake.sent.filter(each => each.key === 'POST /api/config/resolve').map(each => each.body);
            say(['tried', resolves[1], typed().map(each => each.value), text(one).includes('typed-key-value'), text(one).includes('{{apiKey}} (masked)')]);
            handle.unmount();

            // Check this installation: every part is drawn; a part whose answer did not come says so in its place.
            fake.answers['GET /api/runs/health'] = {status: 200, body: {enabled: true, writable: true, directory: './run-history', currentFile: 'runs-2026-11-22.jsonl',
                runsToday: 3, failedWrites: 0, unreadableLines: 0}};
            fake.answers['GET /api/procedure-codes/self-check'] = {status: 500, body: {kind: 'INTERNAL', title: 'This tool hit an internal error.', reference: 'ref-1', cause: 'java.lang.IllegalStateException'}};
            fake.answers['GET /api/config/effective'] = {status: 200, body: {allowProdWrites: false, problems: [], storeDirectory: './config-store',
                operations: [{operation: 'MEMBER_LOOKUP', label: 'Member lookup', protocol: 'LOOKUP', lookupEnabled: false}]}};
            const checks = await import('./js/adv-checks.js');
            const two = document.createElement('div');
            document.body.append(two);
            checks.mount(two, ['advanced', 'checks']);
            await settle();
            say(['checks', two.querySelectorAll('h3').map(text), text(two).includes('Tracking is on, and runs are being recorded.'),
                text(two).includes('The code list did not answer its self-check.'), text(two).includes('ref-1'), text(two).includes('Member lookupoff')]);

            // CDS discovery: nothing is asked until the button is pressed; each value is shown with its presence.
            const discovery = await import('./js/adv-discovery.js');
            const three = document.createElement('div');
            document.body.append(three);
            discovery.mount(three, ['advanced', 'discovery']);
            await settle();
            const asked = () => fake.sent.filter(each => each.key === 'GET /api/crd/discover').length;
            say(['not asked', asked()]);
            fake.answers['GET /api/crd/discover'] = {status: 200, body: {url: {template: '{{baseUrl}}/cds-services', display: 'https://sandbox.example.invalid/cds-services'},
                services: [{id: {presence: 'VALUE', value: 'order-sign-crd'}, hook: {presence: 'VALUE', value: 'order-sign'}, title: {presence: 'ABSENT'},
                    description: {presence: 'EMPTY'}, prefetch: [{key: 'patient', template: {presence: 'VALUE', value: 'Patient/{{context.patientId}}'}}]}],
                additionalFields: [{path: 'services[0].extension', presence: 'VALUE', value: '[]'}]}};
            await press(three, 'Ask for the CDS services');
            say(['asked', asked(), three.querySelectorAll('.chip').map(text), three.querySelectorAll('summary').map(text)]);
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"operations\",[\"CRD order-sign\"],[\"The one the operation uses by default\",\"order-sign Happy Path (Sample ePA), used by default\"]]",
                "[\"fields\",[[\"baseUrl\",\"text\",\"\"],[\"apiKey\",\"password\",\"\"]]]",
                "[\"tried\",{\"operation\":\"CRD_ORDER_SIGN\",\"variables\":{\"baseUrl\":\"https://typed.example.invalid\",\"apiKey\":\"typed-key-value\"}},[\"https://typed.example.invalid\",\"\"],false,true]",
                "[\"checks\",[\"The ledger of test runs\",\"The procedure code list\",\"PAVE and where it is pointed\",\"Member and provider lookup\"],true,true,true,true]",
                "[\"not asked\",0]",
                "[\"asked\",1,[\"not returned\",\"hook\",\"returned empty\"],[\"Returned but not interpreted1\"]]");
    }
}
