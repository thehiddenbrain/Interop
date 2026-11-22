// Configuration > Advanced > In force now: what a run would use, read from the server.
//
// It opens with "Ready to send?", the few lines a tester reads before the first run of the day:
// production or not, what is active and uploaded, how many operations can be sent, whether calls
// sign in, and which secrets have no value. Below that, where PAVE is pointed, the problems the
// server sees, and operation by operation the address a run would go to and what stands in its
// way, the settings of application.yml with secrets masked by the server, and the lookups in
// force. A part is drawn only when the server's answer carries its field.

import {call} from './api.js';
import {app} from './app.js';
import {el, fill, clear, chip, button, fold, kv} from './dom.js';
import {errorPanel} from './fault.js';
import {pageHead, section, part, productionWords, during, sentences, none} from './cfg-parts.js';

/** Names as a sentence lists them: "a", "a and b", "a, b and c". */
function listed(names) {
    return names.length <= 1 ? names.join('') : names.slice(0, -1).join(', ') + ' and ' + names[names.length - 1];
}

/** Draws the page into root. route: the parts of the address after #configuration. */
export function mount(root, route) {
    let gone = false;
    const busy = el('span', {class: 'muted small', role: 'status'});
    const again = button('Read again', () => during(again, busy, 'Reading', load), {small: true});
    const body = el('div');
    // What an action of this page did, kept above the parts so a redraw after it does not take it away.
    const said = el('div', {'aria-live': 'polite'});
    const page = section(pageHead('In force now', 'What a run would use now, as PAVE reads its configuration.', busy, again), said, body);
    root.append(page);

    async function load() {
        const [effective, environments, lookups] = await Promise.all([
            call('GET', '/api/config/effective'), call('GET', '/api/config/environments'), call('GET', '/api/config/lookups')]);
        if (gone) {
            return;
        }
        if (!effective.ok) {
            fill(body, errorPanel(effective.error));
            return;
        }
        const data = effective.data || {};
        const mode = app.mode() || {};
        fill(body,
            checklist(data, mode),
            unreadablePart(data),
            pointed(data, mode, environments.ok ? environments.data : null),
            operationsPart(data),
            settingsPart(data),
            lookups.ok ? lookupsPart(lookups.data) : null);
    }

    /* ---------- ready to send? ---------- */

    function checklist(data, mode) {
        const readiness = data.readiness;
        if (!readiness) {
            return null;
        }
        const access = mode.writeAccess || data.writeAccess || {};
        const environment = mode.environment || data.environment || {};
        // A line that needs action is marked, so it is found first.
        const line = (word, needsAction, ...content) => el('li', null,
            needsAction ? chip('to do', {tone: 'action'}) : null, needsAction ? ' ' : null, el('strong', null, word + ': '), content);
        const link = (text, hash) => button(text, () => app.go(hash), {small: true, quiet: true});
        const lines = [line('Production', false, productionWords(access, environment))];
        if (app.isMock()) {
            lines.push(line('Mock profile', false, 'scenario files answer and nothing is sent. Signing in does not apply.'));
        }
        // On mock a run is answered from a scenario with no environment, so none active is not a step to take.
        lines.push(line('Environment', !readiness.environmentActive && !app.isMock(), (readiness.environmentLine || '') + ' ',
            link(readiness.environmentActive ? 'Open Environments' : 'Make one active', '#configuration/environments')));
        if (!app.isMock()) {
            lines.push(line('Collection', readiness.collections === 0,
                readiness.collections === 0 ? 'none is uploaded. '
                    : readiness.collections + ' uploaded, ' + readiness.mapped + (readiness.mapped === 1 ? ' request' : ' requests') + ' mapped. ',
                link(readiness.collections === 0 ? 'Upload a collection' : 'Open Collections', '#configuration/collections')));
            lines.push(line('Operations', readiness.sendable < readiness.outbound, readiness.sendable + ' of ' + readiness.outbound + ' can be sent.'));
            if (readiness.signIn) {
                lines.push(signInLine(readiness.signIn, line));
            }
            const missing = Array.isArray(readiness.missingSecrets) ? readiness.missingSecrets : [];
            lines.push(line('Secrets with no value', missing.length > 0, missing.length === 0 ? 'none.'
                : missing.map((secret, index) => [index > 0 ? ', ' : null, el('span', {class: 'mono'}, secret.name),
                    secret.readsFrom ? [' (reads ', el('span', {class: 'mono'}, secret.readsFrom), ', which is not set)'] : ' (blank)']).concat(['.'])));
        }
        return part('Ready to send?', el('ul', {class: 'notes'}, lines));
    }

    /** Whether calls sign in. PAVE never switches it on for the tester: it says that it is off, and how to switch it on. */
    function signInLine(signIn, line) {
        if (signIn.enabled) {
            return signIn.problem
                ? line('Signing in to the service', true, 'on, and it cannot work: ' + signIn.problem)
                : line('Signing in to the service', false, 'on.');
        }
        const names = Array.isArray(signIn.environmentSignInVariables) ? signIn.environmentSignInVariables : [];
        const helpers = Array.isArray(signIn.requestHelpers) ? signIn.requestHelpers : [];
        const count = signIn.operationsWithHelper || 0;
        return line('Signing in to the service', names.length > 0 || helpers.length > 0, 'off.',
            names.length > 0 ? el('p', {class: 'small'}, 'The active environment holds ', listed(names),
                names.length === 1 ? ', which reads as a sign-in setting. Nothing uses it' : ', which read as sign-in settings. Nothing uses them',
                ', so every call goes without signing in.',
                signIn.howToSwitchOn ? [' To sign in, start PAVE with ', el('span', {class: 'mono'}, signIn.howToSwitchOn), '.'] : null) : null,
            helpers.length > 0 ? el('p', {class: 'small'}, count === 1
                ? 'A Postman sign-in helper is set on the request used for 1 operation (' + listed(helpers) + '). PAVE does not apply it, so that call goes without signing in.'
                : 'Postman sign-in helpers are set on the requests used for ' + count + ' operations (' + listed(helpers) + '). PAVE does not apply them, so those calls go without signing in.') : null);
    }

    /* ---------- stored files PAVE could not read ---------- */

    /**
     * A stored file that could not be read is left out at start and listed here, with the server's
     * line about it (plan R-32). Removing it moves it aside in the store; the server says what it did.
     */
    function unreadablePart(data) {
        const files = Array.isArray(data.unreadable) ? data.unreadable.filter(each => each && each.file) : [];
        if (files.length === 0) {
            return null;
        }
        return part('Stored files PAVE could not read', el('div', {class: 'verdict verdict--action'},
            el('p', {class: 'verdict__headline'}, files.length === 1 ? 'One stored file was left out, as PAVE could not read it.'
                : files.length + ' stored files were left out, as PAVE could not read them.'),
            kv(files.map(each => {
                const busyLine = el('span', {class: 'muted small', role: 'status'});
                const remove = button('Remove it from the store',
                    () => during(remove, busyLine, 'Removing', () => removeUnreadable(each.file)), {small: true});
                return {key: el('span', {class: 'mono'}, each.file),
                    value: el('div', {class: 'form-section'}, each.line ? el('div', null, each.line) : null, el('div', null, remove, ' ', busyLine))};
            }))));
    }

    async function removeUnreadable(file) {
        clear(said);
        const answer = await call('POST', '/api/config/unreadable/remove', {body: {file}});
        if (gone) {
            return;
        }
        if (!answer.ok) {
            fill(said, errorPanel(answer.error));
            return;
        }
        // removed names the file the server moved; until its shape is known, a value that is not a name is not shown.
        const removed = answer.data && typeof answer.data.removed === 'string' && answer.data.removed !== '' ? answer.data.removed : file;
        fill(said, el('p', {role: 'status'}, 'Removed ', el('span', {class: 'mono'}, removed),
            ' from the configuration store.'));
        await load();
    }

    /* ---------- where PAVE is pointed ---------- */

    function pointed(data, mode, environments) {
        const access = mode.writeAccess || data.writeAccess || {};
        const environment = mode.environment || data.environment || {};
        const active = environments && Array.isArray(environments.environments)
            ? environments.environments.find(each => each.active) : null;
        const warnings = active && Array.isArray(active.addressWarnings) ? active.addressWarnings : [];
        const problems = Array.isArray(data.problems) ? data.problems : [];
        return part('Where PAVE is pointed',
            kv([
                {key: 'Profile', value: app.isMock()
                    ? 'mock. Scenario files answer in place of the service and nothing is sent. The production guardrail applies as on live.'
                    : String(mode.mode || data.mode || '')},
                mode.target ? {key: 'Target', value: mode.target} : null,
                {key: 'Environment', value: environment.active ? el('span', {class: 'environment-name'}, environment.name) : none('none is active')},
                {key: 'Production', value: productionWords(access, environment)},
                access.reason ? {key: 'Can PAVE change anything there now', value: [el('strong', null,
                    access.writesAllowed === true ? 'Writes are not blocked. ' : 'Writes are blocked. '), access.reason]} : null,
                data.storeDirectory ? {key: 'The configuration store', value: el('span', {class: 'mono'}, data.storeDirectory)} : null
            ]),
            problems.length > 0 ? el('div', {class: 'verdict verdict--fault', role: 'alert'},
                el('p', {class: 'verdict__headline'}, problems.length === 1 ? 'The configuration has a problem.' : 'The configuration has ' + problems.length + ' problems.'),
                sentences(problems)) : null,
            warnings.length > 0 ? el('div', {class: 'verdict verdict--action'},
                el('p', {class: 'verdict__headline'}, 'About the addresses of ' + active.name), sentences(warnings)) : null);
    }

    /* ---------- every operation, where it goes, and what stands in its way ---------- */

    function operationsPart(data) {
        const rows = (Array.isArray(data.operations) ? data.operations : []).filter(row => row.operation !== 'UNMAPPED');
        if (rows.length === 0) {
            return null;
        }
        const signIn = data.readiness ? data.readiness.signIn : null;
        // Facts rather than a wide table: an address and its source are long, and they wrap here.
        return part('Where each operation goes', kv(rows.map(row => ({
            key: [row.label || row.operation, row.method ? [' ', chip(row.method, {mono: true})] : null,
                row.write ? [' ', chip('write', {tone: 'blocked', title: 'On production it is blocked unless writes are unlocked.'})] : null],
            value: el('div', {class: 'form-section'}, el('div', null, addressCell(row)), el('div', {class: 'small muted'}, sourceCell(row)),
                el('div', null, statusCell(row, signIn)))
        }))));
    }

    function addressCell(row) {
        const target = row.target;
        if (!target) {
            return none(row.outbound ? 'no address' : 'nothing is sent');
        }
        const unresolved = Array.isArray(target.unresolved) ? target.unresolved : [];
        return [
            target.display ? el('span', {class: 'mono'}, target.display) : none('none'),
            unresolved.length > 0 ? el('div', {class: 'small'}, el('strong', null, 'No value for '),
                unresolved.map((name, index) => [index > 0 ? ', ' : null, el('span', {class: 'mono'}, '{{' + name + '}}')])) : null
        ];
    }

    function sourceCell(row) {
        const target = row.target;
        const requests = Array.isArray(row.requests) ? row.requests : [];
        return [
            target && target.source ? el('span', null, target.source) : null,
            target && target.scripted ? [' ', chip('script, not run', {tone: 'neutral'})] : null,
            !target && requests.length > 0 ? el('span', null, requests.length === 1 ? 'One request of a collection is listed.'
                : requests.length + ' requests of a collection are listed.') : null,
            row.defaultRule ? fold({summary: 'Which request is used', inline: true, body: el('p', {class: 'small'}, row.defaultRule)}) : null
        ];
    }

    function statusCell(row, signIn) {
        const parts = [];
        if (row.blocked) {
            parts.push(el('div', {title: row.blocked}, el('strong', null, 'Blocked by the production guardrail. '), row.blockedShort || row.blocked));
        }
        if (row.unavailable) {
            parts.push(el('div', null, row.outbound ? el('strong', null, 'Cannot be sent. ') : null, row.unavailable));
        }
        if (row.outbound && !row.blocked && !row.unavailable && row.operation !== 'UNMAPPED') {
            if (signIn && signIn.enabled && signIn.problem) {
                parts.push(el('div', null, el('strong', null, 'Cannot sign in. '), signIn.problem));
            } else if (row.withoutSignIn) {
                // The server's reason: a Postman helper PAVE does not apply, or sign-in settings nothing uses.
                parts.push(el('strong', null, 'Yes, without signing in.'), el('div', {class: 'small'}, row.withoutSignIn));
            } else {
                parts.push(el('strong', null, 'Yes.'));
            }
        }
        if (row.note) {
            parts.push(el('div', {class: 'small'}, row.note));
        }
        return parts;
    }

    /* ---------- application.yml, secrets masked by the server ---------- */

    function settingsPart(data) {
        const settings = Array.isArray(data.settings) ? data.settings : [];
        if (settings.length === 0) {
            return null;
        }
        return part(null, fold({summary: 'Settings of application.yml', hint: settings.length + ' settings, secrets masked', body: () =>
            kv(settings.map(setting => ({key: el('span', {class: 'mono'}, setting.property),
                value: [setting.set ? el('span', {class: 'mono'}, setting.value || '') : none('not set'),
                    setting.secret ? [' ', chip('secret, masked', {tone: 'neutral'})] : null]})))}));
    }

    /* ---------- the lookups in force ---------- */

    function lookupsPart(data) {
        if (!data || typeof data !== 'object') {
            return null;
        }
        const rows = [];
        for (const [kind, label] of [['member', 'Member lookup'], ['provider', 'Provider lookup']]) {
            const view = data[kind];
            if (view && view.inForce && view.inForce.words) {
                rows.push({key: label, value: [view.inForce.words, view.inForce.from ? el('span', {class: 'muted'}, ', ' + view.inForce.from) : null,
                    view.problem ? el('div', {class: 'small'}, view.problem) : null]});
            }
        }
        return rows.length > 0 ? part('Lookups in force', kv(rows)) : null;
    }

    const off = app.on('config', () => load());
    load();

    return {
        update() {},
        unmount() {
            gone = true;
            off();
            clear(page);
            page.remove();
        }
    };
}
