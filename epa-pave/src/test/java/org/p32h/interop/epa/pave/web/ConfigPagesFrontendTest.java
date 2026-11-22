package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.service.config.ConfigViews;
import org.p32h.interop.epa.pave.service.config.WriteAccess;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;
import org.p32h.interop.epa.pave.web.StaticSources.Source;

/**
 * The pages of Configuration batch I builds (plan 10.9, I1 and I4): Collections, Production writes
 * with Forget, In force now with the stored files PAVE could not read, Access token and Trail. Each page is
 * run with Node against answers of the shapes the server documents, with a stand-in for the frame
 * (app.js) written in place of the real one, because the frame starts polling as soon as it is
 * loaded. What each test holds:
 * <ul>
 *   <li>a collection upload of 0 requests never says "Every request was placed." and lists each
 *       note once; the file is read in the page and posted as JSON; the input is let go after a
 *       refusal; a failure shows its reference and cause through fault.js (hunt-files 11, T07)</li>
 *   <li>the unlock sentence carries the environment's whole name, the name goes as typed, and a
 *       refusal at the name is shown at its field (T11)</li>
 *   <li>production is said by one function, on every page (T24)</li>
 *   <li>a secret input is a password field never given a value from the server, and a stored
 *       secret is said as "Stored", never by its reference</li>
 *   <li>a part is drawn only when the server's answer carries its field</li>
 *   <li>Trail and Forget ask before they act and send once; Forget sends the name as typed and is
 *       offered only where the server says so (R-21, R-35); a stored file PAVE could not read is
 *       listed with the server's line and removed alone (R-32)</li>
 *   <li>every field the pages read is a property of the record the server answers with</li>
 * </ul>
 */
class ConfigPagesFrontendTest {

    private static final String PRELUDE = """
            import './browser.js';
            import fs from 'node:fs';
            fs.writeFileSync('./js/app.js', [
                'export const app = {',
                '  mode: () => globalThis.fake.mode, isMock: () => globalThis.fake.mode.mode === "mock",',
                '  epoch: () => 1, on: () => () => true, go: hash => globalThis.fake.went.push(hash), notice() {}',
                '};'].join(String.fromCharCode(10)));
            globalThis.fake = {mode: {mode: 'live', environment: {active: false}, writeAccess: {production: false, reason: 'Not production.'}}, went: [], sent: [], answers: {}};
            globalThis.fetch = async (address, init) => {
                const key = init.method + ' ' + address;
                fake.sent.push({key, body: init.body === undefined ? null : JSON.parse(init.body)});
                const answer = fake.answers[key] || {status: 404, body: {kind: 'NOT_FOUND', title: 'Not found.'}};
                return {ok: answer.status < 300, status: answer.status, text: async () => JSON.stringify(answer.body)};
            };
            const settle = () => new Promise(resolve => setTimeout(resolve, 20));
            const say = value => console.log(JSON.stringify(value));
            const text = node => node.textContent;
            """;

    @Test
    void aCollectionOfNoRequestIsNeverCalledPlacedAndEachNoteIsSaidOnce(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "cfg-collections.js", PRELUDE + """

            const request = (name, operation, extra) => Object.assign({id: name, name, method: 'POST', effectiveOperation: operation,
                effectiveLabel: operation === 'UNMAPPED' ? 'Not mapped' : 'PAS submit', detectedOperation: operation}, extra || {});
            fake.answers['GET /api/config/collections'] = {status: 200, body: {collections: [{id: 'c1', name: 'PAVE Sample Collection',
                uploadedAt: '2026-11-22T06:00:00Z', requests: [request('order-sign Happy Path', 'PAS_SUBMIT', {scripted: true}), request('Loose', 'UNMAPPED')]}], epoch: 1}};
            fake.answers['GET /api/config/effective'] = {status: 200, body: {operations: [{operation: 'PAS_SUBMIT', label: 'PAS submit'},
                {operation: 'PAS_INQUIRE', label: 'PAS inquire'}, {operation: 'UNMAPPED', label: 'Not mapped'}]}};
            const {mount} = await import('./js/cfg-collections.js');
            const root = document.createElement('div');
            document.body.append(root);
            const page = mount(root, ['collections']);
            await settle();
            const rows = root.querySelectorAll('tbody tr');
            say(['table', rows.length, text(rows[0])]);

            // An export that holds no request, with the same note among the warnings and the notes.
            fake.answers['POST /api/config/collections'] = {status: 200, body: {collection: {id: 'c2', name: 'Cut short', requests: [],
                notes: ['The items of this collection are text.', 'Another note.']}, replaced: false,
                warnings: ['The items of this collection are text.'], epoch: 2}};
            const input = root.querySelector('input');
            input.files = [new File([new TextEncoder().encode('{"info": {}, "item": "x"}')], 'cut.json')];
            input.value = 'C:/fakepath/cut.json';
            const upload = root.querySelectorAll('button').find(each => text(each) === 'Upload collection');
            await upload.click();
            await settle();
            const posted = fake.sent.filter(each => each.key === 'POST /api/config/collections');
            say(['posted', posted.length, posted[0].body]);
            const outcome = text(root);
            say(['zero', outcome.includes('Every request was placed.'), outcome.includes('No request was read from this file.'),
                outcome.split('The items of this collection are text.').length - 1, outcome.includes('Another note.'), input.value]);

            // Refused by the server: its words, the fault report, and the input let go.
            fake.answers['POST /api/config/collections'] = {status: 500, body: {kind: 'INTERNAL', heading: 'A failure of this tool',
                title: 'PAVE could not read this file. Type the values instead.', reference: 'R-0001', cause: 'IllegalStateException'}};
            input.files = [new File([new TextEncoder().encode('{}')], 'bad.json')];
            input.value = 'C:/fakepath/bad.json';
            await upload.click();
            await settle();
            say(['refused', text(root).includes('PAVE could not read this file.'), text(root).includes('R-0001'),
                text(root).includes('Copy reference and cause'), input.value]);

            // A file the page cannot read is never posted.
            const before = fake.sent.length;
            input.files = [new File([new Uint8Array(0)], 'empty.json')];
            input.value = 'C:/fakepath/empty.json';
            await upload.click();
            await settle();
            say(['unread', fake.sent.filter(each => each.key === 'POST /api/config/collections').length === 2, text(root).includes('empty.json is empty.'), input.value]);

            // A refusal of the file is one plain line, like the page's own (AU-5); while PAVE's files
            // changed on disk, the line under it says that this can be the cause (I-12).
            fake.answers['POST /api/config/collections'] = {status: 422, body: {kind: 'VALIDATION', heading: 'A problem with what was submitted',
                title: 'This is a Postman collection in the old v1 format, which this tool does not read.', action: 'In Postman, export it as Collection v2.1. Then upload it.'}};
            const refuse = async changed => {
                fake.mode.build = {changedOnDisk: changed};
                input.files = [new File([new TextEncoder().encode('{}')], 'col-v1.json')];
                input.value = 'C:/fakepath/col-v1.json';
                await upload.click();
                await settle();
                return [root.querySelectorAll('.error-panel').length, root.querySelectorAll('.file-refusal .field__error').map(text),
                    root.querySelectorAll('.files-changed-note').map(text)];
            };
            say(['line', await refuse(false)]);
            say(['changed', await refuse(true)]);
            // The page's own refusal of a file says it too.
            input.files = [new File([new Uint8Array(0)], 'empty.json')];
            input.value = 'C:/fakepath/empty.json';
            await upload.click();
            await settle();
            say(['own line', text(root).includes('empty.json is empty.'), root.querySelectorAll('.files-changed-note').length]);
            delete fake.mode.build;

            // The mapping: one line per request, the choice sent at once.
            const mapping = root.querySelectorAll('button').find(each => text(each) === 'Mapping');
            await mapping.click();
            const select = root.querySelectorAll('select')[0];
            say(['options', select.childNodes.map(text)]);
            fake.answers['PUT /api/config/collections/c1/mapping'] = {status: 200, body: {collectionId: 'c1', changed: true, notes: ['Mapped by hand.'], epoch: 3}};
            select.value = 'PAS_INQUIRE';
            await select.dispatchEvent({type: 'change'});
            await settle();
            say(['mapped', fake.sent.filter(each => each.key.startsWith('PUT')).map(each => each.body), text(root).includes('Mapped by hand.')]);
            // A removal is offered inside the open row only, and asks first.
            say(['remove offered', root.querySelectorAll('button').filter(each => text(each) === 'Remove collection').length]);
            fake.answers['DELETE /api/config/collections/c1'] = {status: 200, body: {removed: 'PAVE Sample Collection', wasActive: false, epoch: 4, warnings: []}};
            await root.querySelectorAll('button').find(each => text(each) === 'Remove collection').click();
            const dialog = document.body.querySelector('dialog');
            say(['asked', text(dialog.querySelector('h2')), fake.sent.filter(each => each.key.startsWith('DELETE')).length]);
            await dialog.querySelectorAll('button').find(each => text(each) === 'Remove the collection').click();
            await settle();
            say(['removed', fake.sent.filter(each => each.key.startsWith('DELETE')).length, text(root).includes('Removed the collection "PAVE Sample Collection".'),
                document.body.querySelector('dialog') === null]);
            page.unmount();
            say(['unmounted', root.childNodes.length]);
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"table\",1,\"PAVE Sample Collection211 script, not run2026-11-22Mapping\"]",
                // Read in the page, posted as JSON, once.
                "[\"posted\",1,{\"fileName\":\"cut.json\",\"text\":\"{\\\"info\\\": {}, \\\"item\\\": \\\"x\\\"}\"}]",
                // 0 requests: never "placed"; the note given twice is said once; the input is let go.
                "[\"zero\",false,true,1,true,\"\"]",
                // A failure of this tool: its words, the reference with its cause, the input let go.
                "[\"refused\",true,true,true,\"\"]",
                // A file the page cannot read is said in one line and never posted.
                "[\"unread\",true,true,\"\"]",
                "[\"line\",[0,[\"col-v1.json: This is a Postman collection in the old v1 format, which this tool does not read. In Postman, export it as Collection v2.1. Then upload it.\"],[]]]",
                "[\"changed\",[0,[\"col-v1.json: This is a Postman collection in the old v1 format, which this tool does not read. In Postman, export it as Collection v2.1. Then upload it.\"],[\"PAVE's files changed on disk since it started, which can cause this. Stop PAVE and start it again, then try once more.\"]]]",
                "[\"own line\",true,1]",
                "[\"options\",[\"PAS submit\",\"PAS inquire\",\"Unmapped\"]]",
                "[\"mapped\",[{\"requestId\":\"order-sign Happy Path\",\"operation\":\"PAS_INQUIRE\"}],true]",
                // A removal is offered inside the open row only, asks first, and is sent once.
                "[\"remove offered\",1]",
                "[\"asked\",\"Remove the collection \\\"PAVE Sample Collection\\\"?\",0]",
                "[\"removed\",1,true,true]",
                "[\"unmounted\",0]");
    }

    @Test
    void theUnlockSentenceNamesTheWholeEnvironmentAndTheNameGoesAsTyped(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "cfg-writes.js", PRELUDE + """
            const name = 'PAVE Sample Prod with a name long enough that a screen would be tempted to cut it somewhere in the middle of it';
            fake.mode = {mode: 'live', environment: {name, kind: 'prod', active: true},
                writeAccess: {production: true, writesAllowed: false, floorAllows: true, reason: 'Writes to production are blocked for this browser session.'}};
            fake.answers['GET /api/config/effective'] = {status: 200, body: {operations: [{operation: 'PAS_SUBMIT', label: 'PAS submit', write: true},
                {operation: 'PAS_INQUIRE', label: 'PAS inquire', write: false}]}};
            fake.answers['GET /api/config/environments'] = {status: 200, body: {environments: [], productionDestinations: [
                {environment: 'Old Prod', destination: 'https://pas.example.invalid', stored: false}]}};
            const {mount} = await import('./js/cfg-writes.js');
            const root = document.createElement('div');
            document.body.append(root);
            mount(root, ['production-writes']);
            await settle();
            const all = text(root);
            say(['locked', all.includes('To send a write to ' + name + ' from this browser session'), all.includes('Writes to production are blocked for this browser session.'),
                all.includes('Type the name of the active environment, exactly as it is written above'), all.includes('PAS submit'), all.includes('PAS inquire'),
                all.includes('https://pas.example.invalid'), all.includes('No, it was removed')]);
            fake.answers['POST /api/config/writes'] = {status: 422, body: {kind: 'VALIDATION', title: 'The name does not match.',
                fieldErrors: [{field: 'environment', message: 'Type the name exactly as it is written.'}]}};
            const input = root.querySelector('input');
            input.value = '  pave sample prod ';
            const unlock = root.querySelectorAll('button').find(each => text(each) === 'Unlock production writes for this browser session');
            await unlock.click();
            await settle();
            say(['refused', fake.sent.filter(each => each.key === 'POST /api/config/writes').map(each => each.body),
                root.querySelector('.field__error').textContent, input.getAttribute('aria-invalid'), text(root).includes('The name does not match.')]);
            fake.answers['POST /api/config/writes'] = {status: 200, body: {production: true, writesAllowed: true, floorAllows: true, reason: 'Unlocked for this session.'}};
            input.value = name;
            await unlock.click();
            await settle();
            say(['unlocked', text(root).includes('Writes to production are unlocked for this browser session only'), input.value,
                root.querySelectorAll('button').some(each => text(each) === 'Lock production writes again')]);
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"locked\",true,true,true,true,true,true,true]",
                "[\"refused\",[{\"action\":\"unlock\",\"environment\":\"  pave sample prod \"}],\"Type the name exactly as it is written.\",\"true\",false]",
                "[\"unlocked\",true,\"\",true]");
    }

    @Test
    void inForceNowDrawsWhatTheServerServesAndSaysProductionOneWay(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "cfg-inforce.js", PRELUDE + """
            fake.mode = {mode: 'live', environment: {name: 'PAVE Sample Sandbox', kind: 'sandbox', active: true},
                writeAccess: {production: false, writesAllowed: true, floorAllows: true, reason: 'The active environment is not production.'}};
            const effective = {mode: 'live', storeDirectory: './config-store', problems: [],
                settings: [{property: 'onyx.auth.client-secret', value: '********', set: true, secret: true}, {property: 'onyx.base-url', set: false, secret: false}],
                operations: [
                    {operation: 'CRD_ORDER_SIGN', label: 'CRD order-sign', outbound: true, method: 'POST', write: false,
                        target: {display: 'https://crd.example.invalid/cds-services/order-sign', source: 'collection request "order-sign Happy Path"'},
                        withoutSignIn: 'The request carries an oauth2 helper PAVE does not apply.', defaultRule: 'The first request mapped is used.'},
                    {operation: 'PAS_SUBMIT', label: 'PAS submit', outbound: true, method: 'POST', write: true,
                        target: {display: 'https://pas.example.invalid/Claim/$submit', source: 'application.yml'}}],
                readiness: {environmentLine: 'PAVE Sample Sandbox is active.', environmentActive: true, collections: 1, mapped: 13, sendable: 2, outbound: 2,
                    signIn: {enabled: false, environmentSignInVariables: ['clientSecret'], requestHelpers: [], operationsWithHelper: 0, howToSwitchOn: '--onyx.auth.enabled=true'},
                    missingSecrets: []}};
            fake.answers['GET /api/config/effective'] = {status: 200, body: effective};
            fake.answers['GET /api/config/environments'] = {status: 200, body: {environments: [{id: 'sb', name: 'PAVE Sample Sandbox', active: true,
                addressWarnings: ['The PAS address reads as production.']}]}};
            const {mount} = await import('./js/cfg-inforce.js');
            const root = document.createElement('div');
            document.body.append(root);
            mount(root, ['advanced', 'in-force']);
            await settle();
            let all = text(root);
            say(['drawn', all.indexOf('Ready to send?') >= 0 && all.indexOf('Ready to send?') < all.indexOf('Where PAVE is pointed'),
                all.split('No. The active environment is declared as sandbox.').length - 1, all.includes('Yes, without signing in.'),
                all.includes('The first request mapped is used.'), all.includes('./config-store'), all.includes('The PAS address reads as production.'),
                all.includes('Lookups in force'), all.includes('********')]);
            // Only what the server serves is drawn; with no checklist, Where PAVE is pointed says production itself.
            delete effective.readiness;
            await root.querySelectorAll('button').find(each => text(each) === 'Read again').click();
            await settle();
            all = text(root);
            say(['partial', all.includes('Ready to send?'), all.split('No. The active environment is declared as sandbox.').length - 1]);
            // Release 2 serves /api/config/lookups (C2c): each read of the page asks it once, for "Lookups in force".
            say(['asked', fake.sent.filter(each => each.key.includes('/api/config/lookups')).length]);
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                // "Ready to send?" first; the production fact said once, not again under Where PAVE is pointed (look-19);
                // the settings stay in a closed fold.
                "[\"drawn\",true,1,true,true,true,true,false,false]",
                "[\"partial\",false,1]",
                "[\"asked\",2]");
    }

    /** A lookup set up on screen is in force from there: no row tells the tester to type its address where none can go (look-19). */
    @Test
    void aLookupSetUpOnScreenIsNotToldToBeSetUnderTheEnvironment(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "cfg-inforce.js", PRELUDE + """
            fake.mode = {mode: 'live', environment: {name: 'PAVE Sample Sandbox', kind: 'sandbox', active: true},
                writeAccess: {production: false, writesAllowed: true, floorAllows: true, reason: 'The active environment is not production.'}};
            const missing = name => ({operation: name, label: name === 'MEMBER_LOOKUP' ? 'Member lookup' : 'Provider lookup', outbound: true, method: 'GET',
                unavailable: 'No address for ' + (name === 'MEMBER_LOOKUP' ? 'member' : 'provider') + ' lookup. Type one under Addresses of the environment in Configuration.'});
            fake.answers['GET /api/config/effective'] = {status: 200, body: {mode: 'live', problems: [], settings: [],
                operations: [missing('MEMBER_LOOKUP'), missing('PROVIDER_LOOKUP')]}};
            fake.answers['GET /api/config/environments'] = {status: 200, body: {environments: []}};
            fake.answers['GET /api/config/lookups'] = {status: 200, body: {
                member: {inForce: {from: 'this screen', words: 'POST, signs in with client credentials'}},
                provider: {inForce: {from: 'not set', words: 'No lookup is configured: the tester types the details'}}}};
            const {mount} = await import('./js/cfg-inforce.js');
            const root = document.createElement('div');
            document.body.append(root);
            mount(root, ['advanced', 'in-force']);
            await settle();
            const all = text(root);
            say([all.includes('No address for member lookup'), all.includes('No address for provider lookup'),
                all.includes('POST, signs in with client credentials, this screen')]);
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly("[false,true,true]");
    }

    @Test
    void withNoEnvironmentActiveInForceNowSaysSoOnceOnMockAndOnLive(@TempDir Path folder) throws Exception {
        // The server's words as ConfigService and ConfigStoreGateway write them with no environment active.
        NodeRun run = StaticSources.runModule(folder, "cfg-inforce.js", PRELUDE + """
            const none = 'No environment is active. Open Configuration and make one active.';
            const page = async (profile, write, row) => {
                fake.mode = {mode: profile, environment: {active: false}, writeAccess: {production: false, writesAllowed: profile === 'mock', reason: write}};
                fake.answers['GET /api/config/effective'] = {status: 200, body: {mode: profile, problems: [],
                    operations: [
                        Object.assign({operation: 'CRD_ORDER_SIGN', label: 'CRD order-sign', outbound: true, method: 'POST', write: false}, row),
                        Object.assign({operation: 'PAS_SUBMIT', label: 'PAS submit', outbound: true, method: 'POST', write: true}, row),
                        {operation: 'DTR_QUESTIONNAIRE_RESPONSE', label: 'DTR questionnaire response', outbound: false,
                            note: 'This step is completed inside the tool. Nothing is sent for it, so it has no target.'}],
                    readiness: {environmentLine: 'none is active.', environmentActive: false, collections: 0, mapped: 0, sendable: 0, outbound: 2, missingSecrets: []}}};
                fake.answers['GET /api/config/environments'] = {status: 200, body: {environments: []}};
                const {mount} = await import('./js/cfg-inforce.js');
                const root = document.createElement('div');
                document.body.append(root);
                const drawn = mount(root, ['advanced', 'in-force']);
                await settle();
                const all = text(root);
                drawn.unmount();
                return [profile, all.split('No environment is active').length - 1, all.split('no address').length - 1,
                    all.includes('This step is completed inside the tool.'), all.includes('Make one active')];
            };
            say(await page('mock', 'No environment is active. On the mock profile nothing is sent, so writes are allowed.',
                {note: none + ' On the mock profile nothing is sent, so this still runs.'}));
            say(await page('live', 'No environment is active, so writes are refused: this tool cannot tell whether a target is production. '
                + 'Upload an environment, state its kind and activate it.', {unavailable: none}));
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        // Said once, in "Ready to send?" with the way to make one active (look-19); each row keeps its
        // address and a sentence that says anything else.
        assertThat(run.lines()).containsExactly(
                "[\"mock\",1,2,true,true]",
                "[\"live\",1,2,true,true]");
    }

    @Test
    void accessTokenNeverShowsASecretAndOffersOnlyWhatTheServerServes(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "cfg-token.js", PRELUDE + """
            fake.mode = {mode: 'live', environment: {name: 'PAVE Sample Sandbox', kind: 'sandbox', active: true}, writeAccess: {production: false}};
            fake.answers['GET /api/config/environments'] = {status: 200, body: {environments: [{id: 'pave-sample-sandbox', name: 'PAVE Sample Sandbox', active: true}]}};
            const {mount} = await import('./js/cfg-token.js');
            let root = document.createElement('div');
            document.body.append(root);
            let page = mount(root, ['access-token']);
            await settle();
            let all = text(root);
            // Before the server serves the settings and the sign-in state: no method, no state, no test.
            say(['bare', all.includes('Method'), all.includes('State'), all.includes('Test the sign-in'), all.includes('Edit environment'),
                root.querySelectorAll('input').map(each => each.type)]);
            page.unmount();
            fake.mode.signIn = {state: 'SIGNED_IN', short: 'Signed in (renews automatically)', detail: 'Client credentials.'};
            fake.answers['GET /api/config/environments/pave-sample-sandbox/settings'] = {status: 200, body: {id: 'pave-sample-sandbox',
                signIn: {method: 'CLIENT_CREDENTIALS', tokenUrl: '{{tokenUrl}}', clientId: '{{clientId}}', inForceWords: 'Client credentials, this environment',
                    clientSecret: {set: true, reference: '{{env:PAVE_ONYX_CLIENT_SECRET}}'}, bearerToken: {set: false}}}};
            root = document.createElement('div');
            document.body.append(root);
            page = mount(root, ['access-token']);
            await settle();
            all = text(root);
            say(['facts', all.includes('Client credentials, this environment'), all.includes('Stored'), all.includes('PAVE_ONYX_CLIENT_SECRET'),
                all.includes('Signed in (renews automatically)'), all.includes('Test the sign-in'),
                root.querySelectorAll('input').map(each => [each.type, each.value === undefined || each.value === '' ? 'empty' : 'filled'])]);
            await root.querySelectorAll('button').find(each => text(each) === 'Edit environment').click();
            say(['edit', fake.went]);
            fake.answers['POST /api/config/sign-in/token'] = {status: 200, body: {held: true}};
            const box = root.querySelector('input');
            box.value = 'placeholder-token-value';
            await root.querySelectorAll('button').find(each => text(each) === 'Use this token').click();
            await settle();
            say(['pasted', fake.sent.filter(each => each.key === 'POST /api/config/sign-in/token').map(each => each.body), box.value,
                text(root).includes('placeholder-token-value'), root.querySelectorAll('button').some(each => text(each) === 'Forget the pasted token'),
                text(root).includes('Held in memory for this environment, never saved. Cleared by a 401, a restart or a change of the configuration.')]);
            fake.answers['DELETE /api/config/sign-in/token'] = {status: 200, body: {held: false}};
            await root.querySelectorAll('button').find(each => text(each) === 'Forget the pasted token').click();
            await settle();
            say(['forgot', root.querySelectorAll('button').some(each => text(each) === 'Forget the pasted token')]);
            page.unmount();
            fake.mode = {mode: 'mock', environment: {name: 'PAVE Sample Sandbox', kind: 'sandbox', active: true}, writeAccess: {production: false}};
            root = document.createElement('div');
            document.body.append(root);
            mount(root, ['access-token']);
            await settle();
            say(['mock', text(root).includes('Mock profile: nothing signs in.'), root.querySelectorAll('input').length, text(root).includes('Test the sign-in')]);
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"bare\",false,false,false,true,[\"password\"]]",
                // A stored secret is "Stored", never its reference; the token box is a password field left empty.
                "[\"facts\",true,true,false,true,true,[[\"password\",\"empty\"]]]",
                "[\"edit\",[\"#configuration/environments/pave-sample-sandbox/sign-in\"]]",
                // The token goes once, for the active environment; the box is emptied and the token is not on the page.
                "[\"pasted\",[{\"token\":\"placeholder-token-value\",\"environment\":\"pave-sample-sandbox\"}],\"\",false,true,true]",
                "[\"forgot\",false]",
                // On mock nothing signs in: no token field and no test.
                "[\"mock\",true,0,false]");
    }

    /** The Trail page: the server's count, size, folder and number kept; Empty asks first and is sent once; switched off names the setting and offers nothing. */
    @Test
    void theTrailPageSaysWhatIsKeptAndEmptiesItOnlyAfterAsking(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "cfg-trail.js", PRELUDE + """
            fake.answers['GET /api/trail'] = {status: 200, body: {enabled: true, count: 3, maxEntries: 500, bytes: 2048, folder: './run-history/trail'}};
            const {mount} = await import('./js/cfg-trail.js');
            const root = document.createElement('div');
            document.body.append(root);
            const page = mount(root, ['advanced', 'trail']);
            await settle();
            let all = text(root);
            say(['kept', all.includes('The last 500 runs\\' requests and responses, secrets masked, member data inside. Kept in ./run-history/trail, never in the ledger or the log.'),
                all.includes('3 runs kept, 2 KB'), root.querySelectorAll('button').map(text)]);
            await root.querySelectorAll('button').find(each => text(each) === 'Empty the trail').click();
            let dialog = document.querySelector('dialog');
            say(['asked', text(dialog.querySelector('.modal__title')), text(dialog.querySelector('.modal__body')), dialog.querySelectorAll('button').map(text),
                fake.sent.filter(each => each.key === 'DELETE /api/trail').length]);
            await dialog.querySelectorAll('button').find(each => text(each) === 'Keep them').click();
            say(['kept again', document.querySelector('dialog') === null, fake.sent.filter(each => each.key === 'DELETE /api/trail').length]);
            fake.answers['DELETE /api/trail'] = {status: 200, body: {removed: 3}};
            await root.querySelectorAll('button').find(each => text(each) === 'Empty the trail').click();
            dialog = document.querySelector('dialog');
            fake.answers['GET /api/trail'] = {status: 200, body: {enabled: true, count: 0, maxEntries: 500, bytes: 0, folder: './run-history/trail'}};
            await dialog.querySelectorAll('button').find(each => text(each) === 'Empty the trail').click();
            await settle();
            all = text(root);
            say(['emptied', fake.sent.filter(each => each.key === 'DELETE /api/trail').length, all.includes('The trail is emptied: 3 runs\\' requests and responses removed.'),
                all.includes('0 runs kept, 0 bytes'), root.querySelectorAll('button').length, document.querySelector('dialog') === null]);
            page.unmount();
            // Switched off: the server's setting is named and nothing is offered.
            fake.answers['GET /api/trail'] = {status: 200, body: {enabled: false, count: 0, maxEntries: 500, bytes: 0, folder: './run-history/trail'}};
            const off = document.createElement('div');
            mount(off, ['advanced', 'trail']);
            await settle();
            say(['off', text(off), off.querySelectorAll('button').length]);
            // Another number kept is said as the server's; a count it does not give is not said as 0.
            fake.answers['GET /api/trail'] = {status: 200, body: {enabled: true, maxEntries: 40, folder: 'D:/pave/trail', damaged: 2}};
            const other = document.createElement('div');
            mount(other, ['advanced', 'trail']);
            await settle();
            say(['other', text(other).includes('The last 40 runs\\''), text(other).includes('runs kept'), other.querySelectorAll('button').length]);
            // Files the server skipped are said.
            fake.answers['GET /api/trail'] = {status: 200, body: {enabled: true, count: 2, maxEntries: 500, bytes: 10, folder: './run-history/trail', damaged: 2}};
            const damaged = document.createElement('div');
            mount(damaged, ['advanced', 'trail']);
            await settle();
            say(['damaged', text(damaged).includes('2 files of the trail could not be read since PAVE started, and were skipped.')]);
            // A failure is the error panel, with its reference and cause.
            fake.answers['GET /api/trail'] = {status: 500, body: {kind: 'INTERNAL', title: 'A failure of this tool.', reference: 'R-0001', cause: 'IOException'}};
            const failed = document.createElement('div');
            mount(failed, ['advanced', 'trail']);
            await settle();
            say(['failed', text(failed).includes('A failure of this tool.'), text(failed).includes('R-0001'), failed.querySelectorAll('button').map(text).includes('Empty the trail')]);
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"kept\",true,true,[\"Empty the trail\"]]",
                "[\"asked\",\"Empty the trail?\",\"3 runs' requests and responses are removed. The ledger keeps its lines.\",[\"Keep them\",\"Empty the trail\"],0]",
                "[\"kept again\",true,0]",
                "[\"emptied\",1,true,true,0,true]",
                "[\"off\",\"TrailThe trail is switched off on this installation (trail.enabled).\",0]",
                "[\"other\",true,false,0]",
                "[\"damaged\",true]",
                "[\"failed\",true,true,false]");
    }

    /** Forget this production address: offered only where the server marks the address forgettable; the name goes as typed; a refusal at the name is shown at its field. */
    @Test
    void forgetIsOfferedOnlyWhereTheServerSaysAndTheNameGoesAsTyped(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "cfg-writes.js", PRELUDE + """
            fake.mode = {mode: 'live', environment: {name: 'PAVE Sample Sandbox', kind: 'sandbox', active: true},
                writeAccess: {production: false, writesAllowed: true, floorAllows: true, reason: 'The active environment is not production.'}};
            fake.answers['GET /api/config/effective'] = {status: 200, body: {operations: []}};
            const environments = {environments: [], productionDestinations: [
                {environment: 'PAVE Sample Prod', destination: 'https://pas.example.invalid', stored: true, forgettable: false},
                {environment: 'Old Prod', destination: 'https://old.example.invalid', stored: false, forgettable: true}]};
            fake.answers['GET /api/config/environments'] = {status: 200, body: environments};
            const {mount} = await import('./js/cfg-writes.js');
            const root = document.createElement('div');
            document.body.append(root);
            mount(root, ['production-writes']);
            await settle();
            const forgets = () => root.querySelectorAll('button').filter(each => text(each) === 'Forget this production address');
            // Offered only where the server says the address may be forgotten.
            say(['offered', forgets().length, root.querySelectorAll('tr').map(row => row.querySelectorAll('button').length)]);
            await forgets()[0].click();
            let dialog = document.querySelector('dialog');
            say(['asked', text(dialog.querySelector('.modal__title')), text(dialog.querySelector('.modal__body')).includes('https://old.example.invalid'),
                text(dialog.querySelector('.modal__body')).includes('Old Prod'), dialog.querySelector('input').value || '', dialog.querySelectorAll('button').map(text)]);
            fake.answers['POST /api/config/production-destinations/forget'] = {status: 422, body: {kind: 'VALIDATION', title: 'The name does not match.',
                fieldErrors: [{field: 'environment', message: 'Type the name exactly as it was.'}]}};
            dialog.querySelector('input').value = 'old prod ';
            await dialog.querySelectorAll('button').find(each => text(each) === 'Forget this production address').click();
            await settle();
            say(['refused', fake.sent.filter(each => each.key === 'POST /api/config/production-destinations/forget').map(each => each.body),
                text(dialog.querySelector('.field__error')), document.querySelector('dialog') !== null]);
            fake.answers['POST /api/config/production-destinations/forget'] = {status: 200, body: {forgotten: 'https://old.example.invalid', epoch: 7}};
            environments.productionDestinations = environments.productionDestinations.slice(0, 1);
            dialog.querySelector('input').value = 'Old Prod';
            await dialog.querySelectorAll('button').find(each => text(each) === 'Forget this production address').click();
            await settle();
            say(['forgotten', fake.sent.filter(each => each.key === 'POST /api/config/production-destinations/forget').length, document.querySelector('dialog') === null,
                text(root).includes('https://old.example.invalid is no longer remembered as production.'), forgets().length,
                root.querySelectorAll('th').map(text)]);
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"offered\",1,[0,0,1]]",
                "[\"asked\",\"Forget this production address?\",true,true,\"\",[\"Keep it\",\"Forget this production address\"]]",
                "[\"refused\",[{\"destination\":\"https://old.example.invalid\",\"environment\":\"old prod \"}],\"Type the name exactly as it was.\",true]",
                "[\"forgotten\",2,true,true,0,[\"Address\",\"Environment\",\"Environment still stored\"]]");
    }

    /** In force now lists the stored files PAVE could not read with the server's line, and "Remove it from the store" sends that file alone. */
    @Test
    void unreadableStoredFilesAreListedWithTheServersLineAndRemovedOneAtATime(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "cfg-inforce.js", PRELUDE + """
            fake.mode = {mode: 'live', environment: {name: 'PAVE Sample Sandbox', kind: 'sandbox', active: true},
                writeAccess: {production: false, writesAllowed: true, floorAllows: true, reason: 'The active environment is not production.'}};
            const effective = {mode: 'live', storeDirectory: './config-store', problems: ['Not read: it is not valid JSON. It was left out when PAVE started.'], operations: [], settings: [],
                unreadable: [{file: 'environments/pave-sample-uat.json', line: 'Not read: it is not valid JSON. It was left out when PAVE started.'},
                    {file: 'store.json#productionDestinations[2]', line: 'Kept as text: it is not an address PAVE can read.'}]};
            fake.answers['GET /api/config/effective'] = {status: 200, body: effective};
            fake.answers['GET /api/config/environments'] = {status: 200, body: {environments: []}};
            const {mount} = await import('./js/cfg-inforce.js');
            const root = document.createElement('div');
            document.body.append(root);
            mount(root, ['advanced', 'in-force']);
            await settle();
            let all = text(root);
            const removes = () => root.querySelectorAll('button').filter(each => text(each) === 'Remove it from the store');
            say(['said once', all.split('Not read: it is not valid JSON.').length - 1]);
            say(['listed', all.includes('2 stored files were left out, as PAVE could not read them.'), all.includes('environments/pave-sample-uat.json'),
                all.includes('Not read: it is not valid JSON. It was left out when PAVE started.'), removes().length,
                all.indexOf('Stored files PAVE could not read') < all.indexOf('Where PAVE is pointed')]);
            fake.answers['POST /api/config/unreadable/remove'] = {status: 500, body: {kind: 'INTERNAL', title: 'A failure of this tool.', reference: 'R-0003', cause: 'AccessDeniedException'}};
            await removes()[0].click();
            await settle();
            all = text(root);
            say(['failed', all.includes('A failure of this tool.'), all.includes('R-0003'), removes().length]);
            fake.answers['POST /api/config/unreadable/remove'] = {status: 200, body: {removed: 'environments/pave-sample-uat.json', movedTo: 'unreadable/pave-sample-uat.json', epoch: 9}};
            effective.unreadable = effective.unreadable.slice(1);
            await removes()[0].click();
            await settle();
            all = text(root);
            say(['removed', fake.sent.filter(each => each.key === 'POST /api/config/unreadable/remove').map(each => each.body),
                all.includes('Removed environments/pave-sample-uat.json from the configuration store. Moved to unreadable/pave-sample-uat.json.'), all.includes('A failure of this tool.'),
                all.includes('One stored file was left out, as PAVE could not read it.'), removes().length]);
            // None served: no part.
            delete effective.unreadable;
            await root.querySelectorAll('button').find(each => text(each) === 'Read again').click();
            await settle();
            say(['none', text(root).includes('Stored files PAVE could not read'), removes().length]);
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"said once\",1]",
                "[\"listed\",true,true,true,2,true]",
                "[\"failed\",true,true,2]",
                "[\"removed\",[{\"file\":\"environments/pave-sample-uat.json\"},{\"file\":\"environments/pave-sample-uat.json\"}],true,false,true,1]",
                "[\"none\",false,0]");
    }

    @Test
    void productionIsSaidByOneFunction() throws IOException {
        List<String> saying = new ArrayList<>();
        for (Source script : StaticSources.sources(".js")) {
            if (StaticSources.withoutComments(script.text()).contains("The active environment is declared as")) {
                saying.add(script.name());
            }
        }
        assertThat(saying).containsExactly("js/cfg-parts.js");
        String inForce = StaticSources.script("cfg-inforce.js");
        assertThat(inForce.split("productionWords\\(access, environment\\)", -1)).hasSize(3);
    }

    @Test
    void noPageOfThisBatchGivesAnInputAValueFromTheServer() throws IOException {
        for (String name : List.of("cfg-collections.js", "cfg-writes.js", "cfg-inforce.js", "cfg-token.js", "cfg-trail.js")) {
            String code = StaticSources.withoutComments(StaticSources.script(name));
            // An input is only ever emptied here: a value typed by the tester goes out, nothing comes back into one.
            for (String line : code.split("\n")) {
                if (line.contains(".value = ")) {
                    assertThat(line.strip()).as(name).endsWith(".value = '';");
                }
            }
        }
        assertThat(StaticSources.script("cfg-token.js")).contains("type: 'password'").doesNotContain("reference");
    }

    /** One field a page reads: the script, the text of the read, the record, and the property. */
    private record Read(String script, String text, Class<?> record, String property) {
    }

    private static final List<Read> READS = List.of(
            new Read("cfg-collections.js", "collection.requests", ConfigViews.CollectionView.class, "requests"),
            new Read("cfg-collections.js", "collection.uploadedAt", ConfigViews.CollectionView.class, "uploadedAt"),
            new Read("cfg-collections.js", "collection.originalFileName", ConfigViews.CollectionView.class, "originalFileName"),
            new Read("cfg-collections.js", "collection.notes", ConfigViews.CollectionView.class, "notes"),
            new Read("cfg-collections.js", "done.replaced", ConfigViews.CollectionUploaded.class, "replaced"),
            new Read("cfg-collections.js", "done.warnings", ConfigViews.CollectionUploaded.class, "warnings"),
            new Read("cfg-collections.js", "list.data.collections", ConfigViews.CollectionList.class, "collections"),
            new Read("cfg-collections.js", "request.effectiveOperation", ConfigViews.RequestSummary.class, "effectiveOperation"),
            new Read("cfg-collections.js", "request.effectiveLabel", ConfigViews.RequestSummary.class, "effectiveLabel"),
            new Read("cfg-collections.js", "request.correctedOperation", ConfigViews.RequestSummary.class, "correctedOperation"),
            new Read("cfg-collections.js", "request.detectedOperation", ConfigViews.RequestSummary.class, "detectedOperation"),
            new Read("cfg-collections.js", "request.detectionBasis", ConfigViews.RequestSummary.class, "detectionBasis"),
            new Read("cfg-collections.js", "request.scripted", ConfigViews.RequestSummary.class, "scripted"),
            new Read("cfg-collections.js", "request.testScripted", ConfigViews.RequestSummary.class, "testScripted"),
            new Read("cfg-collections.js", "done.changed", ConfigViews.MappingChanged.class, "changed"),
            new Read("cfg-collections.js", "done.notes", ConfigViews.MappingChanged.class, "notes"),
            new Read("cfg-collections.js", "answer.data.removed", ConfigViews.Removed.class, "removed"),
            new Read("cfg-writes.js", "writes.production", WriteAccess.class, "production"),
            new Read("cfg-writes.js", "writes.writesAllowed", WriteAccess.class, "writesAllowed"),
            new Read("cfg-writes.js", "writes.floorAllows", WriteAccess.class, "floorAllows"),
            new Read("cfg-writes.js", "writes.reason", WriteAccess.class, "reason"),
            new Read("cfg-writes.js", "row.write", ConfigViews.OperationRow.class, "write"),
            new Read("cfg-writes.js", "environments.data.productionDestinations", ConfigViews.EnvironmentList.class, "productionDestinations"),
            new Read("cfg-writes.js", "row.destination", ConfigViews.ProductionDestinationView.class, "destination"),
            new Read("cfg-writes.js", "row.stored", ConfigViews.ProductionDestinationView.class, "stored"),
            new Read("cfg-inforce.js", "data.readiness", ConfigViews.Effective.class, "readiness"),
            new Read("cfg-inforce.js", "data.problems", ConfigViews.Effective.class, "problems"),
            new Read("cfg-inforce.js", "data.settings", ConfigViews.Effective.class, "settings"),
            new Read("cfg-inforce.js", "data.storeDirectory", ConfigViews.Effective.class, "storeDirectory"),
            new Read("cfg-inforce.js", "readiness.environmentLine", ConfigViews.Readiness.class, "environmentLine"),
            new Read("cfg-inforce.js", "readiness.missingSecrets", ConfigViews.Readiness.class, "missingSecrets"),
            new Read("cfg-inforce.js", "signIn.howToSwitchOn", ConfigViews.SignIn.class, "howToSwitchOn"),
            new Read("cfg-inforce.js", "row.withoutSignIn", ConfigViews.OperationRow.class, "withoutSignIn"),
            new Read("cfg-inforce.js", "row.defaultRule", ConfigViews.OperationRow.class, "defaultRule"),
            new Read("cfg-inforce.js", "row.blockedShort", ConfigViews.OperationRow.class, "blockedShort"),
            new Read("cfg-inforce.js", "target.unresolved", ConfigViews.TargetView.class, "unresolved"),
            new Read("cfg-inforce.js", "setting.secret", ConfigViews.SettingRow.class, "secret"),
            new Read("cfg-inforce.js", "active.addressWarnings", ConfigViews.EnvironmentView.class, "addressWarnings"),
            new Read("cfg-token.js", "each.active", ConfigViews.EnvironmentView.class, "active"),
            new Read("cfg-token.js", "active.id", ConfigViews.EnvironmentView.class, "id"),
            new Read("cfg-writes.js", "row.forgettable", ConfigViews.ProductionDestinationView.class, "forgettable"),
            new Read("cfg-inforce.js", "data.unreadable", ConfigViews.Effective.class, "unreadable"),
            new Read("cfg-inforce.js", "each.file", ConfigViews.UnreadableView.class, "file"),
            new Read("cfg-inforce.js", "each.line", ConfigViews.UnreadableView.class, "line"),
            new Read("cfg-inforce.js", "data.removed", ConfigViews.UnreadableRemoved.class, "removed"),
            new Read("cfg-inforce.js", "data.movedTo", ConfigViews.UnreadableRemoved.class, "movedTo"),
            new Read("cfg-trail.js", "trail.enabled", TrailController.TrailSummary.class, "enabled"),
            new Read("cfg-trail.js", "trail.count", TrailController.TrailSummary.class, "count"),
            new Read("cfg-trail.js", "trail.maxEntries", TrailController.TrailSummary.class, "maxEntries"),
            new Read("cfg-trail.js", "trail.bytes", TrailController.TrailSummary.class, "bytes"),
            new Read("cfg-trail.js", "trail.folder", TrailController.TrailSummary.class, "folder"),
            new Read("cfg-trail.js", "trail.damaged", TrailController.TrailSummary.class, "damaged"),
            new Read("cfg-trail.js", "answer.data.removed", TrailController.Removed.class, "removed"));

    @Test
    void everyFieldThePagesReadIsOnTheRecordTheServerSends() throws IOException {
        for (Read read : READS) {
            assertThat(StaticSources.script(read.script())).as(read.script() + " reads " + read.text()).contains(read.text());
            assertThat(hasProperty(read.record(), read.property()))
                    .as(read.record().getSimpleName() + " has " + read.property() + ", which " + read.script() + " reads").isTrue();
        }
        assertThat(hasProperty(ConfigViews.CollectionView.class, "notAField")).isFalse();
    }

    private static boolean hasProperty(Class<?> type, String name) {
        try {
            return type.getMethod(name).getParameterCount() == 0;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }
}
