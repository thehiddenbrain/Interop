package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * The shared modules every view builds on (plan, section 9.1), run as the browser runs them, with
 * Node where it is installed and a stand-in for the document: the element helpers of dom.js, the
 * JSON view, and the page's memory of its own runs. Each holds a rule that reading the source
 * cannot: a value from a service is never markup, a fold is closed until opened, a number keeps
 * its digits, a long value is cut on screen and copied whole, an answer nested as deep as a
 * service likes is laid out without overflowing, and the page's memory of runs stays within its
 * limits and drops the oldest first.
 */
class SharedModulesFrontendTest {

    @Test
    void theElementHelpersBuildTextNeverMarkupAndRefuseWhatThePolicyRefuses(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the element helpers", """
                import './browser.js';
                import {el, chip, badge, button, fold, kv, field, setFieldError, modal, firstSentence, named, clear} from './js/dom.js';
                const say = value => console.log(JSON.stringify(value));
                for (const attrs of [{onclick: 'send()'}, {style: 'color: red'}, {attrs: {onload: 'start()'}}, {srcdoc: '<p>x</p>'}, {innerText: 'x'}]) {
                    try {
                        el('div', attrs);
                        say('built');
                    } catch (refused) {
                        say('refused');
                    }
                }
                const node = el('p', {class: ['a', null, 'b'], 'data-step': 'COVERAGE', 'aria-label': 'Step', dataset: {maxRows: 3}},
                    '<b>x</b>', 3, null, ['y', false, [' z']]);
                say([node.className, node.getAttribute('data-step'), node.getAttribute('aria-label'), node.getAttribute('data-max-rows'),
                    node.textContent, node.childNodes.length]);
                let clicks = 0;
                const send = button('Run check', () => { clicks++; }, {primary: true});
                await send.click();
                say([send.className, send.type, send.textContent, clicks, button('Preview', null, {small: true, disabled: true}).disabled]);
                say([chip('PA required', {tone: 'ACTION'}).className, chip('nothing', {tone: 'NO_ANSWER'}).className,
                    chip('x', {tone: 'purple'}).className, chip('not returned', {dashed: true}).className, chip('MOCK', {hatched: true}).className,
                    chip('81162', {mono: true, tone: 'neutral'}).className, chip('A chip text that is long enough to be cut').title,
                    chip('short').title]);
                say([badge('prod', 'prod').className, badge('MOCK', 'mock').className, badge('plain').className]);
                let drawn = 0;
                const lazy = fold({summary: 'Wire traffic', hint: 'request and response JSON', body: () => { drawn++; return el('p', null, 'the body'); }});
                say([lazy.open, drawn, lazy.querySelector('summary').textContent, lazy.className]);
                lazy.open = true;
                say([drawn, lazy.querySelector('.fold__body').textContent]);
                lazy.open = false;
                lazy.open = true;
                say(['drawn once', drawn]);
                const opened = fold({summary: 'Why', open: true, inline: true, body: ['one ', el('b', null, 'two')]});
                say([opened.open, opened.className, opened.querySelector('.fold__body').textContent]);
                const table = kv([{key: 'Covered', value: chip('covered', {tone: 'CLEAR'})}, null, {key: 'Expected', value: 'Not set'}]);
                say([table.className, table.querySelectorAll('tr').length, table.querySelectorAll('th').map(cell => cell.scope), table.textContent]);
                const input = el('input', {class: 'input'});
                const wrapper = field({label: 'Member ID', input, hint: 'As on the member card.'});
                say([input.id !== '', wrapper.querySelector('label').htmlFor === input.id, input.getAttribute('aria-describedby') === input.id + '-hint',
                    wrapper.querySelector('.field__error').hidden]);
                setFieldError(wrapper, 'Enter a member ID.');
                say([wrapper.querySelector('.field__error').textContent, wrapper.querySelector('.field__error').hidden, input.getAttribute('aria-invalid'),
                    input.getAttribute('aria-describedby') === input.id + '-hint ' + input.id + '-error', wrapper.className]);
                setFieldError(wrapper, '');
                say([wrapper.querySelector('.field__error').hidden, input.getAttribute('aria-invalid'),
                    input.getAttribute('aria-describedby') === input.id + '-hint', wrapper.className]);
                const opener = el('button', {type: 'button'}, 'Preview');
                document.body.append(opener);
                opener.focus();
                let closed = 0;
                const box = el('input', {class: 'input'});
                const dialog = modal({title: 'Request preview', body: [el('p', null, 'POST'), box],
                    footer: shown => [button('Close preview', () => shown.close())], onClose: () => { closed++; }});
                say([dialog.open, dialog.isConnected, document.activeElement === box, dialog.querySelector('.modal__title').textContent,
                    dialog.getAttribute('aria-labelledby') === dialog.querySelector('.modal__title').id]);
                await dialog.querySelector('.modal__foot button').click();
                say([dialog.open, dialog.isConnected, closed, document.activeElement === opener]);
                say(firstSentence('The service failed (HTTP 500). Check the environment, then send again.'));
                say(firstSentence('Wait 1.5 s, e.g. a retry. Then send.'));
                say(firstSentence('One sentence only.'));
                say(firstSentence(undefined));
                say([named('short'), named('x'.repeat(89)).length, named('y'.repeat(200))]);
                say(clear(el('p', null, 'a', 'b')).childNodes.length);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                // An inline handler, inline style, srcdoc and any property the helper does not list are refused.
                "\"refused\"", "\"refused\"", "\"refused\"", "\"refused\"", "\"refused\"",
                // A string that looks like a tag is the characters it is.
                "[\"a b\",\"COVERAGE\",\"Step\",\"3\",\"<b>x</b>3y z\",4]",
                "[\"btn btn--primary\",\"button\",\"Run check\",1,true]",
                // The server's tones, the dashed and hatched marks; a tone the page does not know draws a plain chip.
                "[\"chip chip--action\",\"chip chip--none\",\"chip\",\"chip chip--none\",\"chip chip--mock\",\"chip chip--neutral chip--mono\","
                        + "\"A chip text that is long enough to be cut\",null]",
                "[\"badge badge--prod\",\"badge badge--mock\",\"badge\"]",
                // A fold is closed, and a body given as a function is drawn once, when it is first opened.
                "[false,0,\"Wire trafficrequest and response JSON\",\"fold\"]",
                "[1,\"the body\"]",
                "[\"drawn once\",1]",
                "[true,\"fold fold--inline\",\"one two\"]",
                "[\"kv\",2,[\"row\",\"row\"],\"CoveredcoveredExpectedNot set\"]",
                // A field is named by its label and described by its hint, then by its error while it has one.
                "[true,true,true,true]",
                "[\"Enter a member ID.\",false,\"true\",true,\"field field--error\"]",
                "[true,null,true,\"field\"]",
                // A modal takes the focus to its first field, and gives it back when it closes.
                "[true,true,true,\"Request preview\",true]",
                "[false,false,1,true]",
                "[\"The service failed (HTTP 500).\",\"Check the environment, then send again.\"]",
                "[\"Wait 1.5 s, e.g. a retry.\",\"Then send.\"]",
                "[\"One sentence only.\",\"\"]",
                "[\"\",\"\"]",
                "[\"short\",89,\"" + "y".repeat(60) + "... (a name of 200 characters)\"]",
                "0");
    }

    @Test
    void theJsonViewKeepsWhatWasSentCutsALongValueOnScreenAndCopiesItWhole(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the JSON view", """
                import './browser.js';
                import {jsonView, layOut} from './js/json-view.js';
                const say = value => console.log(JSON.stringify(value));
                const backslash = String.fromCharCode(92);
                const newline = String.fromCharCode(10);
                const view = jsonView('{"a":1.10,"b":[],"c":{ },"d":["x",true,null],"e":"caf' + backslash + 'u00e9","f":12345678901234567890}',
                    {label: 'Response body'});
                say(view.querySelector('pre').textContent.split(newline));
                say(view.querySelector('pre').getAttribute('aria-label'));
                const long = 'Q'.repeat(5000);
                const withLong = jsonView(JSON.stringify({data: long, size: 5000}));
                say(withLong.querySelector('pre').textContent.split(newline));
                await withLong.querySelector('button').click();
                say([standIn.clipboard === ['{', '  "data": "' + long + '",', '  "size": 5000', '}'].join(newline), withLong.querySelector('.copy-status').textContent]);
                const html = '<html><body>Whitelabel Error Page</body></html>';
                const notJson = jsonView(html);
                await notJson.querySelector('button').click();
                say([notJson.querySelector('pre').textContent === html, standIn.clipboard === html]);
                say([jsonView(null).querySelector('pre').textContent, jsonView({kind: 'X', n: [1]}).querySelector('pre').textContent.split(newline)]);
                const depth = 100000;
                const laid = layOut('['.repeat(depth) + ']'.repeat(depth)).join('');
                const lines = laid.split(newline);
                const deepest = lines.reduce((most, line) => Math.max(most, line.length - line.trimStart().length), 0);
                const brackets = laid.split('').filter(character => character === '[' || character === ']').length;
                say([lines.length, deepest, brackets === 2 * depth]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                // Laid out from its own characters: 1.10 and a twenty-digit number keep their digits, an escape stays an escape.
                "[\"{\",\"  \\\"a\\\": 1.10,\",\"  \\\"b\\\": [],\",\"  \\\"c\\\": {},\",\"  \\\"d\\\": [\",\"    \\\"x\\\",\",\"    true,\",\"    null\","
                        + "\"  ],\",\"  \\\"e\\\": \\\"caf\\\\u00e9\\\",\",\"  \\\"f\\\": 12345678901234567890\",\"}\"]",
                "\"Response body\"",
                "[\"{\",\"  \\\"data\\\": \\\"" + "Q".repeat(64) + "...\\\" (5000 characters; Copy copies them whole),\",\"  \\\"size\\\": 5000\",\"}\"]",
                "[true,\"Copied.\"]",
                // A text that is not JSON is shown and copied as it is.
                "[true,true]",
                "[\"\",[\"{\",\"  \\\"kind\\\": \\\"X\\\",\",\"  \\\"n\\\": [\",\"    1\",\"  ]\",\"}\"]]",
                // Nested 100,000 deep: laid out, every bracket kept, the indentation held to 32 levels.
                "[199999,64,true]");
    }

    @Test
    void thePagesMemoryOfItsRunsKeepsTwentyAndFiftyMegabytesAndDropsTheOldestFirst(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the page's memory of its runs", """
                import {remember, recall} from './js/session-runs.js';
                const say = value => console.log(JSON.stringify(value));
                for (let i = 1; i <= 21; i++) {
                    remember({runId: 'run-' + i, verdict: {headline: 'Headline ' + i}});
                }
                say([recall('run-1'), recall('run-2').verdict.headline, recall('run-21').verdict.headline]);
                remember({runId: 'run-2', verdict: {headline: 'Made again'}});
                remember({runId: 'run-22'});
                say([recall('run-2').verdict.headline, recall('run-3')]);
                remember(null);
                remember({});
                remember({runId: ''});
                remember({runId: 42});
                say([recall(''), recall(42), recall('nothing')]);
                remember({runId: 'huge', body: 'x'.repeat(50 * 1024 * 1024 + 10)});
                say([recall('huge'), recall('run-22') !== null]);
                const twenty = 'y'.repeat(20 * 1024 * 1024);
                remember({runId: 'a', body: twenty});
                remember({runId: 'b', body: twenty});
                remember({runId: 'c', body: twenty});
                say([recall('a'), recall('b') !== null, recall('c') !== null, recall('run-22'), recall('run-2')]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                // Twenty runs: the twenty-first drops the first.
                "[null,\"Headline 2\",\"Headline 21\"]",
                // A run made again under its id replaces the one before and is the newest; the oldest goes for the next.
                "[\"Made again\",null]",
                // Nothing is kept without a run id.
                "[null,null,null]",
                // A run larger than all the room is not kept, and the others stay.
                "[null,true]",
                // Fifty megabytes: three runs of twenty drop every older run, oldest first, then the first of the three.
                "[null,true,true,null,null]");
    }
}
