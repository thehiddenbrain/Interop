// The first view of the Config tab: what is in force now.
//
// It opens with "Ready to send?", a few lines a tester reads before the first run of the day:
// production or not, what is active and uploaded, how many operations can be sent, whether calls
// sign in, and which secrets have no value. Below that, from the server's own statements, where
// the tool is pointed, whether a write would be sent, and, operation by operation, the complete
// address a run would go to and whether anything stands in its way. Before anything has been
// uploaded the view says exactly what to upload first.

import {el, fill, clear, button, code, facts, heading, whenText} from './dom.js';
import {environmentName, sentences, none, flag, placeholder, requestMarks, addressWarnings} from './config-parts.js';
import {createWritesPanel} from './config-writes.js';
import {createTryPanel} from './config-try.js';

/**
 * Whether the active environment is production, in the words both the checklist and the facts
 * below it use, so that the two can never say it differently. The server decides; this page does
 * not read it off a name.
 */
function productionWords(access, environment) {
    return access.production === true
        ? el('strong', {class: 'config-production'}, 'Yes. The active environment is declared as production.')
        : environment.active ? 'No. The active environment is declared as ' + environment.kind + '.' : 'No environment is active.';
}

/** Names as a sentence lists them: "a", "a and b", "a, b and c". */
function listed(names) {
    return names.length <= 1 ? names.join('') : names.slice(0, -1).join(', ') + ' and ' + names[names.length - 1];
}

export function createInForceView(context) {
    const checklist = el('section', {class: 'result__section config-ready'});
    const pointed = el('section', {class: 'result__section'});
    const start = el('div', {class: 'config-start'});
    const writes = createWritesPanel(context);
    const operations = el('section', {class: 'result__section config-operations-section'});
    const trying = createTryPanel(context);
    // The checklist comes first: its Production line keeps the guardrail state on the first screen.
    const node = el('div', {class: 'config-view'}, checklist, pointed, start, writes.node, operations, trying.node);

    function draw() {
        const stored = context.stored();
        const mode = context.app.mode();
        if (!stored.effective || !mode) {
            clear(checklist);
            fill(pointed, heading(2, 'Where this tool is pointed'),
                el('p', {class: 'quiet'}, 'Reading the configuration from the tool.'));
            clear(start);
            clear(operations);
            return;
        }
        drawChecklist(stored, mode);
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
                ['Production', productionWords(access, environment)],
                ['Can this tool change anything there now', access.reason
                    ? [el('strong', null, access.writesAllowed === true ? 'Writes are not blocked. ' : 'Writes are blocked. '), access.reason]
                    : none('not stated')],
                ['Uploaded files are kept in', code(effective.storeDirectory)]
            ], 'facts--ruled config-facts'),
            problems(effective.problems || []),
            activeAddressWarnings(stored));
    }

    /* ---------- ready to send? ---------- */

    function drawChecklist(stored, mode) {
        const readiness = stored.effective.readiness || {};
        const access = mode.writeAccess || {};
        const environment = mode.environment || {};
        // A line that needs action carries the mark an operation row that cannot be sent carries.
        const line = (word, needsAction, ...content) => el('li', {class: needsAction ? 'config-op--missing' : null},
            el('strong', null, word + ': '), content);
        const link = (text, view) => button(text, () => context.go(view), {class: 'button--text'});
        const lines = [line('Production', false, productionWords(access, environment))];
        if (context.app.isMock()) {
            lines.push(line('Mock profile', false, 'scenario files answer and nothing is sent. Signing in does not apply.'));
        }
        // On mock a run is answered from a scenario with no environment, so none active is not marked as a step to take.
        lines.push(line('Environment', !readiness.environmentActive && !context.app.isMock(), readiness.environmentLine + ' ',
            link(readiness.environmentActive ? 'Environments' : 'Make one active', 'environments')));
        if (!context.app.isMock()) {
            lines.push(line('Collection', readiness.collections === 0,
                readiness.collections === 0 ? 'none is uploaded. '
                    : readiness.collections + ' uploaded, ' + readiness.mapped + (readiness.mapped === 1 ? ' request' : ' requests') + ' mapped. ',
                link(readiness.collections === 0 ? 'Upload a collection' : 'Collections', 'collections')));
            lines.push(line('Operations', readiness.sendable < readiness.outbound,
                readiness.sendable + ' of ' + readiness.outbound + ' can be sent. ',
                button('Show which', () => operations.scrollIntoView({block: 'start'}), {class: 'button--text'})));
            lines.push(signInLine(readiness.signIn || {}, line));
            const missing = readiness.missingSecrets || [];
            lines.push(line('Secrets with no value', missing.length > 0, missing.length === 0 ? 'none.'
                : missing.map((secret, index) => [index > 0 ? ', ' : null, code(secret.name),
                    secret.readsFrom ? [' (reads ', code(secret.readsFrom), ', which is not set)'] : ' (blank)']).concat(['.'])));
        }
        fill(checklist, heading(2, 'Ready to send?'), el('ul', {class: 'config-checklist'}, lines));
    }

    /** Whether calls sign in. The tool never switches it on for the tester: it says that it is off, and how to switch it on. */
    function signInLine(signIn, line) {
        if (signIn.enabled) {
            return signIn.problem
                ? line('Signing in to the service', true, 'on, and it cannot work: ' + signIn.problem)
                : line('Signing in to the service', false, 'on.');
        }
        const names = signIn.environmentSignInVariables || [];
        const helpers = signIn.requestHelpers || [];
        const count = signIn.operationsWithHelper || 0;
        return line('Signing in to the service', names.length > 0 || helpers.length > 0, 'off.',
            names.length > 0 ? el('p', {class: 'small'}, 'The active environment holds ', listed(names),
                names.length === 1 ? ', which reads as a sign-in setting. Nothing uses it' : ', which read as sign-in settings. Nothing uses them',
                ', so every call goes without signing in. To sign in, start the tool with ', code(signIn.howToSwitchOn),
                '. The README paragraph "Access tokens", under "Configuration through Postman", says more.') : null,
            helpers.length > 0 ? el('p', {class: 'small'}, count === 1
                ? 'A Postman sign-in helper is set on the request used for 1 operation (' + listed(helpers) + '). This tool does not apply it, so that call goes without signing in.'
                : 'Postman sign-in helpers are set on the requests used for ' + count + ' operations (' + listed(helpers) + '). This tool does not apply them, so those calls go without signing in.') : null);
    }

    /** What the tool warns about the addresses of the active environment, as the list of environments carries it. */
    function activeAddressWarnings(stored) {
        const active = ((stored.environments || {}).environments || []).find(environment => environment.active);
        return active ? addressWarnings(active.name, active.addressWarnings) : null;
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
                el('tbody', null, rows.map(row => operationRow(row, effective.readiness || {}))))));
    }

    function operationRow(row, readiness) {
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
            el('td', null, statusCell(row, readiness)));
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
                    button('Send as written', () => context.go('raw'), {class: 'button--text'})]
                : none(((context.stored().collections || {}).collections || []).length === 0 ? 'no collection is uploaded' : 'every request is mapped');
        }
        return [
            target && target.source ? el('span', null, target.source) : null,
            target && target.requestName ? requestMarks({scripted: target.scripted}) : null,
            !target && requests.length > 0 ? el('span', null, requests.length === 1 ? 'One sample request of a collection is listed.' : requests.length + ' sample requests of a collection are listed.') : null,
            target && others > 0 ? el('div', {class: 'small quiet'}, others === 1 ? 'One more request is mapped to it.' : others + ' more requests are mapped to it.') : null,
            row.defaultRule ? el('details', {class: 'fold'}, el('summary', null, 'Which request is used'),
                el('p', {class: 'small'}, row.defaultRule)) : null,
            !target && requests.length === 0 && row.outbound ? none('nothing is mapped') : null
        ];
    }

    function statusCell(row, readiness) {
        const parts = [];
        if (row.blocked) {
            // The short form the server gives for a row: the reason in full is said once, with the writes, not on every write row.
            parts.push(el('div', {class: 'config-op__blocked', title: row.blocked}, el('strong', null, 'Blocked by the production guardrail. '), row.blockedShort || row.blocked));
        }
        if (row.unavailable) {
            parts.push(el('div', {class: row.outbound ? 'config-op__unavailable' : null}, row.outbound ? el('strong', null, 'Cannot be sent. ') : null, row.unavailable));
        }
        if (row.outbound && !row.blocked && !row.unavailable && row.operation !== 'UNMAPPED') {
            const signIn = readiness.signIn;
            if (signIn && signIn.enabled && signIn.problem) {
                parts.push(el('div', {class: 'config-op__unavailable'}, el('strong', null, 'Cannot sign in. '), signIn.problem));
            } else if (row.withoutSignIn) {
                // The server's reason: a Postman helper the tool does not apply, or sign-in settings nothing uses.
                parts.push(el('strong', null, 'Yes, without signing in.'), el('div', {class: 'small'}, row.withoutSignIn));
            } else {
                parts.push(el('strong', null, 'Yes.'));
            }
        }
        if (row.note) {
            parts.push(el('div', {class: 'small'}, row.note));
        }
        if (row.outbound && row.operation !== 'UNMAPPED') {
            const control = button('Show how it resolves', () => trying.tryOperation(row.operation),
                {class: 'button--text small', aria: {label: 'Show how it resolves: ' + row.label}});
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
