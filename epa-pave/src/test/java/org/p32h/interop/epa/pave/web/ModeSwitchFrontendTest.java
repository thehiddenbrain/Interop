package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * The switch between mock and live on screen (mode-switch.js), run with Node: the control in place
 * of the badge, the one question before a switch and its words, the refusal said in the dialog, the
 * wait for the new start, and the address the page is loaded again on, which opens a new empty case
 * and carries nothing of a member.
 */
class ModeSwitchFrontendTest {

    @Test
    void theWordsOfTheQuestionTheWaitAndTheLineAfterAreTheOnesDecided(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the words of the switch", """
                import {dialogWords, switchingWords, arrivedWords, droppedWords, NO_COMEBACK} from './js/mode-switch.js';
                const say = value => console.log(JSON.stringify(value));
                say(dialogWords('live', {productionUnlock: false, pastedToken: false}));
                say(dialogWords('mock', {productionUnlock: true, pastedToken: true}).lines);
                say(dialogWords('live', {productionUnlock: true, pastedToken: false}).lines.slice(2));
                say([switchingWords('live'), switchingWords('mock')]);
                say([arrivedWords('live', 'live', {}), arrivedWords('mock', 'mock', {productionUnlock: true}),
                  arrivedWords('live', 'live', {pastedToken: true}), arrivedWords('live', 'live', {productionUnlock: true, pastedToken: true})]);
                say(arrivedWords('mock', 'live', {}));
                say([droppedWords(null), NO_COMEBACK]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "{\"title\":\"Switch to live?\",\"lines\":[\"PAVE restarts itself on live. It takes a few seconds.\",\"The case on the "
                        + "Workbench is cleared.\"],\"confirm\":\"Switch to live\",\"cancel\":\"Cancel\"}",
                "[\"PAVE restarts itself on mock. It takes a few seconds.\",\"The case on the Workbench is cleared.\",\"On mock, answers "
                        + "come from scenario files and nothing is sent.\",\"The production unlock of this browser session is dropped.\","
                        + "\"The pasted access token is dropped.\"]",
                "[\"The production unlock of this browser session is dropped.\"]",
                "[\"Switching to live...\",\"Switching to mock...\"]",
                "[\"Now on live.\",\"Now on mock. The production unlock was dropped.\",\"Now on live. The pasted access token was dropped.\","
                        + "\"Now on live. The production unlock and the pasted access token were dropped.\"]",
                "\"PAVE could not start on mock and is back on live. Its console says why.\"",
                "[\"\",\"PAVE did not come back. Look at its console, then start it again.\"]");
    }

    @Test
    void theControlShowsTheModeInForceAsTheBadgeAndOffersOnlyTheOther(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the control", """
                import './browser.js';
                import {drawControl} from './js/mode-switch.js';
                import {el} from './js/dom.js';
                const say = value => console.log(JSON.stringify(value));
                const chosen = [];
                const host = el('div', {id: 'profile', hidden: true});
                document.body.append(host);
                const parts = () => host.querySelectorAll('button').map(b => [b.textContent, b.getAttribute('aria-pressed'), b.className, b.title,
                  Boolean(b.disabled)]);
                drawControl(host, {mode: 'mock'}, {onChoose: to => chosen.push(to)});
                say([host.hidden, host.getAttribute('role'), host.getAttribute('aria-label')]);
                say(parts());
                await host.querySelector('button[data-mode="mock"]').click();
                await host.querySelector('button[data-mode="live"]').click();
                say(chosen);
                drawControl(host, {mode: 'live'}, {onChoose: to => chosen.push(to)});
                say(parts());
                await host.querySelector('button[data-mode="mock"]').click();
                say(chosen);
                // While a switch is on its way, neither part asks for another.
                drawControl(host, {mode: 'live'}, {onChoose: to => chosen.push(to), busy: true});
                say(parts().map(part => part[4]));
                await host.querySelector('button[data-mode="mock"]').click();
                say(chosen);
                // A profile this page does not know is named as the server names it and offers no switch.
                drawControl(host, {mode: 'other'}, {onChoose: to => chosen.push(to)});
                say([host.querySelectorAll('button').length, host.textContent]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[false,\"group\",\"Profile: mock or live\"]",
                "[[\"MOCK\",\"true\",\"mode-switch__part badge badge--mock\",\"Mock profile: scenarios answer and nothing leaves this "
                        + "machine.\",false],[\"LIVE\",\"false\",\"mode-switch__part mode-switch__other\",\"Switch to live: PAVE restarts "
                        + "itself on live.\",false]]",
                "[\"live\"]",
                "[[\"MOCK\",\"false\",\"mode-switch__part mode-switch__other\",\"Switch to mock: PAVE restarts itself on mock.\",false],"
                        + "[\"LIVE\",\"true\",\"mode-switch__part badge badge--live\",\"Live profile: calls go to the active environment's "
                        + "service.\",false]]",
                "[\"live\",\"mock\"]",
                "[true,true]",
                "[\"live\",\"mock\"]",
                "[0,\"other\"]");
    }

    @Test
    void theDialogAsksFirstSaysARefusalInItsPlaceAndCancelChangesNothing(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the dialog", """
                import './browser.js';
                import {askToSwitch} from './js/mode-switch.js';
                const say = value => console.log(JSON.stringify(value));
                const posted = [];
                const accepted = [];
                let answer = {ok: false, status: 409, error: {kind: 'VALIDATION', title: 'A suite is still running, so PAVE did not switch.',
                  action: 'Wait for the suite to finish, or stop it in Audit > Suites, then switch.'}};
                const call = async (method, path, options) => { posted.push([method, path, options.body]); return answer; };
                const open = () => document.querySelector('dialog.modal');
                const press = words => open().querySelectorAll('button').find(b => b.textContent === words).click();
                // Cancel: nothing is sent.
                askToSwitch('live', {productionUnlock: true, pastedToken: false}, {call, onAccepted: data => accepted.push(data)});
                say([open().open, open().classList.contains('modal--small'), open().querySelector('.modal__title').textContent,
                  open().querySelectorAll('.mode-switch__words p').map(p => p.textContent)]);
                await press('Cancel');
                say([open(), posted.length]);
                // Refused: said in the dialog, which stays open, and nothing is accepted.
                askToSwitch('live', {}, {call, onAccepted: data => accepted.push(data)});
                await press('Switch to live');
                say([open().open, open().querySelector('.mode-switch__refusal').textContent, posted, accepted.length,
                  open().querySelectorAll('button').map(b => Boolean(b.disabled))]);
                // No answer at all.
                answer = {ok: false, status: 0, error: {kind: 'UNREACHABLE'}};
                await press('Switch to live');
                say(open().querySelector('.mode-switch__refusal').textContent);
                // Accepted: the dialog closes and the answer is handed on.
                answer = {ok: true, status: 202, data: {mode: 'mock', switchingTo: 'live', startedAt: 'a', held: {productionUnlock: false, pastedToken: false}}};
                await press('Switch to live');
                say([open(), accepted]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[true,true,\"Switch to live?\",[\"PAVE restarts itself on live. It takes a few seconds.\",\"The case on the Workbench is "
                        + "cleared.\",\"The production unlock of this browser session is dropped.\"]]",
                "[null,0]",
                "[true,\"A suite is still running, so PAVE did not switch. Wait for the suite to finish, or stop it in Audit > Suites, then "
                        + "switch.\",[[\"POST\",\"/api/mode\",{\"mode\":\"live\"}]],0,[false,false]]",
                "\"PAVE is not answering. Is it still running?\"",
                "[null,[{\"mode\":\"mock\",\"switchingTo\":\"live\",\"startedAt\":\"a\",\"held\":{\"productionUnlock\":false,"
                        + "\"pastedToken\":false}}]]");
    }

    @Test
    void thePageWaitsForANewStartAndTellsArrivedFromBackFromGone(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the wait", """
                import {waitForStart, outcomeOf, COME_BACK_MILLIS} from './js/mode-switch.js';
                const say = value => console.log(JSON.stringify(value));
                const before = {mode: 'mock', startedAt: '2026-11-22T10:00:00Z'};
                const at = (mode, startedAt, more) => ({ok: true, status: 200, data: Object.assign({mode, startedAt}, more || {})});
                say([outcomeOf(before, {ok: false, error: {kind: 'UNREACHABLE'}}, 'live'), outcomeOf(before, at('mock', before.startedAt), 'live'),
                  outcomeOf(before, at('mock', before.startedAt, {switchingTo: 'live'}), 'live'), outcomeOf(before, at('live', 'x', {switchingTo: 'mock'}), 'live'),
                  outcomeOf(before, at('live', '2026-11-22T10:00:05Z'), 'live'), outcomeOf(before, at('mock', '2026-11-22T10:00:05Z'), 'live'),
                  outcomeOf(before, at('live', ''), 'live')]);
                // A clock the test moves: every pause moves it by its length.
                const clock = () => {
                  let t = 0;
                  return {now: () => t, pause: async millis => { t += millis; }};
                };
                const sequence = answers => { let i = 0; return async () => answers[Math.min(i++, answers.length - 1)]; };
                let c = clock();
                say(await waitForStart(before, 'live', {call: sequence([{ok: false, error: {kind: 'UNREACHABLE'}}, at('mock', before.startedAt),
                  at('live', 'later')]), pause: c.pause, now: c.now}));
                c = clock();
                say((await waitForStart(before, 'live', {call: sequence([at('mock', 'later')]), pause: c.pause, now: c.now})).outcome);
                c = clock();
                say([(await waitForStart(before, 'live', {call: sequence([{ok: false, error: {kind: 'UNREACHABLE'}}]), pause: c.pause, now: c.now})).outcome,
                  c.now() >= COME_BACK_MILLIS]);
                // A question that is never answered still ends the wait within the minute.
                c = clock();
                say((await waitForStart(before, 'live', {call: () => new Promise(() => {}), pause: c.pause, now: c.now})).outcome);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"waiting\",\"waiting\",\"waiting\",\"waiting\",\"arrived\",\"back\",\"waiting\"]",
                "{\"outcome\":\"arrived\",\"view\":{\"mode\":\"live\",\"startedAt\":\"later\"}}",
                "\"back\"",
                "[\"gone\",true]",
                "\"gone\"");
    }

    /** The page is loaded again on its tab alone, so the Workbench opens on a new empty case; the address carries the mode and what was dropped, nothing else. */
    @Test
    void theAddressAfterASwitchOpensANewCaseAndCarriesNothingOfAMember(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the address after a switch", """
                import {returnAddress, readReturn, carriesReturn} from './js/mode-switch.js';
                const say = value => console.log(JSON.stringify(value));
                const none = {productionUnlock: false, pastedToken: false};
                say([returnAddress('/', 'workbench', 'live', none), returnAddress('/', 'audit', 'mock', {productionUnlock: true, pastedToken: true}),
                  returnAddress('/pave/', 'configuration', 'live', {pastedToken: true}), returnAddress('/', null, 'live', none)]);
                const search = returnAddress('/', 'workbench', 'mock', {productionUnlock: true}).split('#')[0].split('?')[1];
                say([readReturn('?' + search), carriesReturn('?' + search)]);
                // What this page did not write is not read.
                say([readReturn(''), readReturn('?pave-now=prod'), readReturn('?other=1'), carriesReturn('?other=1'), carriesReturn('')]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"/?pave-now=live#workbench\",\"/?pave-now=mock&pave-dropped=unlock%2Ctoken#audit\",\"/pave/?pave-now=live&pave-dropped=token"
                        + "#configuration\",\"/?pave-now=live#workbench\"]",
                "[{\"wanted\":\"mock\",\"held\":{\"productionUnlock\":true,\"pastedToken\":false}},true]",
                "[null,null,null,false,false]");
    }

    /**
     * The frame itself (app.js) on the page that asks: the line said after a switch is read from the
     * address and taken out of it before a view writes the address; the question is asked with what
     * is held read afresh; while PAVE switches, the page shows the waiting state that ignores Escape,
     * disables the control, says nothing of PAVE not answering and keeps its own polls back; once the
     * new start answers, the page is loaded again onto its tab with what was dropped.
     */
    @Test
    void theFrameAsksWaitsWithoutSayingPaveIsDownAndLoadsTheTabAgainOnTheNewMode(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("frame.js"), FRAME, StandardCharsets.UTF_8);
        NodeRun run = StaticSources.runModule(folder, "the frame on the page that asks", """
                import {frame} from './frame.js';
                const say = value => console.log(JSON.stringify(value));
                const page = frame('/?pave-now=live&pave-dropped=unlock#history');
                await page.answer({mode: 'live', startedAt: 's1', held: {productionUnlock: false, pastedToken: true}});
                await page.start();
                say([page.replaced, window.location.search, window.location.hash, page.notice(), page.parts()]);
                // The tester presses MOCK: what is held is read afresh, then the one question.
                await document.getElementById('profile').querySelector('button[data-mode="mock"]').click();
                await page.answer({mode: 'live', startedAt: 's1', held: {productionUnlock: false, pastedToken: true}});
                say([page.dialog().querySelector('.modal__title').textContent, page.dialog().querySelectorAll('.mode-switch__words p').map(p => p.textContent)]);
                page.postAnswer = {status: 202, body: {mode: 'live', startedAt: 's1', switchingTo: 'mock', held: {productionUnlock: false, pastedToken: true}}};
                await page.dialog().querySelectorAll('button').find(b => b.textContent === 'Switch to mock').click();
                await page.settle();
                let prevented = false;
                await page.switching().dispatchEvent({type: 'cancel', preventDefault() { prevented = true; }});
                say([page.posted, page.switching().open, page.switching().textContent, prevented, page.parts().map(part => part[2])]);
                // PAVE is down while it switches: that is not said as PAVE not answering, and the frame's polls wait.
                const health = page.health;
                await page.answer('down');
                await page.fire('focus');
                await page.answer({mode: 'live', startedAt: 's1', switchingTo: 'mock'});
                say([page.notice(), page.health - health, page.loadedOn]);
                await page.answer({mode: 'mock', startedAt: 's2', held: {productionUnlock: false, pastedToken: false}});
                say([page.loadedOn, Object.keys(page.storage)]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[[\"/#history\",\"#audit\"],\"\",\"#audit\",\"Now on live. The production unlock was dropped.\",[[\"MOCK\",\"false\",false],"
                        + "[\"LIVE\",\"true\",false]]]",
                "[\"Switch to mock?\",[\"PAVE restarts itself on mock. It takes a few seconds.\",\"The case on the Workbench is cleared.\","
                        + "\"On mock, answers come from scenario files and nothing is sent.\",\"The pasted access token is dropped.\"]]",
                "[[{\"mode\":\"mock\"}],true,\"Switching to mock...\",true,[true,true]]",
                "[\"Now on live. The production unlock was dropped.\",0,[]]",
                "[[\"/?pave-now=mock&pave-dropped=token#audit\"],[\"pave.tab\"]]");
    }

    /**
     * The frame on a page left open: it sees a switch another page asked for and waits for it the
     * same way; when nothing answers within the minute it says so and polls again; when PAVE is then
     * found on the other mode, the page is loaded again onto its tab, never on its own address.
     */
    @Test
    void theFrameOnAPageLeftOpenFollowsASwitchSaysWhenNothingCameBackAndNoticesTheOtherMode(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("frame.js"), FRAME, StandardCharsets.UTF_8);
        NodeRun run = StaticSources.runModule(folder, "the frame on a page left open", """
                import {frame} from './frame.js';
                const say = value => console.log(JSON.stringify(value));
                const page = frame('/#workbench');
                await page.answer({mode: 'mock', startedAt: 's1', held: {productionUnlock: true, pastedToken: false}});
                await page.start();
                say([page.replaced, page.notice(), page.parts()]);
                // Another page asked for a switch: this one sees it under way and waits for it as well.
                await page.fire('focus');
                await page.answer({mode: 'mock', startedAt: 's1', switchingTo: 'live', held: {productionUnlock: true, pastedToken: false}});
                say([Boolean(page.switching()), page.switching() && page.switching().textContent, page.parts().map(part => part[2])]);
                const health = page.health;
                await page.answer('down');
                await page.fire('focus');
                say([page.notice(), page.health - health]);
                // Nothing answers within the minute.
                for (let i = 0; i < 8 && page.switching(); i++) {
                  await page.expire();
                }
                say([page.switching(), page.notice(), page.parts().map(part => part[2]), page.loadedOn]);
                await page.drain({mode: 'mock', startedAt: 's1', held: {productionUnlock: true, pastedToken: false}});
                // It comes back later on live, started by hand: the page is loaded again onto its tab and says so.
                await page.fire('focus');
                await page.answer({mode: 'live', startedAt: 's3', held: {productionUnlock: false, pastedToken: false}});
                say(page.loadedOn);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[[],\"\",[[\"MOCK\",\"true\",false],[\"LIVE\",\"false\",false]]]",
                "[true,\"Switching to live...\",[true,true]]",
                "[\"\",0]",
                "[null,\"PAVE did not come back. Look at its console, then start it again.\",[false,false],[]]",
                "[\"/?pave-now=live&pave-dropped=unlock#workbench\"]");
    }

    /** The frame: no plain reload on a change of mode, which kept a case's address. */
    @Test
    void theFrameNeverReloadsItsOwnAddressAndHasThePlaceOfTheControl() throws IOException {
        String app = StaticSources.withoutComments(StaticSources.script("app.js"));
        assertThat(app).doesNotContain("location.reload(");
        assertThat(StaticSources.read("index.html")).contains("<div class=\"mode-switch\" id=\"profile\" hidden></div>");
    }

    /**
     * A stand-in for the window around app.js, written beside the scripts as frame.js: an address, a
     * history, storage, listeners, timers on a clock the script moves, and a server whose answers to
     * GET /api/mode the script hands out one at a time, so a question can stay open as on a PAVE
     * that is stopping. The three views are replaced by ones that only name themselves.
     */
    private static final String FRAME = """
            // A stand-in for the window around app.js: an address, a history, storage, listeners, timers on a
            // clock the script moves, and a server whose answers to GET /api/mode the script hands out one by one.
            import './browser.js';
            import {el} from './js/dom.js';
            import fs from 'node:fs';

            for (const view of ['workbench', 'audit', 'configuration']) {
              fs.writeFileSync('./js/' + view + '.js', "export function mount(root) { root.textContent = 'view " + view + "'; return {}; }");
            }

            export function frame(address) {
              const url = new URL(address, 'http://localhost');
              const listeners = {};
              const long = [];
              const t = {now: 0};
              const page = {replaced: [], loadedOn: [], storage: {}, health: 0, posted: []};
              const place = to => {
                const next = new URL(to, 'http://localhost' + window.location.pathname + window.location.search);
                window.location.pathname = next.pathname;
                window.location.search = next.search;
                window.location.hash = next.hash;
              };
              globalThis.window = {
                location: {pathname: url.pathname, search: url.search, hash: url.hash, replace: to => page.loadedOn.push(to)},
                history: {replaceState: (s, title, to) => { page.replaced.push(to); place(to); }, pushState: (s, title, to) => place(to)},
                localStorage: {getItem: k => (k in page.storage ? page.storage[k] : null), setItem: (k, v) => { page.storage[k] = String(v); }},
                addEventListener: (type, fn) => { (listeners[type] = listeners[type] || []).push(fn); },
                setInterval: () => 0,
                // A pause of a second or less passes at once; a longer one only when the script lets time go.
                setTimeout: (fn, millis) => {
                  const due = t.now + millis;
                  if (millis <= 1000) {
                    setTimeout(() => { t.now = Math.max(t.now, due); fn(); }, 1);
                  } else {
                    long.push({due, fn});
                  }
                  return 0;
                },
                scrollTo: () => {}
              };
              Date.now = () => t.now;
              document.hidden = false;
              document.title = '';
              document.addEventListener = (type, fn) => { (listeners['document:' + type] = listeners['document:' + type] || []).push(fn); };
              const nav = el('nav', {id: 'tabs'}, ['workbench', 'audit', 'configuration'].map(tab => el('a', {class: 'tabs__tab', 'data-tab': tab}, tab)));
              document.body.append(el('header', {id: 'topbar'}, el('span', {id: 'tool-name'}), nav, el('span', {id: 'audit-count'}),
                el('select', {id: 'environment'}), el('span', {id: 'kind'}), el('div', {class: 'mode-switch', id: 'profile', hidden: true}),
                el('span', {id: 'sign-in'}), el('span', {id: 'stamp'})),
                el('div', {id: 'line-environment'}), el('div', {id: 'line-ledger'}), el('div', {id: 'line-files'}), el('div', {id: 'line-notice'}),
                el('p', {id: 'frame-news'}), el('main', {id: 'main'}));
              const waiting = [];
              const ready = [];
              const reply = answer => answer === 'down' ? Promise.reject(new TypeError('Failed to fetch'))
                : Promise.resolve({ok: answer.status < 300, status: answer.status, text: async () => JSON.stringify(answer.body)});
              globalThis.fetch = (address, request) => {
                if (address === '/api/runs/health') {
                  page.health += 1;
                  return reply({status: 200, body: {enabled: true, writable: true, runsToday: 0}});
                }
                if (address === '/api/config/environments') {
                  return reply({status: 200, body: {environments: []}});
                }
                if (address === '/api/mode' && request.method === 'POST') {
                  page.posted.push(JSON.parse(request.body));
                  return reply(page.postAnswer);
                }
                if (address === '/api/mode') {
                  if (ready.length > 0) {
                    return reply(ready.shift());
                  }
                  return new Promise(resolve => waiting.push(resolve)).then(reply);
                }
                return reply({status: 404, body: {kind: 'NOT_FOUND', title: 'No such address.'}});
              };
              const settle = () => new Promise(resolve => setTimeout(resolve, 30));
              return Object.assign(page, {
                settle,
                /** The next GET /api/mode is answered so: a view of /api/mode, or 'down' for no answer at all. */
                answer(answer) {
                  const given = answer === 'down' ? 'down' : {status: 200, body: answer};
                  if (waiting.length > 0) {
                    waiting.shift()(given);
                  } else {
                    ready.push(given);
                  }
                  return settle();
                },
                /** Every question still open gets this answer. */
                drain(answer) {
                  while (waiting.length > 0) {
                    waiting.shift()({status: 200, body: answer});
                  }
                  return settle();
                },
                /** Time goes on until every longer pause has passed. */
                async expire() {
                  for (const timer of long.splice(0).sort((a, b) => a.due - b.due)) {
                    t.now = Math.max(t.now, timer.due);
                    timer.fn();
                  }
                  await settle();
                },
                fire(type) {
                  for (const fn of listeners[type] || []) {
                    fn({type});
                  }
                  return settle();
                },
                notice: () => document.getElementById('line-notice').textContent.replace('Dismiss this notice', ''),
                parts: () => document.getElementById('profile').querySelectorAll('button').map(b => [b.textContent, b.getAttribute('aria-pressed'), Boolean(b.disabled)]),
                switching: () => document.querySelector('dialog.modal--switching'),
                dialog: () => document.querySelector('dialog.modal'),
                start: () => import('./js/app.js').then(settle)
              });
            }
            """;
}
