// Configuration > Members and Providers: the saved test members and test providers (plan 3.4, R-12).
//
// One module serves both addresses, because the two lists differ only in their fields: the frame
// mounts it once and calls update(['providers']) or update(['members']) when the tester moves
// between them, so the page is drawn again for the other list. Entries are kept as the tester
// typed them; nothing here looks a member up or fills a field the tester did not fill.
//
// A CSV file is read in the page when its button is pressed (files.js, R-33) and its text is
// posted as JSON (R-30). The server first answers a dry run, and the page shows the columns it
// used and left out and what each row would do before anything is saved (history M19, C13). The
// text that is imported is the text that was shown, kept in this page's memory until the dialog
// closes, never read from the file a second time.

import {call} from './api.js';
import {el, fill, clear, chip, button, field, setFieldError, modal, named} from './dom.js';
import {errorPanel} from './fault.js';
import {readText} from './files.js';
import {pageHead, section, part, during, counted, sentences, none, fileRefusal, fileChooser, shownFile, filesChangedNote, labelsOf} from './cfg-parts.js';

const OFF_LINE = 'Saved test lists are switched off on this installation (test-lists.enabled).';

const GENDERS = ['female', 'male', 'other', 'unknown'];

// The two lists: their words, their address, and the fields of an entry in the order the form and
// the server's record give them. key is the property the server reads and answers with.
const LISTS = {
    members: {
        path: '/api/lists/members',
        entries: 'members',
        title: 'Test members',
        line: 'Sandbox test members only. Kept in the configuration store, never in the ledger or the log.',
        one: 'member',
        many: 'members',
        empty: 'No test member is saved yet. Add one, or import a CSV file.',
        fields: [
            {key: 'label', label: 'Label'},
            {key: 'memberId', label: 'Member ID', mono: true, placeholder: 'S123456789'},
            {key: 'firstName', label: 'First name'},
            {key: 'lastName', label: 'Last name'},
            {key: 'dateOfBirth', label: 'Date of birth', mono: true, placeholder: 'yyyy-MM-dd'},
            {key: 'gender', label: 'Gender', choices: GENDERS},
            {key: 'lineOfBusiness', label: 'Line of business'},
            {key: 'plan', label: 'Plan'},
            {key: 'subscriberId', label: 'Subscriber ID', mono: true},
            {key: 'coverageStatus', label: 'Coverage status'},
            {key: 'coverageStart', label: 'Coverage start', mono: true, placeholder: 'yyyy-MM-dd'},
            {key: 'coverageEnd', label: 'Coverage end', mono: true, placeholder: 'yyyy-MM-dd'},
            {key: 'vendorPatientId', label: 'Vendor patient id', mono: true}
        ],
        columns: [
            {label: 'Label', value: entry => entry.label},
            {label: 'Member ID', value: entry => entry.memberId, mono: true},
            // An entry read from a collection may hold the vendor's patient id alone.
            {label: 'Vendor patient id', value: entry => entry.vendorPatientId, mono: true},
            {label: 'Name', value: entry => joined(entry.firstName, entry.lastName)},
            {label: 'Date of birth', value: entry => entry.dateOfBirth, mono: true},
            {label: 'Line of business', value: entry => entry.lineOfBusiness}
        ]
    },
    providers: {
        path: '/api/lists/providers',
        entries: 'providers',
        title: 'Test providers',
        line: 'Sandbox test providers only. Kept in the configuration store, never in the ledger or the log.',
        one: 'provider',
        many: 'providers',
        empty: 'No test provider is saved yet. Add one, or import a CSV file.',
        fields: [
            {key: 'label', label: 'Label'},
            {key: 'npi', label: 'NPI', mono: true},
            {key: 'vendorPractitionerId', label: 'Vendor practitioner id', mono: true},
            {key: 'firstName', label: 'First name'},
            {key: 'lastName', label: 'Last name'},
            {key: 'organization', label: 'Organization'},
            {key: 'specialty', label: 'Specialty'},
            {key: 'phone', label: 'Phone', mono: true},
            {key: 'fax', label: 'Fax', mono: true},
            {key: 'placeOfService', label: 'Place of service', mono: true}
        ],
        columns: [
            {label: 'Label', value: entry => entry.label},
            {label: 'NPI', value: entry => entry.npi, mono: true},
            {label: 'Vendor practitioner id', value: entry => entry.vendorPractitionerId, mono: true},
            {label: 'Name', value: entry => joined(joined(entry.firstName, entry.lastName), entry.organization, ', ')},
            {label: 'Place of service', value: entry => entry.placeOfService, mono: true}
        ]
    }
};

// What a row of an import does, in the words of the table, with the tone of its chip.
const STATUS = {
    ADD: {words: 'Add', tone: 'clear'},
    REPLACE: {words: 'Replace', tone: 'neutral'},
    REFUSED: {words: 'Refused', tone: 'stop'}
};

function joined(first, second, between) {
    return [first, second].filter(value => typeof value === 'string' && value.trim() !== '').join(between || ' ');
}

/** The list an address names: providers for #configuration/providers, members otherwise. */
function kindOf(route) {
    return Array.isArray(route) && route[0] === 'providers' ? 'providers' : 'members';
}

/** Draws the page into root. route: the parts of the address after #configuration. */
export function mount(root, route) {
    let gone = false;
    let kind = kindOf(route);
    let loads = 0;           // a list read for an earlier address is dropped when it arrives late
    const host = el('div');
    root.append(host);

    function draw() {
        const list = LISTS[kind];
        const busy = el('span', {class: 'muted small', role: 'status'});
        const outcome = el('div', {'aria-live': 'polite'});
        const table = el('div');
        const add = button('Add ' + list.one, () => edit(list, null), {primary: true});
        const importing = button('Import CSV', () => importDialog(list));
        // A plain link: the template is a file of placeholders the browser saves, never member data.
        const template = el('a', {class: 'btn', href: list.path + '/template.csv', download: 'test-' + list.many + '-template.csv'}, 'CSV template');
        const controls = el('span', {class: 'inline-controls', hidden: true}, add, importing, template);
        fill(host, section(pageHead(list.title, list.line, controls, busy), outcome, table));
        return {list, controls, outcome, table, busy};
    }

    let view = draw();

    async function load() {
        const asked = ++loads;
        const list = LISTS[kind];
        const answer = await call('GET', list.path);
        if (gone || asked !== loads) {
            return;
        }
        if (!answer.ok) {
            view.controls.hidden = true;
            fill(view.table, errorPanel(answer.error));
            return;
        }
        const data = answer.data || {};
        if (data.enabled === false) {
            view.controls.hidden = true;
            fill(view.table, part(null, el('p', {class: 'muted'}, OFF_LINE)));
            return;
        }
        view.controls.hidden = false;
        const entries = Array.isArray(data[list.entries]) ? data[list.entries] : [];
        fill(view.table,
            typeof data.problem === 'string' && data.problem !== ''
                ? el('div', {class: 'verdict verdict--action'}, el('p', {class: 'verdict__headline'}, data.problem)) : null,
            entries.length === 0 ? part(null, el('p', {class: 'muted'}, list.empty)) : part(null, table(list, entries)));
    }

    function table(list, entries) {
        return el('div', {class: 'table-wrap'}, el('table', {class: 'table'},
            el('thead', null, el('tr', null, list.columns.map(column => el('th', {scope: 'col'}, column.label)),
                el('th', {scope: 'col'}, el('span', {class: 'visually-hidden'}, 'Actions')))),
            el('tbody', null, entries.map(entry => el('tr', null,
                list.columns.map(column => {
                    const value = column.value(entry);
                    return el('td', null, typeof value === 'string' && value !== ''
                        ? (column.mono ? el('span', {class: 'mono'}, value) : value) : none('not given'));
                }),
                el('td', null, el('button', {type: 'button', class: 'btn btn--small',
                    'aria-label': 'Edit ' + (entry.label || entry.memberId || entry.vendorPatientId || entry.npi || entry.vendorPractitionerId || list.one),
                    on: {click: () => edit(list, entry)}}, 'Edit')))))));
    }

    /** The Add and Edit modal. entry: the stored entry, or null for a new one. */
    function edit(list, entry) {
        const inputs = {};
        const wrappers = {};
        const fields = list.fields.map(spec => {
            let input;
            if (spec.choices) {
                input = el('select', {class: 'select', name: spec.key},
                    el('option', {value: ''}, 'Not stated'),
                    spec.choices.map(choice => el('option', {value: choice}, choice)));
                const stored = entry && typeof entry[spec.key] === 'string' ? entry[spec.key] : '';
                if (stored !== '' && !spec.choices.includes(stored)) {
                    input.append(el('option', {value: stored}, stored));
                }
                input.value = stored;
            } else {
                input = el('input', {type: 'text', class: spec.mono ? 'input mono' : 'input', name: spec.key, autocomplete: 'off',
                    spellcheck: false, placeholder: spec.placeholder || null});
                // The stored entry is the tester's own, typed or imported earlier; a new entry starts empty.
                input.value = entry && typeof entry[spec.key] === 'string' ? entry[spec.key] : '';
            }
            inputs[spec.key] = input;
            wrappers[spec.key] = field({label: spec.label, input});
            return wrappers[spec.key];
        });
        const problems = el('div', {'aria-live': 'polite'});
        const line = el('span', {class: 'muted small', role: 'status'});
        const noun = list.one.charAt(0).toUpperCase() + list.one.slice(1);
        modal({
            title: (entry ? 'Edit ' : 'Add ') + list.one,
            body: [el('div', {class: 'form-grid'}, fields), problems],
            footer: dialog => {
                const save = button('Save ' + list.one, () => during(save, line, 'Saving', () => saveEntry(dialog)), {primary: true});
                const remove = entry ? button('Remove ' + list.one, () => {
                    dialog.close();
                    confirmRemove(list, entry);
                }, {quiet: true, remove: true}) : null;
                return [remove, el('span', {class: 'push'}), line, button('Close without saving', () => dialog.close()), save];
            }
        });

        async function saveEntry(dialog) {
            clear(problems);
            for (const key of Object.keys(wrappers)) {
                setFieldError(wrappers[key], null);
            }
            const body = {};
            for (const spec of list.fields) {
                const value = String(inputs[spec.key].value || '').trim();
                // A blank field is no value: nothing is sent for it, so nothing is stored in its place.
                if (value !== '') {
                    body[spec.key] = value;
                }
            }
            const answer = entry
                ? await call('PUT', list.path + '/' + encodeURIComponent(entry.id), {body})
                : await call('POST', list.path, {body});
            if (!answer.ok) {
                const placed = [];
                const errors = answer.error && Array.isArray(answer.error.fieldErrors) ? answer.error.fieldErrors : [];
                for (const each of errors) {
                    if (each && wrappers[each.field]) {
                        setFieldError(wrappers[each.field], each.message);
                        placed.push(each.field);
                    }
                }
                fill(problems, errorPanel(answer.error, {fieldsPlaced: placed, labelOf: labelsOf(name => wrappers[name])}));
                // The pressed button was disabled while it waited, which drops the focus; it goes
                // to the first field to correct, so the keyboard stays inside the form.
                if (placed.length > 0) {
                    inputs[placed[0]].focus();
                }
                if (answer.status === 404) {
                    await load();
                }
                return;
            }
            dialog.close();
            const saved = answer.data || {};
            fill(view.outcome, el('p', {role: 'status'}, (entry ? 'Saved the ' : 'Added the ') + list.one + ' "'
                + named(saved.label || saved.memberId || saved.npi || saved.vendorPractitionerId || noun) + '".'));
            await load();
        }
    }

    function confirmRemove(list, entry) {
        const line = el('span', {class: 'muted small', role: 'status'});
        const name = named(entry.label || entry.memberId || entry.npi || entry.vendorPractitionerId || list.one);
        modal({
            title: 'Remove the ' + list.one + ' "' + name + '"?',
            body: el('p', null, 'It is removed from the saved list. Runs already recorded are not changed.'),
            footer: dialog => {
                const remove = button('Remove ' + list.one, () => during(remove, line, 'Removing', async () => {
                    const answer = await call('DELETE', list.path + '/' + encodeURIComponent(entry.id));
                    dialog.close();
                    if (!answer.ok) {
                        fill(view.outcome, errorPanel(answer.error));
                    } else {
                        fill(view.outcome, el('p', {role: 'status'}, answer.data && answer.data.removed === false
                            ? 'The ' + list.one + ' "' + name + '" was no longer saved.'
                            : 'Removed the ' + list.one + ' "' + name + '".'));
                    }
                    await load();
                }), {primary: true});
                return [el('span', {class: 'push'}), line, button('Keep it', () => dialog.close()), remove];
            }
        });
    }

    /** The import: read the file, show the dry run, then import exactly the text shown. */
    function importDialog(list) {
        let text = null;         // the text the dry run was asked about, kept until the dialog closes
        let fileName = '';       // the name of the file that text was read from, for a refusal of the import
        const file = el('input', {type: 'file', class: 'input', accept: '.csv,text/csv', 'aria-label': 'CSV file'});
        const line = el('span', {class: 'muted small', role: 'status'});
        const shown = el('div', {'aria-live': 'polite'});
        const foot = el('span', {class: 'inline-controls'});
        const read = button('Read the file', () => during(read, line, 'Reading the file', dryRun));
        modal({
            title: 'Import test ' + list.many,
            body: [
                el('div', {class: 'importer'},
                    el('div', {class: 'inline-controls'}, fileChooser(file, 'CSV file'), read),
                    el('p', {class: 'field__hint'}, 'From Excel, save the sheet as CSV first. Nothing is saved until you import the rows shown.')),
                shown],
            footer: dialog => [el('span', {class: 'push'}), line, button('Close without importing', () => dialog.close()), foot],
            onClose: () => {
                text = null;
            }
        });

        async function dryRun() {
            clear(shown);
            clear(foot);
            text = null;
            const got = await readText(file, {maxBytes: 5 * 1024 * 1024, source: 'csv'});
            shownFile(file);
            if (!got.ok) {
                fill(shown, el('p', {class: 'field__error', role: 'alert'}, got.line), filesChangedNote());
                return;
            }
            const answer = await call('POST', list.path + '/import', {body: {csv: got.text, dryRun: true}});
            // The file is let go: what is imported is the text read now, shown below.
            file.value = '';
            shownFile(file);
            if (!answer.ok) {
                fill(shown, fileRefusal(got.fileName, answer));
                return;
            }
            const result = answer.data || {};
            fileName = got.fileName;
            fill(shown, outcomeOf(result, got.fileName));
            const rows = (result.added || 0) + (result.replaced || 0);
            if (rows > 0) {
                text = got.text;
                // The button names what it will not import (I-27).
                const refused = result.refused || 0;
                const go = button('Import ' + counted(rows, 'row', 'rows') + (refused > 0 ? ' (' + refused + ' refused)' : ''),
                    () => during(go, line, 'Importing', importNow), {primary: true});
                fill(foot, go);
            }
        }

        async function importNow() {
            if (text === null) {
                return;
            }
            const answer = await call('POST', list.path + '/import', {body: {csv: text, dryRun: false}});
            clear(foot);
            if (!answer.ok) {
                fill(shown, fileRefusal(fileName, answer));
                return;
            }
            const result = answer.data || {};
            text = null;
            fill(shown, el('div', {class: 'verdict verdict--clear'},
                el('p', {class: 'verdict__headline'}, importedLine(result))));
            fill(view.outcome, el('p', {role: 'status'}, importedLine(result)));
            await load();
        }
    }

    /** What the dry run found: the columns used and left out, then each row's outcome. */
    function outcomeOf(result, fileName) {
        const used = Array.isArray(result.columnsUsed) ? result.columnsUsed : [];
        const ignored = Array.isArray(result.columnsIgnored) ? result.columnsIgnored : [];
        // The refused rows first: in a long file they would otherwise sit below every row to be added, off screen (I-27).
        const given = Array.isArray(result.rows) ? result.rows : [];
        const rows = given.filter(row => row && row.status === 'REFUSED').concat(given.filter(row => row && row.status !== 'REFUSED'));
        return el('div', {class: 'config-said'},
            el('p', null, 'Read ' + named(fileName) + ': ' + counted(rows.length, 'row', 'rows') + '. Nothing is saved yet.'),
            el('p', null, 'Columns used: ', used.length > 0 ? el('span', {class: 'mono'}, used.join(', ')) : 'none', '.'),
            el('p', null, 'Columns left out: ', ignored.length > 0 ? el('span', {class: 'mono'}, ignored.join(', ')) : 'none', '.'),
            rows.length === 0 ? el('p', {class: 'muted'}, 'The file holds no row below its header.') :
                el('div', {class: 'table-wrap'}, el('table', {class: 'table'},
                    el('thead', null, el('tr', null, el('th', {scope: 'col', class: 'num'}, 'Line'), el('th', {scope: 'col'}, 'Status'),
                        el('th', {scope: 'col'}, 'Label'), el('th', {scope: 'col'}, 'Reason'))),
                    el('tbody', null, rows.map(row => {
                        const status = STATUS[row.status] || {words: String(row.status || ''), tone: null};
                        return el('tr', null,
                            el('td', {class: 'num'}, String(row.line)),
                            el('td', null, chip(status.words, {tone: status.tone})),
                            el('td', null, row.label ? row.label : none('no label')),
                            el('td', {title: row.reason || null}, row.reason || ''));
                    })))),
            sentences([counted(result.added || 0, 'row would be added', 'rows would be added') + ', '
                + (result.replaced || 0) + ' replaced, ' + (result.refused || 0) + ' refused.']));
    }

    function importedLine(result) {
        return 'Imported: ' + counted(result.added || 0, 'row added', 'rows added') + ', ' + (result.replaced || 0) + ' replaced, '
            + (result.refused || 0) + ' refused.';
    }

    load();

    return {
        update(next) {
            const wanted = kindOf(next);
            if (wanted !== kind) {
                kind = wanted;
                view = draw();
                load();
            }
        },
        unmount() {
            gone = true;
            host.remove();
        }
    };
}
