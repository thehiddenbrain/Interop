// The stepper: four steps across, each a number in a circle, its name and a chip (plan 3.2, "Work
// column"). A tab list: the current step is underlined and the arrow keys move between steps.
//
// The names and the chips are the server's. After a result the chain view's steps[] gives each
// step's state and chip; before any run every step shows the vocabulary's NOT_RUN chip. A step
// with a result of this page but no chain view yet shows its outcome's short label from the
// vocabulary. A chip with no tone is drawn dashed.

import {el, chip, toneName, fill} from './dom.js';
import {STEPS} from './wb-journey.js';

/** onPick(n): a step was chosen. */
export function createStepper(onPick) {
    const tabs = STEPS.map(step => {
        const number = el('span', {class: 'stepper__num'}, String(step.n));
        const title = el('span', {class: 'stepper__title'});
        const chipSlot = el('span', {class: 'stepper__chip'});
        const tab = el('button', {type: 'button', class: 'stepper__step', role: 'tab', id: 'step-tab-' + step.n,
            'aria-controls': 'step-panel-' + step.n, 'aria-selected': 'false', tabindex: -1,
            on: {click: () => onPick(step.n)}},
        number, el('span', {class: 'stepper__text'}, title, chipSlot));
        return {step, tab, number, title, chipSlot};
    });
    const node = el('div', {class: 'stepper', role: 'tablist', 'aria-label': 'Steps of the case'}, tabs.map(each => each.tab));
    node.addEventListener('keydown', event => {
        const at = tabs.findIndex(each => each.tab === document.activeElement);
        if (at < 0) {
            return;
        }
        let next = null;
        if (event.key === 'ArrowRight') {
            next = (at + 1) % tabs.length;
        } else if (event.key === 'ArrowLeft') {
            next = (at + tabs.length - 1) % tabs.length;
        } else if (event.key === 'Home') {
            next = 0;
        } else if (event.key === 'End') {
            next = tabs.length - 1;
        }
        if (next !== null) {
            event.preventDefault();
            // Arrow keys move along the tab list, so focus stays on the tabs (plan 3.2 stepper).
            onPick(next + 1, {keyboard: true});
            tabs[next].tab.focus();
        }
    });
    return {
        node,
        /** Marks the open step. */
        select(n) {
            for (const each of tabs) {
                const on = each.step.n === n;
                each.tab.setAttribute('aria-selected', on ? 'true' : 'false');
                each.tab.tabIndex = on ? 0 : -1;
                if (on) {
                    each.tab.setAttribute('aria-current', 'step');
                } else {
                    each.tab.removeAttribute('aria-current');
                }
            }
        },
        /**
         * Draws names and chips. words: the vocabulary (or null); chain: the chain view (or null);
         * local(n): the envelope of a step's last result on this page, or null.
         */
        draw(words, chain, local) {
            const fromChain = chain && Array.isArray(chain.steps) ? chain.steps : null;
            for (const each of tabs) {
                const named = words && Array.isArray(words.steps) ? words.steps.find(step => step.step === each.step.step) : null;
                each.title.textContent = named && named.name ? named.name : '';
                const served = fromChain ? fromChain.find(step => step.step === each.step.step) : null;
                let shown = null;
                if (served && served.chip && served.chip.text) {
                    shown = {text: served.chip.text, tone: served.chip.tone || null, title: served.stateLine || null};
                } else {
                    const envelope = local(each.step.n);
                    const outcome = envelope && words && words.outcomes
                        ? (words.outcomes[envelope.protocol] || []).find(one => envelope.verdict && one.code === envelope.verdict.outcomeCode) : null;
                    if (outcome && outcome.short) {
                        shown = {text: outcome.short, tone: outcome.tone || null};
                    } else if (!envelope && words && words.stepStates && words.stepStates.NOT_RUN) {
                        shown = {text: words.stepStates.NOT_RUN.text, tone: words.stepStates.NOT_RUN.tone || null};
                    }
                }
                fill(each.chipSlot, shown ? chip(shown.text, {tone: shown.tone, dashed: !shown.tone, title: shown.title || shown.text}) : '');
                const tone = shown ? toneName(shown.tone) : null;
                each.number.className = 'stepper__num' + (tone && ['action', 'clear', 'fault', 'stop', 'neutral'].includes(tone)
                    ? ' stepper__num--' + tone : '');
            }
        }
    };
}
