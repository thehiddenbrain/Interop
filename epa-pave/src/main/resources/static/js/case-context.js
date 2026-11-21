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

import {el, fill} from './dom.js';
import {textField, markFieldErrors, unmark} from './forms.js';
import {createLookup} from './lookup.js';
import {createCodePicker} from './code-picker.js';

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

export function createCaseContext() {
    const listeners = [];
    let codes = null;
    const changed = () => {
        // What the code list says about a code depends on the line of business, so the codes hear of a change to it first.
        if (codes) {
            codes.lineChanged();
        }
        listeners.forEach(listener => listener());
    };
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
        subject: 'member', identifier: 'member ID', address: '/api/members?memberId=', record: 'member', refusedAs: 'memberId'
    });
    const provider = group({
        prefix: 'provider', fields: PROVIDER_FIELDS, changed, panelChanged,
        subject: 'provider', identifier: 'NPI', address: '/api/providers?npi=', record: 'provider', refusedAs: 'npi'
    });
    // A code added (by hand, by the picker or with a chosen case) answers a refusal of the codes
    // field, so its mark goes. A code taken away answers nothing, and the mark stays.
    let codesBefore = 0;
    codes = createCodePicker({lineOfBusiness: () => member.get('lineOfBusiness'), changed: () => {
        const now = codes.shown().length;
        if (now > codesBefore) {
            unmark(codes.node);
        }
        codesBefore = now;
        changed();
    }});
    codes.checkList();
    detail.append(member.lookup.panel, provider.lookup.panel);

    // The three things a send needs come first, together; the details the lookups fill come after
    // them, still in view, because they show what a lookup found and what was changed by hand.
    const node = el('div', {class: 'case'},
        el('fieldset', {class: 'case-group case-required'}, el('legend', null, 'Needed to send'),
            member.identifierNode, provider.identifierNode, codes.node),
        el('fieldset', {class: 'case-group'}, el('legend', null, 'Member details'),
            el('div', {class: 'field-grid field-grid--three'}, member.detailNodes)),
        el('fieldset', {class: 'case-group'}, el('legend', null, 'Ordering provider details'),
            el('div', {class: 'field-grid field-grid--three'}, provider.detailNodes)));

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
         * Puts the case a mock scenario file names in place of the one on screen: its member ID and
         * NPI, each looked up as if typed, and its procedure codes, each asked of the code list as a
         * code added by hand is. Reached only from the mock scenario table, which live never draws.
         */
        useSample(sample) {
            member.replace(sample && sample.memberId ? String(sample.memberId) : '');
            provider.replace(sample && sample.npi ? String(sample.npi) : '');
            codes.replace(((sample && sample.procedureCodes) || []).map(value => ({code: String(value)})));
            changed();
        },

        /**
         * Puts a member in the case in place of the one there, as when a run is loaded from the
         * ledger: every detail of the member before goes, typed by hand or not, because it belonged
         * to that member, and the member ID given is looked up. record.memberId absent or blank
         * leaves the member empty.
         */
        fillMember(record) {
            member.replace(record && record.memberId ? String(record.memberId) : '');
            changed();
        },

        /** As fillMember, for the ordering provider: record.npi, or an empty provider. */
        fillProvider(record) {
            provider.replace(record && record.npi ? String(record.npi) : '');
            changed();
        },

        /**
         * What a tester should look at before sending, in sentences: what a lookup said about a
         * member or a provider it found, and a lookup that the environment now active did not answer.
         * Each entry is {about, text, reveal}: reveal() brings the lookup that said it into view, where
         * the sentence is shown in full, and gives it the focus.
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

        /**
         * Puts a detail of the case in its field with where it came from, as when a comparison puts
         * on the form a value it read out of a pasted request. field is the name the request gives it
         * ("member.firstName"); origin "pasted" marks it as from the pasted request rather than typed
         * by hand. The ledger notes it as an override either way: a lookup would not give it back.
         */
        setDetail(field, value, origin) {
            const [prefix, name] = String(field).split('.');
            const owner = prefix === 'member' ? member : prefix === 'provider' ? provider : null;
            if (owner) {
                owner.setDetail(name, value, origin);
                changed();
            }
        },

        /** A line said under the lookup of the member or the provider, or null to take it away. */
        lookupNote(subject, text) {
            const owner = subject === 'member' ? member : subject === 'provider' ? provider : null;
            if (owner) {
                owner.lookup.note(text);
            }
        },

        /**
         * Something the tab on screen says under the member ID or the NPI field that is about how
         * the run will be answered, not about the lookup, such as a mock scenario chosen by hand.
         * content is a node or text, or null to take it away.
         */
        identifierNote(subject, content) {
            const owner = subject === 'member' ? member : subject === 'provider' ? provider : null;
            if (owner) {
                owner.note(content);
            }
        },

        /** Calls back whenever anything in the case changes. */
        onChange(listener) {
            listeners.push(listener);
        }
    };
}

/**
 * Brings a node into view inside the pane that scrolls the case, without moving the focus. A
 * lookup answers after the tester has left the field, often with a sentence below what the pane
 * shows; it is moved up just far enough to be read, and its top is kept in view when it is taller
 * than the pane. Only the pane is scrolled: the frame around it stays where it is. Where the case
 * does not scroll in a pane of its own, on a narrow window, the window is scrolled as little as it can be.
 */
export function bringIntoPane(node) {
    if (!node || node.hidden || !node.isConnected) {
        return;
    }
    const pane = node.closest('.case-pane__scroll');
    if (!pane || pane.scrollHeight <= pane.clientHeight) {
        node.scrollIntoView({block: 'nearest'});
        return;
    }
    const box = pane.getBoundingClientRect();
    const at = node.getBoundingClientRect();
    const margin = 8;
    if (at.top < box.top + margin || at.height > box.height - 2 * margin) {
        pane.scrollTop += at.top - box.top - margin;
    } else if (at.bottom > box.bottom - margin) {
        pane.scrollTop += at.bottom - box.bottom + margin;
    }
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
    // Under the lookup strip: what the tab says about the identifier that is not the lookup's.
    const identifierNote = el('div', {class: 'identifier-note', hidden: true});

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
            return markFieldErrors([identifier.node], fieldErrors.map(problem =>
                (problem.field === spec.refusedAs ? {...problem, field: spec.prefix + '.' + identifier.spec.name} : problem)),
                {focus: viaButton});
        },
        answered(answer) {
            if (answer && answer.status === 'FOUND' && answer[spec.record]) {
                // A field the server marks as masked holds something this tool masked, such as a secret the
                // service sent back. Its mask is not member data and is never put into the case.
                fillFrom(answer[spec.record], new Set((answer.fields || []).filter(field => field.masked === true).map(field => field.name)));
            } else {
                release();
            }
            spec.changed();
        },
        panelChanged: spec.panelChanged,
        // The whole field is brought into view: under the strip, a refusal puts its sentence at the field too.
        ended: strip => bringIntoPane(strip.closest('.field') || strip)
    });

    for (const field of spec.fields) {
        const control = textField({...field, field: spec.prefix + '.' + field.name, beside: field.identifier ? lookup.control : null,
            origin: !field.identifier});
        control.spec = field;
        controls.set(field.name, control);
        nodes.push(control.node);
        if (field.identifier) {
            identifier = control;
            control.node.append(lookup.strip, identifierNote);
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
                if (!pressed && control.value() !== '' && control.value() !== lookup.settledId()) {
                    lookup.lookUp(false);
                }
            });
            // Enter in the identifier looks it up, as the button beside it does. It never sends the
            // run: a tester who presses Enter here expects the lookup, and the send button is the one way to send.
            control.input.addEventListener('keydown', event => {
                if (event.key === 'Enter' && !event.isComposing) {
                    event.preventDefault();
                    lookup.lookUp(true);
                }
            });
            continue;
        }
        // What the lookup on screen gave for this detail, as text. Null while no lookup answer is in force.
        control.given = null;
        control.touched = false;
        // The value put here from a pasted request, while the field still holds it, or null.
        control.pasted = null;
        control.input.addEventListener('input', () => {
            control.touched = true;
            if (control.pasted !== null && control.value() !== control.pasted) {
                control.pasted = null;
            }
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
        if (control.masked && control.value() === '') {
            control.node.classList.remove('field--byhand', 'field--fromlookup');
            control.origin.textContent = 'masked, not taken';
            control.input.title = 'The lookup returned a value holding something this tool masked, so it is not put into the case. '
                + 'Show what the lookup returned to read it with its mask.';
            return;
        }
        const source = sourceOf(control);
        const replaced = control.given !== null && control.given !== '';
        control.node.classList.toggle('field--byhand', source === 'hand');
        control.node.classList.toggle('field--fromlookup', source === 'lookup');
        const pasted = source === 'hand' && control.pasted !== null && control.value() === control.pasted;
        control.origin.textContent = source === 'lookup' ? 'from lookup'
            : source !== 'hand' ? ''
                : pasted ? 'from the pasted request'
                    : !replaced ? 'by hand'
                        : control.value() === '' ? 'cleared, not sent' : 'changed by hand';
        // The pane is narrow and a value can be longer than its input, so the title holds it whole.
        const whole = control.value() === '' ? '' : control.value() + '\n';
        control.input.title = source === 'lookup' ? control.value()
            : source !== 'hand' ? ''
            : pasted ? whole + 'Taken from the pasted request. A lookup would not give this value back, and the ledger notes that the run used it.'
                : replaced ? whole + 'Changed by hand. The lookup returned another value, and the ledger notes that the run used this one.'
                    : whole + 'Typed by hand. A lookup would not give this value back, and the ledger notes that.';
    }

    /** Fills the details from the record a lookup found. A detail the tester edited while the answer was on its way keeps what they typed. */
    function fillFrom(record, masked) {
        for (const control of details()) {
            const returned = record[control.spec.name];
            control.masked = masked.has(control.spec.name);
            const text = control.masked || returned === undefined || returned === null ? '' : String(returned).trim();
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
            control.masked = false;
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
        /** The identifier's field, with its lookup strip under it. */
        identifierNode: nodes[0],
        /** The fields of the details the lookup fills. */
        detailNodes: nodes.slice(1),
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

        /** Empties every detail, typed by hand or not, then puts the identifier in and looks it up. */
        replace(value) {
            for (const control of details()) {
                control.set('');
                control.given = null;
                control.touched = false;
                control.pasted = null;
                mark(control);
            }
            this.setIdentifier(value);
        },

        /** Puts a value in one detail with where it came from: origin "pasted" for a value read out of a pasted request. */
        setDetail(name, value, origin) {
            const control = controls.get(name);
            if (!control || control.spec.identifier) {
                return;
            }
            control.set(value);
            control.touched = true;
            control.pasted = origin === 'pasted' ? control.value() : null;
            mark(control);
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
            const reveal = () => {
                bringIntoPane(identifier.node);
                lookup.strip.focus({preventScroll: true});
            };
            const out = lookup.attention().map(text => ({about, text, reveal}));
            if (lookup.outdated()) {
                out.push({about, text: 'It was made before the configuration changed. The environment now active has not been asked.', reveal});
            }
            return out;
        },

        note(content) {
            identifierNote.hidden = content === null || content === undefined;
            fill(identifierNote, identifierNote.hidden ? null : content);
        },

        madeBefore() {
            lookup.madeBefore('This lookup was made before the configuration changed. The environment now active has not been asked: '
                + 'look the ' + spec.subject + ' up again.');
        }
    };
}
