// The Workbench view. Until the Workbench itself is built (slice A2 of the plan), it says so in one
// line, so the frame, its tabs and its addresses can be used and checked on their own.

import {el} from './dom.js';
// Not used yet. Imported so that the modules the Workbench is built on load with the page and are
// held by StaticJoinTest from the first merge on, before the Workbench itself exists.
import {resultCard} from './result.js';
import {remember, recall} from './session-runs.js';

/** Draws the view into root. route: the parts of the address after #workbench. */
export function mount(root, route) {
    root.append(el('div', {class: 'page'}, el('p', {class: 'stub-line'}, 'The Workbench is being rebuilt and comes in a later merge.')));
    return {update() {}, unmount() {}};
}
