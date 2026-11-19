// The case: one member, one provider and one or more procedure codes, shared by the CRD, DTR and
// PAS tabs so a tester carries a case through all three without retyping.
//
// There is one case in the page and one node that shows it. The tab on screen takes the node, so
// what was typed on CRD is what DTR and PAS show and send.
//
// Nothing here is written to any browser storage, and nothing is remembered between page loads:
// no recent members, no history of what was typed. A reload starts with an empty case.

import {el, button, code} from './dom.js';
import {textField} from './forms.js';

// The fields of a member and of a provider that this pass shows and sends. A request carries
// exactly what is on screen, so a field is sent only if it is listed here and holds something.
// The first of each is the identifier a lookup works from; the rest are details.
const MEMBER_FIELDS = [
    {name: 'memberId', label: 'Member ID', literal: true, wide: true, identifier: true},
    {name: 'firstName', label: 'First name'},
    {name: 'lastName', label: 'Last name'},
    {name: 'dateOfBirth', label: 'Date of birth', placeholder: 'YYYY-MM-DD'},
    {name: 'gender', label: 'Gender', suggestions: ['female', 'male', 'other', 'unknown']},
    {name: 'lineOfBusiness', label: 'Line of business'},
    {name: 'subscriberId', label: 'Subscriber ID', literal: true}
];

const PROVIDER_FIELDS = [
    {name: 'npi', label: 'NPI', literal: true, identifier: true, inputMode: 'numeric'},
    {name: 'firstName', label: 'First name'},
    {name: 'lastName', label: 'Last name'}
];

const CODE_SEPARATORS = /[\s,;|]+/;

export function createCaseContext() {
    const listeners = [];
    const changed = () => listeners.forEach(listener => listener());

    const member = group('member', MEMBER_FIELDS, changed);
    const provider = group('provider', PROVIDER_FIELDS, changed);
    const codes = codeList(changed);

    const node = el('div', {class: 'case'},
        el('fieldset', {class: 'case-group'}, el('legend', null, 'Member'),
            el('div', {class: 'field-grid field-grid--three'}, member.nodes)),
        el('fieldset', {class: 'case-group'}, el('legend', null, 'Ordering provider'),
            el('div', {class: 'field-grid field-grid--three'}, provider.nodes)),
        codes.node);

    return {
        /** The node that shows the case. The tab on screen places it. */
        node,

        /**
         * What a run request needs from the case, in the names every protocol's request uses:
         * member, memberOverrides, provider, providerOverrides, procedureCodes.
         *
         * The overrides name the details that were typed by hand. The ledger then records that the
         * run used details a fresh lookup would not give back, so a replay does not claim to have
         * sent the same thing.
         */
        request() {
            return {
                member: member.values(),
                memberOverrides: member.typedByHand(),
                provider: provider.values(),
                providerOverrides: provider.typedByHand(),
                procedureCodes: codes.values()
            };
        },

        /** The case in a few words, for a tab that only has to say what it was handed. */
        summary() {
            return {
                memberId: member.get('memberId'),
                npi: provider.get('npi'),
                codes: codes.shown().map(entry => entry.code)
            };
        },

        /** Puts a member ID into the case, as when a tester picks a mock scenario's member. */
        setMemberId(value) {
            member.typed('memberId', value);
            changed();
        },

        /**
         * Seam for the member lookup of the next pass: fills the member from what a lookup
         * returned. Fields filled this way are not marked as typed by hand until the tester
         * changes them. A field the lookup returned and this table does not list is not sent,
         * so add it to MEMBER_FIELDS first.
         */
        fillMember(detail) {
            member.fill(detail);
            changed();
        },

        /** Seam for the provider lookup of the next pass. See fillMember. */
        fillProvider(detail) {
            provider.fill(detail);
            changed();
        },

        /**
         * Seam for the procedure code type ahead of the next pass: adds one code as the picker
         * returns it, {code, system, description}. Typing a code by hand calls the same function.
         */
        addCode(entry) {
            codes.add(entry);
        },

        /** Replaces the procedure codes, as when a step hands on the codes it was about. */
        setCodes(list) {
            codes.replace(list);
        },

        /** Calls back whenever anything in the case changes. */
        onChange(listener) {
            listeners.push(listener);
        }
    };
}

function group(prefix, fields, changed) {
    const controls = new Map();
    const nodes = [];
    for (const spec of fields) {
        const control = textField({...spec, field: prefix + '.' + spec.name});
        control.spec = spec;
        control.source = 'none';
        control.input.addEventListener('input', () => {
            control.source = control.value() === '' ? 'none' : 'hand';
            mark(control);
            changed();
        });
        controls.set(spec.name, control);
        nodes.push(control.node);
    }

    // Cream marks a detail typed by hand. The identifier is what a lookup starts from, so it is never marked.
    function mark(control) {
        const byHand = control.source === 'hand' && !control.spec.identifier;
        control.node.classList.toggle('field--byhand', byHand);
        control.input.title = byHand ? 'Typed by hand. A lookup would not give this value back, and the ledger notes that.' : '';
    }

    return {
        nodes,
        get: name => controls.get(name).value(),
        typed(name, value) {
            const control = controls.get(name);
            control.set(value);
            control.source = control.value() === '' ? 'none' : 'hand';
            mark(control);
        },
        fill(detail) {
            for (const [name, control] of controls) {
                const value = detail ? detail[name] : undefined;
                control.set(value);
                control.source = control.value() === '' ? 'none' : 'lookup';
                mark(control);
            }
        },
        /** Only what is on screen and not blank. A blank field is left out, never sent as an empty string. */
        values() {
            const out = {};
            for (const [name, control] of controls) {
                if (control.value() !== '') {
                    out[name] = control.value();
                }
            }
            return out;
        },
        typedByHand() {
            const names = [];
            for (const [name, control] of controls) {
                if (control.source === 'hand' && !control.spec.identifier && control.value() !== '') {
                    names.push(name);
                }
            }
            return names;
        }
    };
}

function codeList(changed) {
    let entries = [];
    const list = el('ul', {class: 'code-list', aria: {label: 'Procedure codes in this case'}});
    const entry = el('input', {
        type: 'text', id: 'case-code-entry', autocomplete: 'off', spellcheck: false, class: 'literal-input',
        placeholder: 'type a code, press Enter', aria: {describedby: 'case-code-hint'}
    });
    const add = button('Add code', () => { commit(); entry.focus(); }, {class: 'button--quiet'});
    const node = el('div', {class: 'case-group field', dataset: {field: 'procedureCodes'}},
        el('label', {htmlFor: 'case-code-entry', class: 'case-legend'}, 'Procedure codes'),
        list,
        el('div', {class: 'code-entry'}, entry, add),
        el('span', {class: 'visually-hidden', id: 'case-code-hint'},
            'Type a code and press Enter to add it. Several can be pasted at once, separated by commas or spaces.'));

    entry.addEventListener('keydown', event => {
        if (event.key === 'Enter') {
            // Enter adds the code. It must not send the request the tester is still composing.
            event.preventDefault();
            commit();
        } else if (event.key === 'Backspace' && entry.value === '' && entries.length > 0) {
            entries = entries.slice(0, -1);
            draw();
        }
    });

    /** Moves whatever is typed in the entry box into the list. */
    function commit() {
        const typed = entry.value.split(CODE_SEPARATORS).map(part => part.trim()).filter(part => part.length > 0);
        if (typed.length === 0) {
            return;
        }
        for (const value of typed) {
            addEntry({code: value});
        }
        entry.value = '';
        draw();
    }

    function addEntry(candidate) {
        if (!candidate || !candidate.code || entries.some(existing => existing.code === candidate.code)) {
            return;
        }
        const kept = {code: String(candidate.code)};
        if (candidate.system) {
            kept.system = candidate.system;
        }
        if (candidate.description) {
            kept.description = candidate.description;
        }
        entries = entries.concat([kept]);
    }

    function draw() {
        list.replaceChildren(...entries.map(item => {
            const remove = el('button', {
                type: 'button', class: 'code-chip__remove', title: 'Remove ' + item.code, aria: {label: 'Remove ' + item.code},
                on: {click: () => { entries = entries.filter(other => other !== item); draw(); entry.focus(); }}
            }, 'x');
            return el('li', {class: 'code-chip'}, code(item.code),
                item.description ? el('span', {class: 'quiet small'}, item.description) : null, remove);
        }));
        changed();
    }

    return {
        node,
        values() {
            // A code still sitting in the entry box is part of what the tester means to send. It is
            // moved into the list first, so that the list on screen is the list that goes out.
            commit();
            return entries.map(item => ({...item}));
        },
        /** The codes in the list as it stands, without touching what is being typed. */
        shown() {
            return entries.map(item => ({...item}));
        },
        add(candidate) {
            addEntry(candidate);
            draw();
        },
        replace(candidates) {
            entries = [];
            for (const candidate of candidates || []) {
                addEntry(typeof candidate === 'string' ? {code: candidate} : candidate);
            }
            entry.value = '';
            draw();
        }
    };
}
