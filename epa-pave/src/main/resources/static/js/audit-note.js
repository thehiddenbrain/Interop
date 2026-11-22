// The note on a run and its tester reference (POST /api/runs/{runId}/note). The ledger keeps both
// as typed, so each field carries the one hint every such field carries, in the Workbench's words.

import {el, button, field, setFieldError, fill, clear, chip} from './dom.js';
import {call} from './api.js';
import {errorPanel} from './fault.js';
import {LEDGER_TEXT_HINT} from './wb-case.js';

/**
 * The note editor of one run. onSaved(run) is given the run as the ledger now holds it.
 * Returns the node.
 */
export function noteEditor(run, onSaved) {
    const note = el('textarea', {class: 'input', name: 'note', rows: 3, maxLength: 4000, value: run.note || ''});
    const ref = el('input', {class: 'input', type: 'text', name: 'testerRef', maxLength: 200, value: run.testerRef || ''});
    const noteField = field({label: 'Note', input: note, hint: LEDGER_TEXT_HINT});
    const refField = field({label: 'Tester reference', input: ref, hint: LEDGER_TEXT_HINT});
    const said = el('div', {class: 'audit-note__said', role: 'status'});
    const save = button('Save note', () => send(), {small: true});

    async function send() {
        setFieldError(noteField, null);
        setFieldError(refField, null);
        clear(said);
        save.disabled = true;
        // Both are sent as they stand on screen; the server decides what a blank value means.
        const answer = await call('POST', '/api/runs/' + encodeURIComponent(run.runId) + '/note',
            {body: {note: note.value, testerRef: ref.value}});
        save.disabled = false;
        if (!answer.ok) {
            const errors = answer.error && Array.isArray(answer.error.fieldErrors) ? answer.error.fieldErrors : [];
            const placed = [];
            for (const each of errors) {
                if (each.field === 'note' || each.field === 'testerRef') {
                    setFieldError(each.field === 'note' ? noteField : refField, each.message);
                    placed.push(each.field);
                }
            }
            fill(said, errorPanel(answer.error, {fieldsPlaced: placed}));
            return;
        }
        const receipt = answer.data && answer.data.receipt ? answer.data.receipt : {};
        if (receipt.recorded) {
            const kept = answer.data.run || {};
            note.value = kept.note || '';
            ref.value = kept.testerRef || '';
            fill(said, el('p', {class: 'small'}, kept.note ? 'Saved in the ledger.' : 'Saved: this run now has no note.'));
            if (typeof onSaved === 'function') {
                onSaved(kept);
            }
        } else {
            // A note the ledger did not keep is said loudly, with the ledger's own words, never folded.
            fill(said, el('p', {class: 'audit-note__missing'}, chip('NOT saved', {tone: 'fault'}), ' ', receipt.message || null));
        }
    }

    return el('div', {class: 'audit-note'}, el('div', {class: 'form-grid'}, noteField, refField),
        el('div', {class: 'form-foot'}, save), said);
}
