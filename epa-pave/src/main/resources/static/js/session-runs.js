// The runs made from this page, kept in its memory so Audit can open their request and answer
// JSON (plan R-21). The ledger keeps no JSON by design, so this is the only place an answer can be
// opened again, and only until the page is closed or reloaded. Nothing here goes to browser
// storage: a run holds member data. At most 20 runs and 50 MB of their text are kept, the oldest
// dropped first, so a long session cannot exhaust the browser.

const MOST_RUNS = 20;

// Counted as characters of the run's JSON, which is what the page holds of it.
const MOST_CHARACTERS = 50 * 1024 * 1024;

const kept = [];
let total = 0;

/**
 * Keeps the envelope of a run as the server answered it, under its run id. A run made again under
 * the same id replaces the one before. A run with no id, or larger than all the room there is, is
 * not kept.
 */
export function remember(envelope) {
    const runId = envelope && typeof envelope.runId === 'string' && envelope.runId !== '' ? envelope.runId : null;
    if (!runId) {
        return;
    }
    let size;
    try {
        size = JSON.stringify(envelope).length;
    } catch (cannotCount) {
        // A run that cannot be measured is not kept, rather than kept beyond the limit.
        return;
    }
    forget(runId);
    if (size > MOST_CHARACTERS) {
        return;
    }
    kept.push({runId, envelope, size});
    total += size;
    while (kept.length > MOST_RUNS || total > MOST_CHARACTERS) {
        total -= kept.shift().size;
    }
}

/** The envelope of a run made from this page, or null when it was not made here or was dropped to make room. */
export function recall(runId) {
    const found = kept.find(each => each.runId === runId);
    return found ? found.envelope : null;
}

function forget(runId) {
    const at = kept.findIndex(each => each.runId === runId);
    if (at >= 0) {
        total -= kept[at].size;
        kept.splice(at, 1);
    }
}
