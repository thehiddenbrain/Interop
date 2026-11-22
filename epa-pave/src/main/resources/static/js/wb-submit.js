// Step 3 of the Workbench, the submission (PAS). Built in slice H2 of the plan; until then the step
// says so in one line, so the Workbench (A2a) can mount it from its first merge.

import {el} from './dom.js';

/**
 * Draws the step into root. ctx: the Workbench's one object for every step module (plan, section
 * 9.1). Returns the calls the Workbench makes as the step is opened, left and its case changes.
 */
export function mount(root, ctx) {
    const line = el('p', {class: 'stub-line'}, 'This step comes in a later merge.');
    root.append(line);
    return {
        show() {
            line.hidden = false;
        },
        hide() {
            line.hidden = true;
        },
        caseChanged() {},
        unmount() {
            line.remove();
        }
    };
}
