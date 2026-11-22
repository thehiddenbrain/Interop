// The Provider card of the case (plan 3.2, "Case column"): the NPI and its lookup, a saved
// provider, the fields a run sends (phone, fax and the vendor's own practitioner id among them),
// and, last in "Edit provider fields", the servicing provider that Submit alone sends.

import {el, fold, button, fill, clear} from './dom.js';
import {caseField, caseChanged, fieldsOf, restoreFields, MARKS} from './wb-case.js';
import {cardHead, latestOnly, lookUp, fillFromLookup, clearLookedUp, statusChip, lookupLine, returnedFold} from './wb-lookup.js';

const PRACTITIONER_HINT = 'The vendor\'s own id for this practitioner. Sent as Practitioner/<id>.';

/**
 * The Provider card. env: {savedProviders() (the saved list, or []), placeOfService(value, mark)
 * (fills the Service card's place of service from a saved provider's default)}.
 */
export function createProviderCard(env) {
    const head = cardHead('Provider', 'provider');
    const npi = caseField({name: 'npi', label: 'NPI', mono: true});
    const fields = [
        caseField({name: 'firstName', label: 'First name'}),
        caseField({name: 'lastName', label: 'Last name'}),
        caseField({name: 'organizationName', label: 'Organization'}),
        caseField({name: 'specialty', label: 'Specialty'}),
        caseField({name: 'phone', label: 'Phone', mono: true}),
        caseField({name: 'fax', label: 'Fax', mono: true}),
        caseField({name: 'vendorPractitionerId', label: 'Vendor practitioner id', mono: true, hint: PRACTITIONER_HINT})
    ];
    let answer = null;
    let extra = {};
    const run = latestOnly();

    // The row keeps the field's name so a refusal of the NPI is shown under the label the tester sees (I-3).
    const npiRow = el('div', {class: 'field', 'data-case-field': 'npi'}, el('label', {class: 'field__label', for: npi.input.id}, 'NPI', npi.node.querySelector('.field__mark')),
        el('div', {class: 'inline-controls'}, npi.input, el('button', {type: 'button', class: 'btn', on: {click: () => lookup()}}, 'Look up')),
        npi.node.querySelector('.masked-carry-slot'), npi.node.querySelector('.field__error'));
    npi.node = npiRow;
    npi.input.addEventListener('change', () => lookup());
    npi.input.addEventListener('keydown', event => {
        if (event.key === 'Enter') {
            event.preventDefault();
            lookup();
        }
    });
    npi.input.addEventListener('input', () => {
        answer = null;
        extra = {};
        draw();
    });

    const picker = el('select', {class: 'select', id: 'provider-picker'});
    const pickerLabel = el('label', {class: 'visually-hidden', for: 'provider-picker'}, 'Saved provider');
    picker.addEventListener('change', () => pickSaved(picker.value));
    const summary = el('div', {class: 'summary', hidden: true});
    const line = el('div');
    const returned = el('div');

    // The servicing provider: Submit only, and only when one is chosen or typed.
    const servicing = createServicing(env);

    const edit = fold({summary: 'Edit provider fields', inline: true, body: el('div', null,
        grid(fields), servicing.node)});
    const node = el('section', {class: 'card', 'aria-label': 'Provider'}, head.node,
        el('div', {class: 'card__body'}, pickerLabel, picker, npiRow, summary, line, edit, returned));

    function drawPicker() {
        const saved = env.savedProviders();
        fill(picker, el('option', {value: ''}, 'Choose a saved provider'),
            saved.map(entry => el('option', {value: entry.id}, savedText(entry))));
        picker.hidden = saved.length === 0;
        pickerLabel.hidden = saved.length === 0;
        servicing.drawPicker();
    }

    function pickSaved(value) {
        const entry = env.savedProviders().find(each => each.id === value);
        if (!entry) {
            return;
        }
        answer = null;
        extra = {};
        npi.clear();
        for (const control of fields) {
            control.clear();
        }
        if (entry.npi) {
            npi.set(entry.npi, MARKS.SAVED);
        }
        for (const control of fields) {
            const name = control.name === 'organizationName' ? 'organization' : control.name;
            if (entry[name]) {
                control.set(entry[name], MARKS.SAVED);
            }
        }
        if (entry.placeOfService) {
            env.placeOfService(entry.placeOfService, MARKS.SAVED);
        }
        draw();
        caseChanged('picked');
    }

    async function lookup() {
        const value = npi.value();
        if (value === '') {
            return;
        }
        await run(() => lookUp('/api/providers', {npi: value}), reply => {
            const found = reply.ok ? reply.data : null;
            answer = found || {status: 'FAULT', error: reply.error};
            if (found && found.status === 'FOUND' && found.provider) {
                fillFromLookup(fields, found.provider, found.fields);
                extra = {taxonomyCode: found.provider.taxonomyCode, networkStatus: found.provider.networkStatus, address: found.provider.address};
            } else {
                clearLookedUp(fields);
                extra = {};
            }
            draw();
            caseChanged('lookup');
        });
    }

    function draw() {
        const values = fieldsOf(fields).values;
        const name = [values.firstName, values.lastName].filter(Boolean).join(' ') || values.organizationName || '';
        const chosen = name || npi.value() || values.vendorPractitionerId || '';
        // An NPI the server refused is never drawn with the tick of a chosen provider (I-3).
        const said = npi.node.querySelector('.field__error');
        const refused = Boolean(said && !said.hidden);
        head.picked(chosen || 'not chosen', Boolean(chosen) && !refused);
        head.attention(Boolean(answer && Array.isArray(answer.attention) && answer.attention.length > 0));
        const status = statusChip(answer);
        const network = extra.networkStatus || null;
        summary.hidden = !(name || status);
        fill(summary, 
            el('div', {class: 'summary__line'}, el('span', {class: 'summary__name'}, name || chosen), status),
            values.specialty || network ? el('div', {class: 'summary__line'}, values.specialty ? el('span', null, values.specialty) : null,
                network ? el('span', null, network) : null) : null);
        fill(line, lookupLine(answer, {words: 'Set up the provider lookup', href: '#configuration/providers'}) || '');
        fill(returned, returnedFold(answer) || '');
    }

    for (const control of [npi].concat(fields)) {
        control.input.addEventListener('input', () => draw());
    }
    drawPicker();
    draw();

    return {
        node,
        drawPicker,
        request() {
            const {values, overrides} = fieldsOf([npi].concat(fields));
            const provider = {...values};
            for (const [key, value] of Object.entries(extra)) {
                if (value !== undefined && value !== null && value !== '' && provider[key] === undefined) {
                    provider[key] = value;
                }
            }
            return {provider, providerOverrides: overrides.filter(name => name !== 'npi'), servicingProvider: servicing.request()};
        },
        label() {
            const values = fieldsOf(fields).values;
            return [values.firstName, values.lastName].filter(Boolean).join(' ') || values.organizationName || npi.value()
                || values.vendorPractitionerId || '';
        },
        show(name, message) {
            if (name.startsWith('servicingProvider.')) {
                edit.open = true;
                return servicing.show(name.slice('servicingProvider.'.length), message);
            }
            const control = [npi].concat(fields).find(each => each.name === name);
            if (!control) {
                return false;
            }
            if (edit.contains(control.input)) {
                edit.open = true;
            }
            control.setError(message || null);
            control.input.focus();
            draw();
            return true;
        },
        /** Takes the NPI of a mock member's sample case, and looks it up. */
        useNpi(value) {
            picker.value = '';
            answer = null;
            extra = {};
            for (const control of fields) {
                control.clear();
            }
            npi.set(value, MARKS.SCENARIO);
            draw();
            lookup();
        },
        clear() {
            picker.value = '';
            answer = null;
            extra = {};
            npi.clear();
            for (const control of fields) {
                control.clear();
            }
            servicing.clear();
            draw();
        },
        /** The provider and servicing provider of a case read back after a reload, marked where they came from; nothing looked up. */
        restore(given, overrides, servingGiven, mark) {
            picker.value = '';
            answer = null;
            const values = given && typeof given === 'object' ? given : {};
            npi.clear();
            if (typeof values.npi === 'string' && values.npi !== '') {
                npi.set(values.npi, mark);
            }
            const rest = restoreFields(fields, values, overrides, mark);
            delete rest.npi;
            extra = rest;
            servicing.restore(servingGiven, mark);
            draw();
        }
    };
}

function savedText(entry) {
    return (entry.label || entry.npi || entry.vendorPractitionerId) + (entry.npi ? ' (NPI ' + entry.npi + ')' : ' (' + entry.vendorPractitionerId + ')');
}

function grid(controls) {
    const rows = [];
    for (let i = 0; i < controls.length; i += 2) {
        rows.push(el('div', {class: 'field-row'}, controls[i].node, controls[i + 1] ? controls[i + 1].node : null));
    }
    return rows;
}

/** The part "Servicing provider (optional, Submit only)": a saved provider, or its fields typed. */
function createServicing(env) {
    const fields = [
        caseField({name: 'npi', label: 'NPI', mono: true}),
        caseField({name: 'vendorPractitionerId', label: 'Vendor practitioner id', mono: true}),
        caseField({name: 'firstName', label: 'First name'}),
        caseField({name: 'lastName', label: 'Last name'}),
        caseField({name: 'organizationName', label: 'Organization'}),
        caseField({name: 'phone', label: 'Phone', mono: true}),
        caseField({name: 'fax', label: 'Fax', mono: true})
    ];
    const picker = el('select', {class: 'select', id: 'servicing-picker'});
    const pickerLabel = el('label', {class: 'field__label', for: 'servicing-picker'}, 'Servicing provider, from the saved list');
    picker.addEventListener('change', () => {
        const entry = env.savedProviders().find(each => each.id === picker.value);
        if (!entry) {
            return;
        }
        for (const control of fields) {
            const name = control.name === 'organizationName' ? 'organization' : control.name;
            if (entry[name]) {
                control.set(entry[name], MARKS.SAVED);
            } else {
                control.clear();
            }
        }
        caseChanged('picked');
    });
    const node = el('div', {class: 'servicing'},
        el('p', {class: 'notes__title'}, 'Servicing provider (optional, Submit only)'),
        pickerLabel, picker, grid(fields),
        button('Remove the servicing provider', () => {
            forget();
            caseChanged('typed');
        }, {small: true, quiet: true}));
    function forget() {
        picker.value = '';
        for (const control of fields) {
            control.clear();
        }
    }
    return {
        node,
        drawPicker() {
            const saved = env.savedProviders();
            fill(picker, el('option', {value: ''}, 'Choose a saved provider'),
                saved.map(entry => el('option', {value: entry.id}, savedText(entry))));
            picker.hidden = saved.length === 0;
            pickerLabel.hidden = saved.length === 0;
        },
        /** The servicing provider, or null when none is given. */
        request() {
            const {values} = fieldsOf(fields);
            return Object.keys(values).length > 0 ? values : null;
        },
        show(name, message) {
            const control = fields.find(each => each.name === name);
            if (!control) {
                return false;
            }
            control.setError(message || null);
            control.input.focus();
            return true;
        },
        clear: forget,
        restore(given, mark) {
            picker.value = '';
            restoreFields(fields, given, [], mark);
        }
    };
}
