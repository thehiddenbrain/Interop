package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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

    /** The frame: no plain reload on a change of mode, which kept a case's address; the address carries the switch and is cleaned at start. */
    @Test
    void theFrameLoadsAPageLeftOpenAgainOntoItsTabAndTakesTheSwitchOutOfTheAddress() throws IOException {
        String app = StaticSources.withoutComments(StaticSources.script("app.js"));
        assertThat(app).doesNotContain("location.reload(");
        assertThat(app).contains("window.location.replace(noticedAddress(");
        assertThat(app).contains("window.location.replace(returnAddress(");
        assertThat(app).contains("window.history.replaceState(null, '', window.location.pathname + window.location.hash)");
        assertThat(StaticSources.read("index.html")).contains("<div class=\"mode-switch\" id=\"profile\" hidden></div>");
    }
}
