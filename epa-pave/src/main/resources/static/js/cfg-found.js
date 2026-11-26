// "Found in the collection": the members, providers, procedure codes and diagnosis codes the
// requests of an uploaded collection state, shown where the collection is chosen, and added to the saved lists on the
// tester's word (the box in the environment form, the button on the Collections page).
//
// The server reads the files and says what it found and what it could not use (POST
// /api/lists/found); it reads them again when they are added (POST /api/lists/found/add), so the
// rules of the reading live in one place. The texts of the files stay in this page's memory only,
// until the part is cleared; nothing goes to the browser's storage.

import {call} from './api.js';
import {el, fill, clear, button, named} from './dom.js';
import {faultReport, errorPanel} from './fault.js';

const FOUND = '/api/lists/found';
const ADD = '/api/lists/found/add';

export const BOX_WORDS = 'Add these to the saved lists when the environment is saved';
export const BUTTON_WORDS = 'Add to the saved lists';

/** The four saved lists, by where each opens, for the result line. */
export const LIST_LINKS = [
    {words: 'Members', href: '#configuration/members'},
    {words: 'Providers', href: '#configuration/providers'},
    {words: 'Procedure codes', href: '#configuration/procedure-codes'},
    {words: 'Diagnosis codes', href: '#configuration/diagnosis-codes'}
];

function present(value) {
    return typeof value === 'string' && value.trim() !== '';
}

function joined(parts) {
    const out = [];
    for (const part of parts.filter(Boolean)) {
        if (out.length > 0) {
            out.push(', ');
        }
        out.push(part);
    }
    return out;
}

function mono(text) {
    return el('span', {class: 'mono'}, text);
}

function fromWords(entry) {
    return present(entry.from) ? el('span', {class: 'muted'}, ' From "' + named(entry.from) + '".') : null;
}

/** One line a member: what identifies it, then the request it was found in. */
export function memberLine(entry) {
    const name = [entry.firstName, entry.lastName].filter(present).join(' ');
    return el('li', null, joined([
        present(entry.memberId) ? ['Member ID ', mono(entry.memberId)] : null,
        present(entry.vendorPatientId) ? ['vendor patient id ', mono(entry.vendorPatientId)] : null,
        name || null,
        present(entry.dateOfBirth) ? ['born ', mono(entry.dateOfBirth)] : null
    ]), fromWords(entry));
}

/**
 * Where a provider was found and what it is there, as its saved label will say: a servicing
 * provider is told from the requester when it is picked.
 */
function roleWords(entry) {
    const requester = present(entry.requesterIn) ? entry.requesterIn : null;
    const performer = present(entry.performerIn) ? entry.performerIn : null;
    if (requester && performer) {
        return el('span', {class: 'muted'}, requester === performer ? ' Requester and performer in "' + named(performer) + '".'
            : ' Requester in "' + named(requester) + '", performer in "' + named(performer) + '".');
    }
    if (performer || requester) {
        return el('span', {class: 'muted'}, (performer ? ' Performer in "' + named(performer) : ' Requester in "' + named(requester)) + '".');
    }
    return fromWords(entry);
}

export function providerLine(entry) {
    const name = [entry.firstName, entry.lastName].filter(present).join(' ');
    return el('li', null, joined([
        present(entry.npi) ? ['NPI ', mono(entry.npi)] : null,
        present(entry.vendorPractitionerId) ? ['vendor practitioner id ', mono(entry.vendorPractitionerId)] : null,
        name || null,
        present(entry.organization) ? entry.organization : null
    ]), roleWords(entry));
}

export function codeLine(entry) {
    return el('li', null, joined([
        [mono(entry.code), ' ', present(entry.systemShort) ? entry.systemShort : 'no code system'],
        present(entry.description) ? entry.description : null
    ]), fromWords(entry));
}

/** The line that says what was added, with a link to each list. */
export function resultNode(answer, links) {
    const data = answer || {};
    const notUsed = Array.isArray(data.notUsed) ? data.notUsed : [];
    const to = Array.isArray(links) ? links : LIST_LINKS;
    const opens = [];
    to.forEach((link, index) => {
        if (index > 0) {
            opens.push(index === to.length - 1 ? ' and ' : ', ');
        }
        opens.push(el('a', {href: link.href}, link.words));
    });
    return el('div', {class: 'config-said found-result'},
        el('p', {role: 'status'}, data.line || ''),
        faultReport(data.reference, data.cause),
        data.enabled === false ? null : el('p', {class: 'small'}, 'Open the saved lists: ', opens, '.'),
        notUsed.length > 0 ? el('ul', {class: 'notes'}, notUsed.map(line => el('li', null, line))) : null);
}

/**
 * The part. options.mode: 'box' (the environment form: a ticked box, the adding done by the form on
 * save) or 'button' (the Collections page: a button that adds at once). options.links: the lists
 * the result line opens.
 */
export function createFoundPart(options) {
    const mode = options && options.mode === 'button' ? 'button' : 'box';
    const links = options && Array.isArray(options.links) ? options.links : LIST_LINKS;
    const node = el('div', {class: 'found', 'aria-live': 'polite', hidden: true});
    const box = el('input', {type: 'checkbox', name: 'addFound'});
    box.checked = true;
    let request = null;      // what was read, kept until the part is cleared, so the adding reads the same files
    let answer = null;       // the last answer of the reading
    let reads = 0;           // a slow answer is dropped once a newer reading was asked for

    function entries(data) {
        const lists = [data.members, data.providers, data.codes, data.diagnoses];
        return lists.reduce((sum, list) => sum + (Array.isArray(list) ? list.length : 0), 0);
    }

    function draw() {
        node.hidden = false;
        const data = answer || {};
        const title = el('h3', {class: 'form-section__title'}, 'Found in the collection');
        if (present(data.line) || data.enabled === false) {
            const notUsed = Array.isArray(data.notUsed) ? data.notUsed : [];
            fill(node, title, el('p', {class: 'muted'}, data.line || ''), faultReport(data.reference, data.cause),
                notUsed.length > 0 ? el('ul', {class: 'notes'}, notUsed.map(line => el('li', null, line))) : null);
            return;
        }
        const groups = [
            ['Members', data.members, memberLine],
            ['Providers', data.providers, providerLine],
            ['Procedure codes', data.codes, codeLine],
            ['Diagnosis codes', data.diagnoses, codeLine]
        ].map(([words, list, line]) => {
            const items = Array.isArray(list) ? list : [];
            return el('div', {class: 'found__group'},
                el('p', {class: 'notes__title'}, words + ' (' + items.length + ')'),
                items.length > 0 ? el('ul', {class: 'notes'}, items.map(line)) : el('p', {class: 'muted small'}, 'None stated.'));
        });
        const notUsed = Array.isArray(data.notUsed) ? data.notUsed : [];
        const action = mode === 'box'
            ? el('label', {class: 'config-check'}, box, el('span', null, BOX_WORDS))
            : addButton();
        fill(node, title, groups,
            notUsed.length > 0 ? el('div', {class: 'found__group'}, el('p', {class: 'notes__title'}, 'Seen and not used'),
                el('ul', {class: 'notes'}, notUsed.map(line => el('li', null, line)))) : null,
            action);
    }

    function addButton() {
        const line = el('span', {class: 'muted small', role: 'status'});
        const press = button(BUTTON_WORDS, async () => {
            press.disabled = true;
            line.textContent = 'Adding';
            const shown = await add();
            fill(node, el('h3', {class: 'form-section__title'}, 'Found in the collection'), shown);
        }, {primary: true});
        return el('div', {class: 'inline-controls'}, press, line);
    }

    /** Reads the files: given = {collectionExport, environmentExport?, forId?, fromActiveEnvironment?}. */
    async function read(given) {
        reads += 1;
        const mine = reads;
        request = given && given.collectionExport ? given : null;
        answer = null;
        if (!request) {
            clearPart();
            return;
        }
        box.checked = true;
        const reply = await call('POST', FOUND, {body: request});
        if (mine !== reads) {
            return;
        }
        if (!reply.ok) {
            request = null;
            node.hidden = false;
            fill(node, el('h3', {class: 'form-section__title'}, 'Found in the collection'), errorPanel(reply.error));
            return;
        }
        answer = reply.data || {};
        draw();
    }

    /** Adds what was read, reading the same files again. Returns the result to show, or null when nothing was read. */
    async function add() {
        if (!request) {
            return null;
        }
        const reply = await call('POST', ADD, {body: request});
        if (!reply.ok) {
            return errorPanel(reply.error);
        }
        request = null;
        answer = null;
        return resultNode(reply.data, links);
    }

    function clearPart() {
        reads += 1;
        request = null;
        answer = null;
        clear(node);
        node.hidden = true;
    }

    return {
        node,
        read,
        add,
        clear: clearPart,
        /** True when the environment form is to add on save: something was found and the box is ticked. */
        wanted() {
            return mode === 'box' && request !== null && answer !== null && !present(answer.line) && answer.enabled !== false
                && entries(answer) > 0 && box.checked === true;
        },
        /** The box, for the tests. */
        box
    };
}
