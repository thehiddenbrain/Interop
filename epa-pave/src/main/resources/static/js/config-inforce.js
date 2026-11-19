// The first view of the Config tab: what is in force now.
//
// It answers, from the server's own statements, where the tool is pointed, whether that is
// production, and whether a write would be sent. Before anything has been uploaded it says
// exactly what to upload first.

import {el, fill, button, code, facts, heading, whenText} from './dom.js';
import {environmentName, sentences, none} from './config-parts.js';

export function createInForceView(context) {
    const pointed = el('section', {class: 'result__section', aria: {live: 'polite'}});
    const start = el('div', {class: 'config-start'});
    const node = el('div', {class: 'config-view'}, pointed, start);

    function draw() {
        const stored = context.stored();
        const mode = context.app.mode();
        if (!stored.effective || !mode) {
            fill(pointed, heading(2, 'Where this tool is pointed'),
                el('p', {class: 'quiet'}, 'Reading the configuration from the tool.'));
            fill(start);
            return;
        }
        drawPointed(stored, mode);
        drawStart(stored);
    }

    function drawPointed(stored, mode) {
        const effective = stored.effective;
        const access = mode.writeAccess || {};
        const environment = mode.environment || {};
        fill(pointed,
            el('div', {class: 'panel__head'},
                heading(2, 'Where this tool is pointed'),
                el('span', {class: 'config-read'},
                    el('span', {class: 'quiet small'}, 'Read from the tool at ', whenText(stored.readAt), '.'),
                    button('Read again', () => context.reload(), {class: 'button--quiet button--small'}))),
            facts([
                ['Profile', profile(mode)],
                ['Target', el('strong', null, mode.target)],
                ['Environment', [environmentName(environment), environment.active ? null
                    : el('span', null, ' Until one is active, an address can only come from application.yml.')]],
                // The server says whether the active environment is production. This page does not read it off a name.
                ['Production', access.production === true
                    ? el('strong', {class: 'config-production'}, 'Yes. The active environment is declared as production.')
                    : environment.active ? 'No. The active environment is declared as ' + environment.kind + '.' : 'No environment is active.'],
                ['Writes', access.reason || none('not stated')],
                ['Uploaded files are kept in', code(effective.storeDirectory)]
            ], 'facts--ruled config-facts'),
            problems(effective.problems || []));
    }

    function profile(mode) {
        if (context.app.isMock()) {
            return [el('span', {class: 'hatch', aria: {hidden: 'true'}}), el('strong', null, mode.mode),
                el('span', null, '. Scenario files answer in place of the service and nothing is sent. What is configured here still '
                    + 'decides the address every request is shown with, and the production guardrail applies as it does on the live profile.')];
        }
        return [el('strong', null, mode.mode), el('span', null, '. Requests are sent to the addresses this tab shows.')];
    }

    function problems(list) {
        if (list.length === 0) {
            return null;
        }
        return el('div', {class: 'form-problem', role: 'alert'},
            el('p', {class: 'form-problem__title'}, list.length === 1 ? 'The configuration has a problem.' : 'The configuration has ' + list.length + ' problems.'),
            sentences(list));
    }

    /* ---------- what to do first ---------- */

    function drawStart(stored) {
        const environments = stored.environments.environments || [];
        const collections = stored.collections.collections || [];
        const active = environments.some(environment => environment.active);
        if (environments.length > 0 && collections.length > 0 && active) {
            fill(start);
            return;
        }
        const nothing = environments.length === 0 && collections.length === 0;
        const step = (done, text, control) => el('li', {class: done ? 'config-step config-step--done' : 'config-step'},
            el('span', null, done ? el('strong', null, 'Done. ') : null, text), done ? null : control);
        fill(start, el('section', {class: 'result__section config-first'},
            el('p', {class: 'empty-state__lead'}, nothing ? 'Nothing has been uploaded to this tool yet.' : 'The configuration is not complete yet.'),
            el('p', null, 'The vendor supplies two Postman files. Upload them here and nobody has to edit a file by hand.'),
            el('ol', {class: 'config-steps'},
                step(environments.length > 0, 'Upload the Postman environment file. It holds the addresses and the credentials of one environment. '
                    + 'You say whether it is a sandbox, UAT or production: the tool never guesses that.',
                    button('Upload an environment', () => context.go('environments'), {class: 'button--primary'})),
                step(active, 'Make that environment the active one. It takes effect at once, on every tab, with no restart.',
                    environments.length > 0 ? button('Choose the active environment', () => context.go('environments'), {class: 'button--quiet'}) : null)),
            context.app.isMock()
                ? el('p', {class: 'quiet'}, 'On the mock profile CRD, DTR and PAS can be run before anything is uploaded: scenario files answer, and nothing is sent.')
                : null));
    }

    return {node, show() {}, draw};
}
