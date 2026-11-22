// The Member card of the case (plan 3.2, "Case column").
//
// On mock, a picker names each test member by what it plays ("P32H-DOC-DTR \u00b7 documentation
// required"), grouped by the step it plays first, and the Member ID sits first in "Edit member
// fields". On live there is no such picker: the Member ID is typed in the card and looked up, or a
// saved test member is picked. The mock groups are built only when the profile is mock and the
// server gave scenario lists; the server refuses every mock helper on live in any case. The page
// never works out which scenario an ID plays: the server decides that from the ID it is sent.

import {el, chip, fold, fill, clear} from './dom.js';
import {sentAs} from './result.js';
import {caseField, caseChanged, fieldsOf, MARKS} from './wb-case.js';
import {cardHead, latestOnly, lookUp, fillFromLookup, clearLookedUp, statusChip, lookupLine, returnedFold, returnedValue} from './wb-lookup.js';

const GENDERS = [['', 'Not stated'], ['female', 'female'], ['male', 'male'], ['other', 'other'], ['unknown', 'unknown']];

// The groups of the mock picker, in the order a case meets the steps.
const MOCK_GROUPS = [['crd', 'Coverage check'], ['dtr', 'Questionnaire'], ['pas', 'Submit and status']];

/**
 * Builds the mock picker's groups from the three scenario lists: one option per scenario that has a
 * test member ID, its text "<member ID> \u00b7 <short>", an ID listed once, in the group of the first
 * step it plays. Returns [] unless mock is true: on live nothing of it is built, whatever lists are
 * in hand.
 */
export function mockGroups(mock, lists) {
    if (mock !== true || !lists) {
        return [];
    }
    const seen = new Set();
    const groups = [];
    for (const [key, label] of MOCK_GROUPS) {
        const list = lists[key];
        const scenarios = list && Array.isArray(list.scenarios) ? list.scenarios : [];
        const options = [];
        for (const scenario of scenarios) {
            const memberId = typeof scenario.sentinelMemberId === 'string' && scenario.sentinelMemberId !== '' ? scenario.sentinelMemberId : null;
            if (!memberId || seen.has(memberId)) {
                continue;
            }
            seen.add(memberId);
            const words = scenario.short || scenario.label;
            options.push({memberId, text: words ? memberId + ' \u00b7 ' + words : memberId, sample: scenario.sampleCase || null});
        }
        if (options.length > 0) {
            groups.push({label, options});
        }
    }
    return groups;
}

/**
 * The Member card. env: {isMock(), mockLists() (the three scenario lists, or null), savedMembers()
 * (the saved list, or []), dateOfService(), useSample(sample) (fills NPI and codes from a mock
 * member's sample case and looks the provider up)}.
 */
export function createMemberCard(env) {
    const head = cardHead('Member', 'member');
    const id = caseField({name: 'memberId', label: 'Member ID', mono: true});
    const fields = [
        caseField({name: 'firstName', label: 'First name'}),
        caseField({name: 'lastName', label: 'Last name'}),
        caseField({name: 'dateOfBirth', label: 'Date of birth', mono: true}),
        caseField({name: 'gender', label: 'Gender', kind: 'select', options: GENDERS}),
        caseField({name: 'lineOfBusiness', label: 'Line of business'}),
        caseField({name: 'planName', label: 'Plan'}),
        caseField({name: 'subscriberId', label: 'Subscriber ID', mono: true}),
        caseField({name: 'coverageStatus', label: 'Coverage status'}),
        caseField({name: 'vendorPatientId', label: 'Vendor patient id', mono: true,
            hint: 'The vendor\'s own id for this patient. Sent as the Patient id and patientId.'})
    ];
    // What a lookup or a saved entry gave that the card has no field for (coverage dates, the
    // address): sent as given, and dropped whenever the member changes.
    let extra = {};
    let answer = null;
    let source = null;
    const run = latestOnly();

    const lookupButton = el('button', {type: 'button', class: 'btn', on: {click: () => lookup()}}, 'Look up');
    const idRow = el('div', {class: 'field'}, el('label', {class: 'field__label', for: id.input.id}, 'Member ID', id.node.querySelector('.field__mark')),
        el('div', {class: 'inline-controls'}, id.input, lookupButton),
        id.node.querySelector('.masked-carry-slot'), id.node.querySelector('.field__error'));
    id.node = idRow;
    id.input.addEventListener('change', () => lookup());
    id.input.addEventListener('keydown', event => {
        if (event.key === 'Enter') {
            event.preventDefault();
            lookup();
        }
    });
    id.input.addEventListener('input', () => {
        // A new ID is a new member: what the lookup gave for the one before is not kept.
        answer = null;
        extra = {};
        drawSummary();
    });

    const picker = el('select', {class: 'select', id: 'member-picker'});
    const pickerLabel = el('label', {class: 'visually-hidden', for: 'member-picker'}, '');
    picker.addEventListener('change', () => picked(picker.value));
    const summary = el('div', {class: 'summary', hidden: true});
    const sent = el('p', {class: 'small muted'});
    const line = el('div');
    const returned = el('div');
    const editBody = el('div', {class: 'fold__body-grid'});
    const edit = fold({summary: 'Edit member fields', inline: true, body: editBody});
    const idSlot = el('div');
    const body = el('div', {class: 'card__body'}, pickerLabel, picker, idSlot, summary, sent, line, edit, returned);
    const node = el('section', {class: 'card', 'aria-label': 'Member'}, head.node, body);

    function placeId() {
        // On mock the picker fills the Member ID, which sits first in "Edit member fields"; on live it is typed in the card.
        if (env.isMock()) {
            fill(editBody, idRow, fieldGrid());
            clear(idSlot);
        } else {
            fill(idSlot, idRow);
            fill(editBody, fieldGrid());
        }
    }

    function fieldGrid() {
        const rows = [];
        for (let i = 0; i < fields.length; i += 2) {
            rows.push(el('div', {class: 'field-row'}, fields[i].node, fields[i + 1] ? fields[i + 1].node : null));
        }
        return rows;
    }

    /** Draws the picker's options: on mock the groups of test members, then the saved list; on live the saved list only. */
    function drawPicker() {
        const mock = env.isMock();
        const groups = mockGroups(mock, env.mockLists());
        const saved = env.savedMembers();
        const parts = [];
        if (groups.length > 0) {
            pickerLabel.textContent = 'Mock member, named by what it plays';
            parts.push(el('option', {value: ''}, 'Choose a member'));
            for (const group of groups) {
                parts.push(el('optgroup', {label: group.label},
                    group.options.map(option => el('option', {value: 'mock:' + option.memberId}, option.text))));
            }
        } else {
            pickerLabel.textContent = 'Saved test member';
            parts.push(el('option', {value: ''}, 'Choose a saved member'));
        }
        if (saved.length > 0) {
            parts.push(el('optgroup', {label: 'Saved test members'},
                saved.map(entry => el('option', {value: 'saved:' + entry.id}, (entry.label || entry.memberId) + ' (' + entry.memberId + ')'))));
        }
        const before = picker.value;
        fill(picker, ...parts);
        picker.value = before;
        const shown = groups.length > 0 || saved.length > 0;
        picker.hidden = !shown;
        pickerLabel.hidden = !shown;
        placeId();
    }

    function picked(value) {
        if (value.startsWith('mock:')) {
            const memberId = value.slice(5);
            const option = mockGroups(env.isMock(), env.mockLists()).flatMap(group => group.options).find(each => each.memberId === memberId);
            clearAll();
            id.set(memberId, MARKS.SCENARIO);
            source = 'mock';
            if (option && option.sample) {
                env.useSample(option.sample);
            }
            lookup();
        } else if (value.startsWith('saved:')) {
            const entry = env.savedMembers().find(each => each.id === value.slice(6));
            if (!entry) {
                return;
            }
            clearAll();
            source = 'saved';
            id.set(entry.memberId, MARKS.SAVED);
            for (const control of fields) {
                const name = control.name === 'planName' ? 'plan' : control.name;
                if (entry[name] !== undefined && entry[name] !== null && entry[name] !== '') {
                    control.set(entry[name], MARKS.SAVED);
                }
            }
            extra = {coverageStart: entry.coverageStart || undefined, coverageEnd: entry.coverageEnd || undefined};
            // A saved entry fills the card; it does not look the member up by itself.
            drawSummary();
            caseChanged('picked');
        }
    }

    function clearAll() {
        answer = null;
        extra = {};
        source = null;
        id.clear();
        for (const control of fields) {
            control.clear();
        }
    }

    async function lookup() {
        const memberId = id.value();
        if (memberId === '') {
            return;
        }
        const dateOfService = env.dateOfService();
        await run(() => lookUp('/api/members', dateOfService ? {memberId, dateOfService} : {memberId}), reply => {
            const found = reply.ok ? reply.data : null;
            answer = found || {status: 'FAULT', error: reply.error};
            if (found && found.status === 'FOUND' && found.member) {
                fillFromLookup(fields, found.member, found.fields);
                extra = {coverageStart: found.member.coverageStart, coverageEnd: found.member.coverageEnd, address: found.member.address};
            } else {
                clearLookedUp(fields);
                extra = {};
            }
            drawSummary();
            caseChanged('lookup');
        });
    }

    function drawSummary() {
        const values = fieldsOf(fields).values;
        const name = [values.firstName, values.lastName].filter(Boolean).join(' ');
        const memberId = id.value();
        head.picked(name || memberId || 'not chosen', Boolean(name || memberId));
        head.attention(Boolean(answer && Array.isArray(answer.attention) && answer.attention.length > 0));
        const found = answer && answer.status === 'FOUND';
        const notReturned = field => found && returnedValue(answer, field) === null ? chip('not returned', {dashed: true}) : null;
        const status = statusChip(answer);
        summary.hidden = !(name || status || values.dateOfBirth || values.lineOfBusiness);
        fill(summary, 
            el('div', {class: 'summary__line'}, el('span', {class: 'summary__name'}, name || memberId), notReturned('firstName'), status),
            values.dateOfBirth || values.lineOfBusiness || found ? el('div', {class: 'summary__line'},
                el('span', null, 'DOB ', values.dateOfBirth ? el('span', {class: 'mono'}, values.dateOfBirth) : notReturned('dateOfBirth')),
                values.lineOfBusiness ? el('span', null, values.lineOfBusiness) : notReturned('lineOfBusiness')) : null);
        fill(sent, ...(answer && answer.memberId ? [sentAs(answer.memberId),
            answer.memberId.note ? el('span', null, ' ' + answer.memberId.note) : null] : []));
        fill(line, lookupLine(answer) || '');
        fill(returned, returnedFold(answer) || '');
    }

    for (const control of [id].concat(fields)) {
        control.input.addEventListener('input', () => drawSummary());
    }
    drawPicker();
    drawSummary();

    return {
        node,
        drawPicker,
        /** The member as every run sends it, and the names of the fields the tester typed or changed. */
        request() {
            const {values, overrides} = fieldsOf([id].concat(fields));
            const member = {...values};
            for (const [key, value] of Object.entries(extra)) {
                if (value !== undefined && value !== null && value !== '' && member[key] === undefined) {
                    member[key] = value;
                }
            }
            return {member, memberOverrides: overrides.filter(name => name !== 'memberId')};
        },
        /** The line of business on the card, for the code list. */
        lineOfBusiness() {
            const value = fields.find(control => control.name === 'lineOfBusiness').value();
            return value;
        },
        /** Name or ID for the banner. */
        label() {
            const values = fieldsOf(fields).values;
            return [values.firstName, values.lastName].filter(Boolean).join(' ') || id.value();
        },
        /** The latest "Sent as" a run gave for this ID. */
        sentAs(forms) {
            if (forms && forms.sent && forms.typed === id.value()) {
                fill(sent, sentAs(forms), forms.note ? el('span', null, ' ' + forms.note) : null);
            }
        },
        /** Opens the fold that holds a field and focuses it ("Show the field", a refused field). */
        show(name, message) {
            const control = [id].concat(fields).find(each => each.name === name);
            if (!control) {
                return false;
            }
            if (edit.contains(control.input)) {
                edit.open = true;
            }
            control.setError(message || null);
            control.input.focus();
            return true;
        },
        clear() {
            picker.value = '';
            clearAll();
            drawSummary();
        },
        source() {
            return source;
        }
    };
}
