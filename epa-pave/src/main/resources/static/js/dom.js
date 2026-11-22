// Small helpers that build elements, shared by every view.
//
// Everything a service returns is untrusted text. Nothing here accepts markup: every string
// becomes a text node, so a value that looks like a tag is shown as the characters it is. The
// build fails (StaticFrontendTest) if any script builds markup or code from a string.

// The properties el() sets directly. Any other name is a mistake in the calling code and is
// refused at once, so a property that would parse text as markup can never be reached through
// this helper by accident.
const PROPERTIES = new Set([
    'id', 'title', 'hidden', 'disabled', 'type', 'value', 'name', 'htmlFor', 'href', 'tabIndex', 'checked',
    'placeholder', 'colSpan', 'rowSpan', 'scope', 'open', 'download', 'rel', 'target', 'min', 'max', 'step',
    'autocomplete', 'inputMode', 'spellcheck', 'rows', 'readOnly', 'required', 'multiple', 'accept', 'selected',
    'label', 'lang', 'maxLength', 'wrap', 'autofocus'
]);

// An inline handler or inline style is refused by the content security policy at run time, with
// nothing on screen to show for it. Refusing them here makes the mistake show while it is written.
const REFUSED = /^(on.+|style|srcdoc)$/i;

/**
 * Builds one element.
 *
 * attrs: class (a string, or a list with empty entries left out), text (set as text), title,
 * aria-* and data-* (as attributes), role, for, on ({event: listener}), the plain properties
 * above, and, for code ported from the earlier screens, aria, dataset and attrs as objects.
 * A null or undefined value is left out. children: nodes, strings and numbers (as text), lists,
 * or null.
 */
export function el(tag, attrs, ...children) {
    const node = document.createElement(tag);
    if (attrs) {
        for (const [key, value] of Object.entries(attrs)) {
            set(node, key, value);
        }
    }
    append(node, children);
    return node;
}

function set(node, key, value) {
    if (value === undefined || value === null) {
        return;
    }
    if (key === 'class') {
        node.className = Array.isArray(value) ? value.filter(Boolean).join(' ') : String(value);
    } else if (key === 'text') {
        node.textContent = String(value);
    } else if (key === 'on') {
        for (const [type, listener] of Object.entries(value)) {
            if (typeof listener === 'function') {
                node.addEventListener(type, listener);
            }
        }
    } else if (key.startsWith('aria-') || key === 'role') {
        node.setAttribute(key, String(value));
    } else if (key.startsWith('data-')) {
        if (value !== false) {
            node.setAttribute(key, value === true ? '' : String(value));
        }
    } else if (key === 'aria' || key === 'dataset') {
        for (const [name, entry] of Object.entries(value)) {
            set(node, key === 'aria' ? 'aria-' + name : 'data-' + dashed(name), entry);
        }
    } else if (key === 'attrs') {
        for (const [name, entry] of Object.entries(value)) {
            if (REFUSED.test(name)) {
                throw new Error('el(): the attribute "' + name + '" is not set from script in this tool.');
            }
            if (entry !== undefined && entry !== null && entry !== false) {
                node.setAttribute(name, entry === true ? '' : String(entry));
            }
        }
    } else if (key === 'for') {
        node.htmlFor = String(value);
    } else if (key === 'tabindex') {
        node.tabIndex = value;
    } else if (PROPERTIES.has(key)) {
        node[key] = value;
    } else if (REFUSED.test(key)) {
        throw new Error('el(): "' + key + '" is refused by the content security policy; use on: {...} or a class.');
    } else {
        throw new Error('el(): "' + key + '" is not a property this helper sets.');
    }
}

/** A dataset name as its attribute is written: maxRows becomes max-rows. */
function dashed(name) {
    let out = '';
    for (const character of String(name)) {
        out += character >= 'A' && character <= 'Z' ? '-' + character.toLowerCase() : character;
    }
    return out;
}

/** Appends children: nodes as they are, strings and numbers as text, lists flattened, nothing for null or false. */
export function append(parent, ...children) {
    for (const child of children.flat(Infinity)) {
        if (child === null || child === undefined || child === false) {
            continue;
        }
        parent.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return parent;
}

/** Removes every child. */
export function clear(node) {
    node.replaceChildren();
    return node;
}

/** Replaces the children of a node. */
export function fill(node, ...children) {
    clear(node);
    return append(node, children);
}

// The tones a chip can carry. The server's tones (model/Tone) and the three test results; any
// other value draws a plain chip, so a colour never appears without a meaning a tester can name.
const TONES = new Set(['clear', 'action', 'pending', 'stop', 'data', 'none', 'fault', 'blocked', 'neutral', 'pass', 'fail',
    'unverified']);

/** The class name of a tone: the server's code (NO_ANSWER) or the name itself; null for one this page does not draw. */
export function toneName(tone) {
    if (!tone) {
        return null;
    }
    const lower = String(tone).toLowerCase();
    const name = lower === 'no_answer' ? 'none' : lower;
    return TONES.has(name) ? name : null;
}

/**
 * A small pill: a status, a code, a count. options: tone (a server tone or a test result),
 * mono (literal data), dashed (nothing came back for this), hatched (a mock answered), title.
 * A long text is cut on screen by the stylesheet and kept whole in the title.
 */
export function chip(text, options) {
    const {tone, mono, dashed: isDashed, hatched, title} = options || {};
    const shown = text === undefined || text === null ? '' : String(text);
    const name = toneName(tone);
    return el('span', {
        class: ['chip', name ? 'chip--' + name : null, isDashed ? 'chip--none' : null, hatched ? 'chip--mock' : null,
            mono ? 'chip--mono' : null],
        title: title || (shown.length > 20 ? shown : null)
    }, el('span', {class: 'chip__text'}, shown));
}

/** A badge: the kind of an environment (sandbox, uat, prod) or the profile (mock, live). */
export function badge(text, kind) {
    return el('span', {class: ['badge', kind ? 'badge--' + token(kind) : null]}, text);
}

/**
 * A fold, closed unless open is given: summary, a quiet hint after it, and the body. A body given
 * as a function is drawn the first time the fold opens, so a large answer costs nothing until it
 * is asked for. inline: the small fold of a card ("Edit fields").
 */
export function fold(options) {
    const {summary, hint, open, body, inline} = options || {};
    const content = el('div', {class: 'fold__body'});
    const details = el('details', {class: inline ? 'fold fold--inline' : 'fold'},
        el('summary', null, summary, hint ? el('span', {class: 'fold__hint'}, hint) : null), content);
    if (typeof body === 'function') {
        let drawn = false;
        const draw = () => {
            if (!drawn && details.open) {
                drawn = true;
                append(content, body());
            }
        };
        details.addEventListener('toggle', draw);
        if (open) {
            details.open = true;
            draw();
        }
    } else {
        append(content, body);
        details.open = Boolean(open);
    }
    return details;
}

/** Facts as a two-column table: rows of {key, value}, value a node or a string, drawn as given. */
export function kv(rows) {
    const body = el('tbody');
    for (const row of rows || []) {
        if (row) {
            body.append(el('tr', null, el('th', {scope: 'row'}, row.key), el('td', null, row.value)));
        }
    }
    return el('table', {class: 'kv'}, body);
}

/**
 * A button that never submits a form by accident. Its label names what it does ("Close preview",
 * never "Close"): ButtonNamesAndMarksTest reads every script for a bare one. options: primary,
 * small, quiet, remove (a removal, set apart by place and words), title, disabled.
 */
export function button(label, onClick, options) {
    const {primary, small, quiet, remove, title, disabled} = options || {};
    return el('button', {
        type: 'button',
        class: ['btn', primary ? 'btn--primary' : null, quiet ? 'btn--quiet' : null, remove ? 'btn--remove' : null,
            small ? 'btn--small' : null],
        title,
        disabled: disabled ? true : null,
        on: typeof onClick === 'function' ? {click: onClick} : null
    }, label);
}

let fields = 0;

/**
 * A labelled field: label, the input (given an id when it has none, so the label names it), a
 * hint, and an error line that setFieldError fills. The hint and the error are tied to the input,
 * so a screen reader reads them with it.
 */
export function field(options) {
    const {label, input, hint, error} = options || {};
    let id = input && input.id ? input.id : null;
    if (input && !id) {
        fields += 1;
        id = 'field-' + fields;
        input.id = id;
    }
    const wrapper = el('div', {class: 'field'},
        el('label', {class: 'field__label', for: id}, label),
        input || null,
        hint ? el('p', {class: 'field__hint', id: id ? id + '-hint' : null}, hint) : null,
        el('p', {class: 'field__error', id: id ? id + '-error' : null, hidden: true}));
    describe(wrapper);
    setFieldError(wrapper, error);
    return wrapper;
}

/** Shows a problem at a field made by field(), or clears it when message is empty. */
export function setFieldError(wrapper, message) {
    if (!wrapper) {
        return;
    }
    const line = wrapper.querySelector('.field__error');
    const input = wrapper.querySelector('input, select, textarea');
    const has = message !== undefined && message !== null && String(message) !== '';
    if (line) {
        line.textContent = has ? String(message) : '';
        line.hidden = !has;
    }
    wrapper.classList.toggle('field--error', has);
    if (input) {
        if (has) {
            input.setAttribute('aria-invalid', 'true');
        } else {
            input.removeAttribute('aria-invalid');
        }
    }
    describe(wrapper);
}

function describe(wrapper) {
    const input = wrapper.querySelector('input, select, textarea');
    if (!input) {
        return;
    }
    const ids = [];
    for (const line of wrapper.querySelectorAll('.field__hint, .field__error')) {
        if (line.id && !line.hidden) {
            ids.push(line.id);
        }
    }
    if (ids.length > 0) {
        input.setAttribute('aria-describedby', ids.join(' '));
    } else {
        input.removeAttribute('aria-describedby');
    }
}

let dialogs = 0;

/**
 * A modal: a native dialog, so focus is held inside it and Escape closes it. footer: nodes, or a
 * function given the dialog that returns them, so a footer button can close it
 * ("Close preview": dialog.close()). Focus goes to an element marked autofocus, else to the first
 * field, else to the first button, and back to where it was when the dialog closes. onClose is
 * called once, after the dialog has left the page. Returns the dialog.
 */
export function modal(options) {
    const {title, body, footer, onClose} = options || {};
    dialogs += 1;
    const titleId = 'modal-title-' + dialogs;
    const opener = document.activeElement;
    const dialog = el('dialog', {class: 'modal', 'aria-labelledby': titleId});
    dialog.append(el('div', {class: 'modal__head'}, el('h2', {class: 'modal__title', id: titleId}, title)),
        el('div', {class: 'modal__body'}, body));
    const foot = typeof footer === 'function' ? footer(dialog) : footer;
    if (foot) {
        dialog.append(el('div', {class: 'modal__foot'}, foot));
    }
    dialog.addEventListener('close', () => {
        dialog.remove();
        if (opener && typeof opener.focus === 'function' && opener.isConnected) {
            opener.focus();
        }
        if (typeof onClose === 'function') {
            onClose();
        }
    });
    document.body.append(dialog);
    dialog.showModal();
    const first = dialog.querySelector('[autofocus]') || dialog.querySelector('.modal__body input, .modal__body select, .modal__body textarea')
        || dialog.querySelector('button');
    if (first) {
        first.focus();
    }
    return dialog;
}

/**
 * A button that copies text and says in a line beside it whether it did. The text is read when
 * the button is pressed, and nothing is kept afterwards.
 */
export function copyButton(label, textOf, options) {
    const status = el('span', {class: 'copy-status', role: 'status'});
    const control = button(label, async () => {
        let done = false;
        try {
            if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
                await navigator.clipboard.writeText(String(textOf()));
                done = true;
            }
        } catch (refused) {
            // The browser can refuse the clipboard. The line below says so, and the text stays on screen to select.
            done = false;
        }
        status.textContent = done ? 'Copied.' : 'Not copied: select the text and copy it.';
    }, options);
    return el('span', {class: 'copy'}, control, status);
}

/**
 * The first sentence of a server sentence and the rest, for a screen that shows one sentence and
 * keeps the rest in a fold. A sentence ends at a full stop, question mark or exclamation mark
 * followed by a space and a capital letter or a digit, so "1.5 s" or "e.g. a" does not end one.
 * Returns [first, rest]; rest is '' when there is one sentence.
 */
export function firstSentence(text) {
    const whole = text === undefined || text === null ? '' : String(text).trim();
    for (let i = 0; i + 2 < whole.length; i++) {
        const mark = whole[i];
        if ((mark === '.' || mark === '?' || mark === '!') && whole[i + 1] === ' ') {
            const next = whole[i + 2];
            if ((next >= 'A' && next <= 'Z') || (next >= '0' && next <= '9') || next === '"') {
                return [whole.slice(0, i + 1), whole.slice(i + 2).trim()];
            }
        }
    }
    return [whole, ''];
}

/**
 * A name an uploaded file gives, as a sentence of this page quotes it, by the server's rule
 * (postman/Names): its first 60 characters and its length when that is shorter than the name,
 * otherwise the name whole, so a name of up to 89 characters keeps its end. A file can give a name
 * of any length, and a sentence that quoted a long one whole ran off the side of the screen.
 * Characters are counted as a reader counts them: a pair of UTF-16 units that makes one character
 * counts once and is never cut. A name shown as a label, in the top bar chooser, a line under the
 * top bar or a list, is shown whole.
 */
export function named(name) {
    const text = name === undefined || name === null ? '' : String(name);
    if (text.length <= 60) {
        return text;
    }
    let length = 0;
    let cut = text.length;
    for (let i = 0; i < text.length; i++) {
        const code = text.charCodeAt(i);
        const before = i > 0 ? text.charCodeAt(i - 1) : 0;
        if (code >= 0xDC00 && code <= 0xDFFF && before >= 0xD800 && before <= 0xDBFF) {
            continue;
        }
        if (length === 60) {
            cut = i;
        }
        length++;
    }
    const suffix = '... (a name of ' + length + ' characters)';
    return length <= 60 + suffix.length ? text : text.slice(0, cut) + suffix;
}

/** Turns a server value into something safe to use as part of a class name. */
export function token(value) {
    return String(value === undefined || value === null ? '' : value).replace(/[^A-Za-z0-9_-]/g, '');
}
