// The only place this page talks to PAVE's server. Every answer comes back in one shape and this
// function never throws, so no view reads a status code or a parse failure its own way:
//
//   {ok: true, status, data}     a 2xx answer. data is the JSON the endpoint documents, or null
//                                when the answer has no body
//   {ok: false, status, error}   any other answer. error is the server's ErrorView (kind, heading,
//                                title, action, fieldErrors, issues, reference, cause). A run the
//                                production guardrail refused (403) and a run whose answer this
//                                tool failed to read (500) are answered with the whole run: then
//                                error is the run's own error and data is the run, drawn as a
//                                result because it holds the run id and what the ledger did
//   error {kind: 'NOT_JSON', status}   the answer could not be read as JSON: an HTML page, or a
//                                failure with no body, as PAVE gives when its classes were rebuilt
//                                under it while it ran
//   error {kind: 'UNREACHABLE'}  no answer came, with status 0. Whether a request reached PAVE
//                                before the connection was lost is not known
//   error {kind: 'PAGE_FAULT'}   this page could not build the request. Nothing was sent
//
// Bodies are JSON only. No screen sends a file as multipart: a file is read in the page
// (files.js) and its text is sent as JSON.

const listeners = new Set();

/**
 * Hears every answer, after the caller's own call has it: {method, path, answer}. The frame uses
 * it to say once, under the top bar, that PAVE is not answering, and to read the configuration
 * again at once after a change made from this page. Returns a function that stops it.
 */
export function watch(listener) {
    listeners.add(listener);
    return () => listeners.delete(listener);
}

/**
 * Calls PAVE. method: GET, POST, PUT or DELETE. path: an address of this tool, its own segments
 * already encoded by the caller. query: values to add to the address (null and undefined left
 * out, a list repeated). body: sent as JSON when given.
 */
export async function call(method, path, options) {
    const {query, body} = options || {};
    let answer;
    let request;
    try {
        request = {
            method,
            headers: body === undefined ? {Accept: 'application/json'} : {Accept: 'application/json', 'Content-Type': 'application/json'},
            body: body === undefined ? undefined : JSON.stringify(body),
            // no-store: an answer can hold a member's name and date of birth, and the browser's cache
            // is storage too. The server says the same in its headers; this is the second lock.
            cache: 'no-store',
            credentials: 'same-origin',
            redirect: 'error'
        };
        request.address = address(path, query);
    } catch (defect) {
        answer = {ok: false, status: 0, error: {kind: 'PAGE_FAULT'}};
    }
    if (!answer) {
        answer = await exchange(request);
    }
    for (const listener of listeners) {
        try {
            listener({method, path, answer});
        } catch (ignored) {
            // A listener's own fault must not change what the caller is given.
        }
    }
    return answer;
}

async function exchange(request) {
    let response;
    let text;
    try {
        response = await fetch(request.address, {
            method: request.method, headers: request.headers, body: request.body, cache: request.cache,
            credentials: request.credentials, redirect: request.redirect
        });
        text = await response.text();
    } catch (lost) {
        return {ok: false, status: 0, error: {kind: 'UNREACHABLE'}};
    }
    const status = response.status;
    const read = parse(text);
    if (response.ok) {
        if (text.length === 0) {
            return {ok: true, status, data: null};
        }
        return read.json ? {ok: true, status, data: read.value} : {ok: false, status, error: {kind: 'NOT_JSON', status}};
    }
    const body = read.json ? read.value : null;
    if (body === null || typeof body !== 'object' || Array.isArray(body)) {
        return {ok: false, status, error: {kind: 'NOT_JSON', status}};
    }
    if (typeof body.runId === 'string' && body.error && typeof body.error === 'object') {
        return {ok: false, status, error: body.error, data: body};
    }
    return {ok: false, status, error: body};
}

function parse(text) {
    if (typeof text !== 'string' || text.length === 0) {
        return {json: false};
    }
    try {
        return {json: true, value: JSON.parse(text)};
    } catch (notJson) {
        return {json: false};
    }
}

function address(path, query) {
    if (!query) {
        return path;
    }
    const params = new URLSearchParams();
    for (const [name, value] of Object.entries(query)) {
        const values = Array.isArray(value) ? value : [value];
        for (const each of values) {
            if (each !== undefined && each !== null) {
                params.append(name, String(each));
            }
        }
    }
    const text = params.toString();
    return text.length === 0 ? path : path + (path.includes('?') ? '&' : '?') + text;
}
