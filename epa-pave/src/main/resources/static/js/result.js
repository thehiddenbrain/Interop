// The result view. One function draws the result of a run for every protocol, in one order:
//
//   1. the verdict strip      what the service said, in its tone
//   2. the test result        what was expected, what happened, whether they match. A run with
//                             nothing declared says so in one quiet line under the strip instead
//   3. the next step          what to do now, with the button that does it when the tool can
//   4. the run line           the run id, the response time and the ledger: recorded, or NOT recorded
//   5. the findings           what a person should look at, apart from the determination
//   6. the determination      the standard codes; elements not returned in a fold under the table
//   7. the response detail    the protocol's own part, passed in by the tab
//   8. the additional fields  everything returned that the view above did not use, its table folded
//   9. the details            the wire view and what was typed with the run, in one closed fold
//  10. the error panel        when the call failed, was refused, or the tool failed after it; never folded
//
// What the service answered is open; what was not returned and the tool's own internals are one
// click away, and never hidden: each fold says what it holds and how many.
//
// Nothing here is decided in the browser. The verdict, the test result, the next step and the
// findings are worded by the server and shown as sent. The authorization outcome and the test
// result are two facts and are drawn as two different things: a strip in a tone, and a stamp.

import {el, append, button, code, facts, table, heading, copyButton, count, millis, token, faultReport} from './dom.js';
import {shown, shownList, presenceMark, noValueMark} from './shown.js';
import {toneClass, stamp, slowFlag, mockMark, localMark, answeredByNobody} from './marks.js';
import {runLink} from './history-common.js';
// lookup.js imports this file as well. The cycle is harmless: idForms is only called while a
// result is drawn, long after both modules have loaded.
import {idForms} from './lookup.js';

// What the server words and the frame keeps current: the label of each operation, and whether the
// code list in use is a placeholder, and where it was read from. Set once by app.js; until then a result shows the
// operation by its name and says nothing about the list.
let serverWords = {operationLabel: () => null, codeListIsPlaceholder: () => null, codeListSource: () => null};

/** Called by the frame at start-up. words: {operationLabel(name), codeListIsPlaceholder(), codeListSource()}. */
export function useServerWords(words) {
    serverWords = words;
}

/** An operation as the server labels it, with its name kept as literal text beside it. One with no label is its name. */
export function operationName(name) {
    const label = name ? serverWords.operationLabel(name) : null;
    return label ? [label, ' ', el('span', {class: 'quiet'}, '(', code(name), ')')] : code(String(name));
}

/**
 * Draws one run result.
 *
 * options.detail(envelope)    returns the protocol's own part of the view, or null
 * options.detailTitle         the heading of that part
 * options.nextStepControl(nextStep, envelope)  returns {label, run} when the next step is
 *                             something the tool can do from here, otherwise null
 * options.lead                a node the tab puts right under the verdict, before everything
 *                             else: the questions of a DTR session, which are the work of the step
 */
export function renderResult(envelope, options) {
    const settings = options || {};
    const root = el('article', {class: 'result', aria: {label: 'Result of run ' + envelope.runId}});
    // Unverified with nothing declared is the common case, and not a failure: one quiet line in the
    // server's words says it. A declared expectation, compared or not, keeps the box beside the strip.
    const quiet = undeclared(envelope.expectation);
    append(root,
        el('div', {class: 'result__top' + (quiet ? ' result__top--alone' : '')}, verdictStrip(envelope),
            quiet ? null : testResult(envelope.expectation)),
        quiet ? el('p', {class: 'result__unverified'}, envelope.expectation.explanation) : null,
        settings.lead || null,
        ownBody(envelope),
        runFacts(envelope, settings),
        findingsSection(envelope),
        determinationSection(envelope.determination || []));

    const detailTitle = settings.detailTitle || 'Response detail';
    const detailNode = envelope.detail !== undefined && envelope.detail !== null && settings.detail ? settings.detail(envelope) : null;
    root.append(section(heading(3, detailTitle),
        detailNode || el('p', {class: 'quiet'}, 'This run has no response detail. The verdict above says why.')));

    root.append(additionalFieldsSection(envelope.additionalFields || [], null, envelope.wire, {folded: true}));
    // The tool's own record of the run and the bytes on the wire: one click away, closed. The wire
    // view keeps its own fold inside, so it is collapsed by default as before.
    root.append(el('details', {class: 'fold result__details'},
        el('summary', null, 'Details: the wire view and what was typed with the run'),
        facts(typedWithRun(envelope.ledger), 'runfacts facts--ruled'),
        el('section', {class: 'result__section'}, wireView(envelope.wire))));
    if (envelope.error) {
        // A refusal is worded once by the server and sent as the headline, as the error's title
        // and as the next step: a request the guardrail refused has its reason as the headline of
        // the strip in the guardrail's tone. It is read in full there; here the panel says which
        // kind of refusal it is and points to it, rather than setting the same paragraph out again.
        const error = envelope.error;
        const step = envelope.nextStep || {};
        const repeated = typeof error.title === 'string'
            && (error.title === envelope.verdict.headline || (error.kind === 'BLOCKED' && envelope.verdict.tone === 'BLOCKED'));
        const said = {...error};
        if (repeated) {
            said.title = 'The headline at the top of this result says what was refused, in full.';
        }
        if (typeof error.action === 'string' && error.action === step.headline) {
            said.action = 'The next step above says what to do.';
        }
        // The server heads every error with what it is (a refusal by the guardrail is not something
        // that went wrong), so the section is headed by that and the panel does not say it twice.
        root.append(section(heading(3, error.heading ? error.heading : ['Error kind ', code(error.kind || 'none')]),
            errorPanel(said, {headed: true})));
    }
    return root;
}

function section(...children) {
    return append(el('section', {class: 'result__section'}), children);
}

/* ---------- 1. the verdict strip ---------- */

/**
 * True when this tool refused the run before it was sent, so nothing answered it, scenario or
 * service. Read from the server's own tone and error kind, never worked out from the wire.
 */
function refusedBeforeSending(envelope) {
    return envelope.verdict.tone === 'BLOCKED' || Boolean(envelope.error && envelope.error.kind === 'BLOCKED');
}

function verdictStrip(envelope) {
    const verdict = envelope.verdict;
    // Who answered is the server's to say. The hatch says a scenario file answered. A request
    // refused before it was sent, and a step done inside this tool, were answered by nobody, so
    // neither carries the hatch; the step done here is drawn as a frame, never filled, because a
    // filled strip is an answer from the payer. A result from a server too old to say keeps the
    // rule it was drawn by before.
    const refused = refusedBeforeSending(envelope);
    const mock = envelope.profile === 'mock';
    const answeredBy = envelope.answeredBy || (refused ? 'NOT_SENT' : mock ? 'SCENARIO' : 'SERVICE');
    const answeredByScenario = answeredBy === 'SCENARIO';
    const local = answeredByNobody(answeredBy);
    const strip = el('div', {class: 'verdict ' + toneClass(verdict.tone) + (answeredByScenario ? ' verdict--mock' : '')
        + (local ? ' verdict--local' : '')});
    if (answeredByScenario) {
        strip.append(el('span', {class: 'visually-hidden'}, 'Mock profile: this answer came from a scenario file. '));
    }
    if (local) {
        strip.append(el('span', {class: 'visually-hidden'}, 'Nobody answered this step: it was done inside this tool. '));
    }
    // A headline is one sentence, set large. A refusal can word its reason into a paragraph, which
    // at that size fills the screen and hides the test result beside it, so a long one is set smaller.
    const length = String(verdict.headline || '').length;
    const size = length > 220 ? ' verdict__headline--long verdict__headline--longest' : length > 80 ? ' verdict__headline--long' : '';
    strip.append(el('h2', {class: 'verdict__headline' + size}, verdict.headline));
    const supporting = verdict.supporting || [];
    if (supporting.length > 0) {
        strip.append(el('ul', {class: 'verdict__supporting'}, supporting.map(line => el('li', null, line))));
    }
    const address = calledAddress(envelope, answeredBy);
    if (address) {
        strip.append(address);
    }
    strip.append(el('p', {class: 'verdict__foot'},
        answeredByScenario ? mockMark() : null,
        local ? localMark() : null,
        mock && refused ? el('span', {class: 'verdict__unanswered'}, 'Mock profile, and no scenario answered: the request was refused before it was sent.') : null,
        el('span', null, 'Outcome code ', code(verdict.outcomeCode, 'verdict__code'))));
    return strip;
}

/**
 * Where a call that could not reach the service, or got no answer in time, was sent: the
 * scheme, host, port and path of the url the wire view prints, already masked by the server.
 * The query and fragment are left out, because they can carry member identifiers and stay in
 * the collapsed wire view. On mock nothing was called, so nothing is drawn.
 */
function calledAddress(envelope, answeredBy) {
    const sentToService = answeredBy === 'SERVICE' || (!envelope.answeredBy && envelope.profile === 'live');
    const kind = envelope.error ? envelope.error.kind : null;
    if (!sentToService || (kind !== 'UNREACHABLE' && kind !== 'TIMEOUT')
        || !(envelope.wire && envelope.wire.request && envelope.wire.request.url)) {
        return null;
    }
    const url = String(envelope.wire.request.url);
    const shown = addressOnly(url);
    return el('p', {class: 'verdict__address'}, 'Address called: ', code(shown),
        shown !== url ? ' (the full address, with its query, is in the wire view)' : null);
}

/** The text of a url before its first "?" or "#": scheme, host, port and path. A url with neither is returned whole. */
export function addressOnly(url) {
    const text = String(url);
    const cut = text.search(/[?#]/);
    return cut < 0 ? text : text.substring(0, cut);
}

/**
 * Said under the verdict and the test result whenever the body sent was the tester's own, for
 * every protocol: the answer is to a body this tool did not build. Whether the text parses is a
 * fact about the text that went out, the same fact the box stated before sending, and a malformed
 * body sent on purpose is a negative case whose answer must not read as the service accepting it.
 */
function ownBody(envelope) {
    const detail = envelope.detail;
    if (!detail || detail.bodyBuiltByTool !== false) {
        return null;
    }
    const sentBody = envelope.wire && envelope.wire.request ? envelope.wire.request.body : null;
    let parses = true;
    if (typeof sentBody === 'string') {
        try {
            JSON.parse(sentBody);
        } catch (notJson) {
            parses = false;
        }
    }
    return el('div', {class: 'result__own-body'},
        el('p', null, el('strong', null, 'A request body of your own was sent.'),
            ' This tool did not build it from the form; it went as you wrote it, with its placeholders filled in. '
            + 'The member, provider and codes on the form were not sent with it. The wire view shows the body.'),
        parses ? null : el('p', null, el('strong', null, 'The body sent was not valid JSON.'),
            envelope.profile === 'mock'
                ? ' On the mock profile a scenario file answers whatever the body holds, so the answer and its next step '
                    + 'are the scenario\'s and say nothing about this body.'
                : ' Whatever the service answered, it answered a body that does not parse: read the answer in that light.'));
}

/* ---------- 2. the test result ---------- */

/** True for a run that is unverified because nothing at all was declared, the case one quiet line says. */
function undeclared(expectation) {
    return Boolean(expectation) && expectation.result === 'unverified' && !expectation.expected && !expectation.expectedLabel;
}

function testResult(expectation) {
    return el('div', {class: 'testresult'},
        el('h3', {class: 'testresult__title'}, 'Test result'),
        stamp(expectation.result, 'large'),
        facts([
            ['Expected', expected(expectation)],
            ['Happened', [expectation.actualLabel, el('div', {class: 'small'}, code(expectation.actual))]]
        ]),
        el('p', {class: 'testresult__explanation'}, expectation.explanation));
}

/**
 * What the tester declared. The server words it when it compared it. When nothing was compared,
 * or the text could not be read as an expectation, the server sends the typed text back as the
 * label too: that is literal text, and it is set as literal text, not as a sentence.
 */
function expected(expectation) {
    const typed = expectation.expected;
    const label = expectation.expectedLabel;
    if (!typed && !label) {
        return el('span', {class: 'presence presence--none', title: 'No expectation was sent with this run.'}, 'nothing declared');
    }
    if (typed && label && label !== typed) {
        return [label, el('div', {class: 'small'}, code(typed))];
    }
    return typed ? code(typed) : label;
}

/* ---------- 3 and 4. the next step, the ledger line, and the facts of the run ---------- */

function runFacts(envelope, settings) {
    const recorded = Boolean(envelope.ledger && envelope.ledger.recorded);
    const rows = [
        ['Next step', nextStep(envelope, settings)],
        // One line for the run: its id, how long it took (with the slow flag when it was slow) and
        // that it was recorded. A run the ledger did not keep gets the full alert as a row of its own.
        ['Run', [runLine(envelope), el('div', {class: 'run-line'}, responseTime(envelope.timing),
            recorded ? [el('span', {class: 'run-line__sep', aria: {hidden: 'true'}}, ' | '), ledgerLine(envelope.ledger, envelope.runId)] : null)]],
        recorded ? null : ['Ledger', ledgerLine(envelope.ledger, envelope.runId)]
    ].filter(row => row !== null);
    const memberId = envelope.wire ? envelope.wire.memberId : null;
    if (memberId && memberId.changed) {
        // The tool removes spaces from a member ID on the way out. It is said here, in the open,
        // and not only inside the collapsed wire view, with every space shown and counted.
        rows.push(['Member ID', idForms(memberId)]);
    }
    const response = envelope.wire ? envelope.wire.response : null;
    if (response && response.maskedPlaces > 0) {
        // The response on screen is not byte for byte what arrived, and the place a tester reads
        // first says so, in the server's words. The wire view says it again beside the body.
        rows.push(['Masked', el('span', {class: 'result__masked'},
            el('span', {class: 'result__masked-count'}, count(response.maskedPlaces) + (response.maskedPlaces === 1 ? ' place' : ' places') + ' masked. '),
            response.maskNote)]);
    }
    return facts(rows, 'runfacts facts--ruled');
}

function nextStep(envelope, settings) {
    const step = envelope.nextStep;
    const control = settings.nextStepControl ? settings.nextStepControl(step, envelope) : null;
    return el('div', {class: 'nextstep'},
        el('div', {class: 'nextstep__row'},
            el('span', {class: 'nextstep__headline'}, step.headline),
            control ? (control.node || button(control.label, control.run, {class: 'button--primary'})) : null),
        step.reason ? el('p', {class: 'nextstep__reason'}, step.reason) : null);
}

function ledgerLine(ledger, runId) {
    if (ledger.recorded) {
        return el('span', {class: 'ledger'}, el('strong', null, 'Recorded.'), ' ',
            typeof ledger.runsToday === 'number'
                ? 'The ledger holds ' + count(ledger.runsToday) + (ledger.runsToday === 1 ? ' run' : ' runs') + ' for today.'
                : 'The ledger did not say how many runs it holds for today.');
    }
    return el('div', {class: 'ledger ledger--missing', role: 'alert'},
        el('p', {class: 'ledger__state'}, 'NOT recorded'),
        el('p', null, 'The ledger did not keep run ', code(runId), '. It said:'),
        el('blockquote', null, ledger.message),
        // A next step of "record this result" is for the tester. With the ledger not keeping it, the record is theirs to make.
        el('p', {class: 'ledger__todo'}, 'This run is in no ledger. If you need it, write down its run id and its result yourself.'));
}

/**
 * The tester reference and the note typed with the run, as the ledger kept them, beside the run id
 * on every tab: an analyst comparing a result with a ledger row looks for them here. The ledger is
 * where they are kept, so a run it did not keep has none to show, and that is said.
 */
function typedWithRun(ledger) {
    const none = text => el('span', {class: 'presence presence--none'}, text);
    if (!ledger || !ledger.recorded || !ledger.record) {
        return [['Tester reference and note', none('not kept: the ledger did not record this run')]];
    }
    const record = ledger.record;
    return [
        ['Tester reference', record.testerRef ? code(record.testerRef) : none('none given')],
        ['Note', record.note ? el('span', {class: 'vendor-inline'}, record.note) : none('none given')]
    ];
}

function responseTime(timing) {
    if (!timing) {
        return el('span', {class: 'presence presence--none', title: 'The result carries no timing.'}, 'not measured');
    }
    return el('span', {class: 'run-line__time'}, el('strong', null, millis(timing.elapsedMillis)), timing.slow ? [' ', slowFlag()] : null,
        el('span', {class: 'quiet'}, timing.slow ? ' Longer than the threshold of ' : ' The threshold for slow is ',
            millis(timing.thresholdMillis), '.'));
}

function runLine(envelope) {
    const environment = envelope.environment || {};
    // A recorded run opens in History. One the ledger did not keep has nothing there to open, so it is not a link.
    const recorded = Boolean(envelope.ledger && envelope.ledger.recorded);
    const parts = [recorded ? runLink(envelope.runId) : code(envelope.runId), ' ', copyButton('Copy run id', () => envelope.runId)];
    const where = el('div', {class: 'small quiet'}, operationName(envelope.operation), ' against ',
        environment.active ? environment.name + ' (' + environment.kind + ')' : 'no environment',
        envelope.profile ? ', ' + envelope.profile + ' profile' : null);
    const chain = envelope.chainId ? el('div', {class: 'small quiet'}, 'In chain ', code(envelope.chainId)) : null;
    return [parts, where, chain];
}

/* ---------- 5. the findings ---------- */

/**
 * True for a finding that a run on the mock profile with no environment active raises as a matter
 * of course: the request went out without elements only an environment or a lookup supplies. It is
 * still drawn whole, in its own closed fold inside Findings, and counted with the rest.
 */
export function expectedOnMock(envelope, item) {
    return Boolean(envelope) && envelope.profile === 'mock' && Boolean(item) && item.kind === 'REQUEST_INCOMPLETE'
        && !(envelope.environment && envelope.environment.active);
}

function findingsSection(envelope) {
    const findings = envelope.findings || [];
    const shown = findings.filter(item => !expectedOnMock(envelope, item));
    const onMock = findings.filter(item => expectedOnMock(envelope, item));
    // The heading counts every finding the run raised, the ones grouped below included.
    const node = section(heading(3, 'Findings', findings.length));
    if (shown.length > 0) {
        node.append(el('p', {class: 'quiet'}, 'Something a person should look at. A finding is not a failure of the call.'),
            el('div', null, shown.map(finding)));
    } else if (onMock.length === 0) {
        node.append(el('p', {class: 'quiet'}, 'None were raised on this run.'));
    } else {
        node.append(el('p', {class: 'quiet'}, 'None apart from the ones expected on the mock profile, below.'));
    }
    if (onMock.length > 0) {
        // Closed, with its count and its reason on the summary: a mock run raises these every time,
        // and open they read as the headline of a run that went right. One click shows each whole.
        node.append(el('details', {class: 'fold result__mock-notes'},
            el('summary', null, 'Expected on the mock profile (' + onMock.length + ')',
                el('span', {class: 'quiet small'}, ' On the mock profile, with no environment active, this is expected.')),
            el('div', null, onMock.map(finding))));
    }
    return node;
}

/**
 * One finding, in the server's words. What it is about (the request this tool sent, the response
 * held to the guide, and so on) is the server's heading for the finding's group, said above its
 * title, so that a request that went out incomplete never reads as a fault of the service. A
 * finding that comes with no group is drawn the same plain way with no such line: it is never
 * dropped and never drawn as a fault.
 */
function finding(item) {
    const node = el('div', {class: 'finding' + (item.group ? ' finding--' + token(item.group) : '')},
        item.groupLabel ? el('p', {class: 'finding__about'}, item.groupLabel) : null,
        el('p', {class: 'finding__title'}, item.title),
        item.detail ? el('p', null, item.detail) : null);
    // A position taken from a placeholder list is not P32H's position, and says so on its own label. A placeholder can be the
    // list that ships with the tool, the sample or a tester's own list marked as synthetic, so the label names where it was read
    // from, as the self-check does, and does not say which of them it is.
    const source = serverWords.codeListSource();
    const listWho = serverWords.codeListIsPlaceholder() === true
        ? ['The code list (a placeholder, not a P32H list', source ? [', read from ', code(source)] : null, ')'] : 'The code list';
    if (item.codeListSays && item.serviceSays) {
        // A disagreement is two positions. Either side can be the one that is wrong, so the two
        // are set side by side with the same weight and neither is marked.
        node.append(el('div', {class: 'positions'},
            position(listWho, item.codeListSays),
            position('The service', item.serviceSays)));
    } else if (item.codeListSays || item.serviceSays) {
        // One position only: the finding is about what one side said, not a comparison of the two.
        node.append(el('div', {class: 'positions positions--one'},
            item.codeListSays ? position(listWho, item.codeListSays) : position('The service', item.serviceSays)));
    }
    node.append(el('p', {class: 'finding__meta'}, 'Kind ', code(item.kind), item.subject ? [', about ', code(item.subject)] : null));
    return node;
}

function position(who, says) {
    return el('div', {class: 'position'},
        el('p', {class: 'position__who'}, who),
        el('p', {class: 'position__says'}, says));
}

/* ---------- 6. the determination grid ---------- */

function determinationSection(rows) {
    if (rows.length === 0) {
        return section(heading(3, 'Determination'), el('p', {class: 'quiet'}, 'This run produced no determination rows.'));
    }
    // Decided once over every row, so the folded table is grouped exactly when the open one is.
    const grouped = rows.some(row => row.subject);
    // Only an element that was not returned at all is folded. Null, empty and sent with no value
    // are answers the service gave, and stay in view.
    const absent = row => (row.presence || 'ABSENT') === 'ABSENT' && row.sentWithNoValue !== true;
    const open = rows.filter(row => !absent(row));
    const notReturned = rows.filter(absent);
    const grid = list => el('table', {class: 'grid determination'}, determinationHead(), el('tbody', null, determinationBody(list, grouped)));
    return section(heading(3, 'Determination'),
        open.length > 0 ? grid(open) : el('p', {class: 'quiet'}, 'The response returned none of these elements.'),
        notReturned.length > 0 ? el('details', {class: 'fold determination__absent'},
            el('summary', null, 'Not returned', el('span', {class: 'quiet'}, '(' + count(notReturned.length) + ')')),
            grid(notReturned)) : null);
}

function determinationHead() {
    return el('thead', null, el('tr', null,
        ['What', 'Code returned', 'Meaning', 'Element'].map(text => el('th', {scope: 'col'}, text))));
}

/** The rows of one determination table, each subject headed by a group row when grouped. A subject with no row here gets none. */
function determinationBody(rows, grouped) {
    const body = [];
    let subject;
    let first = true;
    for (const row of rows) {
        if (grouped && (first || row.subject !== subject)) {
            subject = row.subject;
            body.push(el('tr', {class: 'group'}, el('th', {colSpan: 4, scope: 'colgroup'},
                subject ? ['For ', code(subject)] : 'For the request as a whole')));
        }
        first = false;
        body.push(determinationRow(row));
    }
    return body;
}

/**
 * A code or a value as literal text that a narrow cell breaks only where the value itself is
 * joined (after a slash, a bar or a space), never inside a date or a code: a date range keeps each
 * of its dates whole. The text is unchanged, so what is copied is the value as returned.
 */
function atJoints(value) {
    const node = el('code', {class: 'literal'});
    for (const part of String(value).split(/(?<=[/| ])/)) {
        node.append(el('span', {class: 'nobreak'}, part), el('wbr'));
    }
    return node;
}

function determinationRow(row) {
    const returned = row.presence === 'VALUE' && row.code !== undefined;
    const cells = [el('td', null, row.label)];
    if (returned) {
        cells.push(el('td', null, atJoints(row.code)));
        // A row can hold an identifier, such as an authorization number, which has no meaning to state.
        cells.push(el('td', null, row.display || el('span', {class: 'quiet small'}, 'no meaning to state'),
            row.recognised === false ? [' ', el('span', {class: 'flag'}, 'not a code this tool knows')] : null));
    } else {
        // Nothing was returned for this element, and that is said across both columns: never a blank cell.
        // An element sent with no value at all is said to be that, as the coverage table says it.
        cells.push(el('td', {colSpan: 2}, row.sentWithNoValue === true ? noValueMark() : presenceMark(row.presence || 'ABSENT')));
    }
    cells.push(el('td', null, row.system ? code(row.system) : el('span', {class: 'quiet small'}, 'not stated')));
    return el('tr', {class: row.recognised === false ? 'determination--unrecognised' : null}, cells);
}

/* ---------- 8. the additional fields ---------- */

/**
 * The returned-but-not-interpreted values as the wire view shows the body: with every secret the
 * server masked there masked here too.
 *
 * The server masks a secret that a service sends back in the wire view, and says how many places
 * it masked. Until it builds these fields from the masked body as well, they can carry the secret
 * in clear (a stand-in that echoed the Authorization header showed it here, under a line saying it
 * was masked). So when the wire says anything was masked, each value is taken from the masked body
 * at the same path; one whose path cannot be found there is withheld, with a pointer to the wire
 * view, rather than shown as it came. Nothing is un-masked and nothing is guessed: the masked text
 * is the server's. When nothing was masked the fields are shown exactly as sent.
 */
function maskedLikeTheWire(fields, wire) {
    const response = wire ? wire.response : null;
    if (!response || !(response.maskedPlaces > 0)) {
        return fields;
    }
    let masked;
    let parsed = false;
    if (typeof response.body === 'string') {
        try {
            masked = JSON.parse(response.body);
            parsed = true;
        } catch (notJson) {
            parsed = false;
        }
    }
    return fields.map(field => {
        if (field.kind === 'RAW_BODY') {
            return {...field, value: typeof response.body === 'string' ? response.body : undefined};
        }
        if (field.kind === 'NOTE' || field.value === undefined || field.value === null) {
            return field;
        }
        const found = parsed ? atPath(masked, field.path) : {found: false};
        if (found.found) {
            const text = JSON.stringify(found.value);
            // Only a value the masking changed is replaced, so a number keeps the form it arrived in.
            return typeof found.value === 'string' && text !== field.value ? {...field, value: text} : field;
        }
        return {...field, value: undefined, withheld: true};
    });
}

/**
 * The value at a path written the way the server writes one: names joined by dots, a name holding
 * path punctuation in double quotes, and [n] for an element of an array; "(root)" for the body.
 * Returns {found, value}.
 */
function atPath(body, path) {
    if (path === '(root)') {
        return {found: true, value: body};
    }
    let at = body;
    let index = 0;
    const text = String(path);
    let first = true;
    while (index < text.length) {
        if (text[index] === '[') {
            const close = text.indexOf(']', index);
            const position = close > index ? Number(text.slice(index + 1, close)) : NaN;
            if (!Array.isArray(at) || !Number.isInteger(position) || position < 0 || position >= at.length) {
                return {found: false};
            }
            at = at[position];
            index = close + 1;
            first = false;
            continue;
        }
        if (!first) {
            if (text[index] !== '.') {
                return {found: false};
            }
            index++;
        }
        let name = '';
        if (text[index] === '"') {
            index++;
            while (index < text.length && text[index] !== '"') {
                if (text[index] === '\\' && index + 1 < text.length) {
                    index++;
                }
                name += text[index];
                index++;
            }
            if (text[index] !== '"') {
                return {found: false};
            }
            index++;
        } else {
            while (index < text.length && text[index] !== '.' && text[index] !== '[') {
                name += text[index];
                index++;
            }
        }
        if (at === null || typeof at !== 'object' || Array.isArray(at) || !Object.prototype.hasOwnProperty.call(at, name)) {
            return {found: false};
        }
        at = at[name];
        first = false;
    }
    return {found: true, value: at};
}

/**
 * Everything the response carried that the designed view did not use, each entry presented as what it is.
 * wire, optional: the wire view of the same answer. When it is given and says secrets were masked,
 * the values are drawn masked as the wire's body is (see maskedLikeTheWire). Pass it wherever the
 * answer has one.
 * options, optional: {folded: true} keeps the heading, its count and the line under it open and
 * puts what they count into one closed fold, so a field the view does not know still shows on
 * screen by its count. Left out, everything is open, as the lookup panels want it.
 */
export function additionalFieldsSection(givenFields, title, wire, options) {
    const fields = wire ? maskedLikeTheWire(givenFields, wire) : givenFields;
    const label = title || 'Returned but not interpreted';
    if (fields.length === 0) {
        return section(heading(3, label, 0), el('p', {class: 'quiet'}, 'Nothing was returned that is not shown above.'));
    }
    const notes = fields.filter(field => field.kind === 'NOTE');
    const bodies = fields.filter(field => field.kind === 'RAW_BODY');
    const plain = fields.filter(field => field.kind !== 'NOTE' && field.kind !== 'RAW_BODY');
    const node = section(heading(3, label, fields.length),
        el('p', {class: 'quiet'}, 'The response carried these and this tool has no designed view for them. They are shown so that nothing is dropped.'));
    // The notes and the line that says a body could not be read are caveats on how to read the
    // whole result, so they stay open under the heading; only the field table and the raw text fold.
    if (notes.length > 0) {
        node.append(el('ul', {class: 'note-list'}, notes.map(note => el('li', null, el('strong', null, 'About the body: '), note.value))));
    }
    const folded = Boolean(options && options.folded) && plain.length + bodies.length > 0;
    for (const body of bodies) {
        node.append(el('p', {class: 'raw-body__said'}, el('strong', null, 'The body as received.'), ' It could not be read as JSON, so it is shown whole. ', code(body.path)));
    }
    let into = node;
    if (folded) {
        into = fieldsFold(plain.length + bodies.length);
        node.append(into);
    }
    for (const body of bodies) {
        const text = el('pre', {class: 'body-text body-text--wrap', tabIndex: 0, aria: {label: 'The body as received'}}, body.value === undefined ? '' : body.value);
        into.append(el('div', {class: 'raw-body'}, bodies.length > 1 ? el('p', null, code(body.path)) : null,
            text, copyButton('Copy this body', () => text.textContent, text)));
    }
    if (plain.length > 0) {
        into.append(additionalFieldsTable(plain));
    }
    return node;
}

/**
 * What a fold of returned fields says on its summary, and the fold around them, closed. The
 * heading above it, with its count, stays open, so a field nobody designed a view for still shows.
 */
export function fieldsFold(howMany, ...content) {
    return el('details', {class: 'fold'}, el('summary', null, 'Show the ' + count(howMany) + (howMany === 1 ? ' field' : ' fields')), content);
}

/**
 * The table of returned fields: where each sat, whether it held anything, and its value as JSON text.
 * wire, optional: as for additionalFieldsSection. Pass it where the fields' paths are written from
 * the root of the response body, as the CRD detail's are.
 */
export function additionalFieldsTable(givenFields, wire) {
    const fields = wire ? maskedLikeTheWire(givenFields, wire) : givenFields;
    return table(['Path', 'Presence', 'Value as returned'], fields.map(field => [
        code(field.path),
        field.presence === 'VALUE' ? el('span', {class: 'quiet small'}, 'value') : presenceMark(field.presence),
        field.withheld ? el('span', {class: 'quiet small'}, 'withheld here: secrets were masked in this response and this value could not be matched '
            + 'to the masked body. The wire view shows the body as masked.')
            : field.value !== undefined && field.value !== null ? longValue(String(field.value))
            // An explicit null has no text beyond the word itself, and the word is the literal that was returned.
            : field.presence === 'NULL' ? code('null') : el('span', {class: 'quiet small'}, 'no text given')
    ]), {class: 'additional'});
}

// A value longer than this is shown by its start, with the whole of it in a fold beneath.
const LONG_VALUE = 600;

/** A returned value as literal text. A very long one is folded: nothing is cut, it is one press away. */
function longValue(value) {
    if (value.length <= LONG_VALUE) {
        return code(value);
    }
    return el('div', null,
        code(value.slice(0, LONG_VALUE / 2) + ' ...'),
        el('details', {class: 'additional__long'},
            el('summary', null, 'Show all ' + count(value.length) + ' characters'),
            code(value)));
}

/* ---------- 9. the wire view ---------- */

/**
 * The request as this tool built it and the response exactly as it arrived. Collapsed by default.
 * The text is preserved as it is: a long body scrolls and is never cut short.
 */
export function wireView(wire) {
    if (!wire) {
        return el('p', {class: 'quiet'}, el('strong', null, 'Wire view. '), 'This result carries no request and no response.');
    }
    const request = wire.request;
    const response = wire.response;
    const summaryParts = [];
    if (request) {
        summaryParts.push('request ' + (request.method || 'with no method') + (request.sent ? ', sent' : ', not sent'));
    }
    summaryParts.push(response
        ? (typeof response.status === 'number' ? 'response HTTP ' + response.status : 'no response arrived')
            + (typeof response.byteLength === 'number' ? ', ' + count(response.byteLength) + ' bytes' : '')
            + (response.maskedPlaces > 0 ? ', ' + count(response.maskedPlaces) + (response.maskedPlaces === 1 ? ' place' : ' places') + ' masked' : '')
        : 'no response');
    const node = el('details', {class: 'wire'},
        el('summary', null, 'Wire view', el('span', {class: 'quiet'}, summaryParts.join('; '))));
    node.append(el('div', {class: 'wire__sides'}, requestSide(request), responseSide(response)));
    if (wire.memberId) {
        node.append(el('p', {class: 'wire__memberid'}, 'The member ID: ', idForms(wire.memberId)));
    }
    return node;
}

function requestSide(request) {
    const side = el('div', {class: 'wire__side'}, el('h4', {class: 'sub-heading'}, 'Request'));
    if (!request) {
        side.append(el('p', {class: 'quiet'}, 'No request is recorded for this run.'));
        return side;
    }
    side.append(el('p', null, el('span', {class: request.sent ? null : 'wire__notsent'}, request.sent ? 'Sent.' : 'Not sent.'),
        request.note ? [' ', request.note] : null));
    side.append(facts([
        ['Method', request.method ? code(request.method) : presenceMark('ABSENT')],
        ['Url', request.url ? code(request.url) : el('span', {class: 'presence presence--none'}, 'none')],
        ['Url as configured', request.urlTemplate === undefined ? el('span', {class: 'presence presence--none'}, 'none')
            : request.urlTemplate === '' ? presenceMark('EMPTY') : code(request.urlTemplate)]
    ], 'facts--ruled'));
    side.append(headerTable(request.headers));
    side.append(bodyBlock(request.body, 'Request body', 'Copy request body'));
    return side;
}

function responseSide(response) {
    const side = el('div', {class: 'wire__side'}, el('h4', {class: 'sub-heading'}, 'Response'));
    if (!response) {
        side.append(el('p', {class: 'quiet'}, 'No response is recorded for this run.'));
        return side;
    }
    side.append(el('p', null, typeof response.status === 'number'
        ? ['HTTP ', el('strong', null, String(response.status)), '.']
        : el('strong', null, 'No response arrived.'),
        typeof response.byteLength === 'number' ? ' ' + count(response.byteLength) + ' bytes received.' : null,
        response.parsed ? ' Read as JSON.' : ' Not read as JSON.',
        response.parseError ? [' ', response.parseError] : null));
    if (response.maskNote) {
        // The note is there when something was masked, and also when a secret could not be looked for.
        side.append(el('p', {class: 'result__masked'}, response.maskNote));
    }
    side.append(headerTable(response.headers));
    side.append(bodyBlock(response.body, 'Response body', 'Copy response body'));
    return side;
}

function headerTable(headers) {
    if (!headers || headers.length === 0) {
        return el('p', {class: 'quiet'}, 'Headers: none.');
    }
    return table(['Header', 'Value'], headers.map(header => [code(header.name), code(header.value === undefined ? '' : header.value)]),
        {class: 'wire__headers'});
}

function bodyBlock(body, label, copyLabel) {
    if (body === undefined || body === null) {
        return el('p', {class: 'quiet'}, label + ': none.');
    }
    const text = el('pre', {class: 'body-text', tabIndex: 0, aria: {label}}, body);
    return el('div', {class: 'raw-body'},
        el('div', {class: 'panel__head'}, el('span', {class: 'sub-heading'}, label), copyButton(copyLabel, () => text.textContent, text)),
        text);
}

/* ---------- 10. the error panel ---------- */

// How the panel is drawn for each kind of error: a style, not words. The heading that says which
// kind of thing it is, and the title and action under it, are the server's (error.heading), so
// that the heading can say what happened on either profile, such as a timeout a mock scenario
// played where no environment failed. An error with no heading is shown under its kind's name.
const ERROR_FORMS = {
    VALIDATION: 'plain',
    CONFIGURATION: 'plain',
    BLOCKED: 'blocked',
    VENDOR_REJECTED: 'fault',
    VENDOR_ERROR: 'fault',
    TIMEOUT: 'fault',
    UNREACHABLE: 'fault',
    MALFORMED_RESPONSE: 'fault',
    NOT_FOUND: 'plain',
    INTERNAL: 'fault'
};

/**
 * The error panel, for the error of a run and for an error answered in place of a result.
 * options.headed: the section around the panel is already headed by error.heading, so the panel
 * gives only the HTTP status on that line.
 */
export function errorPanel(error, options) {
    const form = error.kind && Object.prototype.hasOwnProperty.call(ERROR_FORMS, error.kind) ? ERROR_FORMS[error.kind] : 'plain';
    const panel = el('div', {class: 'error-panel error-panel--' + form + ' error-panel--' + token(error.kind || 'none')});
    const status = typeof error.httpStatus === 'number' ? el('strong', null, String(error.httpStatus)) : null;
    if (options && options.headed) {
        if (status) {
            panel.append(el('p', {class: 'error-panel__kind'}, 'HTTP ', status));
        }
    } else if (error.kind) {
        panel.append(el('p', {class: 'error-panel__kind'}, error.heading ? error.heading : ['Error kind ', code(error.kind)],
            status ? [', HTTP ', status] : null));
    }
    panel.append(el('p', {class: 'error-panel__title'}, error.title));
    if (error.action) {
        panel.append(el('p', {class: 'error-panel__action'}, error.action));
    }
    if (error.reference) {
        panel.append(faultReport(error));
    }
    if (error.fieldErrors && error.fieldErrors.length > 0) {
        panel.append(table(['Field', 'Problem'], error.fieldErrors.map(problem => [code(problem.field), problem.message]), {class: 'pairs'}));
    }
    if (error.issues && error.issues.length > 0) {
        panel.append(el('p', {class: 'sub-heading'}, 'Issues the service returned (' + error.issues.length + ')'), issuesTable(error.issues));
    }
    return panel;
}

/** An OperationOutcome issue by issue, as the service sent it. Never reduced to a status and a sentence. */
export function issuesTable(issues) {
    return table(['Severity', 'Code', 'Details', 'Diagnostics', 'Expression', 'Location'], issues.map(issue => [
        shown(issue.severity, {as: 'literal'}),
        shown(issue.code, {as: 'literal'}),
        issueDetails(issue),
        shown(issue.diagnostics),
        issueList(issue.expression, issue.expressionPresence, issue.expressionPresent),
        issueList(issue.location, issue.locationPresence, issue.locationPresent)
    ]), {class: 'issues'});
}

/**
 * The details of an issue. Its text and codings alone are not returned all three times when the
 * details were not returned, null or {}, so the server sends what the details element was and the
 * cell says that in words. An answer that carries no presence is drawn from the text and codings.
 */
function issueDetails(issue) {
    const presence = issue.detailsPresence;
    if (presence && presence !== 'VALUE') {
        return presenceMark(presence);
    }
    return [shown(issue.detailsText), issue.detailsCodes && issue.detailsCodes.length > 0
        ? el('div', {class: 'small'}, shownList(issue.detailsCodes, {as: 'literal'})) : null];
}

/**
 * An expression or a location of an issue. The list alone is empty when the issue did not hold
 * the element, when it held null and when it held an empty array, so the server sends what the
 * element was beside it and the cell says that in words. A value that is not a list cannot be
 * drawn as one, and the cell says where it is shown instead. The flag is read only from an answer
 * that carries no presence.
 */
function issueList(values, presence, present) {
    if (Array.isArray(values) && values.length === 1 && values[0] && values[0].presence && values[0].presence !== 'VALUE') {
        // [null] is a list holding a null, and must not read as the element sent as null.
        return el('span', null, 'a list of one entry: ', shown(values[0], {as: 'literal'}));
    }
    if (Array.isArray(values) && values.length > 0) {
        return shownList(values, {as: 'literal'});
    }
    if (presence === 'VALUE') {
        return el('span', {class: 'small'}, 'not a list: shown among the fields returned but not interpreted');
    }
    if (presence) {
        return presenceMark(presence);
    }
    if (present === false) {
        return presenceMark('ABSENT');
    }
    if (present === true) {
        return presenceMark('EMPTY');
    }
    return shownList(values, {as: 'literal'});
}

/* ---------- a repeating element with nothing in it ---------- */

/**
 * What a repeating element of a designed view was, when the view has no entry of it to show.
 * An empty list on screen cannot say whether the element was not returned, returned as null or
 * returned empty, so each view sends the presence of its lists beside them (lists, keyed by the
 * element's name as the response spells it) and this says it in words: "links not returned",
 * "links null", "links empty". Null when there are entries to show, and when the answer carries
 * no presence for the element, so the caller keeps its own words for an older answer.
 */
export function listState(lists, name, count) {
    if (count > 0 || !lists || !lists[name]) {
        return null;
    }
    return el('p', null, listWords(lists[name], name));
}

/**
 * The same words inside a row of facts, for a list drawn as the value of the row. A list with
 * entries is drawn as it was; one with none says what it was, and only an answer with no
 * presence for it falls back to the mark that claims nothing more than "none returned".
 */
export function listValue(lists, name, values, options) {
    if (Array.isArray(values) && values.length > 0) {
        return shownList(values, options);
    }
    if (!lists || !lists[name]) {
        return shownList(values, options);
    }
    return listWords(lists[name], name);
}

function listWords(presence, name) {
    if (presence === 'VALUE') {
        // Something that is not a list, or a list none of whose entries is the shape the view
        // reads. Either way it stays among the fields returned but not interpreted.
        return [code(name), ' was returned, and none of its entries could be read as one.'];
    }
    return [code(name), ' ', presenceMark(presence)];
}
