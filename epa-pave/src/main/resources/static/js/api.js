// The only place this tool's pages talk to its server. Every answer comes back in one shape, so a
// tab never looks at a status code itself:
//
//   kind 'ok'          a completed call (2xx). body is what the endpoint documents
//   kind 'result'      a 403 or a 500 whose body is a run result (it has a runId). The run was
//                      refused by the production guardrail, or the tool failed after the call.
//                      It is rendered as a result, because it holds the run id and the receipt
//   kind 'form'        a 422: something on the form cannot be used. body.fieldErrors names the
//                      fields. The result area is left as it was
//   kind 'conflict'    a 409: the tool would not do this now. The configuration changed, nothing
//                      is configured for the operation, or something else is running. body says
//                      which in words, and the caller re-reads /api/mode
//   kind 'error'       any other answer. body is the shared error shape
//   kind 'unreachable' no answer at all: the tool is not running, or the connection was lost. Whether
//                      a request reached the server before that is not known
//
// body is always an object. When the server did not send the error shape, one is made here that
// says what the browser saw, so a caller can render body.title and body.action without checking.

const JSON_ACCEPT = {Accept: 'application/json'};

export function get(path) {
    return send(path, {method: 'GET', headers: JSON_ACCEPT});
}

export function postJson(path, payload) {
    return send(path, {
        method: 'POST',
        headers: {...JSON_ACCEPT, 'Content-Type': 'application/json'},
        body: JSON.stringify(payload === undefined ? {} : payload)
    });
}

/** Posts multipart form data: an uploaded file with its plain fields. */
export function postForm(path, formData) {
    return send(path, {method: 'POST', headers: JSON_ACCEPT, body: formData});
}

export function post(path) {
    return send(path, {method: 'POST', headers: JSON_ACCEPT});
}

async function send(path, options) {
    let response;
    try {
        // no-store: an answer can hold a member's name and date of birth, and the browser's cache
        // is storage too. The server says the same in its headers; this is the second lock.
        response = await fetch(path, {...options, cache: 'no-store', credentials: 'same-origin', redirect: 'error'});
    } catch (failure) {
        return {
            kind: 'unreachable',
            status: 0,
            body: {
                // The page cannot know how far a request got before the connection was lost, so it does not say.
                title: 'This page could not reach the PAVE server, or lost it before an answer arrived.',
                action: 'Check that the tool is still running in its terminal window. If a run was on its way, it may or may not '
                    + 'have been made: look for it in the ledger before sending it again.',
                fieldErrors: [],
                issues: []
            }
        };
    }
    const text = await response.text();
    let body = null;
    if (text.length > 0) {
        try {
            body = JSON.parse(text);
        } catch (notJson) {
            body = null;
        }
    }
    const status = response.status;
    if (response.ok) {
        return {kind: 'ok', status, body: body === null ? {} : body};
    }
    if (body === null || typeof body !== 'object') {
        return {
            kind: 'error',
            status,
            body: {
                title: 'The PAVE server answered HTTP ' + status + ' with a body this page cannot read.',
                action: 'Look at the terminal window the tool is running in, and report what it shows.',
                fieldErrors: [],
                issues: []
            }
        };
    }
    if ((status === 403 || status === 500) && typeof body.runId === 'string') {
        return {kind: 'result', status, body};
    }
    if (status === 422) {
        return {kind: 'form', status, body};
    }
    if (status === 409) {
        return {kind: 'conflict', status, body};
    }
    return {kind: 'error', status, body};
}
