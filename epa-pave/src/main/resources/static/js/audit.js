// The Audit view. Until Audit itself is built (batch B of the plan), it says so in one line, so
// the frame, its tabs and its addresses can be used and checked on their own.

import {el} from './dom.js';

/** Draws the view into root. route: the parts of the address after #audit. */
export function mount(root, route) {
    root.append(el('div', {class: 'page'}, el('p', {class: 'stub-line'}, 'Audit is being rebuilt and comes in a later merge.')));
    return {update() {}, unmount() {}};
}
