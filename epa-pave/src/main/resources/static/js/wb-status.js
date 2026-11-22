// Step 4, Status: the inquiry, the subscription and the notifications of the submission (plan 3.2,
// step 4). Built in slice A2b; until then it says so in one line, so the Workbench can mount it in
// its panel from the first merge.

import {el} from './dom.js';

/** mount(root, ctx), as every step module: {show(), hide(), caseChanged(), unmount()}. */
export function mount(root, ctx) {
    root.append(el('p', {class: 'stub-line'}, 'This step comes in a later merge.'));
    return {show() {}, hide() {}, caseChanged() {}, unmount() {}};
}
