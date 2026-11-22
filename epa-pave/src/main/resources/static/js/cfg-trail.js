// A page of Configuration that the next release brings. Until it is built it says so in one line,
// so the page contract (plan 9.1) holds from the first merge and every route of Configuration opens.

import {el} from './dom.js';

/** Draws the page into root. route: the parts of the address after #configuration. */
export function mount(root, route) {
    const page = el('section', {class: 'section'}, el('p', {class: 'stub-line'}, 'This page comes in the next release.'));
    root.append(page);
    return {
        update() {},
        unmount() {
            page.remove();
        }
    };
}
