// The result view. One function draws the result of a run for every protocol, in one order:
//
//   1. the verdict strip      what the service said, in its tone
//   2. the test result        what was expected, what happened, whether they match
//   3. the next step          what to do now, with the button that does it when the tool can
//   4. the ledger line        recorded, or NOT recorded
//   5. the findings           what a person should look at, apart from the determination
//   6. the determination      the standard codes
//   7. the response detail    the protocol's own part, passed in by the tab
//   8. the additional fields  everything returned that the view above did not use
//   9. the wire view          the request as built and the response as received, collapsed
//  10. the error panel        when the call failed, was refused, or the tool failed after it
//
// Nothing here is decided in the browser. The verdict, the test result, the next step and the
// findings are worded by the server and shown as sent. The authorization outcome and the test
// result are two facts and are drawn as two different things: a strip in a tone, and a stamp.

import {el, append, button, code, facts, table, heading, copyButton, count, millis, token} from './dom.js';
import {shown, shownList, presenceMark} from './shown.js';
import {toneClass, stamp, slowFlag, mockMark} from './marks.js';

/**
 * Draws one run result.
 *
 * options.detail(envelope)    returns the protocol's own part of the view, or null
 * options.detailTitle         the heading of that part
 * options.nextStepControl(nextStep, envelope)  returns {label, run} when the next step is
 *                             something the tool can do from here, otherwise null
 */
export function renderResult(envelope, options) {
    const settings = options || {};
    const root = el('article', {class: 'result', aria: {label: 'Result of run ' + envelope.runId}});
    root.append(
        el('div', {class: 'result__top'}, verdictStrip(envelope), testResult(envelope.expectation)),
        runFacts(envelope, settings),
        findingsSection(envelope.findings || []),
        determinationSection(envelope.determination || []));

    const detailTitle = settings.detailTitle || 'Response detail';
    const detailNode = envelope.detail !== undefined && envelope.detail !== null && settings.detail ? settings.detail(envelope) : null;
    root.append(section(heading(3, detailTitle),
        detailNode || el('p', {class: 'quiet'}, 'This run has no response detail. The verdict above says why.')));

    root.append(additionalFieldsSection(envelope.additionalFields || []));
    root.append(el('section', {class: 'result__section'}, wireView(envelope.wire)));
    if (envelope.error) {
        root.append(section(heading(3, 'What went wrong'), errorPanel(envelope.error)));
    }
    return root;
}

function section(...children) {
    return append(el('section', {class: 'result__section'}), children);
}

/* ---------- 1. the verdict strip ---------- */

function verdictStrip(envelope) {
    const verdict = envelope.verdict;
    const mock = envelope.profile === 'mock';
    const strip = el('div', {class: 'verdict ' + toneClass(verdict.tone) + (mock ? ' verdict--mock' : '')});
    if (mock) {
        strip.append(el('span', {class: 'visually-hidden'}, 'Mock profile: this answer came from a scenario file. '));
    }
    strip.append(el('h2', {class: 'verdict__headline'}, verdict.headline));
    const supporting = verdict.supporting || [];
    if (supporting.length > 0) {
        strip.append(el('ul', {class: 'verdict__supporting'}, supporting.map(line => el('li', null, line))));
    }
    strip.append(el('p', {class: 'verdict__foot'},
        mock ? mockMark() : null,
        el('span', null, 'Outcome code ', code(verdict.outcomeCode, 'verdict__code'))));
    return strip;
}

/* ---------- 2. the test result ---------- */

function testResult(expectation) {
    const declared = expectation.expectedLabel || expectation.expected;
    const typedDiffers = expectation.expected && expectation.expected !== expectation.expectedLabel;
    return el('div', {class: 'testresult'},
        el('h3', {class: 'testresult__title'}, 'Test result'),
        stamp(expectation.result, 'large'),
        facts([
            ['Expected', declared
                ? [declared, typedDiffers ? el('div', {class: 'small'}, code(expectation.expected)) : null]
                : el('span', {class: 'presence presence--none', title: 'No expectation was sent with this run.'}, 'nothing declared')],
            ['Happened', [expectation.actualLabel, el('div', {class: 'small'}, code(expectation.actual))]]
        ]),
        el('p', {class: 'testresult__explanation'}, expectation.explanation));
}

/* ---------- 3 and 4. the next step, the ledger line, and the facts of the run ---------- */

function runFacts(envelope, settings) {
    const rows = [
        ['Next step', nextStep(envelope, settings)],
        ['Ledger', ledgerLine(envelope.ledger, envelope.runId)],
        ['Response time', responseTime(envelope.timing)],
        ['Run', runLine(envelope)]
    ];
    const memberId = envelope.wire ? envelope.wire.memberId : null;
    if (memberId && memberId.changed) {
        // The tool removes spaces from a member ID on the way out. It is said here, in the open,
        // and not only inside the collapsed wire view.
        rows.push(['Member ID', ['Typed as ', code(memberId.typed), ' and sent as ', code(memberId.sent), '.']]);
    }
    return facts(rows, 'runfacts facts--ruled');
}

function nextStep(envelope, settings) {
    const step = envelope.nextStep;
    const control = settings.nextStepControl ? settings.nextStepControl(step, envelope) : null;
    return el('div', {class: 'nextstep'},
        el('div', {class: 'nextstep__row'},
            el('span', {class: 'nextstep__headline'}, step.headline),
            control ? button(control.label, control.run, {class: 'button--primary'}) : null),
        step.reason ? el('p', {class: 'nextstep__reason'}, step.reason) : null);
}

function ledgerLine(ledger, runId) {
    if (ledger.recorded) {
        return el('div', {class: 'ledger'}, el('strong', null, 'Recorded.'), ' ',
            typeof ledger.runsToday === 'number'
                ? 'The ledger holds ' + count(ledger.runsToday) + (ledger.runsToday === 1 ? ' run' : ' runs') + ' for today.'
                : 'The ledger did not say how many runs it holds for today.');
    }
    return el('div', {class: 'ledger ledger--missing', role: 'alert'},
        el('p', {class: 'ledger__state'}, 'NOT recorded'),
        el('p', null, 'The ledger did not keep run ', code(runId), '. It said:'),
        el('blockquote', null, ledger.message));
}

function responseTime(timing) {
    if (!timing) {
        return el('span', {class: 'presence presence--none', title: 'The result carries no timing.'}, 'not measured');
    }
    return [el('strong', null, millis(timing.elapsedMillis)), timing.slow ? [' ', slowFlag()] : null,
        el('span', {class: 'quiet'}, timing.slow ? ' Longer than the threshold of ' : ' The threshold for slow is ',
            millis(timing.thresholdMillis), '.')];
}

function runLine(envelope) {
    const environment = envelope.environment || {};
    const parts = [code(envelope.runId), ' ', copyButton('Copy run id', () => envelope.runId)];
    const where = el('div', {class: 'small quiet'}, code(envelope.operation), ' against ',
        environment.active ? environment.name + ' (' + environment.kind + ')' : 'no environment',
        envelope.profile ? ', ' + envelope.profile + ' profile' : null);
    const chain = envelope.chainId ? el('div', {class: 'small quiet'}, 'In chain ', code(envelope.chainId)) : null;
    return [parts, where, chain];
}

/* ---------- 5. the findings ---------- */

function findingsSection(findings) {
    if (findings.length === 0) {
        return section(heading(3, 'Findings', 0), el('p', {class: 'quiet'}, 'None were raised on this run.'));
    }
    return section(heading(3, 'Findings', findings.length),
        el('p', {class: 'quiet'}, 'Something a person should look at. A finding is not a failure of the call.'),
        el('div', null, findings.map(finding)));
}

function finding(item) {
    const node = el('div', {class: 'finding'},
        el('p', {class: 'finding__title'}, item.title),
        item.detail ? el('p', null, item.detail) : null);
    if (item.codeListSays && item.serviceSays) {
        // A disagreement is two positions. Either side can be the one that is wrong, so the two
        // are set side by side with the same weight and neither is marked.
        node.append(el('div', {class: 'positions'},
            position('The code list', item.codeListSays),
            position('The service', item.serviceSays)));
    } else if (item.codeListSays || item.serviceSays) {
        // One position only: the finding is about what one side said, not a comparison of the two.
        node.append(el('div', {class: 'positions positions--one'},
            item.codeListSays ? position('The code list', item.codeListSays) : position('The service', item.serviceSays)));
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
    const grouped = rows.some(row => row.subject);
    const body = [];
    let subject;
    for (const row of rows) {
        if (grouped && row.subject !== subject) {
            subject = row.subject;
            body.push(el('tr', {class: 'group'}, el('th', {colSpan: 4, scope: 'colgroup'},
                subject ? ['For ', code(subject)] : 'For the request as a whole')));
        }
        body.push(determinationRow(row));
    }
    const head = el('thead', null, el('tr', null,
        ['What', 'Code returned', 'Meaning', 'Element'].map(text => el('th', {scope: 'col'}, text))));
    return section(heading(3, 'Determination'), el('table', {class: 'grid determination'}, head, el('tbody', null, body)));
}

function determinationRow(row) {
    const returned = row.presence === 'VALUE' && row.code !== undefined;
    const cells = [el('td', null, row.label)];
    if (returned) {
        cells.push(el('td', null, code(row.code)));
        // A row can hold an identifier, such as an authorization number, which has no meaning to state.
        cells.push(el('td', null, row.display || el('span', {class: 'quiet small'}, 'no meaning to state'),
            row.recognised === false ? [' ', el('span', {class: 'flag'}, 'not a code this tool knows')] : null));
    } else {
        // Nothing was returned for this element, and that is said across both columns: never a blank cell.
        cells.push(el('td', {colSpan: 2}, presenceMark(row.presence || 'ABSENT')));
    }
    cells.push(el('td', null, row.system ? code(row.system) : el('span', {class: 'quiet small'}, 'not stated')));
    return el('tr', {class: row.recognised === false ? 'determination--unrecognised' : null}, cells);
}

/* ---------- 8. the additional fields ---------- */

/** Everything the response carried that the designed view did not use, each entry presented as what it is. */
export function additionalFieldsSection(fields, title) {
    const label = title || 'Returned but not interpreted';
    if (fields.length === 0) {
        return section(heading(3, label, 0), el('p', {class: 'quiet'}, 'Nothing was returned that is not shown above.'));
    }
    const notes = fields.filter(field => field.kind === 'NOTE');
    const bodies = fields.filter(field => field.kind === 'RAW_BODY');
    const plain = fields.filter(field => field.kind !== 'NOTE' && field.kind !== 'RAW_BODY');
    const node = section(heading(3, label, fields.length),
        el('p', {class: 'quiet'}, 'The response carried these and this tool has no designed view for them. They are shown so that nothing is dropped.'));
    if (notes.length > 0) {
        node.append(el('ul', {class: 'note-list'}, notes.map(note => el('li', null, el('strong', null, 'About the body: '), note.value))));
    }
    for (const body of bodies) {
        const text = el('pre', {class: 'body-text body-text--wrap', tabIndex: 0, aria: {label: 'The body as received'}}, body.value === undefined ? '' : body.value);
        node.append(el('div', {class: 'raw-body'},
            el('p', null, el('strong', null, 'The body as received.'), ' It could not be read as JSON, so it is shown whole. ', code(body.path)),
            text, copyButton('Copy this body', () => text.textContent, text)));
    }
    if (plain.length > 0) {
        node.append(additionalFieldsTable(plain));
    }
    return node;
}

/** The table of returned fields: where each sat, whether it held anything, and its value as JSON text. */
export function additionalFieldsTable(fields) {
    return table(['Path', 'Presence', 'Value as returned'], fields.map(field => [
        code(field.path),
        field.presence === 'VALUE' ? el('span', {class: 'quiet small'}, 'value') : presenceMark(field.presence),
        field.value !== undefined && field.value !== null ? code(field.value)
            // An explicit null has no text beyond the word itself, and the word is the literal that was returned.
            : field.presence === 'NULL' ? code('null') : el('span', {class: 'quiet small'}, 'no text given')
    ]), {class: 'additional'});
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
        : 'no response');
    const node = el('details', {class: 'wire'},
        el('summary', null, 'Wire view', el('span', {class: 'quiet'}, summaryParts.join('; '))));
    node.append(el('div', {class: 'wire__sides'}, requestSide(request), responseSide(response)));
    if (wire.memberId) {
        node.append(el('p', {class: 'wire__memberid'}, wire.memberId.changed
            ? ['The member ID was typed as ', code(wire.memberId.typed), ' and sent as ', code(wire.memberId.sent), '.']
            : ['The member ID was sent as typed: ', code(wire.memberId.sent), '.']));
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

// How each kind of error is announced. The server words what happened and what to do; this says
// which kind of thing it is, because the tester's next move differs: fix the form, fix the
// configuration, fix the test data, or report the environment. A kind this page does not know
// is shown under its own name.
const ERROR_KINDS = {
    VALIDATION: {label: 'A problem with what was submitted', form: 'plain'},
    CONFIGURATION: {label: 'About the configuration of this tool, not about the request', form: 'plain'},
    BLOCKED: {label: 'Refused by the production guardrail', form: 'blocked'},
    VENDOR_REJECTED: {label: 'The service rejected the request', form: 'fault'},
    VENDOR_ERROR: {label: 'A fault in the environment: the service failed', form: 'fault'},
    TIMEOUT: {label: 'A fault in the environment: no answer in time', form: 'fault'},
    UNREACHABLE: {label: 'A fault in the environment: the service could not be reached', form: 'fault'},
    MALFORMED_RESPONSE: {label: 'A fault in the environment: the answer could not be read', form: 'fault'},
    NOT_FOUND: {label: 'Not found in this tool', form: 'plain'},
    INTERNAL: {label: 'A failure of this tool, not of the service', form: 'fault'}
};

/** The error panel, for the error of a run and for an error answered in place of a result. */
export function errorPanel(error) {
    const known = error.kind && Object.prototype.hasOwnProperty.call(ERROR_KINDS, error.kind) ? ERROR_KINDS[error.kind] : null;
    const panel = el('div', {class: 'error-panel error-panel--' + (known ? known.form : 'plain') + ' error-panel--' + token(error.kind || 'none')});
    if (error.kind) {
        panel.append(el('p', {class: 'error-panel__kind'}, known ? known.label : ['Error kind ', code(error.kind)],
            typeof error.httpStatus === 'number' ? [', HTTP ', el('strong', null, String(error.httpStatus))] : null));
    }
    panel.append(el('p', {class: 'error-panel__title'}, error.title));
    if (error.action) {
        panel.append(el('p', {class: 'error-panel__action'}, error.action));
    }
    if (error.reference) {
        panel.append(el('p', {class: 'error-panel__reference'}, 'Reference ', code(error.reference), ' ',
            copyButton('Copy reference', () => error.reference)));
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
        [shown(issue.detailsText), issue.detailsCodes && issue.detailsCodes.length > 0
            ? el('div', {class: 'small'}, shownList(issue.detailsCodes, {as: 'literal'})) : null],
        shown(issue.diagnostics),
        shownList(issue.expression, {as: 'literal'}),
        shownList(issue.location, {as: 'literal'})
    ]), {class: 'issues'});
}
