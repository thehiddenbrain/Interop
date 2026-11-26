// The line a step shows before it is sent (D-321): the collection's request for the step carries a
// diagnosis or a servicing provider, and the case names none. A tester may mean to send a check
// without one, so it is a plain line where the step is sent, never a block and never a dialog.
//
// The server reads the request the step would follow (the one chosen on this page, the one a typed
// address goes to, else the first mapped) by presence only, and words the line; the page sends the
// two parts of the case and shows what comes back. Nothing of the request reaches the page, and a
// failure to ask shows nothing: the line is advice, and the step is sent all the same.

import {call} from './api.js';
import {el, fill, clear} from './dom.js';
import {chosenFor, sendsAsWritten, watchChoice} from './wb-request.js';
import {withRunValues} from './wb-values.js';

const QUIET_MS = 300;

/** The case's diagnosis codes as the server reads them: each a code, blank ones left to the server's rule. */
function diagnosisCodes(request) {
    const codes = Array.isArray(request.diagnosisCodes) ? request.diagnosisCodes : [];
    return codes.map(each => (typeof each === 'string' ? {code: each} : each)).filter(each => each && typeof each === 'object');
}

/** What the page asks the server for one step: the step, the request chosen for it, the values typed for the run, and the case's two parts. */
export function beforeBody(operation, request) {
    const given = request && typeof request === 'object' ? request : {};
    const body = {operation, diagnosisCodes: diagnosisCodes(given)};
    if (given.servicingProvider && typeof given.servicingProvider === 'object') {
        body.servicingProvider = given.servicingProvider;
    }
    const ref = chosenFor(operation);
    if (ref) {
        body.request = ref;
    }
    return withRunValues(body);
}

/**
 * The line of one step. spec: {operation() (the operation the step sends now), request() (the
 * case, as ctx.request gives it)}. Returns {node, ask(), soon(), clear(), stop()}.
 */
export function createBeforeLine(spec) {
    const node = el('p', {class: 'small before-line', 'data-before-line': '', role: 'status', hidden: true});
    let asked = 0;
    let timer = null;
    // Another request or another body chosen for the step changes what the line reads from.
    const offChoice = watchChoice(() => ask());

    function hide() {
        node.hidden = true;
        clear(node);
    }

    async function ask() {
        const mine = ++asked;
        clearTimeout(timer);
        const operation = spec.operation();
        // A body as the collection writes it carries the collection's own diagnosis and provider, not the case's.
        if (!operation || sendsAsWritten(operation)) {
            hide();
            return;
        }
        let answer = null;
        try {
            answer = await call('POST', '/api/workbench/before-sending', {body: beforeBody(operation, spec.request())});
        } catch (failure) {
            // The line is advice: an ask that fails says nothing, and the step is sent all the same.
            answer = null;
        }
        if (mine !== asked) {
            return;
        }
        const line = answer && answer.ok && answer.data && typeof answer.data.line === 'string' ? answer.data.line : '';
        if (line === '') {
            hide();
            return;
        }
        fill(node, line);
        node.hidden = false;
    }

    return {
        node,
        ask,
        /** Asks once the tester has stopped typing for a moment. */
        soon() {
            clearTimeout(timer);
            timer = setTimeout(() => ask(), QUIET_MS);
        },
        clear() {
            asked++;
            clearTimeout(timer);
            hide();
        },
        /** The step is taken off the page: nothing more is asked for it. */
        stop() {
            asked++;
            clearTimeout(timer);
            offChoice();
        }
    };
}
