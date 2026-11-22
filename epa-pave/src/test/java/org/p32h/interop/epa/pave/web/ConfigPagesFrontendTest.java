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
 * The pages of Configuration batch I builds first (plan 10.9, I1): Collections, Production writes,
 * In force now and Access token, and the one-line pages that later slices replace. Each page is
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
            say(['unread', fake.sent.filter(each => each.key.startsWith('POST')).length === 2, text(root).includes('empty.json is empty.'), input.value]);

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
            // Release 1 has no /api/config/lookups: the page never asks for it (features-3).
            say(['asked', fake.sent.filter(each => each.key.includes('/api/config/lookups')).length]);
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                // "Ready to send?" first; the production fact said once, not again under Where PAVE is pointed (look-19);
                // the settings stay in a closed fold.
                "[\"drawn\",true,1,true,true,true,true,false,false]",
                "[\"partial\",false,1]",
                "[\"asked\",0]");
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

    @Test
    void everyLaterPageOfThisBatchIsAOneLinePageThatKeepsThePageContract(@TempDir Path folder) throws Exception {
        List<String> stubs = List.of("cfg-trail.js");
        StringBuilder script = new StringBuilder("import './browser.js';\n");
        for (String stub : stubs) {
            script.append("{ const {mount} = await import('./js/").append(stub).append("');\n")
                    .append("  const root = document.createElement('div');\n")
                    .append("  const page = mount(root, ['advanced']);\n")
                    .append("  const shown = root.textContent;\n")
                    .append("  page.update(['advanced']);\n")
                    .append("  page.unmount();\n")
                    .append("  console.log(JSON.stringify([shown, root.childNodes.length])); }\n");
        }
        NodeRun run = StaticSources.runModule(folder, "the later pages", script.toString());
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).hasSize(stubs.size()).allMatch("[\"This page comes in the next release.\",0]"::equals);
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
        for (String name : List.of("cfg-collections.js", "cfg-writes.js", "cfg-inforce.js", "cfg-token.js")) {
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
            new Read("cfg-token.js", "active.id", ConfigViews.EnvironmentView.class, "id"));

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
