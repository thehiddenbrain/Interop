package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * "Found in the collection" (cfg-found.js) and where it is used, run with Node against answers of
 * the shapes FoundListsController sends. What each test holds:
 * <ul>
 *   <li>the part lists the three lists with their counts, one line an entry with the request it was
 *       found in, says what was not used, and offers the ticked box; nothing found offers no box</li>
 *   <li>the environment form reads the collection after Fill and, once the environment is saved
 *       with the box ticked, posts the same files to be added and shows the result line with links
 *       to the lists; unticked, nothing is added; a newly chosen file clears the part</li>
 *   <li>the Collections page offers "Add to the saved lists" after an upload, read with the active
 *       environment</li>
 *   <li>a saved member that holds only the vendor's patient id is named by it in the Member card's
 *       picker, and a pick fills that id and leaves the Member ID for the tester</li>
 *   <li>nothing goes to the browser's storage</li>
 * </ul>
 */
class FoundFrontendTest {

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
            const FOUND_ANSWER = {enabled: true,
                members: [{memberId: 'S123456789', vendorPatientId: 'pat-001', firstName: 'Sample', lastName: 'Placeholder-One',
                    dateOfBirth: '1980-01-31', from: 'Order sign'}, {vendorPatientId: 'pat-002', from: 'Read patient'}],
                providers: [{npi: '1234567893', vendorPractitionerId: 'prac-001', firstName: 'Sample', lastName: 'Placeholder-Provider', from: 'Order sign'}],
                codes: [{code: 'E0601', system: 'https://bluebutton.cms.gov/resources/codesystem/hcpcs', systemShort: 'HCPCS',
                    description: 'Continuous positive airway pressure device', from: 'Order sign'}],
                notUsed: ['"Claim submit": the NPI of a Practitioner was left out, because its last digit does not match the other nine.']};
            const ADDED_ANSWER = {enabled: true, line: 'Added to the saved lists: 2 members, 1 provider. Already there: 1 member.',
                members: {added: 2, already: 1, full: 0, refused: 0}, providers: {added: 1, already: 0, full: 0, refused: 0},
                codes: {added: 0, already: 0, full: 0, refused: 0}, notUsed: []};
            """;

    @Test
    void thePartListsWhatWasFoundOfferstheTickedBoxAndAddsTheSameFiles(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the found part", PRELUDE + """
                const {createFoundPart, BOX_WORDS} = await import('./js/cfg-found.js');
                const part = createFoundPart({mode: 'box'});
                document.body.append(part.node);
                say(['before', part.node.hidden, part.wanted()]);
                answers['POST /api/lists/found'] = {status: 200, body: FOUND_ANSWER};
                const request = {collectionExport: {fileName: 'vendor.json', text: '{}'}, environmentExport: {fileName: 'env.json', text: '{}'}};
                await part.read(request);
                say(['shown', part.node.hidden, text(part.node.querySelector('h3')),
                    part.node.querySelectorAll('.notes__title').map(text), part.node.querySelectorAll('li').map(text)]);
                say(['box', text(part.node.querySelector('label')) === BOX_WORDS, part.box.checked, part.wanted()]);
                part.box.checked = false;
                say(['unticked', part.wanted()]);
                part.box.checked = true;
                answers['POST /api/lists/found/add'] = {status: 200, body: ADDED_ANSWER};
                const result = await part.add();
                say(['added', posted.map(each => each.path), JSON.stringify(posted[1].body) === JSON.stringify(request), text(result),
                    result.querySelectorAll('a').map(each => each.href)]);
                // Nothing found: one line, no box.
                answers['POST /api/lists/found'] = {status: 200, body: {enabled: true, line: 'Nothing to add to the saved lists was found in the collection.',
                    members: [], providers: [], codes: [], notUsed: ['"Order sign": {{patientId}} has no value in the environment or the collection, so nothing was read from its body.']}};
                await part.read(request);
                say(['nothing', part.node.querySelectorAll('input').length, part.node.querySelectorAll('p').map(text), part.wanted()]);
                // A collection the server could not read: its line with the reference and the cause.
                answers['POST /api/lists/found'] = {status: 200, body: {enabled: true, line: 'The collection was not read: PAVE could not read this file. Type the values instead.',
                    reference: 'ref-1', cause: 'java.lang.OutOfMemoryError', members: [], providers: [], codes: [], notUsed: []}};
                await part.read(request);
                say(['failed', text(part.node).includes('ref-1'), text(part.node).includes('java.lang.OutOfMemoryError'), part.wanted()]);
                part.clear();
                say(['cleared', part.node.hidden, part.node.childNodes.length, part.wanted(), stored]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"before\",true,false]",
                "[\"shown\",false,\"Found in the collection\",[\"Members (2)\",\"Providers (1)\",\"Procedure codes (1)\",\"Diagnosis codes (0)\",\"Seen and not used\"],"
                        + "[\"Member ID S123456789, vendor patient id pat-001, Sample Placeholder-One, born 1980-01-31 From \\\"Order sign\\\".\","
                        + "\"vendor patient id pat-002 From \\\"Read patient\\\".\","
                        + "\"NPI 1234567893, vendor practitioner id prac-001, Sample Placeholder-Provider From \\\"Order sign\\\".\","
                        + "\"E0601 HCPCS, Continuous positive airway pressure device From \\\"Order sign\\\".\","
                        + "\"\\\"Claim submit\\\": the NPI of a Practitioner was left out, because its last digit does not match the other nine.\"]]",
                "[\"box\",true,true,true]",
                "[\"unticked\",false]",
                "[\"added\",[\"/api/lists/found\",\"/api/lists/found/add\"],true,\"Added to the saved lists: 2 members, 1 provider. Already there: "
                        + "1 member.Open the saved lists: Members, Providers, Procedure codes and Diagnosis codes.\",[\"#configuration/members\",\"#configuration/providers\",\"#configuration/procedure-codes\",\"#configuration/diagnosis-codes\"]]",
                "[\"nothing\",0,[\"Nothing to add to the saved lists was found in the collection.\"],false]",
                "[\"failed\",true,true,false]",
                "[\"cleared\",true,0,false,[]]");
    }

    @Test
    void theEnvironmentFormReadsAfterFillAndAddsOnSaveOnlyWithTheBoxTicked(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the environment form with the found part", PRELUDE + """
                const {createEditor} = await import('./js/config-environments.js');
                answers['POST /api/config/environments/draft'] = {status: 200, body: {files: [{slot: 'collectionExport', fileName: 'vendor.json',
                    read: true, line: 'Read: 3 requests.'}], variables: [], shapes: [], operations: [], warnings: [], epoch: 1}};
                answers['POST /api/lists/found'] = {status: 200, body: FOUND_ANSWER};
                answers['POST /api/lists/found/add'] = {status: 200, body: ADDED_ANSWER};
                const settings = {id: 'vendor-uat', name: 'Vendor UAT', kind: 'uat', profile: {mode: 'live', words: 'Live'}, signIn: {method: 'NONE'}, epoch: 2};
                answers['POST /api/config/environments/settings'] = {status: 200, body: {environment: {id: 'vendor-uat', name: 'Vendor UAT'},
                    settings, warnings: [], epoch: 2}};
                const run = async (tick) => {
                    posted.length = 0;
                    const editor = createEditor({environment: null, settings: null});
                    const dialog = editor.dialog;
                    const [, collectionBox] = dialog.querySelectorAll('input').filter(each => each.type === 'file');
                    collectionBox.files = [new File(['{"info": {}}'], 'vendor.json', {type: 'application/json'})];
                    collectionBox.value = 'C:/fakepath/vendor.json';
                    await dialog.querySelectorAll('button').find(each => text(each) === 'Fill the form from these files').click();
                    await settle();
                    const part = dialog.querySelector('.found');
                    const shown = [part.hidden, part.querySelectorAll('.notes__title').map(text).join('|')];
                    dialog.querySelectorAll('input').find(each => each.name === 'addFound').checked = tick;
                    dialog.querySelectorAll('input').find(each => each.type === 'text').value = 'Vendor UAT';
                    await dialog.querySelectorAll('button').find(each => text(each) === 'Save environment').click();
                    await settle();
                    const added = posted.find(each => each.path === '/api/lists/found/add');
                    return [shown, posted.map(each => each.path),
                        added ? JSON.stringify(added.body) === JSON.stringify(posted.find(each => each.path === '/api/lists/found').body) : null,
                        text(dialog.querySelector('.config-form__banner')), dialog.querySelector('.found').hidden];
                };
                say(await run(true));
                say(await run(false));
                // A file chosen anew after Fill clears what was read: what was found belongs to the file read.
                const editor = createEditor({environment: null, settings: null});
                const [, collectionBox] = editor.dialog.querySelectorAll('input').filter(each => each.type === 'file');
                collectionBox.files = [new File(['{"info": {}}'], 'vendor.json', {type: 'application/json'})];
                collectionBox.value = 'C:/fakepath/vendor.json';
                await editor.dialog.querySelectorAll('button').find(each => text(each) === 'Fill the form from these files').click();
                await settle();
                const before = editor.dialog.querySelector('.found').hidden;
                await collectionBox.dispatchEvent({type: 'change'});
                say(['changed', before, editor.dialog.querySelector('.found').hidden, stored]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[[false,\"Members (2)|Providers (1)|Procedure codes (1)|Diagnosis codes (0)|Seen and not used\"],[\"/api/config/environments/draft\","
                        + "\"/api/lists/found\",\"/api/config/environments/settings\",\"/api/lists/found/add\"],true,"
                        + "\"Saved \\\"Vendor UAT\\\". Its other settings can be set now.Added to the saved lists: 2 members, 1 provider. Already "
                        + "there: 1 member.Open the saved lists: Members, Providers, Procedure codes and Diagnosis codes.\",true]",
                "[[false,\"Members (2)|Providers (1)|Procedure codes (1)|Diagnosis codes (0)|Seen and not used\"],[\"/api/config/environments/draft\","
                        + "\"/api/lists/found\",\"/api/config/environments/settings\"],null,\"Saved \\\"Vendor UAT\\\". Its other settings can be "
                        + "set now.\",true]",
                "[\"changed\",false,true,[]]");
    }

    /**
     * The join of D-306 and D-307 in one form: after Fill "What these files give" stands first (what
     * configures the environment) and "Found in the collection" after it (the case data); a save
     * records the request chosen for an operation and adds the entries found, and the result line
     * says both. Each part was tested alone on its own branch; this holds them together.
     */
    @Test
    void afterFillWhatTheFilesGiveStandsBeforeWhatWasFoundAndASaveRecordsThePickAndAddsAndSaysBoth(@TempDir Path folder)
            throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the form with both parts", PRELUDE + """
                const {createEditor} = await import('./js/config-environments.js');
                answers['POST /api/config/environments/draft'] = {status: 200, body: {files: [{slot: 'collectionExport', fileName: 'vendor.json',
                    read: true, line: 'Read: 2 requests.'}], variables: [], shapes: [], operations: [], warnings: [], epoch: 1,
                    collectionId: 'vendor-1a2b',
                    addresses: [{operation: 'CRD_ORDER_SIGN', key: 'CRD_ORDER_SIGN', label: 'CRD order-sign', short: 'CRD',
                        lines: ['No request of the collection was found for it. Choose one below.'], candidates: []}],
                    requests: [{id: 'r-c', name: 'CRD / Check', method: 'POST', display: 'https://vendor.example.invalid/cds-services/x',
                        operation: 'UNMAPPED', errorSample: false}],
                    values: []}};
                answers['POST /api/lists/found'] = {status: 200, body: FOUND_ANSWER};
                answers['POST /api/lists/found/add'] = {status: 200, body: ADDED_ANSWER};
                answers['PUT /api/config/collections/vendor-1a2b/mapping'] = {status: 200, body: {changed: true}};
                const settings = {id: 'vendor-uat', name: 'Vendor UAT', kind: 'uat', profile: {mode: 'live', words: 'Live'}, signIn: {method: 'NONE'}, epoch: 2};
                answers['POST /api/config/environments/settings'] = {status: 200, body: {environment: {id: 'vendor-uat', name: 'Vendor UAT'},
                    settings, warnings: [], epoch: 2}};
                answers['GET /api/config/environments/vendor-uat/settings'] = {status: 200, body: settings};
                const editor = createEditor({environment: null, settings: null});
                const dialog = editor.dialog;
                const [, collectionBox] = dialog.querySelectorAll('input').filter(each => each.type === 'file');
                collectionBox.files = [new File(['{"info": {}}'], 'vendor.json', {type: 'application/json'})];
                collectionBox.value = 'C:/fakepath/vendor.json';
                await dialog.querySelectorAll('button').find(each => text(each) === 'Fill the form from these files').click();
                await settle();
                const importer = text(dialog.querySelector('.importer'));
                const gives = importer.indexOf('What these files give');
                const found = importer.indexOf('Found in the collection');
                say([gives >= 0, found > gives, dialog.querySelector('.found').hidden]);
                const select = dialog.querySelector('.config-gives').querySelector('select');
                select.value = 'r-c';
                await select.dispatchEvent({type: 'change'});
                dialog.querySelectorAll('input').find(each => each.type === 'text').value = 'Vendor UAT';
                dialog.querySelector('select').value = 'uat';
                await dialog.querySelectorAll('button').find(each => text(each) === 'Save environment').click();
                await settle();
                say(posted.map(each => each.method + ' ' + each.path));
                say(posted.filter(each => each.method === 'PUT').map(each => each.body));
                say(text(dialog.querySelector('.config-form__banner')));
                say(stored);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[true,true,false]",
                "[\"POST /api/config/environments/draft\",\"POST /api/lists/found\",\"POST /api/config/environments/settings\","
                        + "\"PUT /api/config/collections/vendor-1a2b/mapping\",\"POST /api/lists/found/add\"]",
                "[{\"requestId\":\"r-c\",\"operation\":\"CRD_ORDER_SIGN\"}]",
                "\"Saved \\\"Vendor UAT\\\". Its other settings can be set now.CRD order-sign uses the request \\\"CRD / Check\\\"."
                        + "Added to the saved lists: 2 members, 1 provider. Already there: 1 member.Open the saved lists: Members, Providers, "
                        + "Procedure codes and Diagnosis codes.\"",
                "[]");
    }

    @Test
    void theCollectionsPageOffersTheButtonAfterAnUploadReadWithTheActiveEnvironment(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the Collections page with the found part", PRELUDE + """
                answers['GET /api/config/collections'] = {status: 200, body: {collections: [], epoch: 1}};
                answers['GET /api/config/effective'] = {status: 200, body: {operations: []}};
                answers['POST /api/config/collections'] = {status: 200, body: {collection: {name: 'Vendor sample', requests: []}, replaced: false, warnings: []}};
                answers['POST /api/lists/found'] = {status: 200, body: FOUND_ANSWER};
                answers['POST /api/lists/found/add'] = {status: 200, body: ADDED_ANSWER};
                const {mount} = await import('./js/cfg-collections.js');
                const root = document.createElement('div');
                document.body.append(root);
                mount(root, ['collections']);
                await settle();
                const file = root.querySelectorAll('input').find(each => each.type === 'file');
                file.files = [new File(['{"info": {}}'], 'vendor.json', {type: 'application/json'})];
                file.value = 'C:/fakepath/vendor.json';
                await root.querySelectorAll('button').find(each => text(each) === 'Upload collection').click();
                await settle();
                const read = posted.find(each => each.path === '/api/lists/found');
                say(['read', read.body.fromActiveEnvironment, read.body.collectionExport.fileName, root.querySelectorAll('input').filter(each => each.name === 'addFound').length,
                    root.querySelectorAll('button').map(text).filter(each => each === 'Add to the saved lists')]);
                await root.querySelectorAll('button').find(each => text(each) === 'Add to the saved lists').click();
                await settle();
                say(['added', posted.map(each => each.path), text(root.querySelector('.found-result p'))]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"read\",true,\"vendor.json\",0,[\"Add to the saved lists\"]]",
                "[\"added\",[\"/api/config/collections\",\"/api/lists/found\",\"/api/lists/found/add\"],\"Added to the saved lists: 2 members, "
                        + "1 provider. Already there: 1 member.\"]");
    }

    @Test
    void aSavedMemberWithOnlyTheVendorPatientIdIsNamedByItAndAPickLeavesTheMemberIdEmpty(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the Member card's saved list", PRELUDE + """
                const {createMemberCard, savedMemberText} = await import('./js/wb-member.js');
                const saved = [{id: 'm-1', label: 'From Order sign', vendorPatientId: 'pat-001'},
                    {id: 'm-2', label: 'Typed', memberId: 'S123456789'}, {id: 'm-3', vendorPatientId: 'pat-003'}];
                say(saved.map(savedMemberText));
                const card = createMemberCard({isMock: () => false, mockLists: () => null, savedMembers: () => saved, dateOfService: () => '',
                    useSample: () => null});
                document.body.append(card.node);
                const picker = card.node.querySelector('select');
                say(picker.querySelectorAll('option').map(text));
                picker.value = 'saved:m-1';
                await picker.dispatchEvent({type: 'change'});
                await settle();
                const value = name => card.node.querySelectorAll('input').find(each => each.name === name).value;
                say([value('memberId'), value('vendorPatientId'), card.request().member, posted.length]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"From Order sign (vendor patient id pat-001)\",\"Typed (S123456789)\",\"vendor patient id pat-003\"]",
                "[\"Choose a saved member\",\"From Order sign (vendor patient id pat-001)\",\"Typed (S123456789)\",\"vendor patient id pat-003\"]",
                "[\"\",\"pat-001\",{\"vendorPatientId\":\"pat-001\"},0]");
    }

    @Test
    void theProcedureCodesPageIsTheThirdListAndTheServiceCardPicksASavedCode(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the procedure codes list and Saved codes", PRELUDE + """
                const {PAGES, normal} = await import('./js/configuration.js');
                say(['pages', PAGES.map(each => each.label), normal(['procedure-codes'])]);
                answers['GET /api/lists/codes'] = {status: 200, body: {enabled: true, count: 1, codes: [
                    {id: 'c-1', label: 'From Order sign', code: '97110', system: 'http://www.ama-assn.org/go/cpt', description: 'Therapeutic exercise'}]}};
                const {mount} = await import('./js/cfg-lists.js');
                const root = document.createElement('div');
                document.body.append(root);
                const page = mount(root, ['procedure-codes']);
                await settle();
                say(['page', text(root.querySelector('h2')), root.querySelectorAll('th').map(text), root.querySelectorAll('tbody tr').map(text),
                    root.querySelector('a').href]);
                await root.querySelectorAll('button').find(each => text(each) === 'Add procedure code').click();
                const dialog = document.body.querySelector('dialog');
                const inputs = Object.fromEntries(dialog.querySelectorAll('input').map(each => [each.name, each]));
                inputs.code.value = ' E0601 ';
                answers['POST /api/lists/codes'] = {status: 200, body: {id: 'c-2', code: 'E0601'}};
                await dialog.querySelectorAll('button').find(each => text(each) === 'Save procedure code').click();
                await settle();
                say(['added', posted.map(each => [each.path, each.body]), text(root).includes('Added the procedure code "E0601".')]);
                page.unmount();

                const {createServiceCard} = await import('./js/wb-service.js');
                const saved = [{id: 'c-1', label: 'From Order sign', code: '97110', system: 'http://www.ama-assn.org/go/cpt', description: 'Therapeutic exercise'},
                    {id: 'c-2', code: 'E0601'}];
                let given = [];
                const card = createServiceCard({lineOfBusiness: () => '', options: () => null, savedCodes: () => given});
                document.body.append(card.node);
                const select = card.node.querySelector('select');
                say(['none saved', select.closest('.field').hidden]);
                given = saved;
                card.drawSaved();
                say(['offered', select.closest('.field').hidden, text(select.closest('.field').querySelector('label')),
                    select.querySelectorAll('option').map(text)]);
                select.value = 'c-1';
                await select.dispatchEvent({type: 'change'});
                await settle();
                say(['picked', card.request().procedureCodes, text(card.node.querySelector('.code-lines')).includes('97110 from saved list'),
                    select.value]);
                select.value = 'c-1';
                await select.dispatchEvent({type: 'change'});
                say(['again', card.request().procedureCodes.length, text(card.node).includes('97110 is in the case already.'), stored]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                // Diagnosis codes is the fourth saved list (D-322).
                "[\"pages\",[\"Environments\",\"Collections\",\"Members\",\"Providers\",\"Procedure codes\",\"Diagnosis codes\",\"Access token\","
                        + "\"Production writes\",\"Advanced\"],[\"procedure-codes\"]]",
                "[\"page\",\"Test procedure codes\",[\"Label\",\"Code\",\"Code system\",\"Description\",\"Actions\"],"
                        + "[\"From Order sign97110http://www.ama-assn.org/go/cptTherapeutic exerciseEdit\"],\"/api/lists/codes/template.csv\"]",
                "[\"added\",[[\"/api/lists/codes\",{\"code\":\"E0601\"}]],true]",
                "[\"none saved\",true]",
                "[\"offered\",false,\"Saved codes\",[\"Choose a saved code\",\"97110 \u00b7 From Order sign\",\"E0601\"]]",
                "[\"picked\",[{\"code\":\"97110\",\"system\":\"http://www.ama-assn.org/go/cpt\",\"description\":\"Therapeutic exercise\"}],"
                        + "true,\"\"]",
                "[\"again\",1,true,[]]");
    }
}
