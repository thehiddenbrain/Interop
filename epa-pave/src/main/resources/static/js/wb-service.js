// The Service card of the case (plan 3.2, "Case column"): the procedure codes, the date of
// service, the diagnosis codes, the fold "Service details" that Submit sends (quantity and unit
// per code, place of service, level of service, certification, request and service types) and the
// fold "Options" with the tester's reference and note, which the ledger keeps as typed.
//
// The words of every choice in "Service details" are the server's (GET /api/pas/options): before
// it gives them, a choice offers "Not stated" only and its text fields take any value.

import {el, fold, fill, clear} from './dom.js';
import {createCodePicker} from './code-picker.js';
import {caseField, caseChanged, restoreFields, LEDGER_TEXT_HINT, MARKS} from './wb-case.js';
import {cardHead} from './wb-lookup.js';

let lists = 0;

/**
 * A saved procedure code as the "Saved codes" choice names it: its code, then its label or else its
 * description. Its system is the option's title.
 */
export function savedCodeText(entry) {
    const words = entry.label || entry.description || '';
    return entry.code + (words ? ' \u00b7 ' + words : '');
}

/** env: {lineOfBusiness(), options() (the answer of GET /api/pas/options, or null), savedCodes() (the saved list, or [])}. */
export function createServiceCard(env) {
    lists += 1;
    const head = cardHead('Service', 'service');
    const picker = createCodePicker({lineOfBusiness: () => env.lineOfBusiness(), changed: () => {
        drawDetails();
        draw();
        caseChanged('codes');
    }});
    // "Saved codes": a pick adds the code with its system and description, as a saved member fills the Member card.
    const savedId = 'saved-codes-' + lists;
    const saved = el('select', {class: 'select', id: savedId});
    const savedLabel = el('label', {class: 'field__label', for: savedId}, 'Saved codes');
    const savedField = el('div', {class: 'field', hidden: true}, savedLabel, saved);
    saved.addEventListener('change', () => {
        const entry = savedCodes().find(each => each.id === saved.value);
        saved.value = '';
        if (!entry) {
            return;
        }
        const candidate = {code: entry.code};
        if (entry.system) {
            candidate.system = entry.system;
        }
        if (entry.description) {
            candidate.description = entry.description;
        }
        picker.addSaved(candidate, MARKS.SAVED);
    });

    function savedCodes() {
        const given = typeof env.savedCodes === 'function' ? env.savedCodes() : [];
        return Array.isArray(given) ? given.filter(each => each && typeof each.code === 'string' && each.code !== '') : [];
    }

    /** Draws the saved codes, offered only when the list holds any. */
    function drawSaved() {
        const entries = savedCodes();
        fill(saved, el('option', {value: ''}, 'Choose a saved code'),
            entries.map(entry => el('option', {value: entry.id, title: entry.system || null}, savedCodeText(entry))));
        saved.value = '';
        savedField.hidden = entries.length === 0;
    }
    drawSaved();

    // A text field in mono, ISO as the server reads it and as every other date on screen is shown (prototype 01):
    // a native date field shows the browser's locale order and is cut at 1024.
    const date = caseField({name: 'dateOfService', label: 'Date of service', mono: true});
    date.input.placeholder = 'YYYY-MM-DD';
    // Every step sends them, each in its own place (D-321).
    const diagnosis = caseField({name: 'diagnosisCodes', label: 'Diagnosis codes', mono: true,
        hint: 'Sent as the order\'s reason in the coverage check and the questionnaire request, and as the claim\'s diagnosis in Submit.'});
    const placesId = 'places-' + lists;
    const typesId = 'service-types-' + lists;
    const places = el('datalist', {id: placesId});
    const types = el('datalist', {id: typesId});
    const place = caseField({name: 'placeOfService', label: 'Place of service', mono: true, list: placesId});
    const level = caseField({name: 'levelOfService', label: 'Level of service', kind: 'select', options: [['', 'Not stated']]});
    const certification = caseField({name: 'certificationType', label: 'Certification type', kind: 'select', options: [['', 'Not stated']]});
    const requestType = caseField({name: 'requestType', label: 'Request type', kind: 'select', options: [['', 'Not stated']]});
    const serviceType = caseField({name: 'serviceType', label: 'Service type', mono: true, list: typesId});
    const testerRef = caseField({name: 'testerRef', label: 'Tester reference'});
    const note = caseField({name: 'note', label: 'Note'});

    // Quantity and unit per code, kept by code so that a code removed and added again starts empty.
    let perCode = new Map();
    const perCodeHost = el('div', {class: 'per-code'});
    const details = fold({summary: 'Service details', inline: true, body: el('div', null,
        perCodeHost,
        el('div', {class: 'field-row'}, place.node, level.node),
        el('div', {class: 'field-row'}, certification.node, requestType.node),
        el('div', {class: 'field-row'}, serviceType.node, el('div')),
        places, types,
        el('p', {class: 'field__hint'}, 'Submit sends these; the questionnaire uses the place of service.'))});
    // The ledger hint covers both fields, so it is said once under them (I-5).
    const options = fold({summary: 'Options', inline: true, body: el('div', null,
        el('div', {class: 'field-row'}, testerRef.node, note.node),
        el('p', {class: 'field__hint'}, LEDGER_TEXT_HINT))});
    const node = el('section', {class: 'card', 'aria-label': 'Service'}, head.node,
        el('div', {class: 'card__body'}, savedField, picker.node, el('div', {class: 'field-row'}, date.node, diagnosis.node), details, options));

    date.input.addEventListener('input', () => caseChanged('date'));

    function units() {
        const given = env.options();
        return given && Array.isArray(given.units) ? given.units : [];
    }

    function drawDetails() {
        const codes = picker.values().map(code => code.code);
        const next = new Map();
        for (const code of codes) {
            next.set(code, perCode.get(code) || {
                quantity: caseField({name: 'quantity', label: 'Qty', kind: 'number', mono: true}),
                unit: caseField({name: 'unit', label: 'Unit', kind: 'select', options: [['', 'Not stated']].concat(units().map(each => [each.code, each.label]))})
            });
        }
        perCode = next;
        fill(perCodeHost, ...codes.map(code => {
            const row = perCode.get(code);
            return el('div', {class: 'per-code__row'}, el('span', {class: 'mono per-code__code'}, code), row.quantity.node, row.unit.node);
        }));
    }

    /** Draws the server's choices into the selects and suggestions, keeping what is chosen. */
    function drawOptions() {
        const given = env.options() || {};
        const choices = (control, list) => {
            const before = control.input.value;
            fill(control.input, el('option', {value: ''}, 'Not stated'),
                (Array.isArray(list) ? list : []).map(each => el('option', {value: each.code}, each.code + ' ' + each.label)));
            control.input.value = before;
        };
        choices(level, given.levelsOfService);
        choices(certification, given.certificationTypes);
        choices(requestType, given.requestTypes);
        fill(places, ...(Array.isArray(given.placesOfService) ? given.placesOfService : [])
            .map(each => el('option', {value: each.code, label: each.code + ' ' + each.label})));
        fill(types, ...(Array.isArray(given.serviceTypes) ? given.serviceTypes : [])
            .map(each => el('option', {value: each.code, label: each.code + ' ' + each.label})));
        for (const row of perCode.values()) {
            const before = row.unit.input.value;
            fill(row.unit.input, el('option', {value: ''}, 'Not stated'),
                units().map(each => el('option', {value: each.code}, each.label)));
            row.unit.input.value = before;
        }
    }

    function draw() {
        const codes = picker.values();
        head.picked(codes.length === 0 ? 'no code' : codes[0].code + (codes.length > 1 ? ' +' + (codes.length - 1) : ''), codes.length > 0);
    }
    draw();

    return {
        node,
        drawOptions,
        /** The saved list was read again: its codes are offered as it now holds them. */
        drawSaved,
        /** The service as every run sends it. Quantity and unit go with a code only when given. */
        request() {
            const procedureCodes = picker.values().map(code => {
                const row = perCode.get(code.code);
                const out = {...code};
                if (row && row.quantity.value() !== '') {
                    // A number, as the server reads a quantity; text that is not one goes as typed and is refused at the field.
                    const quantity = Number(row.quantity.value());
                    out.quantity = Number.isFinite(quantity) ? quantity : row.quantity.value();
                }
                if (row && row.unit.value() !== '') {
                    out.unit = row.unit.value();
                }
                return out;
            });
            const text = control => (control.value() === '' ? undefined : control.value());
            const diagnosisCodes = diagnosis.value() === '' ? undefined
                : diagnosis.value().split(',').map(each => each.trim()).filter(each => each !== '');
            return {
                procedureCodes,
                dateOfService: text(date),
                diagnosisCodes,
                placeOfService: text(place),
                levelOfService: text(level),
                certificationType: text(certification),
                requestType: text(requestType),
                serviceType: text(serviceType),
                testerRef: text(testerRef),
                note: text(note)
            };
        },
        /** The case's line of business changed: the code lines show what the list says for it. */
        redrawCodes() {
            picker.redraw();
        },
        /** Text typed in the code box and not added. */
        typedCode() {
            return picker.typed();
        },
        codeInput() {
            return picker.input;
        },
        codesLabel() {
            return picker.values().map(code => code.code).join(', ');
        },
        /** Fills the codes from a mock member's sample case, marked as such. */
        useCodes(codes) {
            picker.replace(codes);
            drawDetails();
            draw();
        },
        placeOfService(value, mark) {
            place.set(value, mark);
        },
        dateOfService() {
            return date.value();
        },
        show(name, message) {
            const all = [date, diagnosis, place, level, certification, requestType, serviceType, testerRef, note];
            // A quantity or unit of one code is shown at that code's row, its fold opened (I-3).
            const perCodeField = /^procedureCodes\[(\d{1,3})\]\.(quantity|unit)$/.exec(name);
            const code = perCodeField ? picker.values()[Number(perCodeField[1])] : null;
            const row = code ? perCode.get(code.code) : null;
            if (row) {
                const control = row[perCodeField[2]];
                details.open = true;
                control.setError(message || null);
                control.input.focus();
                return true;
            }
            if (name === 'procedureCodes' || name.startsWith('procedureCodes')) {
                picker.input.focus();
                return true;
            }
            const control = all.find(each => each.name === name);
            if (!control) {
                return false;
            }
            for (const holder of [details, options]) {
                if (holder.contains(control.input)) {
                    holder.open = true;
                }
            }
            control.setError(message || null);
            control.input.focus();
            return true;
        },
        /**
         * The service of a case read back after a reload: the codes with their quantity and unit,
         * and every field, marked where they came from. Diagnosis codes go back as typed, comma
         * separated; one posted as {code} gives its code.
         */
        restore(given, mark) {
            const values = given && typeof given === 'object' ? given : {};
            const codes = (Array.isArray(values.procedureCodes) ? values.procedureCodes : [])
                .filter(code => code && typeof code.code === 'string' && code.code !== '');
            picker.replace(codes.map(code => {
                const copy = {...code};
                delete copy.quantity;
                delete copy.unit;
                return copy;
            }));
            drawDetails();
            for (const code of codes) {
                const row = perCode.get(code.code);
                if (row && code.quantity !== undefined && code.quantity !== null && code.quantity !== '') {
                    row.quantity.set(code.quantity, mark);
                }
                if (row && typeof code.unit === 'string' && code.unit !== '') {
                    row.unit.set(code.unit, mark);
                }
            }
            const diagnoses = (Array.isArray(values.diagnosisCodes) ? values.diagnosisCodes : [])
                .map(each => (typeof each === 'string' ? each : each && typeof each.code === 'string' ? each.code : ''))
                .filter(each => each !== '');
            const text = {...values, diagnosisCodes: diagnoses.length > 0 ? diagnoses.join(', ') : undefined};
            restoreFields([date, diagnosis, place, level, certification, requestType, serviceType, testerRef, note], text, [], mark);
            draw();
        },
        clear() {
            picker.replace([]);
            for (const control of [date, diagnosis, place, level, certification, requestType, serviceType, testerRef, note]) {
                control.clear();
            }
            drawDetails();
            draw();
        }
    };
}
