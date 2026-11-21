// Small helpers that build elements.
//
// Everything a service returns is untrusted text. Nothing in this file accepts markup: every
// string becomes a text node, so a card summary that looks like a tag is shown as the characters
// it is. The build fails (StaticFrontendTest) if any script writes markup from a string.

// Only these properties can be set through el(). A name outside the list is a mistake in the
// calling code and is refused at once, so a property that parses text as markup can never be
// reached through this helper by accident.
const PROPERTIES = new Set([
    'id', 'title', 'hidden', 'disabled', 'type', 'value', 'name', 'htmlFor', 'href', 'tabIndex', 'checked',
    'placeholder', 'colSpan', 'rowSpan', 'scope', 'open', 'download', 'rel', 'target', 'min', 'max', 'low',
    'high', 'optimum', 'autocomplete', 'inputMode', 'spellcheck', 'rows', 'readOnly', 'required', 'multiple',
    'accept', 'selected', 'label', 'lang', 'method', 'noValidate', 'maxLength', 'wrap'
]);

// Attributes that would run script or carry inline style, both of which the content security
// policy refuses anyway. Refusing them here makes the mistake visible while writing the code.
const REFUSED_ATTRIBUTES = /^(on|style$|srcdoc$)/i;

/**
 * Builds one element.
 *
 * props: class (string), text (string, set as text), dataset, aria, attrs, on (event listeners),
 * and the plain properties listed above. children: nodes, strings (as text), arrays, or null.
 */
export function el(tag, props, ...children) {
    const node = document.createElement(tag);
    if (props) {
        for (const [key, value] of Object.entries(props)) {
            if (value === undefined || value === null) {
                continue;
            }
            if (key === 'class') {
                node.className = value;
            } else if (key === 'text') {
                node.textContent = String(value);
            } else if (key === 'dataset') {
                for (const [name, entry] of Object.entries(value)) {
                    if (entry !== undefined && entry !== null) {
                        node.dataset[name] = String(entry);
                    }
                }
            } else if (key === 'aria') {
                for (const [name, entry] of Object.entries(value)) {
                    if (entry !== undefined && entry !== null) {
                        node.setAttribute('aria-' + name, String(entry));
                    }
                }
            } else if (key === 'role') {
                node.setAttribute('role', value);
            } else if (key === 'attrs') {
                for (const [name, entry] of Object.entries(value)) {
                    if (REFUSED_ATTRIBUTES.test(name)) {
                        throw new Error('el(): the attribute "' + name + '" is not set from script in this tool.');
                    }
                    if (entry !== undefined && entry !== null && entry !== false) {
                        node.setAttribute(name, entry === true ? '' : String(entry));
                    }
                }
            } else if (key === 'on') {
                for (const [name, listener] of Object.entries(value)) {
                    node.addEventListener(name, listener);
                }
            } else if (PROPERTIES.has(key)) {
                node[key] = value;
            } else {
                throw new Error('el(): "' + key + '" is not a property this helper sets.');
            }
        }
    }
    append(node, children);
    return node;
}

/** Appends children: nodes as they are, strings and numbers as text, arrays flattened, nothing for null or false. */
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

/** A button that never submits a form by accident. */
export function button(label, onClick, props) {
    const extra = props || {};
    return el('button', {type: 'button', ...extra, class: 'button ' + (extra.class || 'button--quiet'), on: {click: onClick}}, label);
}

/** A term and description list laid out as a two-column table of facts. Rows with no value node are left out. */
export function facts(rows, extraClass) {
    const list = el('dl', {class: 'facts' + (extraClass ? ' ' + extraClass : '')});
    for (const row of rows) {
        if (!row || row[1] === null || row[1] === undefined || row[1] === false) {
            continue;
        }
        list.append(el('dt', null, row[0]), el('dd', null, row[1]));
    }
    return list;
}

/** A table from column headings and rows of cells. A cell is a node, a string, or {node, class, colSpan}. */
export function table(headings, rows, props) {
    const head = el('tr', null, headings.map(heading => {
        const cell = typeof heading === 'string' ? {text: heading} : heading;
        return el('th', {scope: 'col', class: cell.class}, cell.text);
    }));
    const body = el('tbody', null, rows.map(row => tableRow(row)));
    const extra = props || {};
    return el('table', {...extra, class: 'grid ' + (extra.class || '')}, el('thead', null, head), body);
}

export function tableRow(row) {
    const cells = Array.isArray(row) ? row : row.cells;
    const tr = el('tr', Array.isArray(row) ? null : {class: row.class, dataset: row.dataset});
    for (const cell of cells) {
        if (cell && typeof cell === 'object' && !(cell instanceof Node) && !Array.isArray(cell)) {
            tr.append(el('td', {class: cell.class, colSpan: cell.colSpan}, cell.node));
        } else {
            tr.append(el('td', null, cell));
        }
    }
    return tr;
}

// Where a long literal may be broken across lines: after an underscore, a dot, a slash and the
// like. A path or an enum name then wraps at a joint instead of in the middle of a word. Only
// break opportunities are added; the text itself, and what is copied from it, is unchanged.
const BREAK_AFTER = /([_./&=|\]?:,])/;

/** Literal data: a code, an identifier, a path. The one place the monospace face is asked for. */
export function code(text, extraClass) {
    const node = el('code', {class: 'literal' + (extraClass ? ' ' + extraClass : '')});
    const value = String(text);
    if (value.length <= 10) {
        node.textContent = value;
        return node;
    }
    for (const part of value.split(BREAK_AFTER)) {
        if (part === '') {
            continue;
        }
        node.append(part);
        if (part.length === 1 && BREAK_AFTER.test(part)) {
            node.append(document.createElement('wbr'));
        }
    }
    return node;
}

/** A heading for a section of a result or a page, with an optional count after it. */
export function heading(level, text, count) {
    const node = el('h' + level, {class: 'section-heading'}, text);
    if (count !== undefined && count !== null) {
        node.append(' ', el('span', {class: 'section-heading__count'}, '(' + count + ')'));
    }
    return node;
}

/**
 * A button that copies text to the clipboard and says that it did.
 *
 * The text is read when the button is pressed, and nothing is kept afterwards. When the browser
 * refuses the clipboard, the fallback selects the text on screen so it can be copied by hand.
 */
export function copyButton(label, getText, fallbackNode) {
    const status = el('span', {class: 'copy__status', role: 'status'});
    const control = button(label, async () => {
        const text = getText();
        try {
            await navigator.clipboard.writeText(text);
            status.textContent = 'Copied.';
        } catch (refused) {
            if (fallbackNode) {
                const range = document.createRange();
                range.selectNodeContents(fallbackNode);
                const selection = window.getSelection();
                selection.removeAllRanges();
                selection.addRange(range);
                status.textContent = 'The browser did not allow copying. The text is selected: press Ctrl+C.';
            } else {
                status.textContent = 'The browser did not allow copying.';
            }
        }
    }, {class: 'button--quiet button--small'});
    return el('span', {class: 'copy'}, control, status);
}

/**
 * The reference of a failure of this tool with its cause, as the server log names them, and one
 * button that copies both. The log can be on a machine the tester never looks at, so the cause is
 * shown here, and reporting it is one paste. Nothing when the error carries no reference.
 */
export function faultReport(error) {
    if (!error || !error.reference) {
        return null;
    }
    const lines = el('div', {class: 'fault-report__lines'},
        el('p', {class: 'fault-report__reference'}, 'Reference ', code(error.reference)),
        error.cause ? el('p', {class: 'fault-report__cause'}, 'Cause, as the server log names it: ', code(error.cause)) : null);
    const copied = () => 'Reference ' + error.reference + (error.cause ? '\nCause, as the server log names it: ' + error.cause : '');
    return el('div', {class: 'fault-report'}, lines,
        copyButton(error.cause ? 'Copy reference and cause' : 'Copy reference', copied, lines));
}

/**
 * A server sentence about a failure of this tool, for a screen that shows faultReport under it.
 * The server writes the reference into the sentence so that the sentence stands alone in the
 * results CSV, which has no column for the cause. On the screen the reference stands in the report
 * below, beside its cause, so it is not given a second time in the sentence, and a sentence that
 * sends the reader to the reason for it is sent to the report instead.
 */
export function withoutReference(sentence, fault) {
    if (!sentence || !fault || !fault.reference) {
        return sentence;
    }
    return sentence.split(' Reference ' + fault.reference + '.').join('')
        .split('Report the reference ' + fault.reference + '.')
        .join(fault.cause ? 'Report the reference and the cause below.' : 'Report the reference below.')
        .split('The reason gives the reference.')
        .join(fault.cause ? 'The reference and the cause are below the reason.' : 'The reference is below the reason.');
}

/**
 * A link taken from a response. It becomes an anchor only when its address starts with http:// or
 * https://, opens in a new tab without handing this page to it, and always shows the address
 * itself so a tester sees where it goes. Anything else is shown as the text it is.
 */
export function externalLink(address, labelText) {
    const text = String(address);
    const isWeb = /^https?:\/\//i.test(text);
    const shownAddress = code(text, 'literal--address');
    if (!isWeb) {
        // Not a web address, so there is nothing to open. No label is put beside it that would suggest otherwise.
        return el('span', null, shownAddress, ' ', el('span', {class: 'quiet small'}, 'not a web address, shown as text'));
    }
    const anchor = el('a', {href: text, rel: 'noopener noreferrer', target: '_blank'}, labelText || 'Open in a new tab');
    return el('span', {class: 'external'}, anchor, ' ', shownAddress);
}

/** A whole number with thousands separators, in the tester's own locale. */
export function count(value) {
    return typeof value === 'number' ? value.toLocaleString() : String(value);
}

/** Milliseconds as written by the server, with a unit. */
export function millis(value) {
    return typeof value === 'number' ? value.toLocaleString() + ' ms' : null;
}

/**
 * A timestamp from the server as text, in the tester's local time. Every time on the page is
 * written through here, so a time reads the same in a table, a list and the text of an option.
 * A value that is not a time is shown as it came.
 */
export function whenText(timestamp) {
    const date = new Date(timestamp);
    return Number.isNaN(date.getTime()) ? String(timestamp)
        : date.toLocaleString(undefined, {year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit'});
}

/** A timestamp from the server, shown in the tester's local time with the original kept as a tooltip. */
export function when(timestamp) {
    if (!timestamp) {
        return null;
    }
    return el('time', {title: String(timestamp), attrs: {datetime: String(timestamp)}}, whenText(timestamp));
}

/**
 * A name an uploaded file gives, as a sentence of this page quotes it: whole when it has at most 60
 * characters, otherwise its first 60 and its length, in the words the server's own sentences use. A
 * file can give a name of any length, and a sentence that quoted a long one whole ran off the side
 * of the screen. A name shown as the tester's own label, in a list, the strip or the banner, is shown whole.
 */
export function named(name) {
    const text = name === undefined || name === null ? '' : String(name);
    if (text.length <= 60) {
        return text;
    }
    const code = text.charCodeAt(59);
    const cut = code >= 0xD800 && code <= 0xDBFF ? 59 : 60;
    return text.slice(0, cut) + '... (a name of ' + text.length + ' characters)';
}

/** Turns a server value into something safe to use as part of a class name. */
export function token(value) {
    return String(value === undefined || value === null ? '' : value).replace(/[^A-Za-z0-9_-]/g, '');
}

/**
 * What explains a step rather than says what to do in it, closed under "About this step", so the
 * first screen keeps the words needed to act. Nothing is deleted: it is one click away.
 */
export function aboutStep(...content) {
    return el('details', {class: 'fold about-step'}, el('summary', null, 'About this step'), content);
}
