// The case: one member, one provider and one or more procedure codes, shared by the CRD, DTR and
// PAS tabs so a tester carries a case through all three without retyping.
//
// There is one case in the page and one node that shows it. The tab on screen takes the node, so
// what was typed on CRD is what DTR and PAS show and send.
//
// A member and a provider are each looked up by their identifier, when the tester leaves the
// field or asks for it. What the lookup returns fills the details, and every detail says where
// its value came from: the lookup, or the tester's own hand. A request carries exactly what is
// on screen, so a detail changed by hand goes out as changed, and is named as an override.
//
// Nothing here is written to any browser storage, and nothing is remembered between page loads:
// no recent members, no history of what was typed, no lookup kept. A reload starts with an empty
// case.

import {el, button, code} from './dom.js';
import {textField, markFieldErrors, unmark} from './forms.js';
import {createLookup} from './lookup.js';

// The fields of a member and of a provider that the form shows and sends. A request carries
// exactly what is on screen, so a field is sent only if it is listed here and holds something.
// The first of each is the identifier a lookup works from; the rest are details, and they are
// the details the request builders use. What else a lookup returns is shown with the lookup and
// is not sent, because no request has a place for it.
const MEMBER_FIELDS = [
    {name: 'memberId', label: 'Member ID', literal: true, wide: true, identifier: true},
    {name: 'firstName', label: 'First name'},
    {name: 'lastName', label: 'Last name'},
    {name: 'dateOfBirth', label: 'Date of birth', placeholder: 'YYYY-MM-DD'},
    {name: 'gender', label: 'Gender', suggestions: ['female', 'male', 'other', 'unknown']},
    {name: 'lineOfBusiness', label: 'Line of business'},
    {name: 'subscriberId', label: 'Subscriber ID', literal: true},
    {name: 'planName', label: 'Plan'},
    {name: 'coverageStatus', label: 'Coverage status'}
];

const PROVIDER_FIELDS = [
    {name: 'npi', label: 'NPI', literal: true, wide: true, identifier: true, inputMode: 'numeric'},
    {name: 'firstName', label: 'First name'},
    {name: 'lastName', label: 'Last name'},
    {name: 'organizationName', label: 'Organization'}
];

const CODE_SEPARATORS = /[\s,;|]+/;

export function createCaseContext() {
    const listeners = [];
    const changed = () => listeners.forEach(listener => listener());
    let epochSeen = null;

    // Hidden while it shows nothing, so that it takes no room among the things the tab shows.
    const detail = el('div', {class: 'case-detail', hidden: true});
    const panelChanged = section => {
        detail.hidden = detail.querySelector('.panel') === null;
        if (section) {
            section.scrollIntoView({block: 'start'});
            section.focus({preventScroll: true});
        }
    };

    const member = group({
        prefix: 'member', fields: MEMBER_FIELDS, changed, panelChanged,
        subject: 'member', identifier: 'member ID', address: '/api/members/', record: 'member', refusedAs: 'memberId'
    });
    const provider = group({
        prefix: 'provider', fields: PROVIDER_FIELDS, changed, panelChanged,
        subject: 'provider', identifier: 'NPI', address: '/api/providers/', record: 'provider', refusedAs: 'npi'
    });
    const codes = codeList(changed);
    detail.append(member.lookup.panel, provider.lookup.panel);

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
         * Everything the latest lookups returned, when a tester asks to see it. There is one such
         * node in the page, as there is one case. The tab on screen places it where there is room
         * for a table and a wire view, which the case itself has not.
         */
        detail,

        /**
         * What a run request needs from the case, in the names every protocol's request uses:
         * member, memberOverrides, provider, providerOverrides, procedureCodes.
         *
         * The overrides name the details that were typed or changed by hand. The ledger then
         * records that the run used details a fresh lookup would not give back, so a replay does
         * not claim to have sent the same thing.
         */
        request() {
            return {
                member: member.values(),
                memberOverrides: member.byHand(),
                provider: provider.values(),
                providerOverrides: provider.byHand(),
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

        /** Puts a member ID into the case, as when a tester picks a mock scenario's member, and looks it up. */
        setMemberId(value) {
            member.setIdentifier(value);
            changed();
        },

        /**
         * What a tester should look at before sending, in sentences: what a lookup said about a
         * member or a provider it found, and a lookup that the environment now active did not answer.
         * Each entry is {about, text}.
         */
        attention() {
            return member.attention().concat(provider.attention());
        },

        /**
         * Call when the configuration changes. A lookup on screen was answered by the environment
         * that was active then, and says so from now on. Calling it twice for one epoch does nothing more.
         */
        configChanged(epoch) {
            if (epoch === epochSeen) {
                return;
            }
            const first = epochSeen === null;
            epochSeen = epoch;
            if (!first) {
                member.madeBefore();
                provider.madeBefore();
                changed();
            }
        },

        /**
         * Seam for the procedure code type ahead: adds one code as the picker returns it,
         * {code, system, description}. Typing a code by hand calls the same function.
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

/**
 * One identifier with the details that belong to it: the member, or the provider.
 *
 * spec: prefix (how the request names the group), fields, changed, and what the lookup needs:
 * subject, identifier, address, record, refusedAs (the name a refused lookup gives the
 * identifier) and panelChanged (called when what the lookup returned is shown or put away).
 */
function group(spec) {
    const controls = new Map();
    const nodes = [];
    let identifier = null;

    const lookup = createLookup({
        subject: spec.subject, identifier: spec.identifier, address: spec.address, record: spec.record,
        read: () => identifier.value(),
        starting() {
            unmark(identifier.node);
            for (const control of details()) {
                control.touched = false;
            }
        },
        refused(fieldErrors, viaButton) {
            // The server names the identifier the way its own address does. Here it is a field of the case.
            markFieldErrors([identifier.node], fieldErrors.map(problem =>
                (problem.field === spec.refusedAs ? {...problem, field: spec.prefix + '.' + identifier.spec.name} : problem)),
                {focus: viaButton});
        },
        answered(answer) {
            if (answer && answer.status === 'FOUND' && answer[spec.record]) {
                fillFrom(answer[spec.record]);
            } else {
                release();
            }
            spec.changed();
        },
        panelChanged: spec.panelChanged
    });

    for (const field of spec.fields) {
        const control = textField({...field, field: spec.prefix + '.' + field.name, beside: field.identifier ? lookup.control : null,
            origin: !field.identifier});
        control.spec = field;
        controls.set(field.name, control);
        nodes.push(control.node);
        if (field.identifier) {
            identifier = control;
            control.node.append(lookup.strip);
            control.input.addEventListener('input', () => {
                unmark(control.node);
                identifierChanged();
                spec.changed();
            });
            // The lookup is made when the tester leaves the field, never for each key. Tab moves the
            // focus onto the button beside the field, and that is leaving the field too. Only a
            // press of that button is left to the button, so one lookup is not made twice.
            let pressing = false;
            lookup.control.addEventListener('pointerdown', () => { pressing = true; });
            lookup.control.addEventListener('click', () => { pressing = false; });
            control.input.addEventListener('blur', event => {
                const pressed = pressing && event.relatedTarget === lookup.control;
                pressing = false;
                if (!pressed && control.value() !== '' && control.value() !== lookup.id()) {
                    lookup.lookUp(false);
                }
            });
            continue;
        }
        // What the lookup on screen gave for this detail, as text. Null while no lookup answer is in force.
        control.given = null;
        control.touched = false;
        control.input.addEventListener('input', () => {
            control.touched = true;
            mark(control);
            spec.changed();
        });
    }
    function details() {
        return Array.from(controls.values()).filter(control => !control.spec.identifier);
    }

    /** Where the value of a detail came from: nowhere (it is empty), the lookup on screen, or the tester's hand. */
    function sourceOf(control) {
        const value = control.value();
        if (control.given !== null) {
            return value === control.given ? (value === '' ? 'none' : 'lookup') : 'hand';
        }
        return value === '' ? 'none' : 'hand';
    }

    // Cream marks a detail typed or changed by hand, and the words beneath it say which, so the
    // mark does not rest on colour alone. The identifier is what a lookup starts from, so it is never marked.
    function mark(control) {
        const source = sourceOf(control);
        const replaced = control.given !== null && control.given !== '';
        control.node.classList.toggle('field--byhand', source === 'hand');
        control.node.classList.toggle('field--fromlookup', source === 'lookup');
        control.origin.textContent = source === 'lookup' ? 'from lookup'
            : source !== 'hand' ? ''
                : !replaced ? 'by hand'
                    : control.value() === '' ? 'cleared, not sent' : 'changed by hand';
        control.input.title = source !== 'hand' ? ''
            : replaced ? 'Changed by hand. The lookup returned another value, and the ledger notes that the run used this one.'
                : 'Typed by hand. A lookup would not give this value back, and the ledger notes that.';
    }

    /** Fills the details from the record a lookup found. A detail the tester edited while the answer was on its way keeps what they typed. */
    function fillFrom(record) {
        for (const control of details()) {
            const returned = record[control.spec.name];
            const text = returned === undefined || returned === null ? '' : String(returned).trim();
            if (!control.touched) {
                control.set(text);
            }
            control.given = text;
            control.touched = false;
            mark(control);
        }
    }

    /**
     * Lets go of what a lookup filled. A detail still holding the lookup's value is emptied: it
     * described the identifier that was looked up, and that is no longer the answer on screen.
     * A detail typed or changed by hand is the tester's own and stays.
     */
    function release() {
        for (const control of details()) {
            if (sourceOf(control) === 'lookup') {
                control.set('');
            }
            control.given = null;
            control.touched = false;
            mark(control);
        }
    }

    function identifierChanged() {
        if (identifier.value() !== lookup.id()) {
            lookup.forget();
            release();
        }
    }

    return {
        nodes,
        lookup,
        get: name => controls.get(name).value(),

        /** Puts an identifier in the field, as typing it would, and looks it up. */
        setIdentifier(value) {
            identifier.set(value);
            unmark(identifier.node);
            lookup.forget();
            release();
            if (identifier.value() !== '') {
                lookup.lookUp(false);
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

        /** The details typed or changed by hand, by name: every one whose value is not what the lookup on screen gave. */
        byHand() {
            return details().filter(control => sourceOf(control) === 'hand').map(control => control.spec.name);
        },

        attention() {
            const about = spec.subject.charAt(0).toUpperCase() + spec.subject.slice(1) + ' lookup';
            const out = lookup.attention().map(text => ({about, text}));
            if (lookup.outdated()) {
                out.push({about, text: 'It was made before the configuration changed. The environment now active has not been asked.'});
            }
            return out;
        },

        madeBefore() {
            lookup.madeBefore('This lookup was made before the configuration changed. The environment now active has not been asked: '
                + 'look the ' + spec.subject + ' up again.');
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
