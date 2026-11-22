// Reading a file the tester chose, in the page, at the moment the button that uses it is pressed
// (plan R-33 and R-06). A chosen file is a handle on a file on disk. Read when it is chosen it is
// stale by the time the button is pressed; sent as it is, a file changed since makes the browser
// refuse the whole request, and the page then said that the server could not be reached. So it is
// read at the press, here, and every way the read can fail is one plain line, never a throw. The
// file input is cleared after every refusal, so the next press reads what is chosen again.

const DEFAULT_MOST_BYTES = 20 * 1024 * 1024;

const CHANGED = 'The file you chose can no longer be read: it was moved, renamed, deleted or changed after you chose it. Choose it again.';

const NO_FILE = 'Choose a file first.';

// The words that name where a file comes from. A Postman export is exported again from Postman; a
// CSV is saved again by whatever wrote it, so it is never sent to Postman.
const SOURCES = {
    postman: {
        what: 'exports',
        incomplete: 'This file is not complete. Export it again from Postman.',
        encoding: ' is saved in another encoding (for example by PowerShell or Notepad). Save it as UTF-8, or export it again from Postman.'
    },
    csv: {
        what: 'files',
        incomplete: 'This file is not complete. Save it again, then choose it again.',
        encoding: ' is saved in another encoding (for example by Excel or Notepad). Save it as CSV UTF-8, then choose it again.'
    }
};

/**
 * Reads a chosen file as text: a Postman export or a CSV. file: the chosen file, or the file input
 * itself. UTF-8 is read with or without its byte order mark; UTF-16 by its byte order mark, or without one by its zero bytes.
 * options.maxBytes: the most read (20 MB when not given). options.source: 'postman' (the default)
 * or 'csv', for the words of a refusal.
 * Resolves to {ok: true, text, fileName, size} or {ok: false, line}.
 */
export async function readText(file, options) {
    const {maxBytes, source} = options || {};
    const words = SOURCES[source] || SOURCES.postman;
    const read = await bytesOf(file, maxBytes, (name, size, most) => name + ' is ' + size + '; PAVE reads ' + words.what + ' up to ' + most + '.',
        name => name + ' is empty.');
    if (!read.ok) {
        return read;
    }
    const decoded = decode(read.bytes);
    if (decoded.problem) {
        return refuse(read.input, decoded.problem === 'incomplete' ? words.incomplete : read.fileName + words.encoding);
    }
    return {ok: true, text: decoded.text, fileName: read.fileName, size: read.bytes.length};
}

/**
 * Reads a chosen file as base64: an attachment. file: the chosen file, or the file input itself.
 * options.maxBytes: the most read (20 MB when not given).
 * Resolves to {ok: true, data, fileName, size, contentType} or {ok: false, line}. contentType is
 * the browser's own reading of the file's kind, or '' when it has none; the kinds PAVE sends and
 * their limits are the server's to say (GET /api/pas/options).
 */
export async function readBase64(file, options) {
    const {maxBytes} = options || {};
    const read = await bytesOf(file, maxBytes, (name, size, most) => name + ' is ' + size + '; the limit is ' + most + '. Not added.',
        name => name + ' is empty. Not added.');
    if (!read.ok) {
        return read;
    }
    return {ok: true, data: base64Of(read.bytes), fileName: read.fileName, size: read.bytes.length, contentType: read.contentType};
}

/** A size as a tester reads it: "512 bytes", "1.2 KB", "1.2 MB". */
export function sizeText(bytes) {
    const count = Number(bytes);
    if (!Number.isFinite(count) || count < 1024) {
        return count === 1 ? '1 byte' : String(count) + ' bytes';
    }
    if (count < 1024 * 1024) {
        return tenths(count / 1024) + ' KB';
    }
    return tenths(count / (1024 * 1024)) + ' MB';
}

function tenths(value) {
    const rounded = Math.round(value * 10) / 10;
    return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1);
}

/** A whole number with a comma every three digits, the same in every locale. */
function grouped(count) {
    const digits = String(Math.trunc(count));
    let out = '';
    for (let i = 0; i < digits.length; i++) {
        if (i > 0 && (digits.length - i) % 3 === 0) {
            out += ',';
        }
        out += digits[i];
    }
    return out;
}

async function bytesOf(target, maxBytes, tooBig, empty) {
    let input = null;
    let file = target;
    try {
        if (target && typeof target === 'object' && 'files' in target && typeof target.arrayBuffer !== 'function') {
            input = target;
            file = target.files && target.files.length > 0 ? target.files[0] : null;
        }
        if (!file || typeof file !== 'object' || typeof file.arrayBuffer !== 'function') {
            return refuse(input, NO_FILE);
        }
        const fileName = typeof file.name === 'string' && file.name !== '' ? file.name : 'The file';
        const most = typeof maxBytes === 'number' && maxBytes > 0 ? maxBytes : DEFAULT_MOST_BYTES;
        const size = Number(file.size);
        if (size > most) {
            // Said before it is read, so a file far too large never fills the page's memory. At the
            // limit itself the rounded size would read as the limit, so the bytes are given.
            const shown = sizeText(size) === sizeText(most) ? grouped(size) + ' bytes' : sizeText(size);
            return refuse(input, tooBig(fileName, shown, sizeText(most)));
        }
        // Emptiness is judged by what is read, never by the size the browser reports: Chromium
        // reports 0 for a file deleted since it was chosen, which is a file gone, not an empty one.
        let buffer;
        try {
            buffer = await file.arrayBuffer();
        } catch (gone) {
            // The browser cannot read a file that was moved, deleted or written again since it was chosen.
            return refuse(input, CHANGED);
        }
        const bytes = new Uint8Array(buffer);
        if (bytes.length === 0) {
            return refuse(input, empty(fileName));
        }
        return {ok: true, input, bytes, fileName, contentType: typeof file.type === 'string' ? file.type : ''};
    } catch (defect) {
        // Whatever else stops the read: the tester chooses the file again; nothing was sent.
        return refuse(input, CHANGED);
    }
}

function refuse(input, line) {
    if (input) {
        try {
            input.value = '';
        } catch (readOnly) {
            // An input that cannot be cleared still gets its line; choosing again replaces the file.
        }
    }
    return {ok: false, line};
}

/**
 * The text of the bytes: UTF-16 by its byte order mark, or without one by the zero bytes among its
 * first 64 bytes; UTF-8 otherwise (its own mark dropped). problem 'incomplete' when every character
 * is whole but the last, which was cut; 'encoding' when the bytes are not that encoding at all.
 */
function decode(bytes) {
    let encoding = 'utf-8';
    let other = null;
    if (bytes.length >= 2 && bytes[0] === 0xFF && bytes[1] === 0xFE) {
        encoding = 'utf-16le';
    } else if (bytes.length >= 2 && bytes[0] === 0xFE && bytes[1] === 0xFF) {
        encoding = 'utf-16be';
    } else {
        // A strict UTF-8 decoder takes UTF-16 text written without its mark (as some scripts save
        // it) as valid, full of NUL characters, and the server then refused it as not JSON: the
        // wrong reason. Text never holds a zero byte, so zero bytes say UTF-16, and where they stand
        // says which way round: after each ASCII character little-endian, before it big-endian.
        const guess = utf16Guess(bytes);
        if (guess) {
            encoding = guess;
            other = guess === 'utf-16le' ? 'utf-16be' : 'utf-16le';
        }
    }
    const first = decodeAs(bytes, encoding);
    if (first.problem && other) {
        const second = decodeAs(bytes, other);
        return second.problem ? first : second;
    }
    return first;
}

/** 'utf-16le' or 'utf-16be' when zero bytes stand among the first 64 bytes, else null. */
function utf16Guess(bytes) {
    let odd = 0;
    let even = 0;
    const end = Math.min(bytes.length, 64);
    for (let i = 0; i < end; i++) {
        if (bytes[i] === 0) {
            if (i % 2 === 1) {
                odd++;
            } else {
                even++;
            }
        }
    }
    if (odd === 0 && even === 0) {
        return null;
    }
    return odd >= even ? 'utf-16le' : 'utf-16be';
}

function decodeAs(bytes, encoding) {
    try {
        return {text: new TextDecoder(encoding, {fatal: true}).decode(bytes)};
    } catch (notWhole) {
        const decoder = new TextDecoder(encoding, {fatal: true});
        try {
            decoder.decode(bytes, {stream: true});
        } catch (inside) {
            return {problem: 'encoding'};
        }
        try {
            decoder.decode();
        } catch (atTheEnd) {
            return {problem: 'incomplete'};
        }
        return {problem: 'encoding'};
    }
}

function base64Of(bytes) {
    let binary = '';
    const step = 0x8000;
    for (let i = 0; i < bytes.length; i += step) {
        binary += String.fromCharCode.apply(null, bytes.subarray(i, i + step));
    }
    return btoa(binary);
}
