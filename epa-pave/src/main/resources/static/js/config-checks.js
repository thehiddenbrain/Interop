// "Check this installation": the state of this copy of the tool in one place, for a tester to
// read out to support. The ledger, the procedure code list, the mode and what the tool is pointed
// at, and whether each lookup has somewhere to go.
//
// Each part is the server's answer, read when the view is shown and on request. A part whose
// answer did not arrive says so in its place; it is never left out, so a check never passes by
// omission.

import * as calls from './config-calls.js';
import {el, fill, button, code, facts, table, heading, whenText, count, copyButton} from './dom.js';
import {errorPanel} from './result.js';
import {environmentName, none, sentences} from './config-parts.js';

export function createChecksView(context) {
    let ledger = null;      // the latest answer of /api/runs/health
    let codeList = null;    // the latest answer of /api/procedure-codes/self-check
    let readAt = null;
    let reading = 0;

    // Not a live region: it holds whole tables, and a live region is read out whole on every
    // redraw. The tab's status line says in one sentence that the checks were read.
    const body = el('div', {class: 'config-checks'});
    const readNote = el('span', {class: 'quiet small'});
    const node = el('div', {class: 'config-view'},
        el('section', {class: 'result__section'},
            el('div', {class: 'panel__head'},
                heading(2, 'Check this installation'),
                el('span', {class: 'config-read'},
                    readNote,
                    button('Check again', () => read(), {class: 'button--quiet button--small'}),
                    copyButton('Copy as text for support', () => body.innerText, body))),
            el('p', {class: 'quiet'}, 'Everything below is what this copy of the tool reports about itself. '
                + 'Read it out, or copy it, when asking for help. It holds no member data and no secret.')),
        body);

    async function read() {
        const turn = ++reading;
        const [health, check] = await Promise.all([calls.ledgerHealth(), calls.codeListSelfCheck()]);
        if (turn !== reading) {
            return;
        }
        ledger = health;
        codeList = check;
        readAt = new Date();
        draw();
        const alarms = body.querySelectorAll('.config-check__alarm').length;
        context.announce('Checked at ' + whenText(readAt) + '. ' + (alarms === 0 ? 'Nothing needs attention.'
            : alarms === 1 ? 'One part needs attention.' : alarms + ' parts need attention.'));
    }

    function draw() {
        readNote.textContent = readAt ? 'Read from the tool at ' + whenText(readAt) + '.' : 'Reading.';
        fill(body,
            ledgerPart(),
            codeListPart(),
            modePart(),
            lookupsPart());
    }

    /* ---------- the ledger ---------- */

    function ledgerPart() {
        const section = (alarm, ...children) => el('section', {class: 'result__section config-check' + (alarm ? ' config-check--alarm' : '')},
            heading(3, 'The ledger of test runs'), children);
        if (!ledger) {
            return section(false, el('p', {class: 'quiet'}, 'Reading.'));
        }
        if (ledger.kind !== 'ok') {
            return section(true, el('p', null, el('strong', null, 'The ledger did not answer. Whether runs are being recorded is not known.')), errorPanel(ledger.body));
        }
        const health = ledger.body;
        const failed = typeof health.failedWrites === 'number' && health.failedWrites > 0;
        const alarm = health.enabled !== true || health.writable !== true || failed || Boolean(health.lastError);
        let headline;
        if (health.enabled !== true) {
            headline = 'Tracking is switched off. Runs are NOT recorded.';
        } else if (health.writable !== true) {
            headline = 'The ledger cannot be written. Runs are NOT being recorded.';
        } else if (failed) {
            headline = 'Runs are being recorded now, but ' + count(health.failedWrites) + (health.failedWrites === 1 ? ' write has' : ' writes have')
                + ' failed since the tool started. Those runs were NOT recorded.';
        } else {
            headline = 'Tracking is on, and runs are being recorded.';
        }
        return section(alarm,
            el('p', {class: alarm ? 'config-check__alarm' : 'config-check__fine'}, headline),
            facts([
                ['Tracking', health.enabled === true ? 'on' : el('strong', null, 'off')],
                ['Can be written', health.writable === true ? 'yes' : el('strong', null, 'no')],
                ['Directory', health.directory ? code(health.directory) : none('not stated')],
                ['Today\'s file', health.currentFile ? code(health.currentFile) : none('not stated')],
                ['Runs recorded today', typeof health.runsToday === 'number' ? count(health.runsToday) : el('strong', null, 'cannot say: today\'s file could not be read')],
                ['Last run written', health.lastWriteAt ? whenText(health.lastWriteAt) : none('none since the tool started')],
                ['Writes that failed', typeof health.failedWrites === 'number' ? (failed ? el('strong', null, count(health.failedWrites)) : '0') : none('not stated')],
                ['Last failure', health.lastError ? [el('strong', null, health.lastError), health.lastErrorAt ? ' at ' + whenText(health.lastErrorAt) : null] : null],
                ['Lines that could not be read', typeof health.unreadableLines === 'number'
                    ? (health.unreadableLines > 0 ? el('strong', null, count(health.unreadableLines) + '. They are skipped in the history and the export.') : '0')
                    : none('not stated')]
            ], 'facts--ruled config-facts'));
    }

    /* ---------- the procedure code list ---------- */

    function codeListPart() {
        const section = (alarm, ...children) => el('section', {class: 'result__section config-check' + (alarm ? ' config-check--alarm' : '')},
            heading(3, 'The procedure code list'), children);
        if (!codeList) {
            return section(false, el('p', {class: 'quiet'}, 'Reading.'));
        }
        if (codeList.kind !== 'ok') {
            return section(true, el('p', null, el('strong', null, 'The code list did not answer its self-check.')), errorPanel(codeList.body));
        }
        const list = codeList.body;
        const lines = Object.entries(list.linesOfBusiness || {});
        const columns = Object.entries(list.recognisedColumns || {});
        const unrecognised = list.unrecognisedColumns || [];
        // Said loudly, in large type, but not in the heavy frame: the key gives that frame to a fault
        // to report, and a placeholder list is a known state of a fresh installation, not a fault.
        return section(false,
            list.placeholder === true
                ? el('p', {class: 'config-check__alarm'}, 'The list in use is the synthetic placeholder, not a P32H export. '
                    + 'Every code, line of business and vendor it gives is made up.')
                : el('p', {class: 'config-check__fine'}, 'The list in use is a file supplied to this installation.'),
            facts([
                ['Source', list.source ? code(list.source) : none('not stated')],
                ['Format', list.format ? code(list.format) : none('not stated')],
                ['Rows read', count(list.rowCount)],
                ['Rows stating whether prior authorization is required', count(list.statingAuthRequirement)],
                ['Rows naming a delegated vendor', count(list.namingVendor)],
                ['Lines of business', lines.length > 0 ? lines.map(([name, rows], index) => [index > 0 ? ', ' : null, name, ' (' + count(rows) + ')'])
                    : el('strong', null, 'none: no row names a line of business')],
                ['Columns the file has that the tool does not read', unrecognised.length > 0
                    ? unrecognised.map((name, index) => [index > 0 ? ', ' : null, code(name)]) : 'none']
            ], 'facts--ruled config-facts'),
            sentences(list.warnings, 'config-warnings'),
            columns.length > 0 ? el('details', {class: 'fold'},
                el('summary', null, 'Which header each column was found under ', el('span', {class: 'quiet'}, '(' + columns.length + ')')),
                table(['Column', 'Header in the file'], columns.map(([column, header]) => [code(column), code(header)]), {class: 'pairs'})) : null);
    }

    /* ---------- the mode and what the tool is pointed at ---------- */

    function modePart() {
        const mode = context.app.mode();
        const section = (...children) => el('section', {class: 'result__section config-check'}, heading(3, 'The tool and where it is pointed'), children);
        if (!mode) {
            return section(el('p', null, el('strong', null, 'The tool has not answered /api/mode, so where it is pointed is not known.')));
        }
        const environment = mode.environment || {};
        const access = mode.writeAccess || {};
        const effective = context.stored().effective;
        return section(facts([
            ['Tool', [mode.name, ' ', mode.version]],
            ['Port', String(mode.port)],
            ['Profile', [el('strong', null, mode.mode), context.app.isMock() ? ': scenario files answer, and nothing leaves this machine' : ': requests are sent']],
            ['Target', mode.target],
            ['Active environment', environmentName(environment)],
            ['Writes', access.reason || none('not stated')],
            ['Production writes may be unlocked on this installation', effective
                ? [effective.allowProdWrites === true ? 'yes, by a tester who types the environment name' : 'no', floorSetting(effective)]
                : none('not read')],
            ['Configuration epoch', code(String(mode.epoch))],
            ['Problems with the configuration', effective
                ? ((effective.problems || []).length > 0 ? el('strong', null, effective.problems.length + ', listed under In force now') : 'none')
                : none('not read')]
        ], 'facts--ruled config-facts'));
    }

    /** The setting behind the answer, as the server reports it, so the words name the real property and its value. */
    function floorSetting(effective) {
        const setting = (effective.settings || []).find(candidate => candidate.property === 'config-store.allow-prod-writes');
        if (!setting) {
            return null;
        }
        return el('div', {class: 'small quiet'}, code(setting.property), ' ', setting.set ? ['is set to ', code(String(setting.value))] : 'is not set');
    }

    /* ---------- the lookups ---------- */

    function lookupsPart() {
        const effective = context.stored().effective;
        const section = (...children) => el('section', {class: 'result__section config-check'}, heading(3, 'Member and provider lookup'), children);
        if (!effective) {
            return section(el('p', {class: 'quiet'}, 'Reading the configuration.'));
        }
        const rows = (effective.operations || []).filter(row => row.protocol === 'LOOKUP');
        if (rows.length === 0) {
            return section(el('p', null, el('strong', null, 'The tool listed no lookup operation.')));
        }
        const settings = new Map((effective.settings || []).map(setting => [setting.property, setting]));
        return section(table(['Lookup', 'Where it is sent', 'Switched on', 'What the tool says'], rows.map(row => {
            const prefix = row.operation === 'MEMBER_LOOKUP' ? 'lookup.member.' : row.operation === 'PROVIDER_LOOKUP' ? 'lookup.provider.' : null;
            const enabled = prefix ? settings.get(prefix + 'enabled') : null;
            const target = row.target;
            return [
                el('strong', null, row.label),
                target && target.display ? [code(target.display, 'literal--address'), target.source ? el('div', {class: 'small quiet'}, target.source) : null]
                    : none('no address configured'),
                enabled ? switchedOn(enabled) : none('not stated'),
                [row.unavailable ? el('strong', null, row.unavailable) : null, row.blocked ? el('div', null, row.blocked) : null,
                    row.note ? el('div', {class: 'small'}, row.note) : null,
                    !row.unavailable && !row.blocked && !row.note ? 'Configured.' : null]
            ];
        }), {class: 'config-lookups'}));
    }

    /** A setting the server reports as text. true and false are said in words; anything else is shown as it was set. */
    function switchedOn(setting) {
        if (!setting.set) {
            return none('not set');
        }
        const value = String(setting.value).trim().toLowerCase();
        return value === 'true' ? 'yes' : value === 'false' ? 'no' : code(String(setting.value));
    }

    function show() {
        read();
    }

    return {node, show, draw};
}
