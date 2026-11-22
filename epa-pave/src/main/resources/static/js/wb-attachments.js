// The files a tester adds to a submission (plan 3.2, step 3; R-13): a compact block with "Add files",
// one row per file (its title, editable; its kind; its size; Remove) and one line for each file
// refused. The kinds and the limits are the server's (GET /api/pas/options), never this page's.
//
// A chosen file is read when the request is sent or previewed, never earlier (R-33): what is kept
// until then is the File the browser handed over, so a file changed, moved or deleted after it was
// chosen is said at the send, and nothing is sent. Nothing of a file is kept in browser storage.

import {el, chip, button, fill, clear} from './dom.js';
import {readBase64, sizeText} from './files.js';

const KINDS_WORDS = 'PAVE sends PDF, PNG, JPEG, TIFF, GIF, text and Word files.';

/** The extension of a file name, lower case, without its dot; '' when it has none. */
export function extensionOf(name) {
    const text = String(name || '');
    const dot = text.lastIndexOf('.');
    return dot > 0 && dot < text.length - 1 ? text.slice(dot + 1).toLowerCase() : '';
}

/** A file name without its extension, as the title a file starts with. */
export function titleOf(name) {
    const text = String(name || '');
    const dot = text.lastIndexOf('.');
    return dot > 0 ? text.slice(0, dot) : text;
}

/**
 * Whether a chosen file may be added: its kind among the server's, its size within the limit for a
 * file, and all the files within the limit in all. Returns {kind} or {line} with the refusal's words.
 * limits: {kinds [{extension, contentType, label}], perFileBytes, totalBytes}; heldBytes: the files
 * already added.
 */
export function admit(file, limits, heldBytes) {
    const name = file && file.name ? String(file.name) : 'The file';
    const kinds = limits && Array.isArray(limits.kinds) ? limits.kinds : [];
    const kind = kinds.find(each => each.extension === extensionOf(name));
    if (!kind) {
        return {line: name + ': ' + KINDS_WORDS + ' Not added.'};
    }
    const size = Number(file.size) || 0;
    if (limits.perFileBytes > 0 && size > limits.perFileBytes) {
        return {line: name + ' is ' + sizeText(size) + '; the limit is ' + sizeText(limits.perFileBytes) + '. Not added.'};
    }
    if (limits.totalBytes > 0 && heldBytes + size > limits.totalBytes) {
        return {line: 'These files come to ' + sizeText(heldBytes + size) + '; the limit is ' + sizeText(limits.totalBytes) + ' in all. Not added.'};
    }
    return {kind};
}

/**
 * The block. spec: {limits() (the server's attachments, or null while not read), changed()}.
 * Returns {node, count(), bytes(), read() -> {ok, attachments} | {ok: false, line}, clear(), redraw()}.
 */
export function createAttachments(spec) {
    const held = [];
    const input = el('input', {type: 'file', multiple: true, class: 'visually-hidden', id: 'pas-files', 'aria-label': 'Add files'});
    const add = button('Add files', () => input.click(), {small: true});
    const hint = el('span', {class: 'field__hint'});
    const rows = el('tbody');
    const table = el('div', {class: 'table-wrap', hidden: true}, el('table', {class: 'table'},
        el('thead', null, el('tr', null, el('th', {scope: 'col'}, 'Title'), el('th', {scope: 'col'}, 'Kind'), el('th', {scope: 'col'}, 'Size'),
            el('th', {scope: 'col'}, el('span', {class: 'visually-hidden'}, 'Remove')))), rows));
    const refused = el('div', {class: 'attachments__refused'});
    const node = el('div', {class: 'panel__section attachments'},
        el('div', {class: 'form-head'}, el('h3', {class: 'form-head__title'}, 'Attachments'), add, input),
        hint, refused, table);

    function drawLimits() {
        const limits = spec.limits();
        if (!limits) {
            add.disabled = true;
            fill(hint, 'The kinds and limits PAVE takes could not be read; files cannot be added now.');
            return;
        }
        add.disabled = false;
        input.setAttribute('accept', (limits.kinds || []).map(kind => '.' + kind.extension).join(','));
        fill(hint, 'PDF, image or document; up to ' + sizeText(limits.perFileBytes) + ' each and ' + sizeText(limits.totalBytes) + ' in all.');
    }

    function bytes() {
        return held.reduce((sum, one) => sum + (Number(one.file.size) || 0), 0);
    }

    input.addEventListener('change', () => {
        const limits = spec.limits();
        const lines = [];
        for (const file of Array.from(input.files || [])) {
            const verdict = limits ? admit(file, limits, bytes()) : {line: 'The kinds and limits PAVE takes could not be read. Not added.'};
            if (verdict.line) {
                lines.push(verdict.line);
                continue;
            }
            const title = el('input', {class: 'input', type: 'text', value: titleOf(file.name), 'aria-label': 'Title of ' + file.name});
            const one = {file, kind: verdict.kind, title};
            const row = el('tr', null,
                el('td', null, title),
                el('td', null, chip(verdict.kind.label)),
                el('td', {class: 'small mono'}, sizeText(file.size)),
                el('td', null, button('Remove', () => {
                    held.splice(held.indexOf(one), 1);
                    row.remove();
                    table.hidden = held.length === 0;
                    spec.changed();
                }, {small: true, remove: true, title: 'Remove ' + file.name})));
            one.row = row;
            held.push(one);
            rows.append(row);
        }
        table.hidden = held.length === 0;
        fill(refused, lines.map(line => el('p', {class: 'field__error'}, line)));
        // Cleared, so choosing the same file again after removing it is seen as a change.
        input.value = '';
        spec.changed();
    });

    drawLimits();
    return {
        node,
        count: () => held.length,
        bytes,
        /**
         * Reads every file, now. Any file that can no longer be read refuses the whole send with its
         * line: a request never goes with a file missing that the tester saw listed.
         */
        async read() {
            const attachments = [];
            for (const one of held) {
                const read = await readBase64(one.file, {maxBytes: spec.limits() ? spec.limits().perFileBytes : undefined});
                if (!read.ok) {
                    return {ok: false, line: read.line};
                }
                attachments.push({title: one.title.value.trim() || titleOf(one.file.name), contentType: one.kind.contentType,
                    data: read.data, size: read.size});
            }
            return {ok: true, attachments};
        },
        clear() {
            held.length = 0;
            clear(rows);
            table.hidden = true;
            clear(refused);
        },
        redraw: drawLimits
    };
}
