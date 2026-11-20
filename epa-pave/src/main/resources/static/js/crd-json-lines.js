// Where each element of an indented JSON text sits, by line.
//
// The comparison names every difference by the JSON Pointer of its element in each request. The
// two requests come back as indented text, and a tester reads them as text, so a pointer has to
// become the lines it covers before it can be marked or brought into view. This walks the text
// once and records, for every element, its first and its last line. It reads the text and
// nothing else: no value is changed and nothing is parsed into objects.

/**
 * Returns a Map from JSON Pointer ("" for the whole document) to {start, end}, zero-based line
 * numbers. A text that is not JSON gives an empty map: there is nothing to point into.
 */
export function lineMap(text) {
    const map = new Map();
    if (typeof text !== 'string' || text.length === 0) {
        return map;
    }
    const reader = {text, at: 0, line: 0};
    try {
        value(reader, '', map, 0);
    } catch (notJson) {
        // A text the server could not read as JSON is shown as it is, unmarked.
        map.clear();
    }
    return map;
}

/** A JSON Pointer token, escaped as RFC 6901 escapes it. */
export function pointerToken(key) {
    return String(key).replace(/~/g, '~0').replace(/\//g, '~1');
}

/** The pointers that hold the given one, nearest first, ending with the document itself. */
export function ancestors(pointer) {
    const out = [];
    let at = pointer;
    while (at.length > 0) {
        at = at.slice(0, at.lastIndexOf('/'));
        out.push(at);
    }
    return out;
}

// The server reads at most 64 levels. A deeper text is not compared, so it is never mapped either.
const DEEPEST = 200;

function skip(reader) {
    const text = reader.text;
    while (reader.at < text.length) {
        const ch = text[reader.at];
        if (ch === '\n') {
            reader.line += 1;
        } else if (ch !== ' ' && ch !== '\t' && ch !== '\r') {
            return;
        }
        reader.at += 1;
    }
}

function value(reader, pointer, map, depth) {
    if (depth > DEEPEST) {
        throw new Error('too deep');
    }
    skip(reader);
    const start = reader.line;
    const ch = reader.text[reader.at];
    if (ch === '{') {
        reader.at += 1;
        skip(reader);
        if (reader.text[reader.at] === '}') {
            reader.at += 1;
        } else {
            for (;;) {
                skip(reader);
                const key = string(reader);
                skip(reader);
                expect(reader, ':');
                value(reader, pointer + '/' + pointerToken(key), map, depth + 1);
                skip(reader);
                if (reader.text[reader.at] === ',') {
                    reader.at += 1;
                    continue;
                }
                expect(reader, '}');
                break;
            }
        }
    } else if (ch === '[') {
        reader.at += 1;
        skip(reader);
        if (reader.text[reader.at] === ']') {
            reader.at += 1;
        } else {
            for (let index = 0; ; index += 1) {
                value(reader, pointer + '/' + index, map, depth + 1);
                skip(reader);
                if (reader.text[reader.at] === ',') {
                    reader.at += 1;
                    continue;
                }
                expect(reader, ']');
                break;
            }
        }
    } else if (ch === '"') {
        string(reader);
    } else {
        // A number, true, false or null: everything up to the next delimiter.
        const from = reader.at;
        while (reader.at < reader.text.length && !/[\s,\]}]/.test(reader.text[reader.at])) {
            reader.at += 1;
        }
        if (reader.at === from) {
            throw new Error('no value');
        }
    }
    map.set(pointer, {start, end: reader.line});
}

function expect(reader, ch) {
    if (reader.text[reader.at] !== ch) {
        throw new Error('expected ' + ch);
    }
    reader.at += 1;
}

/** Reads one string token and gives back its decoded text, which is what a pointer names. */
function string(reader) {
    const text = reader.text;
    if (text[reader.at] !== '"') {
        throw new Error('expected a string');
    }
    const from = reader.at;
    reader.at += 1;
    while (reader.at < text.length) {
        const ch = text[reader.at];
        if (ch === '\\') {
            reader.at += 2;
            continue;
        }
        reader.at += 1;
        if (ch === '"') {
            return JSON.parse(text.slice(from, reader.at));
        }
        if (ch === '\n') {
            throw new Error('a line break inside a string');
        }
    }
    throw new Error('an unended string');
}
