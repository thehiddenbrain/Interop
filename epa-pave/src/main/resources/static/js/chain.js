// The chain: one case followed through CRD, DTR and PAS under one id.
//
// The server owns the chain. A run sent with the chain id "new" starts one and returns its id;
// every later run of the case sends that id. The chain is then read back from the ledger with
// GET /api/chains/{chainId}, and everything shown here is what that answer says in its own words:
// where each stage stands, why a stage did not run, and what the next step is. This page keeps
// one thing only, the id of the chain the case on screen belongs to, and forgets it on reload.

import * as api from './api.js';
import {el, fill, clear, button, code, facts, tableRow, heading, copyButton, millis, when, token} from './dom.js';
import {outcome, stamp, slowFlag, toneMark} from './marks.js';
import {errorPanel, expectedOnMockKind} from './result.js';
import {runLink, chainRunsLink} from './history-common.js';

const PROTOCOL_TABS = ['crd', 'dtr', 'pas'];

/**
 * The runs of a chain and the time they took, said so that the total never reads as covering a run
 * nobody timed: a step done inside this tool is not measured, and the server's total adds only the
 * runs that were. runCount: the runs of the chain; measured: how many of them carry a time; took:
 * the server's total, as text.
 */
export function chainTotal(runCount, measured, took) {
    const runs = String(runCount) + (runCount === 1 ? ' run' : ' runs');
    if (measured >= runCount) {
        return runs + ' taking ' + took + ' in all';
    }
    if (measured <= 0) {
        return runs + (runCount === 1 ? ', not measured' : ', none of them measured');
    }
    return runs + ', ' + measured + ' of them measured, taking ' + took + ' in all';
}

/** How many runs of the chain, in its stages and outside them, carry a time the server measured. */
function measuredRuns(chain) {
    const runs = (chain.stages || []).flatMap(stage => stage.runs || []).concat(chain.otherRuns || []);
    return runs.filter(run => typeof run.elapsedMillis === 'number').length;
}

/**
 * The case a chain is for, as one text: the member ID with every space removed, the NPI trimmed and
 * the codes sorted, so the same case typed again, or with its codes added in another order, is the
 * same case. Held in page memory only, never stored and never logged.
 */
export function caseKey(summary) {
    const given = summary || {};
    const codes = (given.codes || []).map(value => String(value)).sort();
    return [String(given.memberId || '').split(' ').join(''), String(given.npi || '').trim(), codes.join(',')].join('|');
}

/** The case of the chain in one line of text, the title of the bar's case when it is cut short. */
function caseLine(view) {
    const codes = view.procedureCodes && view.procedureCodes.length > 0 ? ', ' + view.procedureCodes.join(', ') : '';
    return 'for ' + (view.member || 'a member the ledger does not show') + codes;
}

export function createChain(app, bar) {
    let chainId = null;       // the chain the case on screen belongs to, once a run has started one
    let view = null;          // the last answer of GET /api/chains/{chainId}
    let showing = false;
    // The case of the chain on screen, and the case of the latest request built with a chain value.
    // Page memory only, gone on reload as the chain id is; never stored, never logged.
    let chainKey = null;
    let sentKey = null;

    // On when the page opens: a case followed through CRD, DTR and PAS is then one journey in
    // History without the tester having to know to ask for it first.
    const follow = el('input', {type: 'checkbox', id: 'chain-follow', checked: true});
    const control = el('div', {class: 'case-group'},
        el('label', {class: 'choice', htmlFor: 'chain-follow'}, follow, 'Run as part of a chain'),
        el('span', {class: 'field__hint'}, 'On unless you untick it: a case followed through CRD, DTR and PAS is one journey in History. '
            + 'Another case starts another chain.'));
    follow.addEventListener('change', draw);

    async function refresh() {
        if (!chainId) {
            view = null;
            draw();
            return;
        }
        const asked = chainId;
        const answer = await api.get('/api/chains/' + encodeURIComponent(asked));
        if (chainId !== asked) {
            // The case moved on while the answer was on its way. It is about a chain no longer on screen.
            return;
        }
        view = answer.kind === 'ok' ? answer.body : {problem: answer.body};
        draw();
    }

    function draw() {
        // No bar before a chain exists: with the box on by default, a bar saying "not started" would sit on every tab from the first load.
        const wanted = showing && Boolean(chainId);
        bar.hidden = !wanted;
        if (!wanted) {
            clear(bar);
            return;
        }
        if (!view) {
            fill(bar, el('div', {class: 'chain-bar chain-bar--line'}, el('p', {class: 'chain-bar__lead'}, 'Reading the chain from the ledger.')));
            return;
        }
        if (view.problem) {
            fill(bar, el('div', {class: 'chain-bar'}, errorPanel(view.problem), actions()));
            return;
        }
        // One line: the result's own Next step row says what to do, so the bar does not say it again.
        fill(bar, el('div', {class: 'chain-bar chain-bar--line'},
            el('ol', {class: 'chain-stages'}, (view.stages || []).map(stage => el('li', {class: 'chain-stage chain-stage--' + token(stage.state)},
                el('span', {class: 'chain-stage__name'}, stage.name),
                el('span', {class: 'chain-stage__state', title: stage.stateLine || ''}, stage.latest ? [toneMark(stage.latest.tone, stage.latest.answeredBy), ' '] : null,
                    stateParts(stage.stateLine))))),
            // Whose journey this is, as the ledger shows it, so a case changed on screen is not taken for the case of the chain.
            // Set without the break points code() adds, which would break the one line even where wrapping is off.
            el('span', {class: 'chain-bar__case', title: caseLine(view)}, el('span', {class: 'quiet'}, 'for '),
                view.member ? el('code', {class: 'literal'}, view.member) : 'a member the ledger does not show',
                view.procedureCodes && view.procedureCodes.length > 0
                    ? [', ', view.procedureCodes.map((value, index) => [index > 0 ? ', ' : null, code(value)])] : null),
            follow.checked ? null : el('span', {class: 'chain-bar__paused'}, 'Paused: runs sent now join no chain.'),
            actions()));
    }

    /**
     * The server's line starts with where the stage stands ("Done", "Next", "Not reached", "In progress", "Needs attention")
     * before its first colon. The bar shows that word whole and no fragment of the rest, which was cut to a few letters at
     * every width; the rest is read out to a screen reader, is the title, and is whole in the journey on the History tab.
     */
    function stateParts(line) {
        const text = String(line || '');
        const colon = text.indexOf(': ');
        if (colon < 0 || colon > 24) {
            return el('span', {class: 'chain-stage__rest'}, text);
        }
        return [el('span', {class: 'chain-stage__word'}, text.slice(0, colon)),
            el('span', {class: 'visually-hidden'}, ': ' + text.slice(colon + 2))];
    }

    /**
     * Where the closed chain is found again. A link opens it whatever the Chains list is set to show: the list leaves out a
     * chain of one run by default, and a closed chain is often one.
     */
    function keptIn(closed) {
        return ['The ledger keeps it: ', el('a', {href: '#history/chains/' + encodeURIComponent(closed)}, 'open it in History'), '.'];
    }

    function actions() {
        return el('div', {class: 'button-row chain-bar__actions'},
            button('Open the whole journey', () => app.navigate('history/chains/' + chainId), {class: 'button--quiet button--small'}),
            button('Start a new case', startNewCase, {class: 'button--quiet button--small'}));
    }

    function startNewCase() {
        const closed = chainId;
        chainId = null;
        chainKey = null;
        view = null;
        // What the closed case carried to its next step belongs to that case and goes with it.
        app.forgetCarried();
        draw();
        app.notice(['The chain ' + closed + ' is closed in this page. ', keptIn(closed), ' The next run from this case starts a new chain.'],
            {plain: true});
    }

    return {
        /** The control that turns chain following on. It belongs with the case, so every protocol tab shows it. */
        control,

        /**
         * What to send as chainId with the next run: nothing, "new" to start a chain, or the id of
         * the chain on screen when the case is that chain's case. It changes nothing on screen: a
         * preview calls it too, and a send can be refused after it, so the chain on screen changes
         * only when a result arrives (onResult).
         */
        requestValue() {
            if (!follow.checked) {
                return undefined;
            }
            const key = caseKey(app.caseContext.summary());
            sentKey = key;
            return chainId && key === chainKey ? chainId : 'new';
        },

        /**
         * Call with every run result. A result that names a chain makes that chain the one on
         * screen, and when it replaces another the page says so: a run was recorded under a new
         * chain, so the sentence is true when it is given.
         */
        onResult(envelope) {
            if (envelope && envelope.chainId) {
                const closed = chainId && envelope.chainId !== chainId ? chainId : null;
                if (envelope.chainId !== chainId) {
                    view = null;
                }
                chainId = envelope.chainId;
                chainKey = sentKey;
                if (closed) {
                    app.notice(['This run started a new chain, because its case is not the case of chain ' + closed + '. ', keptIn(closed)],
                        {plain: true});
                }
                refresh();
            }
        },

        /** The id of the chain on screen, or null. */
        id: () => chainId,

        refresh,

        /** The bar is shown on the CRD, DTR and PAS tabs only. */
        showFor(tabId) {
            showing = PROTOCOL_TABS.includes(tabId);
            draw();
        }
    };
}

/**
 * The whole journey of one chain on one screen: each stage with its runs, each run with its
 * outcome, its test result, its elapsed time and whether it has been run again since. A stage
 * that did not run says why, in the server's words.
 */
export function chainView(chain) {
    const node = el('article', {class: 'chain-view'});
    node.append(
        el('h3', {class: 'chain-view__headline'}, chain.headline),
        el('div', {class: 'facts-pair'},
            facts([
                ['Next step', chain.nextHeadline ? el('strong', null, chain.nextHeadline) : 'The latest run did not state one.'],
                ['Journey', chain.ended ? 'Ended: the latest run said the journey is complete.' : 'Open: the latest run did not end the journey.'],
                ['Chain', [code(chain.chainId), ' ', copyButton('Copy chain id', () => chain.chainId)]],
                // As the ledger shows it: the member ID when recording it is on, otherwise a token, which is not the ID.
                ['Member, as recorded', chain.member ? code(chain.member) : el('span', {class: 'note--none'}, 'not stated')],
                ['Its runs', chainRunsLink(chain.chainId, 'List them in Runs')]
            ], 'facts--ruled'),
            facts([
                ['Procedure codes', chain.procedureCodes && chain.procedureCodes.length > 0
                    ? chain.procedureCodes.map((value, index) => [index > 0 ? ', ' : null, code(value)])
                    : el('span', {class: 'note--none'}, 'none recorded')],
                ['Ran against', [chain.environment ? chain.environment + (chain.environmentKind ? ' (' + chain.environmentKind + ')' : '') : 'no environment',
                    ', ', chain.profile, ' profile, target ', chain.target]],
                ['Started', when(chain.startedAt)],
                ['Latest run', [when(chain.lastRunAt), ', ', chainTotal(chain.runCount, measuredRuns(chain), millis(chain.elapsedMillis))]]
            ], 'facts--ruled')));
    if (chain.notes && chain.notes.length > 0) {
        node.append(el('ul', {class: 'note-list'}, chain.notes.map(note => el('li', null, note))));
    }
    for (const stage of chain.stages || []) {
        node.append(stageSection(stage));
    }
    if (chain.otherRuns && chain.otherRuns.length > 0) {
        node.append(el('section', {class: 'chain-view__stage'},
            heading(4, 'Other runs in this chain', chain.otherRuns.length), runTable(chain.otherRuns)));
    }
    return node;
}

function stageSection(stage) {
    const runs = stage.runs && stage.runs.length > 0 ? stage.runs : (stage.latest ? [stage.latest] : []);
    return el('section', {class: 'chain-view__stage chain-stage--' + token(stage.state)},
        el('h4', {class: 'chain-view__stage-name'}, stage.name),
        el('p', {class: 'chain-view__stage-state'}, stage.stateLine),
        runs.length > 0 ? runTable(runs) : null);
}

/**
 * The runs of a stage. Each run takes two lines: what it produced, then what ties it to the
 * ledger and what was found on it. The identifiers and the kinds of finding are long, and a
 * column each would squeeze the others out of a pane this narrow.
 */
function runTable(runs) {
    const none = text => el('span', {class: 'note--none'}, text);
    const headings = [{text: 'When', class: 'col-17'}, {text: 'Ran', class: 'col-13'}, 'Outcome', {text: 'Expected', class: 'col-19'},
        {text: 'Test result', class: 'col-stamp'}, {text: 'Elapsed', class: 'num col-time'}];
    const body = el('tbody');
    for (const run of runs) {
        body.append(
            tableRow({class: 'chain-run', cells: [
                when(run.timestamp),
                run.what,
                outcome(run.outcomeLabel, run.tone, run.answeredBy),
                run.expected ? code(run.expected) : none('nothing declared'),
                stamp(run.result),
                {class: 'num', node: [millis(run.elapsedMillis) || none('not measured'), run.slow ? el('div', null, slowFlag()) : null]}
            ]}),
            tableRow({class: 'chain-run__more', cells: [{colSpan: headings.length, node: [
                run.latestOfItsKind === false ? [el('span', {class: 'flag'}, 'run again since'), ' '] : null,
                el('span', {class: 'quiet small'}, 'Run '), runLink(run.runId), ' ', copyButton('Copy', () => run.runId),
                el('span', {class: 'quiet small'}, typeof run.httpStatus === 'number' ? ' HTTP ' + run.httpStatus : ' no HTTP status',
                    run.scenarioId ? [', scenario ', code(run.scenarioId)] : null, '.'),
                findingsLine(run)
            ]}]}));
    }
    return el('table', {class: 'grid chain-runs'},
        el('thead', null, el('tr', null, headings.map(item => (typeof item === 'string'
            ? el('th', {scope: 'col'}, item) : el('th', {scope: 'col', class: item.class}, item.text))))),
        body);
}

/**
 * The kinds of finding a run of the chain raised, every one listed. A chain run carries who answered
 * it rather than the profile, and only a scenario answers on mock, so the two kinds every scenario run
 * raises are said apart from the others when a scenario answered.
 */
function findingsLine(run) {
    const profile = run.answeredBy === 'SCENARIO' ? 'mock' : 'live';
    const kinds = run.findings || [];
    const toLook = kinds.filter(kind => !expectedOnMockKind(profile, kind));
    const expected = kinds.filter(kind => expectedOnMockKind(profile, kind));
    const list = values => values.map((kind, index) => [index > 0 ? ', ' : null, code(kind)]);
    if (kinds.length === 0) {
        return el('span', {class: 'quiet small'}, ' No findings.');
    }
    return [
        toLook.length > 0 ? el('span', {class: 'small'}, ' Findings: ', list(toLook), '.') : null,
        expected.length > 0 ? el('span', {class: 'quiet small'}, ' Expected on mock: ', list(expected), '.') : null
    ];
}
