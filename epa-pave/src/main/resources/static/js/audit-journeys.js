// Audit > Journeys (#audit/journeys): one case followed through Coverage check, Questionnaire,
// Submit and Status, read from the ledger by GET /api/chains, and one journey
// (#audit/journeys/<chainId>) with the runs of each step from GET /api/chains/{chainId}.
//
// A journey is what the ledger kept of it: codes, times, who answered and the server's sentences,
// never a request or an answer, which a run's own page reads from the trail.

import {el, chip, kv, fold, button, field, copyButton, fill, firstSentence, toneName} from './dom.js';
import {call} from './api.js';
import {errorPanel} from './fault.js';
import {runLink, none, outcomeChip, testChip, timedTotal, utcText, utcToday} from './audit-common.js';

// The most journeys one list asks for, as the server's ChainService.MAX_LIMIT allows.
const MOST_LISTED = 200;

/** The filters of an address part such as '?from=2026-11-22&single=include'. */
export function journeyFiltersOf(part) {
    const text = typeof part === 'string' && part.startsWith('?') ? part.slice(1) : '';
    const read = new URLSearchParams(text);
    const kept = new URLSearchParams();
    for (const name of ['from', 'to', 'single']) {
        const value = read.get(name);
        if (value !== null && value !== '') {
            kept.set(name, value);
        }
    }
    return kept;
}

/**
 * The latest run a journey summary names: the newest of its stages' latest runs, so the list can
 * say who answered it. Null when the summary names none.
 */
export function latestRun(chain) {
    const runs = (Array.isArray(chain && chain.stages) ? chain.stages : []).map(stage => stage && stage.latest).filter(Boolean);
    let latest = null;
    for (const run of runs) {
        if (!latest || String(run.timestamp || '') > String(latest.timestamp || '')) {
            latest = run;
        }
    }
    return latest;
}

/**
 * Who answered a run, as a chip: the scenario hatched, the tool itself dashed. A run the service
 * answered has no chip, as on the result card, so only a run nobody outside answered stands out.
 */
export function answeredChip(words, code) {
    const found = code ? words.answered(code) : null;
    if (!found || !found.chip) {
        return null;
    }
    return chip(found.chip, {hatched: code === 'SCENARIO', dashed: code !== 'SCENARIO', title: 'Who answered the latest run'});
}

/** A step of a journey as its chip: the server's words and tone, dashed while the step has not run. */
export function stepChip(step) {
    const text = step && step.chip && step.chip.text ? step.chip.text : 'Not run';
    const tone = step && step.chip ? step.chip.tone : null;
    return chip(text, {tone: tone || null, dashed: !tone, title: step && step.name ? step.name : null});
}

/** Every run of a journey, oldest first: the stages' runs and the runs of no stage. */
export function journeyRuns(chain) {
    const runs = [];
    for (const stage of Array.isArray(chain && chain.stages) ? chain.stages : []) {
        for (const run of Array.isArray(stage && stage.runs) ? stage.runs : []) {
            runs.push(run);
        }
    }
    for (const run of Array.isArray(chain && chain.otherRuns) ? chain.otherRuns : []) {
        runs.push(run);
    }
    return runs.sort((a, b) => String(a.timestamp || '').localeCompare(String(b.timestamp || '')));
}

/** The runs of one step of the journey (code COVERAGE, QUESTIONNAIRE, SUBMIT or STATUS), by the vocabulary's step of their operation. */
export function runsOfStep(runs, words, stepCode) {
    return runs.filter(run => {
        const operation = words.operation(run.operation);
        return operation ? operation.step === stepCode : false;
    });
}

/**
 * The Journeys view. ctx: {app, words(), ledger(health), go(parts)}. Returns {node, show(filters), open(chainId)}.
 */
export function journeysView(ctx) {
    const node = el('div', {class: 'audit-journeys'});
    let asked = 0;

    async function show(filters) {
        asked += 1;
        const mine = asked;
        const today = utcToday();
        const query = {limit: String(MOST_LISTED)};
        for (const name of ['from', 'to']) {
            if (filters.get(name)) {
                query[name] = filters.get(name);
            }
        }
        query.zone = 'UTC';
        const [words, answer, todays, health] = await Promise.all([ctx.words(), call('GET', '/api/chains', {query}),
            call('GET', '/api/chains', {query: {from: today, to: today, zone: 'UTC', limit: String(MOST_LISTED)}}), call('GET', '/api/runs/health')]);
        if (mine !== asked) {
            // A later choice was made while this answer was on its way; the later one stands.
            return;
        }
        const counted = todays.ok && todays.data && Array.isArray(todays.data.chains) ? todays.data.chains.length : null;
        fill(node,
            el('div', {class: 'page-head'}, el('h1', {class: 'page-head__title'}, 'Journeys'),
                counted === null ? null : el('span', {class: 'page-head__note'},
                    (counted >= MOST_LISTED ? MOST_LISTED + ' or more' : String(counted)) + ' today, UTC')),
            ctx.ledger(health.ok ? health.data : null),
            filterForm(filters),
            answer.ok ? list(answer.data || {}, filters, words) : errorPanel(answer.error));
    }

    function filterForm(filters) {
        const text = (name, label) => {
            const input = el('input', {class: 'input mono', type: 'text', name, value: filters.get(name) || '', placeholder: 'YYYY-MM-DD', autocomplete: 'off'});
            input.addEventListener('change', apply);
            return {name, input, node: field({label, input})};
        };
        const from = text('from', 'From');
        const to = text('to', 'To');
        const single = el('input', {type: 'checkbox', name: 'single', checked: filters.get('single') === 'include'});
        single.addEventListener('change', apply);
        function apply() {
            const query = new URLSearchParams();
            for (const control of [from, to]) {
                if (control.input.value.trim() !== '') {
                    query.set(control.name, control.input.value.trim());
                }
            }
            if (single.checked) {
                query.set('single', 'include');
            }
            const part = query.toString();
            ctx.go(part ? ['journeys', '?' + part] : ['journeys']);
        }
        return el('div', {class: 'audit-filters'},
            el('form', {class: 'filters', 'aria-label': 'Filters', on: {submit: event => { event.preventDefault(); apply(); }}},
                from.node, to.node,
                el('label', {class: 'audit-check'}, single, ' Include journeys of one run'),
                el('div', {class: 'audit-filters__clear'}, button('Clear the filters', () => ctx.go(['journeys']), {small: true, quiet: true}))));
    }

    function list(data, filters, words) {
        const all = Array.isArray(data.chains) ? data.chains : [];
        // Every run is part of a journey, so a run made alone is a journey of one run: left out unless asked for.
        const chains = filters.get('single') === 'include' ? all : all.filter(chain => Number(chain.runCount) >= 2);
        const hidden = all.length - chains.length;
        const foot = [];
        if (hidden > 0) {
            foot.push(hidden === 1 ? '1 journey of one run is left out.' : hidden + ' journeys of one run are left out.');
        }
        if (all.length >= MOST_LISTED) {
            foot.push('These are the newest ' + MOST_LISTED + ' journeys; choose From and To to see others.');
        }
        if (chains.length === 0) {
            return el('div', null, el('p', {class: 'audit-empty'}, 'No journeys match these filters.'),
                foot.length > 0 ? el('p', {class: 'muted small audit-list__foot'}, foot.join(' ')) : null);
        }
        const rows = chains.map(chain => {
            const when = utcText(chain.lastRunAt);
            const day = when ? when.slice(0, 10) : null;
            const latest = latestRun(chain);
            const steps = Array.isArray(chain.steps) ? chain.steps : [];
            const [headline] = firstSentence(chain.headline || '');
            return el('tr', null,
                el('td', {class: 'audit-when'}, when ? (day === utcToday() ? when.slice(11, 19) : when.slice(5, 19)) : none('no time')),
                el('td', null, chain.member ? el('span', {class: 'mono'}, chain.member) : none('not recorded')),
                el('td', null, Array.isArray(chain.procedureCodes) && chain.procedureCodes.length > 0
                    ? el('span', {class: 'mono'}, chain.procedureCodes.join(', ')) : null),
                el('td', null, el('span', {class: 'audit-steps'}, steps.map(step => stepChip(step)),
                    latest ? answeredChip(words, latest.answeredBy) : null)),
                el('td', {class: 'audit-journey__headline'}, headline || null),
                el('td', null, el('a', {class: 'table__link', href: journeyAddress(chain.chainId)}, 'Open')));
        });
        const heads = ['Time', 'Member', 'Code', 'Steps', 'Where it stands', ''];
        return el('div', {class: 'audit-list'},
            el('div', {class: 'table-wrap'}, el('table', {class: 'table'},
                el('thead', null, el('tr', null, heads.map(head => el('th', {scope: 'col', title: head === 'Time' ? 'UTC, the latest run' : null}, head)))),
                el('tbody', null, rows))),
            foot.length > 0 ? el('p', {class: 'muted small audit-list__foot'}, foot.join(' ')) : null);
    }

    async function open(chainId) {
        asked += 1;
        const mine = asked;
        fill(node, crumbs(null), el('p', {class: 'muted', role: 'status'}, 'Reading the journey from the ledger.'));
        const [words, answer, health] = await Promise.all([ctx.words(), call('GET', '/api/chains/' + encodeURIComponent(chainId)),
            call('GET', '/api/runs/health')]);
        if (mine !== asked) {
            return;
        }
        if (!answer.ok) {
            fill(node, crumbs(null), ctx.ledger(health.ok ? health.data : null), errorPanel(answer.error));
            return;
        }
        drawJourney(answer.data || {}, words, health.ok ? health.data : null);
    }

    function crumbs(title) {
        return el('p', {class: 'crumbs'}, el('a', {href: '#audit/journeys'}, 'Journeys'), title ? [el('span', {'aria-hidden': 'true'}, '/'), el('span', null, title)] : null);
    }

    function drawJourney(chain, words, health) {
        const runs = journeyRuns(chain);
        const steps = Array.isArray(chain.steps) ? chain.steps : [];
        const started = utcText(chain.startedAt);
        const last = utcText(chain.lastRunAt);
        const placed = new Set();
        const stepParts = steps.map(step => {
            const own = runsOfStep(runs, words, step.step);
            own.forEach(run => placed.add(run.runId));
            return el('div', {class: 'panel__section audit-journey__step'},
                el('h2', {class: 'audit-journey__name'}, step.name || step.step, ' ', stepChip(step)),
                step.stateLine ? el('p', {class: 'muted small'}, step.stateLine) : null,
                own.length > 0 ? runTable(own, words) : null);
        });
        const others = runs.filter(run => !placed.has(run.runId));
        const latest = runs.length > 0 ? runs[runs.length - 1] : null;
        const notes = [chain.caseLine, chain.replayNote].concat(Array.isArray(chain.notes) ? chain.notes : []).filter(text => typeof text === 'string' && text !== '');
        fill(node,
            crumbs((chain.member || 'Journey') + (last ? ', ' + last.slice(11, 19) : '')),
            el('div', {class: 'page-head'},
                el('h1', {class: 'page-head__title', tabIndex: -1}, 'Journey'),
                el('span', {class: 'page-head__note'}, el('span', {class: 'mono'}, chain.chainId)),
                el('div', {class: 'page-head__end'}, copyButton('Copy chain id', () => chain.chainId, {small: true}))),
            ctx.ledger(health),
            el('section', {class: 'panel'},
                el('div', {class: ['verdict', 'verdict--' + (toneName(latest ? latest.tone : null) || 'neutral')]},
                    el('p', {class: 'verdict__headline'}, chain.headline || 'No headline recorded'),
                    kv([
                        {key: 'Member', value: chain.member ? el('span', {class: 'mono'}, chain.member) : none('not recorded')},
                        {key: 'Code', value: Array.isArray(chain.procedureCodes) && chain.procedureCodes.length > 0
                            ? el('span', {class: 'mono'}, chain.procedureCodes.join(', ')) : none('not recorded')},
                        {key: 'Next', value: chain.nextHeadline || (chain.ended ? 'The journey has ended.' : none('none recorded'))},
                        {key: 'Environment', value: chain.environment ? [chain.environment, ' ', chain.environmentKind ? chip(String(chain.environmentKind).toLowerCase()) : null]
                            : none('no environment')},
                        {key: 'Profile', value: chain.profile || none('not recorded')},
                        {key: 'Runs', value: [started ? started : null, last && last !== started ? ' to ' + last : null]}
                    ]),
                    latest ? el('div', {class: 'verdict__foot'},
                        button('Load into the Workbench', () => ctx.app.go('#workbench/load/' + encodeURIComponent(latest.runId)), {primary: true}),
                        el('span', {class: 'muted small'}, 'Reads the case and each step back into the Workbench; sends nothing.')) : null),
                stepParts,
                others.length > 0 ? el('div', {class: 'panel__section audit-journey__step'},
                    el('h2', {class: 'audit-journey__name'}, 'Other runs'), runTable(others, words)) : null,
                el('p', {class: 'muted small audit-list__foot'}, timedTotal(runs), '.'),
                notes.length > 0 ? fold({summary: 'Notes', hint: notes.length === 1 ? '1 note' : notes.length + ' notes',
                    body: () => el('ul', {class: 'notes'}, notes.map(text => el('li', null, text)))}) : null));
    }

    function runTable(runs, words) {
        return el('div', {class: 'table-wrap'}, el('table', {class: 'table'},
            el('thead', null, el('tr', null, ['Time', 'Call', 'Outcome', 'Test', 'ms'].map(head =>
                el('th', {scope: 'col', class: head === 'ms' ? 'num' : null, title: head === 'Time' ? 'UTC' : null}, head)))),
            el('tbody', null, runs.map(run => {
                const when = utcText(run.timestamp);
                const operation = words.operation(run.operation);
                return el('tr', null,
                    el('td', {class: 'audit-when'}, runLink(run.runId, when ? when.slice(11, 19) : run.runId)),
                    el('td', null, operation ? operation.label : run.what || run.operation || null, ' ', answeredChip(words, run.answeredBy)),
                    el('td', null, outcomeChip(words, run)),
                    el('td', null, testChip(words, run.result)),
                    el('td', {class: 'num', title: typeof run.elapsedMillis === 'number' ? null : 'Not measured'},
                        typeof run.elapsedMillis === 'number' ? String(run.elapsedMillis) : null));
            }))));
    }

    return {node, show, open};
}

/** The address of one journey in Audit. */
export function journeyAddress(chainId) {
    return '#audit/journeys/' + encodeURIComponent(chainId);
}
