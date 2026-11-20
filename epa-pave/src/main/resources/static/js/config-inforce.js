// The first view of the Config tab: what is in force now.
//
// It answers, from the server's own statements, where the tool is pointed, whether that is
// production, whether a write would be sent, and, operation by operation, the complete address a
// run would go to and whether anything stands in its way. This is the table a tester checks
// before the first run of the day. Before anything has been uploaded the view says exactly what
// to upload first.

import {el, fill, clear, button, code, facts, heading, whenText} from './dom.js';
import {environmentName, sentences, none, flag, placeholder, requestMarks} from './config-parts.js';
import {createWritesPanel} from './config-writes.js';
import {createTryPanel} from './config-try.js';

export function createInForceView(context) {
    const pointed = el('section', {class: 'result__section'});
    const start = el('div', {class: 'config-start'});
    const writes = createWritesPanel(context);
    const operations = el('section', {class: 'result__section config-operations-section'});
    const trying = createTryPanel(context);
    const node = el('div', {class: 'config-view'}, pointed, start, writes.node, operations, trying.node);

    function draw() {
        const stored = context.stored();
        const mode = context.app.mode();
        if (!stored.effective || !mode) {
            fill(pointed, heading(2, 'Where this tool is pointed'),
                el('p', {class: 'quiet'}, 'Reading the configuration from the tool.'));
            clear(start);
            clear(operations);
            return;
        }
        drawPointed(stored, mode);
        drawStart(stored);
        writes.draw();
        drawOperations(stored.effective);
        trying.draw();
    }

    function drawPointed(stored, mode) {
        const effective = stored.effective;
        const access = mode.writeAccess || {};
        const environment = mode.environment || {};
        fill(pointed,
            el('div', {class: 'panel__head'},
                heading(2, 'Where this tool is pointed'),
                el('span', {class: 'config-read'},
                    el('span', {class: 'quiet small'}, 'Read from the tool at ', whenText(stored.readAt), '.'),
                    button('Read again', () => context.changed(), {class: 'button--quiet button--small'}))),
            facts([
                ['Profile', profile(mode)],
                ['Target', el('strong', null, mode.target)],
                ['Environment', [environmentName(environment), environment.active ? null
                    : el('span', null, ' Until one is active, an address can only come from application.yml.')]],
                // The server says whether the active environment is production. This page does not read it off a name.
                ['Production', access.production === true
                    ? el('strong', {class: 'config-production'}, 'Yes. The active environment is declared as production.')
                    : environment.active ? 'No. The active environment is declared as ' + environment.kind + '.' : 'No environment is active.'],
                ['Can this tool change anything there now', access.reason
                    ? [el('strong', null, access.writesAllowed === true ? 'Writes are not blocked. ' : 'Writes are blocked. '), access.reason]
                    : none('not stated')],
                ['Uploaded files are kept in', code(effective.storeDirectory)]
            ], 'facts--ruled config-facts'),
            problems(effective.problems || []));
    }

    function profile(mode) {
        if (context.app.isMock()) {
            return [el('span', {class: 'hatch', aria: {hidden: 'true'}}), el('strong', null, mode.mode),
                el('span', null, '. Scenario files answer in place of the service and nothing is sent. What is configured here still '
                    + 'decides the address every request is shown with, and the production guardrail applies as it does on the live profile.')];
        }
        return [el('strong', null, mode.mode), el('span', null, '. Requests are sent to the addresses this view shows.')];
    }

    function problems(list) {
        if (list.length === 0) {
            return null;
        }
        return el('div', {class: 'form-problem', role: 'alert'},
            el('p', {class: 'form-problem__title'}, list.length === 1 ? 'The configuration has a problem.' : 'The configuration has ' + list.length + ' problems.'),
            sentences(list));
    }

    /* ---------- what to do first ---------- */

    function drawStart(stored) {
        const environments = stored.environments.environments || [];
        const collections = stored.collections.collections || [];
        const active = environments.some(environment => environment.active);
        if (environments.length > 0 && collections.length > 0 && active) {
            clear(start);
            return;
        }
        const nothing = environments.length === 0 && collections.length === 0;
        const step = (done, text, control) => el('li', {class: done ? 'config-step config-step--done' : 'config-step'},
            el('span', null, done ? el('strong', null, 'Done. ') : null, text), done ? null : control);
        fill(start, el('section', {class: 'result__section config-first'},
            el('p', {class: 'empty-state__lead'}, nothing
                ? 'Nothing has been uploaded yet. Start with the Postman environment file the vendor gave you.'
                : 'The configuration is not complete yet.'),
            el('p', null, 'The vendor supplies two Postman files: an environment and a collection. Upload them here and nobody has to edit a file by hand.'),
            el('ol', {class: 'config-steps'},
                step(environments.length > 0, 'Upload the Postman environment file. It holds the addresses and the credentials of one environment. '
                    + 'You say whether it is a sandbox, UAT or production: the tool never guesses that.',
                    button('Upload an environment', () => context.go('environments'), {class: 'button--primary'})),
                step(active, 'Make that environment the active one. It takes effect at once, on every tab, with no restart.',
                    environments.length > 0 ? button('Choose the active environment', () => context.go('environments')) : null),
                step(collections.length > 0, 'Upload the Postman collection. Its requests are mapped to the operations of this tool, '
                    + 'and its sample bodies become the starting point on the CRD, DTR and PAS tabs.',
                    button('Upload a collection', () => context.go('collections'), {class: environments.length > 0 ? 'button--primary' : 'button--quiet'}))),
            context.app.isMock()
                ? el('p', {class: 'quiet'}, 'On the mock profile CRD, DTR and PAS can be run before anything is uploaded: scenario files answer, and nothing is sent.')
                : null));
    }

    /* ---------- every operation, where it goes, and what stands in its way ---------- */

    function drawOperations(effective) {
        const rows = effective.operations || [];
        const sendable = rows.filter(row => row.outbound && row.operation !== 'UNMAPPED');
        const stuck = sendable.filter(row => row.unavailable || row.blocked).length;
        fill(operations,
            heading(2, 'Where each operation is sent'),
            el('p', {class: 'quiet'}, 'The complete address a run of each operation goes to, under the configuration in force now. ',
                stuck === 0 ? 'Nothing stands in the way of any of them.'
                    : el('strong', null, stuck === 1 ? 'One of them cannot be sent as things stand.' : stuck + ' of them cannot be sent as things stand.')),
            el('div', {class: 'table-scroll'}, el('table', {class: 'grid config-operations'},
                el('thead', null, el('tr', null,
                    el('th', {scope: 'col'}, 'Operation'),
                    el('th', {scope: 'col'}, 'Address a run goes to'),
                    el('th', {scope: 'col'}, 'Where it comes from'),
                    el('th', {scope: 'col'}, 'Can it be sent now'))),
                el('tbody', null, rows.map(operationRow)))));
    }

    function operationRow(row) {
        const target = row.target || null;
        const missing = Boolean(row.unavailable) && row.outbound;
        const classes = ['config-op'];
        if (missing) {
            classes.push('config-op--missing');
        }
        if (row.blocked) {
            classes.push('config-op--blocked');
        }
        if (!row.outbound) {
            classes.push('config-op--inside');
        }
        return el('tr', {class: classes.join(' ')},
            el('th', {scope: 'row'},
                el('span', {class: 'config-op__label'}, row.label),
                el('div', {class: 'config-marks'},
                    row.method ? code(row.method) : null,
                    row.write ? flag('write', 'This operation changes something at the far side. On production it is blocked unless writes are unlocked.') : null)),
            el('td', null, addressCell(row, target)),
            el('td', null, sourceCell(row, target)),
            el('td', null, statusCell(row)));
    }

    function addressCell(row, target) {
        if (!target) {
            return row.operation === 'UNMAPPED'
                ? none((row.requests || []).length > 0 ? 'each one is sent to its own address' : 'there is none to send')
                : none(row.outbound ? 'no address' : 'nothing is sent');
        }
        return [
            target.display ? code(target.display, 'literal--address') : none('none'),
            target.template && target.template !== target.display
                ? el('div', {class: 'small quiet'}, 'written as ', code(target.template)) : null,
            (target.unresolved || []).length > 0
                ? el('div', {class: 'config-op__unresolved'}, el('strong', null, 'No value for '),
                    target.unresolved.map((name, index) => [index > 0 ? ', ' : null, placeholder(name)]))
                : null,
            (target.tokens || []).length > 0
                ? el('div', {class: 'small'}, 'filled when the run is made: ',
                    target.tokens.map((name, index) => [index > 0 ? ', ' : null, code('{' + name + '}')]))
                : null
        ];
    }

    function sourceCell(row, target) {
        const requests = row.requests || [];
        const others = requests.filter(available => !available.usedByDefault).length;
        if (row.operation === 'UNMAPPED') {
            return requests.length > 0
                ? [requests.length === 1 ? 'One request of a collection is not mapped. ' : requests.length + ' requests of a collection are not mapped. ',
                    button('Send one as written', () => context.go('raw'), {class: 'button--text'})]
                : none(((context.stored().collections || {}).collections || []).length === 0 ? 'no collection is uploaded' : 'every request is mapped');
        }
        return [
            target && target.source ? el('span', null, target.source) : null,
            target && target.requestName ? requestMarks({scripted: target.scripted}) : null,
            !target && requests.length > 0 ? el('span', null, requests.length === 1 ? 'One sample request of a collection is listed.' : requests.length + ' sample requests of a collection are listed.') : null,
            target && others > 0 ? el('div', {class: 'small quiet'}, others === 1 ? 'One more request is mapped to it.' : others + ' more requests are mapped to it.') : null,
            row.defaultRule ? el('div', {class: 'small'}, row.defaultRule) : null,
            !target && requests.length === 0 && row.outbound ? none('nothing is mapped') : null
        ];
    }

    function statusCell(row) {
        const parts = [];
        if (row.blocked) {
            // The short form the server gives for a row: the reason in full is said once, with the writes, not on every write row.
            parts.push(el('div', {class: 'config-op__blocked', title: row.blocked}, el('strong', null, 'Blocked by the production guardrail. '), row.blockedShort || row.blocked));
        }
        if (row.unavailable) {
            parts.push(el('div', {class: row.outbound ? 'config-op__unavailable' : null}, row.outbound ? el('strong', null, 'Cannot be sent. ') : null, row.unavailable));
        }
        if (row.outbound && !row.blocked && !row.unavailable && row.operation !== 'UNMAPPED') {
            parts.push(el('strong', null, 'Yes.'));
        }
        if (row.note) {
            parts.push(el('div', {class: 'small'}, row.note));
        }
        if (row.outbound && row.operation !== 'UNMAPPED') {
            const control = button('How it resolves', () => trying.tryOperation(row.operation),
                {class: 'button--text small', aria: {label: 'Show how ' + row.label + ' resolves'}});
            parts.push(el('div', null, control));
        }
        return parts;
    }

    function show(argument) {
        if (argument === 'try') {
            trying.node.scrollIntoView({block: 'start'});
        }
    }

    return {node, show, draw, configChanged: () => trying.configChanged()};
}
