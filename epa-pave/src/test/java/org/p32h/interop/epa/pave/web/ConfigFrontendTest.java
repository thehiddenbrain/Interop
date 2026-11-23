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
 * any page but Environments, whose update() redraws. The lookup forms of Members and Providers
 * (plan 3.4, 5.5) hold theirs: GET hides the body template and POST says the member ID goes in the
 * body; each sign-in kind shows its own fields and sends only those; a lookup secret is a password
 * field never given a value, sent as typed and gone from the page once sent; and a server without
 * lookup settings gets no form at all.
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
                // The server's words since the switch in the top bar: the mode is no longer chosen only at start.
                profile: {mode: 'live', words: 'Live. Switch it in the top bar.'},
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
                    inForce: {display: 'https://fhir.vendor.example.invalid', source: 'EXPORT', sourceLabel: "from the export's variable fhirServer"}}],
                addresses: [{operation: 'PAS_SUBMIT', label: 'PAS submit', short: 'PAS submit', typed: null,
                    inForce: {display: 'https://vendor.example.invalid/fhir/Claim/$submit', source: 'COLLECTION', sourceLabel: `from the collection's request "PAS submit"`,
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
                say(dialog.querySelectorAll('.config-form__banner .error-fields li').map(each => each.textContent));
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[[\"timeouts\",false],[\"shapes\",false],[\"values\",false]]",
                "[[\"timeouts\",true],[\"shapes\",false],[\"values\",true]]",
                "[\"Between 100 and 600000.\",\"Type an absolute address.\"]",
                "true",
                // I-3 (live-config-8): the panel names each field by its label with its sentence, also when it is placed at the field.
                "true",
                "[\"Wait for a response up to (ms) Between 100 and 600000.\",\"FHIR server Type an absolute address.\",\"Said above the form.\"]");
    }

    /**
     * D-307: on the first real run a box that stood empty beside a value the files gave was read as
     * a field left unfilled. A row in force shows that value and where it comes from as its own
     * content, its box closed; "Change" opens the box and the button under it closes it and takes
     * what was typed away; a typed row and a row with nothing in force show their box; a stored
     * secret is said as stored and its box opens on Change.
     */
    @Test
    void aRowInForceIsNotAnEmptyBoxAndChangeOpensItAndTakesItBack(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the rows in force", PRELUDE + """
                const {createEditor} = await import('./js/config-environments.js');
                const settings = Object.assign({}, full, {
                    values: [full.values[0],
                        {name: 'payerName', label: 'Payer name', why: 'PAS requires it.', typed: 'Placeholder Payer',
                            inForce: {display: 'Placeholder Payer', source: 'THIS_ENVIRONMENT', sourceLabel: 'typed here'}},
                        {name: 'memberIdSystem', label: 'Member ID system', why: 'On the Coverage.', typed: null,
                            inForce: {source: 'NOT_SET', sourceLabel: 'not set'}}]});
                const editor = createEditor({environment: {id: full.id, name: full.name, kind: 'sandbox'}, settings});
                const dialog = editor.dialog;
                const row = label => dialog.querySelectorAll('.field').find(each => each.querySelector('.field__label')
                    && each.querySelector('.field__label').textContent === label);
                const state = label => {
                    const wrapper = row(label);
                    const input = wrapper.querySelector('input');
                    const shown = wrapper.querySelectorAll('button').filter(each => !each.hidden).map(each => each.textContent);
                    return [wrapper.querySelector('.config-in-force').textContent, input.hidden, input.value, shown];
                };
                for (const label of ['FHIR server', 'Payer name', 'Member ID system', 'PAS submit']) {
                    say(state(label));
                }
                const press = (label, words) => row(label).querySelectorAll('button').find(each => each.textContent === words).click();
                press('PAS submit', 'Change');
                say(state('PAS submit'));
                row('PAS submit').querySelector('input').value = 'https://typed.example.invalid/Claim/$submit';
                say(editor.input().addresses.PAS_SUBMIT);
                press('PAS submit', "Use the collection's request again");
                say([state('PAS submit'), editor.input().addresses.PAS_SUBMIT]);
                press('Payer name', "Use the file's again");
                say([state('Payer name'), editor.input().values.payerName, row('Payer name').textContent.includes('taken away when the environment is saved')]);
                const secret = row('Client secret');
                say([secret.querySelector('input').hidden, secret.textContent.includes('Stored: {{env:PAVE_ONYX_CLIENT_SECRET}}')]);
                secret.querySelectorAll('button').find(each => each.textContent === 'Change').click();
                say([secret.querySelector('input').hidden, secret.querySelector('input').type, editor.input().signIn.clientSecret]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"https://fhir.vendor.example.invalid from the export's variable fhirServer\",true,\"\",[\"Change\"]]",
                "[\"Placeholder Payer typed here\",false,\"Placeholder Payer\",[\"Use the file's again\"]]",
                "[\"not set\",false,\"\",[]]",
                "[\"https://vendor.example.invalid/fhir/Claim/$submit from the collection's request \\\"PAS submit\\\"\",true,\"\",[\"Change\"]]",
                "[\"https://vendor.example.invalid/fhir/Claim/$submit from the collection's request \\\"PAS submit\\\"\",false,\"\","
                        + "[\"Use the collection's request again\"]]",
                "\"https://typed.example.invalid/Claim/$submit\"",
                "[[\"https://vendor.example.invalid/fhir/Claim/$submit from the collection's request \\\"PAS submit\\\"\",true,\"\",[\"Change\"]],\"\"]",
                "[[\"Placeholder Payer typed here\",true,\"\",[\"Change\"]],\"\",true]",
                "[true,true]",
                "[false,\"password\",\"\"]");
    }

    /**
     * D-307: right after Fill the form shows what the files give each address and value, with where
     * it comes from, in the place of the chips and of the sentence that the rest can be set once
     * saved. A request chosen for an operation none was found for, or the other of two that fit, is
     * mapped once the save has stored the collection (one before it that fits is left unmapped); a
     * value nothing gives can be typed and goes with the save; a mapping that fails is said and the
     * environment stays saved.
     */
    @Test
    void whatTheFilesGiveIsShownAndARequestChosenForAnOperationIsMappedOnceSaved(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "what the files give", PRELUDE + """
                const {createEditor} = await import('./js/config-environments.js');
                answers['POST /api/config/environments/draft'] = {status: 200, body: {files: [], name: 'Vendor UAT', nameTaken: false,
                    signIn: {method: 'CLIENT_CREDENTIALS', tokenUrl: '{{token_endpoint}}', from: 'the collection\\'s token request "Auth / Token"',
                        fields: ['Token url']},
                    operations: [{operation: 'PAS_SUBMIT', short: 'PAS submit'}], warnings: [], epoch: 1, collectionId: 'vendor-requests-1a2b',
                    addresses: [
                        {operation: 'CRD_ORDER_SIGN', key: 'CRD_ORDER_SIGN', label: 'CRD order-sign', short: 'CRD',
                            lines: ['No request of the collection was found for it. Choose one below.'], candidates: []},
                        {operation: 'PAS_SUBMIT', key: 'PAS_SUBMIT', label: 'PAS submit', short: 'PAS submit', requestId: 'r-a',
                            requestName: 'PAS / Claim Submit', display: 'https://vendor.example.invalid/Claim/$submit',
                            from: `from the collection's request "PAS / Claim Submit"`, lines: ['2 requests fit this operation.'],
                            candidates: ['r-a', 'r-b']}],
                    requests: [
                        {id: 'r-a', name: 'PAS / Claim Submit', method: 'POST', display: 'https://vendor.example.invalid/Claim/$submit',
                            operation: 'PAS_SUBMIT', errorSample: false},
                        {id: 'r-c', name: 'CRD / Check', method: 'POST', display: 'https://vendor.example.invalid/cds-services/x',
                            operation: 'UNMAPPED', errorSample: false},
                        {id: 'r-b', name: 'PAS / Claim Submit v2', method: 'POST', display: 'https://vendor.example.invalid/v2/Claim/$submit',
                            operation: 'PAS_SUBMIT', errorSample: false}],
                    values: [
                        {name: 'fhirServer', label: 'FHIR server', display: 'https://ehr.example.invalid/fhir',
                            from: "from the export's variable fhirServer"},
                        {name: 'payerName', label: 'Payer name', line: 'Not in these files.'}]}};
                answers['POST /api/config/environments/settings'] = {status: 200, body: {environment: {id: 'vendor-uat', name: 'Vendor UAT'},
                    settings: Object.assign({}, full, {id: 'vendor-uat', name: 'Vendor UAT'}), warnings: [], epoch: 2}};
                let mappings = 0;
                // The third mapping is refused: its status is read after its body is made.
                answers['PUT /api/config/collections/vendor-requests-1a2b/mapping'] = {get status() { return mappings === 3 ? 404 : 200; }, body: () => {
                    mappings += 1;
                    return mappings === 3 ? {kind: 'NOT_FOUND', title: 'That request is no longer held.'} : {changed: true};
                }};
                answers['GET /api/config/environments/vendor-uat/settings'] = {status: 200, body: Object.assign({}, full, {id: 'vendor-uat'})};
                const editor = createEditor({environment: null, settings: null});
                const dialog = editor.dialog;
                const box = inputsOf(dialog).find(each => each.type === 'file');
                box.files = [new File(['{}'], 'env.json', {type: 'application/json'})];
                box.value = 'C:/fakepath/env.json';
                const second = inputsOf(dialog).filter(each => each.type === 'file')[1];
                second.files = [new File(['{}'], 'col.json', {type: 'application/json'})];
                second.value = 'C:/fakepath/col.json';
                await dialog.querySelectorAll('button').find(each => each.textContent === 'Fill the form from these files').click();
                await settle();
                const part = dialog.querySelector('.config-gives');
                say([part.querySelector('.form-section__title').textContent, dialog.querySelectorAll('.chip').length,
                    dialog.textContent.includes('can be set once the environment is saved')]);
                const rows = part.querySelectorAll('.config-gives__row');
                say(rows.map(row => row.querySelector('.config-in-force').textContent));
                say(rows.map(row => row.querySelectorAll('.field__hint').map(each => each.textContent)));
                const selects = part.querySelectorAll('select');
                say(selects.map(select => select.querySelectorAll('option').map(option => option.textContent)));
                selects[0].value = 'r-c';
                await selects[0].dispatchEvent({type: 'change'});
                selects[1].value = 'r-b';
                await selects[1].dispatchEvent({type: 'change'});
                say(rows.slice(0, 2).map(row => row.querySelector('.config-in-force').textContent));
                rows[3].querySelector('input').value = 'Placeholder Health Plan';
                dialog.querySelector('select').value = 'sandbox';
                await dialog.querySelectorAll('button').find(each => each.textContent === 'Save environment').click();
                await settle();
                const saved = JSON.parse(posted.find(each => each.path === '/api/config/environments/settings').body);
                say(saved.values);
                say(posted.filter(each => each.method === 'PUT').map(each => JSON.parse(each.body)));
                say(dialog.querySelector('.config-form__banner').textContent.includes(
                    'The environment is saved, but the request chosen for PAS submit was not recorded. That request is no longer held.'));
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                // The part stands where the chips and the sentence stood; neither is drawn.
                "[\"What these files give\",0,false]",
                "[\"not given\",\"https://vendor.example.invalid/Claim/$submit from the collection's request \\\"PAS / Claim Submit\\\"\","
                        + "\"https://ehr.example.invalid/fhir from the export's variable fhirServer\",\"not given\"]",
                "[[\"No request of the collection was found for it. Choose one below.\"],[\"2 requests fit this operation.\"],[],"
                        + "[\"Not in these files.\"]]",
                // The requests no operation uses, and for a row with two, the other that fits.
                "[[\"Choose a request\",\"CRD / Check\"],[\"Keep \\\"PAS / Claim Submit\\\"\",\"CRD / Check\","
                        + "\"PAS / Claim Submit v2 (also fits)\"]]",
                "[\"https://vendor.example.invalid/cds-services/x from the collection's request \\\"CRD / Check\\\", chosen here\","
                        + "\"https://vendor.example.invalid/v2/Claim/$submit from the collection's request \\\"PAS / Claim Submit v2\\\", "
                        + "chosen here\"]",
                "{\"payerName\":\"Placeholder Health Plan\"}",
                "[{\"requestId\":\"r-c\",\"operation\":\"CRD_ORDER_SIGN\"},{\"requestId\":\"r-a\",\"operation\":\"UNMAPPED\"},"
                        + "{\"requestId\":\"r-b\",\"operation\":\"PAS_SUBMIT\"}]",
                "true");
    }

    /**
     * D-308: a value a sample request states is shown with the request it comes from and the
     * box "Use the values these samples state", ticked; the save asks the server to store it, and
     * unticked it does not. On Edit a value stored from a sample is said as in force with where it
     * came from, its box closed, and an empty closed box keeps it; Change and a typed value replace it.
     */
    @Test
    void aValueASampleStatesIsShownWithItsRequestStoredByTheTickedBoxAndKeptOnEdit(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "values from samples", PRELUDE + """
                const {createEditor} = await import('./js/config-environments.js');
                answers['POST /api/config/environments/draft'] = {status: 200, body: {files: [], name: 'Vendor UAT', nameTaken: false,
                    warnings: [], epoch: 1, collectionId: 'vendor-requests-1a2b', addresses: [], requests: [],
                    values: [
                        {name: 'fhirServer', label: 'FHIR server', display: 'https://ehr-sandbox.example.org/fhir',
                            from: `from the collection's request "Coverage / Prior Auth Check"`, request: 'Coverage / Prior Auth Check',
                            notes: ['The sample also sends fhirAuthorization. PAVE does not send it: if the service refuses the check, that is the first thing to look at.']}]}};
                answers['POST /api/config/environments/settings'] = {status: 200, body: {environment: {id: 'vendor-uat', name: 'Vendor UAT'},
                    settings: Object.assign({}, full, {id: 'vendor-uat', name: 'Vendor UAT'}),
                    warnings: [`FHIR server set from the collection's request "Coverage / Prior Auth Check".`], epoch: 2}};
                const fillAndRead = async () => {
                    const editor = createEditor({environment: null, settings: null});
                    const dialog = editor.dialog;
                    const boxes = inputsOf(dialog).filter(each => each.type === 'file');
                    boxes[0].files = [new File(['{}'], 'env.json', {type: 'application/json'})];
                    boxes[0].value = 'C:/fakepath/env.json';
                    boxes[1].files = [new File(['{}'], 'col.json', {type: 'application/json'})];
                    boxes[1].value = 'C:/fakepath/col.json';
                    await dialog.querySelectorAll('button').find(each => each.textContent === 'Fill the form from these files').click();
                    await settle();
                    return {editor, dialog};
                };
                const first = await fillAndRead();
                const part = first.dialog.querySelector('.config-gives');
                const row = part.querySelectorAll('.config-gives__row')[0];
                say([row.querySelector('.config-in-force').textContent, row.querySelectorAll('.field__hint').map(each => each.textContent),
                    row.querySelector('input') === null]);
                const choice = part.querySelector('.config-gives__choice');
                const ticked = choice.querySelector('input');
                say([choice.querySelector('label').textContent, ticked.checked, choice.querySelector('.field__hint').textContent]);
                first.dialog.querySelector('select').value = 'sandbox';
                await first.dialog.querySelectorAll('button').find(each => each.textContent === 'Save environment').click();
                await settle();
                say(JSON.parse(posted.filter(each => each.path === '/api/config/environments/settings').pop().body).sampleValues);
                say(first.dialog.querySelector('.config-form__banner').textContent.includes(
                    `FHIR server set from the collection's request "Coverage / Prior Auth Check".`));

                const second = await fillAndRead();
                second.dialog.querySelector('.config-gives__choice input').checked = false;
                second.dialog.querySelector('select').value = 'sandbox';
                await second.dialog.querySelectorAll('button').find(each => each.textContent === 'Save environment').click();
                await settle();
                say(JSON.parse(posted.filter(each => each.path === '/api/config/environments/settings').pop().body).sampleValues);

                const stored = Object.assign({}, full, {values: [{name: 'fhirServer', label: 'FHIR server', why: 'Sent in CRD.',
                    inForce: {display: 'https://ehr-sandbox.example.org/fhir', source: 'SAMPLE',
                        sourceLabel: `from the collection's request "Coverage / Prior Auth Check"`}}]});
                const edit = createEditor({environment: {id: full.id, name: full.name, kind: 'sandbox'}, settings: stored});
                const field = edit.dialog.querySelectorAll('.field').find(each => each.querySelector('.field__label')
                    && each.querySelector('.field__label').textContent === 'FHIR server');
                const input = field.querySelector('input');
                say([field.querySelector('.config-in-force').textContent, input.hidden,
                    field.querySelectorAll('button').filter(each => !each.hidden).map(each => each.textContent),
                    Object.prototype.hasOwnProperty.call(edit.input().values, 'fhirServer')]);
                field.querySelectorAll('button').find(each => each.textContent === 'Change').click();
                say(field.querySelectorAll('button').filter(each => !each.hidden).map(each => each.textContent));
                input.value = 'https://typed.example.org/fhir';
                say(edit.input().values.fhirServer);
                field.querySelectorAll('button').find(each => each.textContent === "Use the sample's again").click();
                say(Object.prototype.hasOwnProperty.call(edit.input().values, 'fhirServer'));
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"https://ehr-sandbox.example.org/fhir from the collection's request \\\"Coverage / Prior Auth Check\\\"\","
                        + "[\"The sample also sends fhirAuthorization. PAVE does not send it: if the service refuses the check, that is the "
                        + "first thing to look at.\"],true]",
                "[\"Use the values these samples state\",true,\"Each is stored with the save where nothing else gives that value. A value "
                        + "typed, or one the export gives, stays as it is.\"]",
                "true",
                "true",
                "false",
                "[\"https://ehr-sandbox.example.org/fhir from the collection's request \\\"Coverage / Prior Auth Check\\\"\",true,"
                        + "[\"Change\"],false]",
                "[\"Use the sample's again\"]",
                "\"https://typed.example.org/fhir\"",
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
        // I-3 (live-config-8): the panel also names the field, Kind, with its sentence.
        assertThat(run.lines()).containsExactly("[\"Declared as sandbox.\"]", "[true,true,true]");
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

    /**
     * D-305: a field several variables could fill is left empty with one button per variable under
     * it; a press fills the reference, a secret as the reference the draft would have filled; the
     * line says which fields were filled and from where, and every line of the draft is shown.
     */
    @Test
    void theVariablesThatCouldFillAFieldAreButtonsAndAPressFillsTheReference(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the candidate buttons", PRELUDE + """
                const {createEditor} = await import('./js/config-environments.js');
                answers['POST /api/config/environments/draft'] = {status: 200, body: {files: [{slot: 'environmentExport', fileName: 'uat.json',
                    read: true, line: 'Read: 5 variables, 2 secrets.'}], name: 'Placeholder UAT',
                    signIn: {method: 'CLIENT_CREDENTIALS', tokenUrl: '{{tokenEndpoint}}', from: "the environment's variable names",
                        fields: ['Token url'], candidates: [{field: 'clientId', references: ['{{uat_client_id}}', '{{prod_client_id}}']},
                        {field: 'clientSecret', references: ['{{uat_client_secret}}', '{{prod_client_secret}}']}]},
                    variables: [], shapes: [], operations: [], epoch: 1,
                    warnings: ['Several variables could be the client id: {{uat_client_id}}, {{prod_client_id}}. Choose one.',
                        "{{tokenEndpoint}} is empty in this export. Postman exports a variable's initial value only: type the value here, or set the initial value in Postman and export again."]}};
                answers['POST /api/config/environments/settings'] = {status: 200, body: {environment: {id: full.id, name: full.name}, settings: full,
                    warnings: [], epoch: 2}};
                const editor = createEditor({environment: {id: full.id, name: full.name, kind: 'sandbox'}, settings: full});
                const dialog = editor.dialog;
                const box = inputsOf(dialog).find(each => each.type === 'file');
                box.files = [new File(['{"name": "Placeholder UAT", "values": []}'], 'uat.json', {type: 'application/json'})];
                box.value = 'C:/fakepath/uat.json';
                await dialog.querySelectorAll('button').find(each => each.textContent === 'Fill the form from these files').click();
                await settle();
                const lines = dialog.querySelectorAll('p').map(each => each.textContent)
                    .filter(each => each.startsWith('Sign-in filled') || each.startsWith('Several') || each.includes('is empty in this export'));
                say(lines);
                const field = label => dialog.querySelectorAll('.field').find(each => (each.querySelector('.field__label') || {}).textContent === label);
                const buttonsUnder = label => field(label).querySelectorAll('button').map(each => each.textContent);
                say([buttonsUnder('Token url'), buttonsUnder('Client id'), buttonsUnder('Client secret'), buttonsUnder('Scope')]);
                const textInputs = () => inputsOf(dialog).filter(each => each.type === 'text' && each.classList.contains('mono')).map(each => each.value);
                say(textInputs().slice(0, 2));
                await field('Client id').querySelectorAll('button').find(each => each.textContent === '{{prod_client_id}}').click();
                await field('Client id').querySelectorAll('button').find(each => each.textContent === '{{uat_client_id}}').click();
                await field('Client secret').querySelectorAll('button').find(each => each.textContent === '{{uat_client_secret}}').click();
                await settle();
                say([textInputs().slice(0, 2), dialog.textContent.includes('Filled from the export as {{uat_client_secret}}.'),
                    inputsOf(dialog).filter(each => each.type === 'password').map(each => each.value)]);
                await dialog.querySelectorAll('button').find(each => each.textContent === 'Save environment').click();
                await settle();
                const saved = JSON.parse(posted.find(each => each.path === '/api/config/environments/settings').body);
                say([saved.signIn.tokenUrl, saved.signIn.clientId, saved.signIn.clientSecret]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"Several variables could be the client id: {{uat_client_id}}, {{prod_client_id}}. Choose one.\","
                        + "\"{{tokenEndpoint}} is empty in this export. Postman exports a variable's initial value only: type the value here, or "
                        + "set the initial value in Postman and export again.\","
                        + "\"Sign-in filled from the environment's variable names: Token url.\"]",
                // Buttons only under the fields the draft could not fill, one per variable, in the order of the export.
                // D-307: the stored secret is said as stored and its box opens on Change, so the field carries that button too.
                "[[],[\"{{uat_client_id}}\",\"{{prod_client_id}}\"],[\"Change\",\"{{uat_client_secret}}\",\"{{prod_client_secret}}\"],[]]",
                // No candidate is filled until a button is pressed: the field keeps what the stored environment holds.
                "[\"{{tokenEndpoint}}\",\"{{clientId}}\"]",
                // The last press wins; a secret is held as the reference and its password field stays empty.
                "[[\"{{tokenEndpoint}}\",\"{{uat_client_id}}\"],true,[\"\"]]",
                "[\"{{tokenEndpoint}}\",\"{{uat_client_id}}\",\"{{uat_client_secret}}\"]");
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
                // The saved lists read the collection after Fill too (cfg-found.js); the save is found by its address.
                const save = posted.find(each => each.path === '/api/config/environments/settings');
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
                say(root.querySelectorAll('.config-page__body').map(each => each.textContent));
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
                "[\"config-lookups.js\",\"cfg-lists.js\"]",
                "[\"In force now\"]",
                "[\"mount config-environments.js environments\",\"update config-environments.js environments/pave-sample-sandbox/values\","
                        + "\"unmount config-environments.js\","
                        // Members and Providers: the lookup form above the saved list, each mounted and unmounted on its own.
                        + "\"mount config-lookups.js members\",\"mount cfg-lists.js members\",\"unmount config-lookups.js\",\"unmount cfg-lists.js\","
                        + "\"mount config-lookups.js providers\",\"mount cfg-lists.js providers\",\"unmount config-lookups.js\",\"unmount cfg-lists.js\","
                        + "\"mount cfg-inforce.js advanced/in-force\",\"unmount cfg-inforce.js\","
                        + "\"mount config-environments.js environments\",\"unmount config-environments.js\"]",
                "[\"config-environments.js\",\"config-lookups.js\",\"cfg-lists.js\",\"config-lookups.js\",\"cfg-lists.js\",\"cfg-inforce.js\","
                        + "\"config-environments.js\"]",
                "[\"Environments\",\"Collections\",\"Members\",\"Providers\",\"Procedure codes\",\"Access token\",\"Production writes\",\"Advanced\"]",
                "[\"in-force\",\"trail\",\"raw\",\"try\",\"checks\",\"discovery\",\"compare\"]",
                "[[\"advanced\",\"in-force\"],[\"advanced\",\"trail\"],[\"environments\"]]");
    }

    /** A member lookup's view in the shape of plan 5.5, with every secret stored. */
    private static final String LOOKUP_VIEW = """
            const lookupView = (extra) => Object.assign({kind: 'member', stated: true, enabled: true, method: 'POST',
                address: 'https://members.example.invalid/member/search',
                bodyTemplate: '{"memberIds": ["{memberId}"], "from": "{dateOfService}"}', dateFormat: 'MM/dd/yyyy', idForm: 'AS_TYPED',
                signIn: {method: 'API_KEY', headerName: 'x-api-key', apiKey: {set: true, reference: '{{env:PAVE_MEMBER_KEY}}'},
                    bearerToken: {set: true}, tokenUrl: null, clientId: null, clientSecret: {set: true}, scope: null, clientAuthentication: 'BASIC'},
                headers: [{name: 'x-trace', value: {set: true}}], listPath: 'members', fieldPaths: {dateOfBirth: 'dob'},
                connectMs: 3000, readMs: 5000, inForce: {from: 'this screen', words: 'POST, signs in with an API key header'},
                fields: [{name: 'firstName', label: 'First name'}, {name: 'dateOfBirth', label: 'Date of birth'}, {name: 'planName', label: 'Plan'}],
                defaults: {connectMs: 3000, readMs: 5000}}, extra || {});
            const {createLookupForm, KINDS} = await import('./js/config-lookups.js');
            const {el} = await import('./js/dom.js');
            const labelsShown = node => node.querySelectorAll('.field').filter(each => {
                for (let at = each; at; at = at.parentNode) { if (at.hidden) { return false; } }
                return true;
            }).map(each => each.querySelector('.field__label').textContent);
            const selectWith = (node, value) => node.querySelectorAll('select').find(each => each.querySelectorAll('option').some(o => o.value === value));
            """;

    @Test
    void getHidesTheBodyTemplateAndPostNamesTheMemberIdsPlace(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the lookup form's method", PRELUDE + LOOKUP_VIEW + """
                const form = createLookupForm(KINDS.members, lookupView({method: 'GET', address: 'https://members.example.invalid/m/{id}'}), null);
                const node = form.node;
                document.body.append(node);
                const hint = () => node.querySelector('.field__hint').textContent;
                say([labelsShown(node).includes('Body template'), labelsShown(node).includes('Date format'), hint()]);
                say(Object.keys(form.input()).filter(key => ['bodyTemplate', 'dateFormat'].includes(key)));
                const method = selectWith(node, 'POST');
                method.value = 'POST';
                await method.dispatchEvent({type: 'change'});
                say([labelsShown(node).includes('Body template'), labelsShown(node).includes('Date format'),
                    labelsShown(node).includes('Date of service to try'), hint()]);
                const body = form.input();
                say([body.method, body.bodyTemplate, body.dateFormat]);
                // A template without the date leaves the date format out, on screen and in what is sent.
                const template = node.querySelector('textarea');
                template.value = '{"memberId": "{memberId}"}';
                await template.dispatchEvent({type: 'input'});
                say([labelsShown(node).includes('Date format'), 'dateFormat' in form.input()]);
                const provider = createLookupForm(KINDS.providers, lookupView({kind: 'provider', method: 'POST', bodyTemplate: '{"npi": "{npi}"}'}), null);
                say([provider.node.querySelector('.field__hint').textContent, labelsShown(provider.node).includes('Member ID form'),
                    'idForm' in provider.input()]);
                // The field paths are the server's own fields, by its names and labels.
                say([Object.keys(form.input().fieldPaths), labelsShown(node).includes('Plan'), form.input().fieldPaths.dateOfBirth]);
                // A blank timeout says what applies instead, and is sent as not stated.
                const blank = createLookupForm(KINDS.members, lookupView({connectMs: null, readMs: null}), null);
                say([blank.node.querySelectorAll('input').filter(each => each.type === 'number').map(each => each.placeholder),
                    blank.input().connectMs, blank.input().readMs]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[false,false,\"Put {id} where the member ID goes, in the path or the query.\"]",
                "[]",
                "[true,true,true,\"The member ID goes in the body, never in the address.\"]",
                "[\"POST\",\"{\\\"memberIds\\\": [\\\"{memberId}\\\"], \\\"from\\\": \\\"{dateOfService}\\\"}\",\"MM/dd/yyyy\"]",
                "[false,false]",
                "[\"The NPI goes in the body, never in the address.\",false,false]",
                "[[\"firstName\",\"dateOfBirth\",\"planName\"],true,\"dob\"]",
                "[[\"3000, from application.yml\",\"5000, from application.yml\"],null,null]");
    }

    @Test
    void eachSignInKindShowsItsOwnFields(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the lookup form's sign-in", PRELUDE + LOOKUP_VIEW + """
                const form = createLookupForm(KINDS.members, lookupView(), null);
                const node = form.node;
                document.body.append(node);
                const signIn = selectWith(node, 'ENVIRONMENT');
                const own = ['Header name', 'API key', 'Bearer token', 'Token url', 'Client id', 'Client secret', 'Scope', 'Client authentication'];
                for (const method of ['NONE', 'API_KEY', 'BEARER_TOKEN', 'CLIENT_CREDENTIALS', 'ENVIRONMENT']) {
                    signIn.value = method;
                    await signIn.dispatchEvent({type: 'change'});
                    say([method, labelsShown(node).filter(label => own.includes(label)), Object.keys(form.input().signIn)]);
                }
                say(signIn.querySelectorAll('option').map(each => each.textContent));
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"NONE\",[],[\"method\"]]",
                "[\"API_KEY\",[\"Header name\",\"API key\"],[\"method\",\"headerName\",\"apiKey\",\"removeApiKey\"]]",
                "[\"BEARER_TOKEN\",[\"Bearer token\"],[\"method\",\"bearerToken\",\"removeBearerToken\"]]",
                "[\"CLIENT_CREDENTIALS\",[\"Token url\",\"Client id\",\"Client secret\",\"Scope\",\"Client authentication\"],"
                        + "[\"method\",\"tokenUrl\",\"clientId\",\"clientSecret\",\"removeClientSecret\",\"scope\",\"clientAuthentication\"]]",
                // The active environment's sign-in has no fields of its own: it is the environment's.
                "[\"ENVIRONMENT\",[],[\"method\"]]",
                "[\"None\",\"API key header\",\"Bearer token\",\"Client credentials\",\"The active environment's sign-in\"]");
    }

    @Test
    void aLookupSecretIsNeverGivenAValueAndLeavesThePageOnceSent(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the lookup form's secrets", PRELUDE + LOOKUP_VIEW + """
                const {mount} = await import('./js/config-lookups.js');
                answers['GET /api/config/lookups'] = {status: 200, body: {member: lookupView({listPath: '', fieldPaths: {}}), provider: lookupView({kind: 'provider'})}};
                answers['PUT /api/config/lookups/member'] = {status: 422, body: {kind: 'VALIDATION', title: 'Some fields need attention.',
                    fieldErrors: [{field: 'fieldPaths.dateOfBirth', message: 'A path is names and [n] joined by dots.'}]}};
                const root = el('section');
                document.body.append(root);
                const page = mount(root, ['members']);
                await settle();
                const passwords = () => root.querySelectorAll('input').filter(each => each.type === 'password');
                const signIn = selectWith(root, 'ENVIRONMENT');
                for (const method of ['API_KEY', 'BEARER_TOKEN', 'CLIENT_CREDENTIALS']) {
                    signIn.value = method;
                    await signIn.dispatchEvent({type: 'change'});
                    say([method, passwords().map(each => [String(each.value ?? ''), each.autocomplete, each.placeholder])]);
                }
                say([root.textContent.includes('Remove the stored secret'), root.querySelector('details').open]);
                signIn.value = 'API_KEY';
                await signIn.dispatchEvent({type: 'change'});
                say(root.textContent.includes('Stored: {{env:PAVE_MEMBER_KEY}}'));
                const [apiKey, header] = passwords();
                apiKey.value = 'typed-placeholder-key';
                header.value = 'typed-placeholder-trace';
                await root.querySelectorAll('button').find(each => each.textContent === 'Save lookup').click();
                await settle();
                const sent = JSON.parse(posted.find(each => each.path === '/api/config/lookups/member').body);
                say([sent.signIn.apiKey, sent.signIn.removeApiKey, sent.headers]);
                // Refused: the field that failed opens and shows why; nothing typed as a secret stays on the page.
                say([passwords().map(each => String(each.value ?? '')), root.querySelector('details').open,
                    root.querySelector('.field--error .field__error').textContent]);
                // A blank secret is sent blank: the server keeps what it stored.
                await root.querySelectorAll('button').find(each => each.textContent === 'Save lookup').click();
                await settle();
                const again = JSON.parse(posted.filter(each => each.path === '/api/config/lookups/member')[1].body);
                say([again.signIn.apiKey, again.headers.map(each => each.value)]);
                page.unmount();
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"API_KEY\",[[\"\",\"new-password\",\"blank keeps stored\"],[\"\",\"new-password\",\"blank keeps stored\"]]]",
                "[\"BEARER_TOKEN\",[[\"\",\"new-password\",\"blank keeps stored\"],[\"\",\"new-password\",\"blank keeps stored\"]]]",
                "[\"CLIENT_CREDENTIALS\",[[\"\",\"new-password\",\"blank keeps stored\"],[\"\",\"new-password\",\"blank keeps stored\"]]]",
                "[true,false]",
                "true",
                "[\"typed-placeholder-key\",false,[{\"name\":\"x-trace\",\"value\":\"typed-placeholder-trace\",\"remove\":false}]]",
                "[[\"\",\"\"],true,\"A path is names and [n] joined by dots.\"]",
                "[\"\",[\"\"]]");
    }

    @Test
    void theLookupPageDrawsNothingWithoutTheSettingAndTestsTheSavedLookupInOneLine(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the lookup page", PRELUDE + LOOKUP_VIEW + """
                const {mount, testLine} = await import('./js/config-lookups.js');
                const root = el('section');
                document.body.append(root);
                // No lookup settings on this server: no form, no copy of its words.
                let page = mount(root, ['members']);
                await settle();
                say(root.textContent);
                page.unmount();
                globalThis.standInMode = {mode: 'mock', epoch: 1, writeAccess: {}};
                answers['GET /api/config/lookups'] = {status: 200, body: {member: lookupView({stated: false, inForce: {from: 'not set', words: 'Off'}}),
                    provider: lookupView({kind: 'provider', method: 'GET', address: 'https://providers.example.invalid/p/{id}'})}};
                answers['GET /api/providers'] = {status: 200, body: {status: 'NOT_FOUND', statusLabel: 'Not found',
                    message: 'The service does not know NPI 1234567893. Check the test data.', elapsedMillis: 41,
                    wire: {response: {status: 404}}}};
                const clean = el('section');
                document.body.append(clean);
                page = mount(clean, ['providers']);
                await settle();
                say([clean.querySelector('h2').textContent, clean.textContent.includes("On mock, lookups answer from PAVE's test fixtures."),
                    clean.querySelectorAll('button').some(each => each.textContent === 'Remove the saved lookup')]);
                clean.querySelectorAll('input').find(each => each.placeholder === '1234567893').value = '1234567893';
                await clean.querySelectorAll('button').find(each => each.textContent === 'Test the lookup').click();
                await settle();
                say(clean.querySelector('.config-try .config-status').textContent);
                page.unmount();
                say(testLine({status: 'NOT_ANSWERING', statusLabel: 'Not answering', message: 'No answer within 5000 ms.'}, 5003));
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "\"\"",
                "[\"Provider lookup\",true,true]",
                "\"Not found, HTTP 404, 41 ms, The service does not know NPI 1234567893.\"",
                "\"Not answering, 5003 ms, No answer within 5000 ms.\"");
    }
}
