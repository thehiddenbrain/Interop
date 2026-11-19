// The chain: one case followed through CRD, DTR and PAS under one id.
//
// The server owns the chain. A run sent with the chain id "new" starts one and returns its id;
// every later run of the case sends that id. The chain is then read back from the ledger with
// GET /api/chains/{chainId}, and everything shown here is what that answer says in its own words:
// where each stage stands, why a stage did not run, and what the next step is. This page keeps
// one thing only, the id of the chain the case on screen belongs to, and forgets it on reload.

import * as api from './api.js';
import {el, fill, clear, button, code, facts, table, heading, copyButton, millis, when, token} from './dom.js';
import {outcome, stamp, slowFlag} from './marks.js';
import {errorPanel} from './result.js';

const PROTOCOL_TABS = ['crd', 'dtr', 'pas'];

export function createChain(app, bar) {
    let chainId = null;       // the chain the case on screen belongs to, once a run has started one
    let view = null;          // the last answer of GET /api/chains/{chainId}
    let showing = false;

    const follow = el('input', {type: 'checkbox', id: 'chain-follow'});
    const control = el('div', {class: 'case-group'},
        el('label', {class: 'choice', htmlFor: 'chain-follow'}, follow, 'Run as part of a chain'),
        el('span', {class: 'field__hint'},
            'A chain gives the journey of this case through CRD, DTR and PAS one id in the ledger. The first run starts it.'));
    follow.addEventListener('change', draw);

    async function refresh() {
        if (!chainId) {
            view = null;
            draw();
            return;
        }
        const answer = await api.get('/api/chains/' + encodeURIComponent(chainId));
        view = answer.kind === 'ok' ? answer.body : {problem: answer.body};
        draw();
    }

    function draw() {
        const wanted = showing && (follow.checked || chainId);
        bar.hidden = !wanted;
        if (!wanted) {
            clear(bar);
            return;
        }
        if (!chainId) {
            fill(bar, el('div', {class: 'chain-bar'}, el('p', {class: 'chain-bar__lead'},
                el('strong', null, 'Chain: not started.'), ' The next run from this case starts one and every run after it joins it.')));
            return;
        }
        if (!view) {
            fill(bar, el('div', {class: 'chain-bar'}, el('p', {class: 'chain-bar__lead'}, 'Reading the chain from the ledger.')));
            return;
        }
        if (view.problem) {
            fill(bar, el('div', {class: 'chain-bar'}, errorPanel(view.problem), actions()));
            return;
        }
        fill(bar, el('div', {class: 'chain-bar'},
            el('ol', {class: 'chain-stages'}, (view.stages || []).map(stage => el('li', {class: 'chain-stage chain-stage--' + token(stage.state)},
                el('span', {class: 'chain-stage__name'}, stage.name),
                el('span', {class: 'chain-stage__state'}, stage.latest ? [outcome(stage.stateLine, stage.latest.tone)] : stage.stateLine)))),
            el('div', {class: 'chain-bar__next'},
                el('span', {class: 'chain-bar__next-label'}, 'Next step'),
                el('span', {class: 'chain-bar__next-headline'}, view.nextHeadline || 'The latest run did not state one.')),
            actions()));
    }

    function actions() {
        return el('div', {class: 'button-row chain-bar__actions'},
            button('Open the whole journey', () => app.navigate('history/chains/' + chainId), {class: 'button--quiet button--small'}),
            button('Start a new case', startNewCase, {class: 'button--quiet button--small'}));
    }

    function startNewCase() {
        const closed = chainId;
        chainId = null;
        view = null;
        draw();
        app.notice('The chain ' + closed + ' is closed in this page. The ledger keeps it, and History lists it under Chains. '
            + 'The next run from this case starts a new chain.', {plain: true});
    }

    return {
        /** The control that turns chain following on. It belongs with the case, so every protocol tab shows it. */
        control,

        /** What to send as chainId with the next run: nothing, "new" to start a chain, or the id of the running one. */
        requestValue() {
            if (chainId) {
                return chainId;
            }
            return follow.checked ? 'new' : undefined;
        },

        /** Call with every run result. A result that names a chain makes that chain the one on screen. */
        onResult(envelope) {
            if (envelope && envelope.chainId) {
                chainId = envelope.chainId;
                follow.checked = true;
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
        facts([
            ['Next step', chain.nextHeadline ? el('strong', null, chain.nextHeadline) : 'The latest run did not state one.'],
            ['Journey', chain.ended ? 'Ended: the latest run said the journey is complete.' : 'Open: the latest run did not end the journey.'],
            ['Chain', [code(chain.chainId), ' ', copyButton('Copy chain id', () => chain.chainId)]],
            ['Member', chain.member ? code(chain.member) : el('span', {class: 'presence presence--none'}, 'not stated')],
            ['Procedure codes', chain.procedureCodes && chain.procedureCodes.length > 0
                ? chain.procedureCodes.map((value, index) => [index > 0 ? ', ' : null, code(value)])
                : el('span', {class: 'presence presence--none'}, 'none recorded')],
            ['Ran against', [chain.environment ? chain.environment + (chain.environmentKind ? ' (' + chain.environmentKind + ')' : '') : 'no environment',
                ', ', chain.profile, ' profile, target ', chain.target]],
            ['Started', when(chain.startedAt)],
            ['Latest run', when(chain.lastRunAt)],
            ['Runs', String(chain.runCount) + ', taking ' + millis(chain.elapsedMillis) + ' in all']
        ], 'facts--ruled'));
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

function runTable(runs) {
    return table(['When', 'Ran', 'Outcome', 'Test result', {text: 'Elapsed', class: 'num'}, 'HTTP', 'Findings', 'Run'], runs.map(run => [
        [when(run.timestamp), run.latestOfItsKind === false ? el('div', {class: 'small quiet'}, 'Run again since') : null],
        [run.what, run.scenarioId ? el('div', {class: 'small quiet'}, 'scenario ', code(run.scenarioId)) : null],
        [outcome(run.outcomeLabel, run.tone), run.expected ? el('div', {class: 'small quiet'}, 'expected ', code(run.expected)) : null],
        stamp(run.result),
        {class: 'num', node: [millis(run.elapsedMillis), run.slow ? [' ', slowFlag()] : null]},
        run.httpStatus === undefined || run.httpStatus === null ? el('span', {class: 'presence presence--none'}, 'none') : String(run.httpStatus),
        run.findings && run.findings.length > 0 ? el('ul', {class: 'plain-list'}, run.findings.map(kind => el('li', null, code(kind))))
            : el('span', {class: 'quiet'}, 'none'),
        code(run.runId)
    ]), {class: 'chain-runs'});
}
