package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * Configuration > Diagnosis codes, the fourth saved list (D-322), run with Node against answers of
 * the shapes TestListController sends. What each test holds:
 * <ul>
 *   <li>the page is drawn from the code of the procedure codes page: its table, add, edit, remove
 *       and the CSV template, at its own address</li>
 *   <li>switched off, a file that could not be read, and an answer that fails are each said, and
 *       the page still opens</li>
 *   <li>nothing goes to the browser's storage</li>
 * </ul>
 */
class DiagnosisListFrontendTest {

    static final String PRELUDE = """
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
            const stored = [];
            globalThis.localStorage = {setItem: (key) => stored.push(key), getItem: () => null, removeItem() {}};
            globalThis.sessionStorage = globalThis.localStorage;
            const say = value => console.log(JSON.stringify(value));
            const posted = [];
            const answers = {};
            globalThis.fetch = async (address, init) => {
                const method = init && init.method ? init.method : 'GET';
                const path = String(address).split('?')[0];
                if (method !== 'GET') {
                    posted.push({method, path, body: init.body ? JSON.parse(init.body) : null});
                }
                const found = answers[method + ' ' + path] || {status: 404, body: {kind: 'NOT_FOUND', title: 'Not here.'}};
                const text = JSON.stringify(found.body);
                return {ok: found.status >= 200 && found.status < 300, status: found.status, text: async () => text};
            };
            const tick = () => new Promise(done => setTimeout(done, 0));
            const settle = async () => { for (let i = 0; i < 20; i++) { await tick(); } };
            const text = node => node.textContent;
            const ICD = 'http://hl7.org/fhir/sid/icd-10-cm';
            """;

    @Test
    void theDiagnosisCodesPageIsTheFourthListBuiltAsTheThird(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the diagnosis codes list", PRELUDE + """
                const {PAGES, normal} = await import('./js/configuration.js');
                say(['page entry', PAGES.find(each => each.id === 'diagnosis-codes'), normal(['diagnosis-codes'])]);
                answers['GET /api/lists/diagnoses'] = {status: 200, body: {enabled: true, count: 1, diagnoses: [
                    {id: 'd-1', label: 'From Order sign', code: 'G47.33', system: ICD, description: 'Obstructive sleep apnea'}]}};
                const {mount} = await import('./js/cfg-lists.js');
                const root = document.createElement('div');
                document.body.append(root);
                const page = mount(root, ['diagnosis-codes']);
                await settle();
                say(['page', text(root.querySelector('h2')), text(root).includes('Diagnosis codes the sandbox knows, picked in the Service card under Saved '
                    + 'diagnosis codes. Codes only: an entry never says whose diagnosis it is.'), root.querySelectorAll('th').map(text),
                    root.querySelectorAll('tbody tr').map(text), root.querySelector('a').href]);

                // Add: a blank field is not sent; a refusal at a field is shown at that field.
                await root.querySelectorAll('button').find(each => text(each) === 'Add diagnosis code').click();
                let dialog = document.body.querySelector('dialog');
                let inputs = Object.fromEntries(dialog.querySelectorAll('input').map(each => [each.name, each]));
                say(['add', text(dialog.querySelector('h2')), Object.keys(inputs), inputs.code.placeholder]);
                inputs.code.value = ' E11.9 ';
                inputs.system.value = ICD;
                answers['POST /api/lists/diagnoses'] = {status: 422, body: {kind: 'VALIDATION', title: 'The diagnosis code has 1 problem. Nothing was saved.',
                    fieldErrors: [{field: 'code', message: 'A code is at most 40 characters, with no space in it.'}]}};
                await dialog.querySelectorAll('button').find(each => text(each) === 'Save diagnosis code').click();
                await settle();
                say(['refused', dialog.querySelectorAll('.field--error').map(text)]);
                answers['POST /api/lists/diagnoses'] = {status: 200, body: {id: 'd-2', code: 'E11.9', system: ICD}};
                await dialog.querySelectorAll('button').find(each => text(each) === 'Save diagnosis code').click();
                await settle();
                say(['added', posted.filter(each => each.path === '/api/lists/diagnoses').map(each => each.body),
                    text(root).includes('Added the diagnosis code "E11.9".')]);

                // Edit and remove, by the entry's id.
                await root.querySelectorAll('button').find(each => each.getAttribute('aria-label') === 'Edit From Order sign').click();
                dialog = document.body.querySelector('dialog');
                inputs = Object.fromEntries(dialog.querySelectorAll('input').map(each => [each.name, each]));
                say(['edit', text(dialog.querySelector('h2')), inputs.code.value, inputs.system.value, inputs.description.value]);
                inputs.description.value = 'Sleep apnea';
                answers['PUT /api/lists/diagnoses/d-1'] = {status: 200, body: {id: 'd-1', code: 'G47.33'}};
                await dialog.querySelectorAll('button').find(each => text(each) === 'Save diagnosis code').click();
                await settle();
                say(['put', posted.filter(each => each.method === 'PUT').map(each => [each.path, each.body])]);
                await root.querySelectorAll('button').find(each => each.getAttribute('aria-label') === 'Edit From Order sign').click();
                await document.body.querySelector('dialog').querySelectorAll('button').find(each => text(each) === 'Remove diagnosis code').click();
                dialog = document.body.querySelector('dialog');
                answers['DELETE /api/lists/diagnoses/d-1'] = {status: 200, body: {removed: true}};
                say(['asked', text(dialog.querySelector('h2'))]);
                await dialog.querySelectorAll('button').find(each => text(each) === 'Remove diagnosis code').click();
                await settle();
                say(['removed', posted.filter(each => each.method === 'DELETE').map(each => each.path),
                    text(root).includes('Removed the diagnosis code "From Order sign".')]);

                // Moving to the procedure codes and back draws each list at its own address.
                answers['GET /api/lists/codes'] = {status: 200, body: {enabled: true, count: 0, codes: []}};
                page.update(['procedure-codes']);
                await settle();
                const codesTitle = text(root.querySelector('h2'));
                page.update(['diagnosis-codes']);
                await settle();
                say(['moved', codesTitle, text(root.querySelector('h2'))]);
                page.unmount();
                say(['storage', stored]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"page entry\",{\"id\":\"diagnosis-codes\",\"label\":\"Diagnosis codes\",\"module\":\"cfg-lists.js\"},[\"diagnosis-codes\"]]",
                "[\"page\",\"Test diagnosis codes\",true,[\"Label\",\"Code\",\"Code system\",\"Description\",\"Actions\"],"
                        + "[\"From Order signG47.33http://hl7.org/fhir/sid/icd-10-cmObstructive sleep apneaEdit\"],"
                        + "\"/api/lists/diagnoses/template.csv\"]",
                "[\"add\",\"Add diagnosis code\",[\"label\",\"code\",\"system\",\"description\"],\"G47.33\"]",
                "[\"refused\",[\"CodeA code is at most 40 characters, with no space in it.\"]]",
                "[\"added\",[{\"code\":\"E11.9\",\"system\":\"http://hl7.org/fhir/sid/icd-10-cm\"},"
                        + "{\"code\":\"E11.9\",\"system\":\"http://hl7.org/fhir/sid/icd-10-cm\"}],true]",
                "[\"edit\",\"Edit diagnosis code\",\"G47.33\",\"http://hl7.org/fhir/sid/icd-10-cm\",\"Obstructive sleep apnea\"]",
                "[\"put\",[[\"/api/lists/diagnoses/d-1\",{\"label\":\"From Order sign\",\"code\":\"G47.33\","
                        + "\"system\":\"http://hl7.org/fhir/sid/icd-10-cm\",\"description\":\"Sleep apnea\"}]]]",
                "[\"asked\",\"Remove the diagnosis code \\\"From Order sign\\\"?\"]",
                "[\"removed\",[\"/api/lists/diagnoses/d-1\"],true]",
                "[\"moved\",\"Test procedure codes\",\"Test diagnosis codes\"]",
                "[\"storage\",[]]");
    }

    @Test
    void switchedOffUnreadableOrFailingThePageStillOpensAndSaysWhy(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the diagnosis codes list in its bad states", PRELUDE + """
                const {mount} = await import('./js/cfg-lists.js');
                const states = [
                    ['off', {status: 200, body: {enabled: false, count: 0, diagnoses: []}}],
                    ['unreadable', {status: 200, body: {enabled: true, count: 0, diagnoses: [],
                        problem: 'The file /store/test-lists/diagnoses.json could not be read, so this list is shown empty. PAVE does not write over it: move the file aside, then add the entries again.'}}],
                    ['no list in the answer', {status: 200, body: {enabled: true}}],
                    ['failed', {status: 500, body: {kind: 'INTERNAL', title: 'This tool hit an internal error.', reference: 'ref-1'}}]
                ];
                for (const [name, answer] of states) {
                    answers['GET /api/lists/diagnoses'] = answer;
                    const root = document.createElement('div');
                    document.body.append(root);
                    const page = mount(root, ['diagnosis-codes']);
                    await settle();
                    say([name, text(root.querySelector('h2')), root.querySelector('.inline-controls').hidden, root.querySelectorAll('tbody tr').length,
                        text(root).includes('switched off'), text(root).includes('could not be read'), text(root).includes('No diagnosis code is saved yet.'),
                        text(root).includes('internal error')]);
                    page.unmount();
                }
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"off\",\"Test diagnosis codes\",true,0,true,false,false,false]",
                "[\"unreadable\",\"Test diagnosis codes\",false,0,false,true,true,false]",
                "[\"no list in the answer\",\"Test diagnosis codes\",false,0,false,false,true,false]",
                "[\"failed\",\"Test diagnosis codes\",true,0,false,false,false,true]");
    }
}
