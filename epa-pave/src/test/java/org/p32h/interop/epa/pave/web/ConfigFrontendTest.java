package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * Configuration's router and its Environments page (plan 3.4, 5.4, 9.1), run as the browser runs
 * them, with Node and the stand-in for the document. The frame (app.js) is replaced by a stand-in
 * that answers the mode, and the server by a stand-in for fetch that answers in the shapes of plan
 * 5.3 and 5.4 and records what the page posts. Each test holds a rule a reading of the source
 * cannot: a refused field opens the fold that holds it; a secret input is a password field never
 * given a value from the server; files are read through files.js and posted as JSON text; the file
 * input is let go after a refusal; a part of the form is drawn only when the server's view carries
 * its field; and the router mounts each page module by its contract and unmounts it, and mounts afresh for a new address
 * any page but Environments, whose update() redraws.
 */
class ConfigFrontendTest {

    /** The stand-ins every script of this test starts with: the document, the frame and the server. */
    private static final String PRELUDE = """
            import './browser.js';
            import {writeFileSync} from 'node:fs';
            writeFileSync('./js/app.js', `
                export const app = {
                    mode: () => globalThis.standInMode,
                    isMock: () => globalThis.standInMode.mode === 'mock',
                    epoch: () => 1,
                    on: () => () => true,
                    go() {},
                    notice() {}
                };
                export function ledgerLine() { return null; }
            `);
            globalThis.standInMode = {mode: 'live', epoch: 1, writeAccess: {}};
            globalThis.window = {history: {replaceState() {}}, location: {hash: ''}};
            const say = value => console.log(JSON.stringify(value));
            const posted = [];
            const answers = {};
            globalThis.fetch = async (address, init) => {
                const method = init && init.method ? init.method : 'GET';
                const path = String(address).split('?')[0];
                if (method !== 'GET') {
                    posted.push({method, path, body: init.body, type: init.headers['Content-Type']});
                }
                const found = answers[method + ' ' + path] || {status: 404, body: {kind: 'NOT_FOUND', title: 'Not here.'}};
                const text = JSON.stringify(typeof found.body === 'function' ? found.body(init) : found.body);
                return {ok: found.status >= 200 && found.status < 300, status: found.status, text: async () => text};
            };
            const tick = () => new Promise(done => setTimeout(done, 0));
            const settle = async () => { for (let i = 0; i < 20; i++) { await tick(); } };
            const view = (extra) => Object.assign({
                id: 'pave-sample-sandbox', name: 'PAVE Sample Sandbox', kind: 'sandbox', active: true, acknowledgedNotProd: false,
                profile: {mode: 'live', words: 'Live, chosen when PAVE starts'},
                signIn: {method: 'CLIENT_CREDENTIALS', tokenUrl: '{{tokenUrl}}', clientId: '{{clientId}}', scope: '',
                    clientAuthentication: 'BASIC', clientSecret: {set: true, reference: '{{env:PAVE_ONYX_CLIENT_SECRET}}'},
                    bearerToken: {set: true}, inForceWords: 'Client credentials, this environment'},
                epoch: 1
            }, extra || {});
            const full = view({
                timeouts: {connectMs: null, readMs: 60000, inForce: {connectMs: 5000, readMs: 60000, connectFrom: 'application.yml',
                    readFrom: 'this environment'}, defaults: {connectMs: 5000, readMs: 120000}},
                memberIdRule: {rule: 'PACKED', from: 'default', options: [{value: 'PACKED', words: 'packed: spaces removed before sending'},
                    {value: 'SPACED', words: 'spaced'}, {value: 'SPLIT', words: 'split'}]},
                shapes: [{key: 'CRD_DRAFT_ORDERS_TYPE', label: 'draftOrders type', value: 'SENT', from: 'default',
                    options: [{value: 'SENT', words: 'Sent (FHIR requires it)'}, {value: 'LEFT_OUT', words: 'Left out (this vendor refuses it)'}],
                    why: 'Why it exists.'}],
                values: [{name: 'fhirServer', label: 'FHIR server', why: 'Sent in CRD.', typed: null,
                    inForce: {display: 'https://fhir.vendor.example.invalid', source: 'EXPORT', sourceLabel: 'from the export'}}],
                addresses: [{operation: 'PAS_SUBMIT', label: 'PAS submit', short: 'PAS submit', typed: null,
                    inForce: {display: 'https://vendor.example.invalid/fhir/Claim/$submit', source: 'COLLECTION', sourceLabel: 'collection request',
                        resolved: true, unresolved: []}}]
            });
            const inputsOf = dialog => dialog.querySelectorAll('input');
            """;

    @Test
    void aRefusedFieldOpensTheFoldThatHoldsItAndWhatHasNoFieldIsSaidAboveTheForm(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the environment form's refusals", PRELUDE + """
                const {createEditor} = await import('./js/config-environments.js');
                answers['POST /api/config/environments/settings'] = {status: 422, body: {kind: 'VALIDATION', title: 'Some fields need attention.',
                    fieldErrors: [{field: 'timeouts.readMs', message: 'Between 100 and 600000.'},
                        {field: 'values.fhirServer', message: 'Type an absolute address.'},
                        {field: 'somethingElse', message: 'Said above the form.'}]}};
                const editor = createEditor({environment: {id: full.id, name: full.name, kind: 'sandbox'}, settings: full});
                const dialog = editor.dialog;
                const folds = () => dialog.querySelectorAll('details').filter(each => each.dataset.section).map(each => [each.dataset.section, each.open]);
                say(folds());
                await dialog.querySelectorAll('button').find(each => each.textContent === 'Save environment').click();
                await settle();
                say(folds());
                say(dialog.querySelectorAll('.field--error').map(each => each.querySelector('.field__error').textContent));
                say(dialog.querySelector('.config-form__banner').textContent.includes('Said above the form.'));
                say(dialog.querySelector('.config-form__banner').textContent.includes('Type an absolute address.'));
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[[\"timeouts\",false],[\"shapes\",false],[\"values\",false]]",
                "[[\"timeouts\",true],[\"shapes\",false],[\"values\",true]]",
                "[\"Between 100 and 600000.\",\"Type an absolute address.\"]",
                "true",
                // A refusal placed at its field is not said a second time above the form.
                "false");
    }

    @Test
    void aRefusalWhoseFieldErrorsAreAllPlacedStillSaysItsTitleAboveTheForm(@TempDir Path folder) throws Exception {
        // look-guardrail-1: a sandbox given a production address is refused at Kind; the title names the address.
        NodeRun run = StaticSources.runModule(folder, "the title of a placed refusal", PRELUDE + """
                const {createEditor} = await import('./js/config-environments.js');
                answers['POST /api/config/environments/settings'] = {status: 422, body: {kind: 'VALIDATION',
                    title: 'This environment was declared as sandbox, and its address http://127.0.0.1:8950/fhir/Claim/$submit is an address of the production environment "vendor-live". It was not stored.',
                    fieldErrors: [{field: 'kind', message: 'Declared as sandbox.'}]}};
                const editor = createEditor({environment: {id: full.id, name: full.name, kind: 'sandbox'}, settings: full});
                const dialog = editor.dialog;
                await dialog.querySelectorAll('button').find(each => each.textContent === 'Save environment').click();
                await settle();
                say(dialog.querySelectorAll('.field--error').map(each => each.querySelector('.field__error').textContent));
                const banner = dialog.querySelector('.config-form__banner').textContent;
                say([banner.includes('declared as sandbox'), banner.includes('vendor-live'), banner.includes('Declared as sandbox.')]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly("[\"Declared as sandbox.\"]", "[true,true,false]");
    }

    @Test
    void aRefusalBringsThePanelThatSaysWhyIntoViewAndFocusesItNotAFieldFurtherDown(@TempDir Path folder) throws Exception {
        // look-guardrail-8: the focus went to Kind, which scrolled the dialog body past the title naming the address.
        NodeRun run = StaticSources.runModule(folder, "the refusal in view", PRELUDE + """
                const {createEditor} = await import('./js/config-environments.js');
                answers['POST /api/config/environments/settings'] = {status: 422, body: {kind: 'VALIDATION', heading: 'A problem with what was submitted',
                    title: 'This environment was declared as sandbox, and its address http://127.0.0.1:8950/fhir/Claim/$submit is an address of the production environment "vendor-live". It was not stored.',
                    fieldErrors: [{field: 'signIn.tokenUrl', message: 'Not the token url of a production environment.'}]}};
                const editor = createEditor({environment: {id: full.id, name: full.name, kind: 'sandbox'}, settings: full});
                const dialog = editor.dialog;
                const banner = dialog.querySelector('.config-form__banner');
                const scrolled = [];
                banner.scrollIntoView = how => scrolled.push(how.block);
                await dialog.querySelectorAll('button').find(each => each.textContent === 'Save environment').click();
                await settle();
                const active = document.activeElement;
                say([scrolled, Boolean(active) && banner.contains(active), active && active.getAttribute('tabindex'), active && active.textContent]);
                say(dialog.querySelectorAll('.field--error').map(each => each.querySelector('.field__error').textContent));
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[[\"start\"],true,\"-1\",\"A problem with what was submitted\"]",
                "[\"Not the token url of a production environment.\"]");
    }

    @Test
    void theKindAndMethodOptionsSayWhatTheyDoInPlainWords(@TempDir Path folder) throws Exception {
        // I-15: no "application.yml" and no "never guessed" in the options a tester picks from.
        NodeRun run = StaticSources.runModule(folder, "the option words", PRELUDE + """
                const {createEditor} = await import('./js/config-environments.js');
                const editor = createEditor({});
                const options = value => editor.dialog.querySelectorAll('select')
                    .find(each => each.querySelectorAll('option').some(o => o.value === value))
                    .querySelectorAll('option').filter(o => o.value === '').map(o => o.textContent);
                say([options('prod'), options('BEARER_TOKEN')]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly("[[\"Choose a kind\"],[\"As PAVE was started\"]]");
    }

    @Test
    void operationsThatShareTheirShortWordsAreOneChipWithItsCount(@TempDir Path folder) throws Exception {
        // I-22: four subscription operations were four chips that read "Subscription".
        NodeRun run = StaticSources.runModule(folder, "the grouped chips", PRELUDE + """
                const {groupedChips} = await import('./js/config-environments.js');
                const chips = groupedChips([{words: 'PAS submit', title: 'submit Happy Path'},
                    {words: 'Subscription', title: 'subscription-get Happy Path'}, {words: 'Subscription', title: 'subscription-create Happy Path'},
                    {words: 'Subscription', title: 'subscription-update Happy Path'}, {words: 'Subscription', title: 'subscription-delete Happy Path'},
                    {words: 'notification', title: null}, {words: 'order-sign', dashed: true}, {words: 'order-sign', dashed: false}]);
                say(chips.map(each => [each.textContent, each.getAttribute('title') || each.title || null]));
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly("[[\"PAS submit\",\"submit Happy Path\"],"
                + "[\"Subscription x4\",\"subscription-get Happy Path, subscription-create Happy Path, subscription-update Happy Path, subscription-delete Happy Path\"],"
                + "[\"notification\",null],[\"order-sign\",null],[\"order-sign\",null]]");
    }

    @Test
    void aFileIsChosenWithTheChooserAndAnEmptyStoreIsOneLineAcrossTheGrid(@TempDir Path folder) throws Exception {
        // look-11: a "Choose file" button and the chosen name, never the browser's own control.
        // look-18: the line in place of the cards spans the grid, not one narrow column.
        NodeRun run = StaticSources.runModule(folder, "the file chooser and the empty store", PRELUDE + """
                const {createEditor, mount} = await import('./js/config-environments.js');
                const editor = createEditor({});
                const boxes = () => editor.dialog.querySelectorAll('.file').map(box => [box.querySelector('button').textContent,
                    box.querySelector('.file__name').textContent, box.querySelector('input').className.includes('visually-hidden')]);
                say(boxes());
                const input = editor.dialog.querySelectorAll('input').find(each => each.type === 'file');
                input.files = [new File(['{}'], 'env.json')];
                input.value = 'C:/fakepath/env.json';
                await input.dispatchEvent({type: 'change'});
                say(boxes()[0]);
                answers['GET /api/config/environments'] = {status: 200, body: {environments: [], epoch: 1}};
                const root = document.createElement('div');
                document.body.append(root);
                mount(root, ['environments']);
                await settle();
                say(root.querySelectorAll('.config-grid-line').map(each => each.textContent));
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[[\"Choose file\",\"No file chosen\",true],[\"Choose file\",\"No file chosen\",true]]",
                "[\"Choose file\",\"env.json\",true]",
                "[\"No environment is stored yet. Start with New environment.\"]");
    }

    @Test
    void aSecretInputIsAPasswordFieldNeverGivenAValueFromTheServer(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the secret inputs", PRELUDE + """
                const {createEditor} = await import('./js/config-environments.js');
                answers['POST /api/config/environments/draft'] = {status: 200, body: {files: [{slot: 'environmentExport', fileName: 'env.json', read: true,
                    line: 'Read: 2 variables, 1 secret.'}], name: 'PAVE Sample Sandbox',
                    signIn: {method: 'CLIENT_CREDENTIALS', clientSecret: {set: true, reference: '{{clientSecret}}'}, bearerToken: {set: false}},
                    variables: [], shapes: [], operations: [], warnings: [], epoch: 1}};
                answers['POST /api/config/environments/settings'] = {status: 200, body: {environment: {id: full.id, name: full.name}, settings: full,
                    warnings: [], epoch: 2}};
                const editor = createEditor({environment: {id: full.id, name: full.name, kind: 'sandbox'}, settings: full});
                const dialog = editor.dialog;
                const secrets = () => inputsOf(dialog).filter(each => each.type === 'password').map(each => [each.value, each.autocomplete]);
                say(secrets());
                say(dialog.textContent.includes('Stored: {{env:PAVE_ONYX_CLIENT_SECRET}}'));
                const method = dialog.querySelectorAll('select').find(each => each.querySelectorAll('option').some(o => o.value === 'BEARER_TOKEN'));
                method.value = 'BEARER_TOKEN';
                await method.dispatchEvent({type: 'change'});
                say(secrets());
                method.value = 'CLIENT_CREDENTIALS';
                await method.dispatchEvent({type: 'change'});
                const box = inputsOf(dialog).find(each => each.type === 'file');
                box.files = [new File(['{"name": "PAVE Sample Sandbox", "values": []}'], 'env.json', {type: 'application/json'})];
                box.value = 'C:/fakepath/env.json';
                await dialog.querySelectorAll('button').find(each => each.textContent === 'Fill the form from these files').click();
                await settle();
                say(secrets());
                say(dialog.textContent.includes('Filled from the export as {{clientSecret}}.'));
                await dialog.querySelectorAll('button').find(each => each.textContent === 'Save environment').click();
                await settle();
                const saved = JSON.parse(posted.find(each => each.path === '/api/config/environments/settings').body);
                // The reference the draft found goes back as the reference: the page never held a secret.
                say([saved.signIn.clientSecret, saved.signIn.removeClientSecret]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[[\"\",\"new-password\"]]",
                "true",
                "[[\"\",\"new-password\"]]",
                "[[\"\",\"new-password\"]]",
                "true",
                "[\"{{clientSecret}}\",false]");
    }

    @Test
    void filesAreReadThroughFilesJsPostedAsJsonTextAndLetGoAfterARefusal(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the files of the environment form", PRELUDE + """
                const {createEditor} = await import('./js/config-environments.js');
                answers['POST /api/config/environments/draft'] = {status: 200, body: {files: [], variables: [], shapes: [], operations: [],
                    warnings: [], epoch: 1}};
                answers['POST /api/config/environments/settings'] = {status: 422, body: {kind: 'VALIDATION', title: 'Not stored.',
                    fieldErrors: [{field: 'collectionExport', message: 'This is a HAR file, not a Postman export.'}]}};
                const editor = createEditor({environment: null, settings: null});
                const dialog = editor.dialog;
                const [environmentBox, collectionBox] = inputsOf(dialog).filter(each => each.type === 'file');
                // Saved by another program in another encoding: refused in the page, nothing posted, the input let go.
                environmentBox.files = [new File([new Uint8Array([0x7B, 0x22, 0x6E, 0xE9, 0x22, 0x7D])], 'notepad.json')];
                environmentBox.value = 'C:/fakepath/notepad.json';
                await dialog.querySelectorAll('button').find(each => each.textContent === 'Fill the form from these files').click();
                await settle();
                say([posted.length, environmentBox.value, dialog.querySelector('.config-file-lines').textContent]);
                collectionBox.files = [new File(['{"log": {"entries": []}}'], 'capture.har')];
                collectionBox.value = 'C:/fakepath/capture.har';
                await dialog.querySelectorAll('button').find(each => each.textContent === 'Fill the form from these files').click();
                await settle();
                const draft = posted[0];
                say([draft.path, draft.type, JSON.parse(draft.body)]);
                await dialog.querySelectorAll('button').find(each => each.textContent === 'Save environment').click();
                await settle();
                const save = posted[1];
                say([save.path, save.type, JSON.parse(save.body).collectionExport]);
                // Refused by the server at the file's field: the input is let go, so the next press reads what is chosen again.
                say([collectionBox.value, collectionBox.closest('.field').querySelector('.field__error').textContent]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[0,\"\",\"Environment export: not read: notepad.json is saved in another encoding (for example by PowerShell or Notepad). "
                        + "Save it as UTF-8, or export it again from Postman.\"]",
                "[\"/api/config/environments/draft\",\"application/json\",{\"collectionExport\":{\"fileName\":\"capture.har\","
                        + "\"text\":\"{\\\"log\\\": {\\\"entries\\\": []}}\"}}]",
                "[\"/api/config/environments/settings\",\"application/json\",{\"fileName\":\"capture.har\","
                        + "\"text\":\"{\\\"log\\\": {\\\"entries\\\": []}}\"}]",
                "[\"\",\"This is a HAR file, not a Postman export.\"]");
    }

    @Test
    void aPartOfTheFormIsDrawnOnlyWhenTheServersViewCarriesItsField(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the parts of the environment form", PRELUDE + """
                const {createEditor} = await import('./js/config-environments.js');
                const parts = settings => {
                    const editor = createEditor({environment: {id: 'pave-sample-sandbox', name: 'PAVE Sample Sandbox', kind: 'sandbox'}, settings});
                    const text = editor.dialog.textContent;
                    const out = ['Member ID rule', 'Timeouts', 'Request shapes', 'Values', 'Addresses', 'Mock or live'].filter(word => text.includes(word));
                    const body = editor.input();
                    editor.close();
                    return [out, Object.keys(body)];
                };
                say(parts(view()));
                say(parts(full));
                say(parts(null));
                // The rule and a shape go only once changed: sending what is shown would turn a default, or the
                // evidence of the collection's sample, into a value stated by this environment.
                const editor = createEditor({environment: {id: full.id, name: full.name, kind: 'sandbox'}, settings: full});
                const shape = editor.dialog.querySelectorAll('select').find(each => each.querySelectorAll('option').some(o => o.value === 'LEFT_OUT'));
                const rule = editor.dialog.querySelectorAll('select').find(each => each.querySelectorAll('option').some(o => o.value === 'SPLIT'));
                say([editor.input().shapes, editor.input().memberIdRule]);
                shape.value = 'LEFT_OUT';
                rule.value = 'SPLIT';
                say([editor.input().shapes, editor.input().memberIdRule]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                // The view carries only the sign-in: no other part is drawn, and none is sent.
                "[[\"Mock or live\"],[\"id\",\"name\",\"kind\",\"signIn\"]]",
                "[[\"Member ID rule\",\"Timeouts\",\"Request shapes\",\"Values\",\"Addresses\",\"Mock or live\"],"
                        + "[\"id\",\"name\",\"kind\",\"signIn\",\"timeouts\",\"addresses\",\"values\"]]",
                // No view at all: the facts the frame knows, the sign-in, and nothing guessed.
                "[[\"Mock or live\"],[\"id\",\"name\",\"kind\",\"signIn\"]]",
                "[null,null]",
                "[{\"CRD_DRAFT_ORDERS_TYPE\":\"LEFT_OUT\"},\"SPLIT\"]");
    }

    @Test
    void theRouterMountsEachPageModuleByItsContractAndUnmountsIt(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the Configuration router", PRELUDE + """
                const {mountWith, normal, PAGES, ADVANCED} = await import('./js/configuration.js');
                const {el} = await import('./js/dom.js');
                const log = [];
                const page = name => ({mount(root, route) {
                    log.push('mount ' + name + ' ' + route.join('/'));
                    root.append(el('p', null, name));
                    return {update: next => log.push('update ' + name + ' ' + next.join('/')), unmount: () => log.push('unmount ' + name)};
                }});
                const loaded = [];
                const load = name => { loaded.push(name); return Promise.resolve(page(name)); };
                const root = el('section');
                document.body.append(root);
                const config = mountWith(root, [], load);
                await settle();
                config.update(['environments', 'pave-sample-sandbox', 'values']);
                await settle();
                config.update(['members']);
                await settle();
                config.update(['providers']);
                await settle();
                config.update(['advanced']);
                await settle();
                say(root.querySelectorAll('[aria-current]').map(each => each.textContent));
                config.update(['made-up']);
                await settle();
                config.unmount();
                say(log);
                say(loaded);
                say(PAGES.map(each => each.label));
                say(ADVANCED.map(each => each.id));
                say([normal(['advanced', 'nothing']), normal(['advanced', 'trail']), normal([])]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"In force now\"]",
                "[\"mount config-environments.js environments\",\"update config-environments.js environments/pave-sample-sandbox/values\","
                        + "\"unmount config-environments.js\",\"mount cfg-lists.js members\",\"unmount cfg-lists.js\",\"mount cfg-lists.js providers\","
                        + "\"unmount cfg-lists.js\",\"mount cfg-inforce.js advanced/in-force\",\"unmount cfg-inforce.js\","
                        + "\"mount config-environments.js environments\",\"unmount config-environments.js\"]",
                "[\"config-environments.js\",\"cfg-lists.js\",\"cfg-lists.js\",\"cfg-inforce.js\",\"config-environments.js\"]",
                "[\"Environments\",\"Collections\",\"Members\",\"Providers\",\"Access token\",\"Production writes\",\"Advanced\"]",
                "[\"in-force\",\"trail\",\"raw\",\"try\",\"checks\",\"discovery\",\"compare\"]",
                "[[\"advanced\",\"in-force\"],[\"advanced\",\"trail\"],[\"environments\"]]");
    }
}
