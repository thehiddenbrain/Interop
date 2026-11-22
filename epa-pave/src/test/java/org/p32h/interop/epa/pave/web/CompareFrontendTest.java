package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * Configuration > Advanced > Compare with a request that works (plan 10.9, I3; history M37). The
 * page runs with Node against answers of the shape POST /api/crd/compare sends. What it holds:
 * the request is typed or read from a file through files.js, a file that cannot be read is
 * refused and its input let go; an empty box is not sent; the case typed is sent with the request
 * and blank fields are left out; the comparison is sent once a press; every difference is drawn as
 * the server words it, group by group, a group the page does not know included; both requests are
 * drawn line by line with each difference marked in both; a refusal is shown at its field. The
 * line map walks with its own stack, so no depth of request overflows the page.
 */
class CompareFrontendTest {

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

    /** A file refused, a file read, the comparison sent once and drawn, a difference chosen, then a refusal. */
    @Test
    void theRequestIsReadOrTypedComparedOnceAndEveryDifferenceDrawnAsTheServerWordsIt(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "Compare with a request that works", PRELUDE + """
            const yours = '{\\n  "hook" : "order-sign",\\n  "context" : {\\n    "patientId" : "placeholder-patient"\\n  }\\n}';
            const tool = '{\\n  "hook" : "order-sign",\\n  "context" : {\\n    "patientId" : "patient-1",\\n    "userId" : "Practitioner/practitioner-1"\\n  }\\n}';
            const comparison = {comparable: true, headline: 'The two requests differ in 2 places: 1 in how the request is built and 1 in labels.',
                caveat: 'This comparison shows where the two requests differ. It cannot show which one the service expects.',
                hook: 'order-sign', notes: ['A note the server makes once.'],
                counts: [{group: 'BUILD', label: 'How the request is built', description: 'One request has an element the other does not.', count: 1, startsOpen: true},
                    {group: 'LABELS', label: 'Labels', count: 1, startsOpen: true}],
                caseSource: [{field: 'member.memberId', label: 'Member ID', source: 'FORM', sourceLabel: 'The form', value: {presence: 'VALUE', value: 'S123456789'}}],
                notFound: [{field: 'member.firstName', label: 'Member\\'s given name', lookedIn: 'Patient.name[0].given[0]', candidates: []}],
                differences: [
                    {path: 'context.userId', kind: 'ONLY_IN_TOOL', kindLabel: 'Only in the tool\\'s request', group: 'BUILD', toolPointer: '/context/userId',
                        toolValue: {presence: 'VALUE', value: 'Practitioner/practitioner-1'}, yourValue: {presence: 'ABSENT'},
                        what: 'The clinician using the sender\\'s system.', explanation: 'The tool\\'s request has this element and your request does not.'},
                    {path: 'context.patientId', kind: 'DIFFERENT_VALUE', kindLabel: 'A different value', group: 'LABELS', toolPointer: '/context/patientId',
                        yourPointer: '/context/patientId', toolValue: {presence: 'VALUE', value: 'patient-1'}, yourValue: {presence: 'EMPTY'}, yourType: 'string',
                        what: 'The patient.', explanation: 'An id is a label inside one request.'},
                    {path: 'odd', kind: 'X', group: 'NEW_GROUP', toolValue: {presence: 'NULL'}, yourValue: {presence: 'VALUE', value: '1'}, explanation: 'Kept.'}],
                toolBody: tool, yourBody: yours};
            const {mount} = await import('./js/adv-compare.js');
            const root = document.createElement('div');
            document.body.append(root);
            mount(root, ['advanced', 'compare']);
            await settle();
            const compares = () => fake.sent.filter(each => each.key === 'POST /api/crd/compare').map(each => each.body);

            // An empty box is not sent; the field says why.
            await press(root, 'Compare with PAVE\\'s request');
            say(['empty', compares().length, root.querySelectorAll('.field--error').map(each => each.querySelector('.field__error').textContent)]);

            // The request is read from a file through files.js, into the box; a file that is not UTF-8 is refused and the input let go.
            const file = root.querySelectorAll('input').find(each => each.name === 'file');
            file.files = [new File([new Uint8Array([0x7b, 0xff, 0xfe, 0x7d])], 'cp1252.json')];
            file.value = 'C:/fakepath/cp1252.json';
            await press(root, 'Read the file');
            say(['refused file', file.value, root.querySelectorAll('p').map(text).filter(line => line.includes('cp1252.json')).length > 0,
                String(root.querySelector('textarea').value)]);
            file.files = [new File([new TextEncoder().encode(yours)], 'works.json')];
            await press(root, 'Read the file');
            say(['read', root.querySelector('textarea').value === yours, root.querySelectorAll('p').map(text).filter(line => line.startsWith('Read works.json'))]);

            // The case typed is sent with the request, blank fields left out; the comparison is sent once a press.
            root.querySelectorAll('select').find(each => each.name === 'hook').value = 'order-select';
            root.querySelectorAll('input').find(each => each.name === 'memberId').value = ' S123456789 ';
            root.querySelectorAll('input').find(each => each.name === 'procedureCodes').value = 'E0601, ,E0470';
            fake.answers['POST /api/crd/compare'] = {status: 200, body: comparison};
            await press(root, 'Compare with PAVE\\'s request');
            say(['sent', compares()]);

            // Differences are drawn as the server words them, group by group, the uncounted one too, with both values in words.
            const shown = text(root);
            say(['headline', shown.includes(comparison.headline), shown.includes(comparison.caveat), shown.split('A note the server makes once.').length - 1]);
            say(['differences', root.querySelectorAll('[data-diff]').map(each => each.querySelector('p').textContent)]);
            say(['values', root.querySelectorAll('[data-diff]').map(each => each.querySelectorAll('td').map(text))]);
            say(['groups', root.querySelectorAll('summary').map(text)]);

            // Both requests are drawn line by line; the lines of a difference are marked in each, the chosen one in bold.
            const panes = root.querySelectorAll('pre');
            say(['marked', panes.map(pane => pane.querySelectorAll('mark').map(text))]);
            await root.querySelectorAll('button').filter(each => text(each) === 'Show in both requests')[1].click();
            await settle();
            say(['chosen', panes.map(pane => pane.querySelectorAll('strong').map(text)),
                root.querySelectorAll('p').map(text).filter(line => line.startsWith('Difference '))]);

            // A refusal from the server is shown at its field, and the comparison below is said to answer an earlier press.
            fake.answers['POST /api/crd/compare'] = {status: 422, body: {kind: 'VALIDATION', title: 'The form has 1 problem. Nothing was compared.',
                fieldErrors: [{field: 'member.memberId', message: 'Enter the member ID as it is on the card.'}]}};
            await press(root, 'Compare with PAVE\\'s request');
            say(['refused', compares().length, root.querySelectorAll('.field--error').map(each => each.querySelector('.field__error').textContent),
                root.querySelectorAll('p').map(text).filter(line => line.startsWith('The latest Compare was refused'))]);
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"empty\",0,[\"Paste the request, or read it from a file, first.\"]]",
                "[\"refused file\",\"\",true,\"\"]",
                "[\"read\",true,[\"Read works.json into the box.\"]]",
                "[\"sent\",[{\"hook\":\"order-select\",\"rawBody\":\"{\\n  \\\"hook\\\" : \\\"order-sign\\\",\\n  \\\"context\\\" : {\\n    \\\"patientId\\\" : \\\"placeholder-patient\\\"\\n  }\\n}\",\"configEpoch\":7,\"member\":{\"memberId\":\"S123456789\"},\"procedureCodes\":[{\"code\":\"E0601\"},{\"code\":\"E0470\"}]}]]",
                "[\"headline\",true,true,1]",
                "[\"differences\",[\"1. context.userId Only in the tool's request Show in both requests\",\"2. context.patientId A different value Show in both requests\",\"3. odd X Show in both requests\"]]",
                "[\"values\",[[\"Practitioner/practitioner-1\",\"not in this request\"],[\"patient-1\",\"empty text\"],[\"null\",\"1\"]]]",
                "[\"groups\",[\"The case PAVE built its request for1 value, 0 read from your request\",\"Looked for in your request and not found1\",\"How the request is built1 difference\",\"Labels1 difference\",\"In a group this page does not know1\"]]",
                "[\"marked\",[[\"4      \\\"patientId\\\" : \\\"patient-1\\\",\",\"5      \\\"userId\\\" : \\\"Practitioner/practitioner-1\\\"\"],[\"4      \\\"patientId\\\" : \\\"placeholder-patient\\\"\"]]]",
                "[\"chosen\",[[\"4      \\\"patientId\\\" : \\\"patient-1\\\",\"],[\"4      \\\"patientId\\\" : \\\"placeholder-patient\\\"\"]],[\"Difference 2 of 3: context.patientId\"]]",
                "[\"refused\",2,[\"Enter the member ID as it is on the card.\"],[\"The latest Compare was refused, so the comparison below answers an earlier one.\"]]");
    }

    /** Pointers to lines, for a deep text, a plain one and texts that are not JSON. */
    @Test
    void theLineMapWalksWithItsOwnStack(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the line map", PRELUDE + """
            // The line map walks with its own stack: a request nested far deeper than any stack is mapped, never overflows.
            const {lineMap, pointerToken, ancestors} = await import('./js/adv-json-lines.js');
            let deep = '';
            for (let i = 0; i < 200000; i++) deep += '[';
            for (let i = 0; i < 200000; i++) deep += ']';
            say(['deep', lineMap(deep).size]);
            const text2 = '{\\n  "a/b~c" : [ 1, {\\n    "x" : null\\n  } ],\\n  "e" : { }\\n}';
            say(['map', [...lineMap(text2)].map(([pointer, range]) => pointer + ' ' + range.start + '-' + range.end)]);
            say(['not json', lineMap('{"a": 1} x').size, lineMap('[1,]').size, lineMap('').size]);
            say(['tokens', pointerToken('a/b~c'), ancestors('/context/draftOrders/entry/0')]);
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"deep\",200000]",
                "[\"map\",[\"/a~1b~0c/0 1-1\",\"/a~1b~0c/1/x 2-2\",\"/a~1b~0c/1 1-3\",\"/a~1b~0c 1-3\",\"/e 4-4\",\" 0-5\"]]",
                "[\"not json\",0,0,0]",
                "[\"tokens\",\"a~1b~0c\",[\"/context/draftOrders/entry\",\"/context/draftOrders\",\"/context\",\"\"]]");
    }

    @Test
    void theFileIsReadThroughFilesJsAndTheComparisonPostedFromOnePlace() throws IOException {
        String code = StaticSources.withoutComments(StaticSources.script("adv-compare.js"));
        assertThat(code).contains("import {readText} from './files.js';").contains("readText(chosen, {maxBytes: LARGEST})")
                .doesNotContain("FileReader").doesNotContain("FormData").doesNotContain("Storage");
        assertThat(code.split("'/api/crd/compare'", -1)).hasSize(2);
        String lines = StaticSources.withoutComments(StaticSources.script("adv-json-lines.js"));
        // No function of the line map calls itself: the walk keeps its own stack.
        assertThat(inside(StaticSources.body(lines, "function walk(text, map) {"))).doesNotContain("walk(");
        assertThat(inside(StaticSources.body(lines, "function string(reader) {"))).doesNotContain("string(reader)");
    }

    /** A function's body without its own opening line, which names it. */
    private static String inside(String function) {
        return function.substring(function.indexOf('{') + 1);
    }
}
