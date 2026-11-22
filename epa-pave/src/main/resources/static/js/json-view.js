// JSON as it went and came back: the wire view, and the request and answer of a run in Audit.
//
// The text is laid out from its own characters and never parsed and printed again, so a number
// keeps its digits and a string its escapes exactly as they were sent. The walk counts its depth
// instead of calling itself, and indents at most MOST_INDENT levels, so an answer nested as deep as
// a service likes can neither overflow the page's stack nor grow without end on screen. A string
// longer than 2,000 characters is cut on screen to its first 64 and its length; Copy copies the
// whole text, cut strings included.

import {el, copyButton} from './dom.js';

const LONGEST_SHOWN = 2000;
const CUT_TO = 64;
const MOST_INDENT = 32;

/**
 * A JSON text as a block, two spaces a level, with Copy above it. A text that is not JSON is shown
 * as it is, and copied as it is. An object is shown as JSON. options.label names the block for a
 * screen reader ("Request body").
 */
export function jsonView(text, options) {
    const source = text === undefined || text === null ? '' : typeof text === 'string' ? text : JSON.stringify(text, null, 2);
    const parts = layOut(source);
    const whole = parts.map(part => (typeof part === 'string' ? part : part.full)).join('');
    const block = el('pre', {class: 'json', tabindex: 0, 'aria-label': (options && options.label) || 'JSON'});
    for (const part of parts) {
        if (typeof part === 'string') {
            block.append(part);
        } else {
            block.append(part.shown, el('span', {class: 'json__cut'}, part.note));
        }
    }
    return el('div', {class: 'json-view'},
        el('div', {class: 'json-view__bar'}, copyButton('Copy', () => whole, {small: true})), block);
}

/**
 * The text laid out as parts: plain text, or a cut string {full, shown, note}. The full parts
 * joined are the whole text laid out; nothing is dropped from them.
 */
export function layOut(source) {
    if (source.trim() === '' || !isJson(source)) {
        return [source];
    }
    const parts = [];
    let text = '';
    let depth = 0;
    const newline = () => '\n' + '  '.repeat(Math.min(depth, MOST_INDENT));
    let i = 0;
    while (i < source.length) {
        const character = source[i];
        if (character === '"') {
            let end = i + 1;
            while (end < source.length && source[end] !== '"') {
                end += source[end] === '\\' ? 2 : 1;
            }
            const token = source.slice(i, end + 1);
            const length = token.length - 2;
            if (length > LONGEST_SHOWN) {
                parts.push(text);
                text = '';
                parts.push({full: token, shown: '"' + token.slice(1, 1 + CUT_TO) + '..."',
                    note: ' (' + length + ' characters; Copy copies them whole)'});
            } else {
                text += token;
            }
            i = end + 1;
        } else if (character === '{' || character === '[') {
            const close = character === '{' ? '}' : ']';
            let next = i + 1;
            while (next < source.length && isSpace(source[next])) {
                next++;
            }
            if (source[next] === close) {
                text += character + close;
                i = next + 1;
            } else {
                depth++;
                text += character + newline();
                i++;
            }
        } else if (character === '}' || character === ']') {
            depth--;
            text += newline() + character;
            i++;
        } else if (character === ',') {
            text += ',' + newline();
            i++;
        } else if (character === ':') {
            text += ': ';
            i++;
        } else if (isSpace(character)) {
            i++;
        } else {
            text += character;
            i++;
        }
    }
    parts.push(text);
    return parts.filter(part => part !== '');
}

function isJson(source) {
    try {
        JSON.parse(source);
        return true;
    } catch (notJson) {
        // Not JSON, or nested deeper than the browser's own parser goes: shown as it was sent.
        return false;
    }
}

function isSpace(character) {
    return character === ' ' || character === '\n' || character === '\r' || character === '\t';
}
