package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.service.lists.TestListService;
import org.p32h.interop.epa.pave.service.lists.TestListViews;
import org.p32h.interop.epa.pave.service.lists.TestMember;
import org.p32h.interop.epa.pave.service.lists.TestMemberInput;
import org.p32h.interop.epa.pave.service.lists.TestProvider;
import org.p32h.interop.epa.pave.service.lists.TestProviderInput;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * Configuration > Members and Providers, the saved test lists (plan 10.9, I2). The page is run
 * with Node against answers of the shapes TestListController sends, with a stand-in for the frame
 * (app.js) written in place of the real one. What each test holds:
 * <ul>
 *   <li>an import shows the columns used and left out and each row's outcome before anything is
 *       imported, and imports the text it showed, never a file chosen after (history M19, C13)</li>
 *   <li>the file is read in the page through files.js and posted as JSON; a refusal shows the
 *       server's words and lets the input go; a file the page cannot read is never posted</li>
 *   <li>a refusal at a field is shown at that field; a blank field is not sent</li>
 *   <li>one instance serves both lists and draws the other one on update</li>
 *   <li>switched off, and a list file that could not be read, are each said in the server's words</li>
 *   <li>every field the form sends is a property of the record the server reads</li>
 * </ul>
 */
class ListsFrontendTest {

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
    void theImportShowsColumnsAndRowsBeforeAnythingIsImportedAndTheListsAreEditedInPlace(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "cfg-lists.js", PRELUDE + """
            
            const member = (id, label, memberId, extra) => Object.assign({id, label, memberId}, extra || {});
            fake.answers['GET /api/lists/members'] = {status: 200, body: {enabled: true, count: 2, members: [
                member('m-1', 'Approved knee', 'S123456789', {firstName: 'Test', lastName: 'Member', dateOfBirth: '1980-01-31', lineOfBusiness: 'Commercial', gender: 'female'}),
                member('m-2', 'Pended', 'S123456788')]}};
            const {mount} = await import('./js/cfg-lists.js');
            const root = document.createElement('div');
            document.body.append(root);
            const page = mount(root, ['members']);
            await settle();
            const buttons = () => root.querySelectorAll('button').map(text);
            const rows = () => root.querySelectorAll('tbody tr').map(text);
            say(['members', text(root.querySelector('h2')), buttons(), rows(), root.querySelector('a').getAttribute === undefined ? null : root.querySelector('a').href]);
            
            // Add: blank fields are not sent; a refusal at a field is shown at that field and not repeated below.
            await root.querySelectorAll('button').find(each => text(each) === 'Add member').click();
            let dialog = document.body.querySelector('dialog');
            const inputs = () => Object.fromEntries(dialog.querySelectorAll('input, select').map(each => [each.name, each]));
            say(['add', text(dialog.querySelector('h2')), Object.values(inputs()).map(each => each.value).join('|'),
                dialog.querySelectorAll('button').map(text)]);
            inputs().memberId.value = ' S123456700 ';
            inputs().dateOfBirth.value = '31/01/1980';
            fake.answers['POST /api/lists/members'] = {status: 422, body: {kind: 'VALIDATION', title: 'The member has 1 problem. Nothing was saved.',
                fieldErrors: [{field: 'dateOfBirth', message: 'Write the date of birth as yyyy-MM-dd, for example 1980-01-31.'}]}};
            await dialog.querySelectorAll('button').find(each => text(each) === 'Save member').click();
            await settle();
            const at = dialog.querySelectorAll('.field--error').map(text);
            say(['refused', fake.sent.filter(each => each.key === 'POST /api/lists/members').map(each => each.body), at,
                text(dialog).split('Write the date of birth as').length - 1, text(dialog).includes('Nothing was saved.'), document.activeElement.name]);
            inputs().dateOfBirth.value = '1980-01-31';
            fake.answers['POST /api/lists/members'] = {status: 200, body: member('m-3', null, 'S123456700', {dateOfBirth: '1980-01-31'})};
            await dialog.querySelectorAll('button').find(each => text(each) === 'Save member').click();
            await settle();
            say(['added', document.body.querySelector('dialog') === null, text(root).includes('Added the member "S123456700".'),
                fake.sent.filter(each => each.key === 'GET /api/lists/members').length]);
            
            // Edit: the stored values in the fields, sent back by the entry's id.
            await root.querySelectorAll('button').find(each => each.getAttribute('aria-label') === 'Edit Approved knee').click();
            dialog = document.body.querySelector('dialog');
            say(['edit', text(dialog.querySelector('h2')), inputs().memberId.value, inputs().gender.value, inputs().plan.value]);
            inputs().plan.value = 'Gold';
            fake.answers['PUT /api/lists/members/m-1'] = {status: 200, body: member('m-1', 'Approved knee', 'S123456789')};
            await dialog.querySelectorAll('button').find(each => text(each) === 'Save member').click();
            await settle();
            say(['put', fake.sent.filter(each => each.key === 'PUT /api/lists/members/m-1').map(each => each.body)]);
            
            // Remove asks first, then removes by id.
            await root.querySelectorAll('button').find(each => each.getAttribute('aria-label') === 'Edit Pended').click();
            await document.body.querySelector('dialog').querySelectorAll('button').find(each => text(each) === 'Remove member').click();
            dialog = document.body.querySelector('dialog');
            say(['asked', text(dialog.querySelector('h2')), fake.sent.filter(each => each.key.startsWith('DELETE')).length]);
            fake.answers['DELETE /api/lists/members/m-2'] = {status: 200, body: {removed: true}};
            await dialog.querySelectorAll('button').find(each => text(each) === 'Remove member').click();
            await settle();
            say(['removed', fake.sent.filter(each => each.key.startsWith('DELETE')).map(each => each.key), text(root).includes('Removed the member "Pended".')]);
            
            // Import: the dry run first, columns and rows shown, nothing imported until the button is pressed.
            await root.querySelectorAll('button').find(each => text(each) === 'Import CSV').click();
            dialog = document.body.querySelector('dialog');
            const csv = 'label,memberId,favouriteColour\\nKnee,S123456789,blue\\nNew,S123456787,red\\nBad,,green\\n';
            const file = dialog.querySelector('input');
            file.files = [new File([new TextEncoder().encode(csv)], 'members.csv')];
            file.value = 'C:/fakepath/members.csv';
            fake.answers['POST /api/lists/members/import'] = {status: 200, body: {columnsUsed: ['label', 'memberId'], columnsIgnored: ['favouriteColour'],
                rows: [{line: 2, status: 'REPLACE', label: 'Knee'}, {line: 3, status: 'ADD', label: 'New'},
                    {line: 4, status: 'REFUSED', label: 'Bad', reason: 'Enter the member ID as it is on the card.'}],
                added: 1, replaced: 1, refused: 1, applied: false}};
            await dialog.querySelectorAll('button').find(each => text(each) === 'Read the file').click();
            await settle();
            const imports = () => fake.sent.filter(each => each.key === 'POST /api/lists/members/import').map(each => each.body);
            say(['dry run', imports(), file.value]);
            say(['shown', dialog.querySelectorAll('p').map(text).filter(line => line.startsWith('Columns')),
                dialog.querySelectorAll('tbody tr').map(text), dialog.querySelectorAll('button').map(text)]);
            // Another file chosen after the dry run is not what is imported: the text shown is.
            file.files = [new File([new TextEncoder().encode('label,memberId\\nOther,S123456786\\n')], 'other.csv')];
            fake.answers['POST /api/lists/members/import'] = {status: 200, body: {columnsUsed: ['label', 'memberId'], columnsIgnored: ['favouriteColour'],
                rows: [], added: 1, replaced: 1, refused: 1, applied: true}};
            await dialog.querySelectorAll('button').find(each => text(each) === 'Import 2 rows (1 refused)').click();
            await settle();
            say(['imported', imports().length, imports()[1].dryRun, imports()[1].csv === csv, text(root).includes('Imported: 1 row added, 1 replaced, 1 refused.')]);
            
            // A refused file: the server's words with the fault report, the input let go, no import offered.
            fake.answers['POST /api/lists/members/import'] = {status: 422, body: {kind: 'BAD_INPUT', title: 'The file has no memberId column, so no row can be matched to a saved member.',
                action: 'Start from the CSV template and keep its first row.'}};
            file.files = [new File([new TextEncoder().encode('name\\nx\\n')], 'nocolumn.csv')];
            file.value = 'C:/fakepath/nocolumn.csv';
            await dialog.querySelectorAll('button').find(each => text(each) === 'Read the file').click();
            await settle();
            // One plain line naming the file, never the boxed panel (AU-5).
            say(['refused file', text(dialog).includes('The file has no memberId column'), file.value,
                dialog.querySelectorAll('button').map(text).some(each => each.startsWith('Import ')),
                dialog.querySelectorAll('.error-panel').length, dialog.querySelectorAll('.file-refusal .field__error').map(text)]);
            // A file the page cannot read is never posted.
            const posted = imports().length;
            file.files = [new File([new Uint8Array(0)], 'empty.csv')];
            file.value = 'C:/fakepath/empty.csv';
            await dialog.querySelectorAll('button').find(each => text(each) === 'Read the file').click();
            await settle();
            say(['unread', imports().length === posted, text(dialog).includes('empty.csv is empty.'), file.value]);
            await dialog.querySelectorAll('button').find(each => text(each) === 'Close without importing').click();
            
            // The other list, drawn again in place when the frame moves to it.
            fake.answers['GET /api/lists/providers'] = {status: 200, body: {enabled: true, count: 1, providers: [
                {id: 'p-1', label: 'Ordering', npi: '1234567893', organization: 'Sample Clinic', placeOfService: '11'}]}};
            page.update(['providers']);
            await settle();
            say(['providers', text(root.querySelector('h2')), buttons(), rows(), root.querySelector('a').href]);
            page.update(['providers']);
            await settle();
            say(['same page read once', fake.sent.filter(each => each.key === 'GET /api/lists/providers').length]);
            
            // Switched off: the server's switch said, nothing offered.
            fake.answers['GET /api/lists/members'] = {status: 200, body: {enabled: false, members: [], count: 0}};
            page.update(['members']);
            await settle();
            say(['off', text(root).includes('Saved test lists are switched off on this installation (test-lists.enabled).'),
                root.querySelector('.inline-controls').hidden]);
            // A list file that could not be read: the server's sentence, above an empty list.
            fake.answers['GET /api/lists/providers'] = {status: 200, body: {enabled: true, providers: [], count: 0,
                problem: 'The file test-lists/providers.json could not be read, so this list is shown empty.'}};
            page.update(['providers']);
            await settle();
            say(['problem', text(root).includes('could not be read, so this list is shown empty.'), text(root).includes('No test provider is saved yet.')]);
            page.unmount();
            say(['unmounted', root.childNodes.length]);
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"members\",\"Test members\",[\"Add member\",\"Import CSV\",\"Edit\",\"Edit\"],[\"Approved kneeS123456789Test Member1980-01-31CommercialEdit\",\"PendedS123456788not givennot givennot givenEdit\"],\"/api/lists/members/template.csv\"]",
                "[\"add\",\"Add member\",\"||||||||||||\",[\"Close without saving\",\"Save member\"]]",
                "[\"refused\",[{\"memberId\":\"S123456700\",\"dateOfBirth\":\"31/01/1980\"}],[\"Date of birthWrite the date of birth as yyyy-MM-dd, for example 1980-01-31.\"],1,true,\"dateOfBirth\"]",
                "[\"added\",true,true,2]",
                "[\"edit\",\"Edit member\",\"S123456789\",\"female\",\"\"]",
                "[\"put\",[{\"label\":\"Approved knee\",\"memberId\":\"S123456789\",\"firstName\":\"Test\",\"lastName\":\"Member\",\"dateOfBirth\":\"1980-01-31\",\"gender\":\"female\",\"lineOfBusiness\":\"Commercial\",\"plan\":\"Gold\"}]]",
                "[\"asked\",\"Remove the member \\\"Pended\\\"?\",0]",
                "[\"removed\",[\"DELETE /api/lists/members/m-2\"],true]",
                "[\"dry run\",[{\"csv\":\"label,memberId,favouriteColour\\nKnee,S123456789,blue\\nNew,S123456787,red\\nBad,,green\\n\",\"dryRun\":true}],\"\"]",
                "[\"shown\",[\"Columns used: label, memberId.\",\"Columns left out: favouriteColour.\"],[\"4RefusedBadEnter the member ID as it is on the card.\",\"2ReplaceKnee\",\"3AddNew\"],[\"Read the file\",\"Close without importing\",\"Import 2 rows (1 refused)\"]]",
                "[\"imported\",2,false,true,true]",
                "[\"refused file\",true,\"\",false,0,[\"nocolumn.csv: The file has no memberId column, so no row can be matched to a saved member. Start from the CSV template and keep its first row.\"]]",
                "[\"unread\",true,true,\"\"]",
                "[\"providers\",\"Test providers\",[\"Add provider\",\"Import CSV\",\"Edit\"],[\"Ordering1234567893not givenSample Clinic11Edit\"],\"/api/lists/providers/template.csv\"]",
                "[\"same page read once\",1]",
                "[\"off\",true,true]",
                "[\"problem\",true,true]",
                "[\"unmounted\",0]");
    }

    @Test
    void everyFieldTheFormSendsIsAPropertyTheServerReadsInTheTemplatesOrder() throws IOException {
        String code = StaticSources.script("cfg-lists.js");
        assertKeysInOrder(code, "members: {", TestMemberInput.class, TestListService.memberTemplate());
        assertKeysInOrder(code, "providers: {", TestProviderInput.class, TestListService.providerTemplate());
        // The fields the page reads from the answers are on the records the server sends.
        for (String name : List.of("enabled", "problem")) {
            assertThat(code).contains("data." + name);
            assertThat(hasProperty(TestListViews.MemberList.class, name)).as(name).isTrue();
            assertThat(hasProperty(TestListViews.ProviderList.class, name)).as(name).isTrue();
        }
        assertThat(hasProperty(TestListViews.MemberList.class, "members")).isTrue();
        assertThat(hasProperty(TestListViews.ProviderList.class, "providers")).isTrue();
        for (String name : List.of("columnsUsed", "columnsIgnored", "rows", "added", "replaced", "refused")) {
            assertThat(code).contains("result." + name);
            assertThat(hasProperty(TestListViews.ImportResult.class, name)).as(name).isTrue();
        }
        for (String name : List.of("line", "status", "label", "reason")) {
            assertThat(code).contains("row." + name);
            assertThat(hasProperty(TestListViews.ImportRow.class, name)).as(name).isTrue();
        }
        assertThat(hasProperty(TestMember.class, "id")).isTrue();
        assertThat(hasProperty(TestProvider.class, "id")).isTrue();
        assertThat(code).contains("entry.id").contains("answer.data.removed");
    }

    @Test
    void theFileIsReadInThePageAsACsvAndTheImportIsADryRunFirst() throws IOException {
        String code = StaticSources.withoutComments(StaticSources.script("cfg-lists.js"));
        assertThat(code).contains("readText(file, {maxBytes: 5 * 1024 * 1024, source: 'csv'})")
                .contains("{csv: got.text, dryRun: true}")
                .contains("{csv: text, dryRun: false}")
                .doesNotContain("FormData")
                .doesNotContain("Storage");
        // One dry run and one import, nothing else posts to the import.
        assertThat(code.split("/import'", -1)).hasSize(3);
    }

    private static void assertKeysInOrder(String code, String start, Class<? extends Record> input, String template) {
        int from = code.indexOf(start);
        int fieldsAt = code.indexOf("fields: [", from);
        int end = code.indexOf("],", fieldsAt);
        String fields = code.substring(fieldsAt, end);
        List<String> keys = Arrays.stream(fields.split("\\{key: '")).skip(1).map(each -> each.substring(0, each.indexOf('\''))).toList();
        List<String> components = Arrays.stream(input.getRecordComponents()).map(RecordComponent::getName).toList();
        assertThat(keys).as(input.getSimpleName()).isEqualTo(components);
        String header = template.lines().findFirst().orElseThrow();
        assertThat(String.join(",", keys)).isEqualTo(header);
    }

    private static boolean hasProperty(Class<?> type, String name) {
        try {
            return type.getMethod(name).getParameterCount() == 0;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }
}
