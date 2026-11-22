// Configuration > Advanced > Check this installation: the state of this copy of PAVE in one place,
// for a tester to read out or copy to support. The ledger, the procedure code list's self-check,
// the mode and what PAVE is pointed at, and whether each lookup has somewhere to go.
//
// Each part is the server's answer, read when the page opens and on request. A part whose answer
// did not arrive says so in its place; it is never left out, so a check never passes by omission.
// Nothing here holds member data or a secret: the server masks every setting it reports.

import {call} from './api.js';
import {app} from './app.js';
import {el, fill, chip, button, fold, kv, copyButton} from './dom.js';
import {errorPanel} from './fault.js';
import {pageHead, section, part, during, sentences, none, counted, productionWords} from './cfg-parts.js';

const mono = text => el('span', {class: 'mono'}, text);

/** A time as the server wrote it, to the second, in UTC: the same on every tester's screen. */
function when(instant) {
    return typeof instant === 'string' && instant.length >= 19 ? instant.slice(0, 10) + ' ' + instant.slice(11, 19) + ' UTC' : String(instant || '');
}

/** Draws the page into root. route: the parts of the address after #configuration. */
export function mount(root, route) {
    let gone = false;
    const busy = el('span', {class: 'muted small', role: 'status'});
    const body = el('div');
    const again = button('Check again', () => during(again, busy, 'Running the checks', read), {small: true});
    const copy = copyButton('Copy as text for support', () => body.innerText !== undefined ? body.innerText : body.textContent, {small: true});
    const page = section(pageHead('Check this installation', 'What this copy of PAVE reports about itself. No member data and no secret.', busy, again, copy),
        body);
    root.append(page);
    const off = app.on('config', () => {
        if (!again.disabled) {
            during(again, busy, 'Running the checks', read);
        }
    });

    async function read() {
        // The lookups are among the operations of /api/config/effective: release 1 has no page of saved lookups to ask.
        const [health, codes, effective] = await Promise.all([
            call('GET', '/api/runs/health'), call('GET', '/api/procedure-codes/self-check'),
            call('GET', '/api/config/effective')]);
        if (gone) {
            return;
        }
        const settled = effective.ok ? effective.data || {} : null;
        fill(body,
            ledgerPart(health),
            codesPart(codes),
            modePart(app.mode(), settled, effective),
            lookupsPart(settled, effective));
    }

    during(again, busy, 'Running the checks', read);
    return {
        update() {},
        unmount() {
            gone = true;
            off();
            page.remove();
        }
    };
}

/** A part whose answer did not come: said in its place with the server's or the page's words. */
function noAnswer(title, sentence, answer) {
    return part(title, el('p', null, chip('to check', {tone: 'action'}), ' ', el('strong', null, sentence)), errorPanel(answer.error));
}

function ledgerPart(answer) {
    const title = 'The ledger of test runs';
    if (!answer.ok) {
        return noAnswer(title, 'The ledger did not answer. Whether runs are being recorded is not known.', answer);
    }
    const health = answer.data || {};
    const failed = typeof health.failedWrites === 'number' && health.failedWrites > 0;
    const alarm = health.enabled !== true || health.writable !== true || failed || Boolean(health.lastError);
    let headline;
    if (health.enabled !== true) {
        headline = 'Tracking is switched off. Runs are NOT recorded.';
    } else if (health.writable !== true) {
        headline = 'The ledger cannot be written. Runs are NOT being recorded.';
    } else if (failed) {
        headline = 'Runs are being recorded now, but ' + counted(health.failedWrites, 'write has', 'writes have')
            + ' failed since PAVE started. Those runs were NOT recorded.';
    } else {
        headline = 'Tracking is on, and runs are being recorded.';
    }
    const unreadableFiles = health.unreadableFiles && typeof health.unreadableFiles === 'object' ? Object.entries(health.unreadableFiles) : [];
    return part(title,
        el('p', null, alarm ? [chip('to check', {tone: 'action'}), ' '] : null, el('strong', null, headline)),
        kv([
            {key: 'Tracking', value: health.enabled === true ? 'on' : el('strong', null, 'off')},
            {key: 'Can be written', value: health.writable === true ? 'yes' : el('strong', null, 'no')},
            {key: 'Folder', value: health.directory ? mono(health.directory) : none('not stated')},
            {key: 'Today\'s file', value: health.currentFile ? mono(health.currentFile) : none('not stated')},
            {key: 'Runs recorded today', value: typeof health.runsToday === 'number' ? String(health.runsToday)
                : el('strong', null, 'cannot say: today\'s file could not be read')},
            {key: 'Last run written', value: health.lastWriteAt ? when(health.lastWriteAt) : none('none since PAVE started')},
            {key: 'Writes that failed', value: typeof health.failedWrites === 'number' ? (failed ? el('strong', null, String(health.failedWrites)) : '0')
                : none('not stated')},
            health.lastError ? {key: 'Last failure', value: [el('strong', null, health.lastError),
                health.lastErrorAt ? ' at ' + when(health.lastErrorAt) : null]} : null,
            {key: 'Lines that could not be read', value: typeof health.unreadableLines === 'number'
                ? (health.unreadableLines > 0 ? el('strong', null, health.unreadableLines + '. They are left out of Audit and the export.') : '0')
                : none('not stated')},
            unreadableFiles.length > 0 ? {key: 'Files that could not be read', value: el('strong', null,
                unreadableFiles.map(([file, count]) => file + ' (' + count + ')').join(', '))} : null
        ]));
}

function codesPart(answer) {
    const title = 'The procedure code list';
    if (!answer.ok) {
        return noAnswer(title, 'The code list did not answer its self-check.', answer);
    }
    const list = answer.data || {};
    const lines = Object.entries(list.linesOfBusiness || {});
    const columns = Object.entries(list.recognisedColumns || {});
    const unrecognised = Array.isArray(list.unrecognisedColumns) ? list.unrecognisedColumns : [];
    const count = value => (typeof value === 'number' ? String(value) : none('not stated'));
    return part(title,
        el('p', null, list.placeholder === true
            ? [chip('placeholder', {tone: 'action'}), ' ', el('strong', null, 'The list in use is a placeholder, not a P32H export.')]
            : 'The list in use is a file supplied to this installation.'),
        kv([
            {key: 'Source', value: list.source ? mono(list.source) : none('not stated')},
            {key: 'Format', value: list.format ? mono(list.format) : none('not stated')},
            {key: 'Rows read', value: count(list.rowCount)},
            {key: 'Rows stating whether prior authorization is required', value: count(list.statingAuthRequirement)},
            {key: 'Rows naming a delegated vendor', value: count(list.namingVendor)},
            {key: 'Lines of business', value: lines.length > 0 ? lines.map(([name, rows]) => name + ' (' + rows + ')').join(', ')
                : el('strong', null, 'none: no row names a line of business')},
            {key: 'Columns the file has that PAVE does not read', value: unrecognised.length > 0 ? mono(unrecognised.join(', ')) : 'none'}
        ]),
        sentences(list.warnings),
        columns.length > 0 ? fold({summary: 'Which header each column was found under', hint: String(columns.length),
            body: kv(columns.map(([column, header]) => ({key: mono(column), value: mono(header)})))}) : null);
}

function modePart(mode, effective, effectiveAnswer) {
    const title = 'PAVE and where it is pointed';
    if (!mode) {
        return part(title, el('p', null, el('strong', null, 'PAVE has not answered /api/mode, so where it is pointed is not known.')));
    }
    const environment = mode.environment || {};
    const access = mode.writeAccess || {};
    const build = mode.build && typeof mode.build === 'object' ? mode.build : null;
    const floor = effective ? (Array.isArray(effective.settings) ? effective.settings : [])
        .find(setting => setting.property === 'config-store.allow-prod-writes') : null;
    const problems = effective && Array.isArray(effective.problems) ? effective.problems : null;
    return part(title,
        kv([
            {key: 'PAVE', value: [mode.name || '', ' ', mode.version || '']},
            build && build.time ? {key: 'Built', value: when(build.time)} : null,
            {key: 'Port', value: String(mode.port)},
            {key: 'Profile', value: [el('strong', null, String(mode.mode)), mode.mode === 'mock'
                ? ': scenario files answer, and nothing leaves this machine' : ': requests are sent']},
            {key: 'Target', value: mode.target || none('not stated')},
            {key: 'Active environment', value: environment.active ? environment.name + ' (' + environment.kind + ')' : none('none active')},
            {key: 'Production', value: productionWords(access, environment)},
            {key: 'Writes', value: access.reason || none('not stated')},
            {key: 'Production writes may be unlocked here', value: effective
                ? [effective.allowProdWrites === true ? 'yes, by a tester who types the environment name' : 'no',
                    floor ? el('span', {class: 'kv__display'}, floor.property + (floor.set ? ' is set to ' + floor.value : ' is not set')) : null]
                : none('not read')},
            {key: 'Configuration epoch', value: mono(String(mode.epoch))},
            {key: 'Configuration store', value: effective && effective.storeDirectory ? mono(effective.storeDirectory) : none('not read')},
            {key: 'Problems with the configuration', value: problems === null ? none('not read')
                : problems.length > 0 ? el('strong', null, problems.length + ', listed under In force now') : 'none'}
        ]),
        effectiveAnswer.ok ? null : errorPanel(effectiveAnswer.error));
}

function lookupsPart(effective, effectiveAnswer) {
    const title = 'Member and provider lookup';
    if (!effective) {
        return noAnswer(title, 'The configuration did not answer, so the lookups are not known.', effectiveAnswer);
    }
    const rows = (Array.isArray(effective.operations) ? effective.operations : []).filter(row => row.protocol === 'LOOKUP');
    if (rows.length === 0) {
        return part(title, el('p', null, el('strong', null, 'PAVE listed no lookup operation.')));
    }
    return part(title,
        kv(rows.map(row => {
            const target = row.target || {};
            return {key: row.label || row.operation, value: [
                typeof row.lookupEnabled === 'boolean' ? (row.lookupEnabled ? 'on' : el('strong', null, 'off')) : none('on or off not stated'),
                ', ',
                target.display ? mono(target.display) : none('no address configured'),
                target.source || target.sourceLabel ? el('span', {class: 'kv__display'}, target.source || target.sourceLabel) : null,
                row.unavailable ? el('div', null, el('strong', null, row.unavailable)) : null,
                row.blocked ? el('div', null, row.blocked) : null,
                row.note ? el('div', {class: 'small muted'}, row.note) : null
            ]};
        })));
}
