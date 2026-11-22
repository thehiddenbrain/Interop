// The result card of a run, drawn the same on the Workbench and in Audit. Built with the Workbench
// (slice A2 of the plan); until then it says so in one line, and Audit can import it already.

import {el} from './dom.js';
// Not drawn from yet. Imported so that the modules the card is built from load with the page and
// are held by StaticJoinTest from the first merge on, before the card itself exists.
import {responseDetail} from './detail.js';
import {errorPanel} from './fault.js';

/** A run envelope as a result card. options.context: 'workbench' or 'audit'. */
export function resultCard(envelope, options) {
    return el('div', {class: 'result result--bare'}, el('p', {class: 'stub-line'}, 'The result card comes with the Workbench.'));
}
