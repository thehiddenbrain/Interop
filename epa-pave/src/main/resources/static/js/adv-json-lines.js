// Where each element of an indented JSON text sits, by line.
//
// The comparison names every difference by the JSON Pointer of its element in each request. The
// two requests come back as indented text, and a tester reads them as text, so a pointer has to
// become the lines it covers before it can be marked or brought into view. This walks the text
// once and records, for every element, its first and its last line. It reads the text and
// nothing else: no value is changed and nothing is parsed into objects.
//
// The walk keeps its own stack of open objects and arrays instead of calling itself, so a request
// nested as deep as a tester likes can never overflow the page's stack (the rule the server's
// RecursionGuardTest holds for its own code holds here too).

/**
 * Returns a Map from JSON Pointer ("" for the whole document) to {start, end}, zero-based line
 * numbers. A text that is not JSON gives an empty map: there is nothing to point into.
 */
export function lineMap(text) {
    const map = new Map();
    if (typeof text !== 'string' || text.length === 0) {
        return map;
    }
    try {
        walk(text, map);
    } catch (notJson) {
        // A text the server could not read as JSON is shown as it is, unmarked.
        map.clear();
    }
    return map;
}

/** A JSON Pointer token, escaped as RFC 6901 escapes it. */
export function pointerToken(key) {
    let out = '';
    for (const character of String(key)) {
        out += character === '~' ? '~0' : character === '/' ? '~1' : character;
    }
    return out;
}

/** The pointers that hold the given one, nearest first, ending with the document itself. */
export function ancestors(pointer) {
    const out = [];
    let at = typeof pointer === 'string' ? pointer : '';
    while (at.length > 0) {
        at = at.slice(0, at.lastIndexOf('/'));
        out.push(at);
    }
    return out;
}

function isSpace(character) {
    return character === ' ' || character === '\t' || character === '\r' || character === '\n';
}

function isDelimiter(character) {
    return isSpace(character) || character === ',' || character === ']' || character === '}';
}

/**
 * One pass over the text. stack holds the containers still open: {pointer, start, array, index}.
 * After a value ends, the container it sits in either takes another member or closes.
 */
function walk(text, map) {
    const reader = {text, at: 0, line: 0};
    const stack = [];
    let pointer = '';
    for (;;) {
        skip(reader);
        const start = reader.line;
        const character = text[reader.at];
        let ended = true;
        if (character === '{' || character === '[') {
            reader.at += 1;
            skip(reader);
            const array = character === '[';
            if (text[reader.at] === (array ? ']' : '}')) {
                reader.at += 1;
            } else {
                stack.push({pointer, start, array, index: 0});
                pointer = array ? pointer + '/0' : pointer + '/' + pointerToken(key(reader));
                ended = false;
            }
        } else if (character === '"') {
            string(reader);
        } else {
            // A number, true, false or null: everything up to the next delimiter.
            const from = reader.at;
            while (reader.at < text.length && !isDelimiter(text[reader.at])) {
                reader.at += 1;
            }
            if (reader.at === from) {
                throw new Error('no value');
            }
        }
        if (!ended) {
            continue;
        }
        map.set(pointer, {start, end: reader.line});
        // Close every container this value was the last member of, then move to the next member.
        for (;;) {
            if (stack.length === 0) {
                skip(reader);
                if (reader.at !== text.length) {
                    throw new Error('text after the document');
                }
                return;
            }
            const open = stack[stack.length - 1];
            skip(reader);
            if (text[reader.at] === ',') {
                reader.at += 1;
                open.index += 1;
                pointer = open.array ? open.pointer + '/' + open.index : open.pointer + '/' + pointerToken(key(reader));
                break;
            }
            expect(reader, open.array ? ']' : '}');
            stack.pop();
            pointer = open.pointer;
            map.set(pointer, {start: open.start, end: reader.line});
        }
    }
}

/** Reads a member's key and its colon. */
function key(reader) {
    skip(reader);
    const name = string(reader);
    skip(reader);
    expect(reader, ':');
    return name;
}

function skip(reader) {
    const text = reader.text;
    while (reader.at < text.length && isSpace(text[reader.at])) {
        if (text[reader.at] === '\n') {
            reader.line += 1;
        }
        reader.at += 1;
    }
}

function expect(reader, character) {
    if (reader.text[reader.at] !== character) {
        throw new Error('expected ' + character);
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
        const character = text[reader.at];
        if (character === '\\') {
            reader.at += 2;
            continue;
        }
        reader.at += 1;
        if (character === '"') {
            return JSON.parse(text.slice(from, reader.at));
        }
        if (character === '\n') {
            throw new Error('a line break inside a string');
        }
    }
    throw new Error('an unended string');
}
