// The Environments view of the Config tab: upload a Postman environment, see the ones the tool
// holds, make one active, remove one, and read the variables of one.
//
// The kind is chosen by the person who uploads and is sent as chosen: none is preselected, since
// the server never guesses a kind and this page must not either. When the server answers that an
// environment declared as something else reads like production, its sentence is shown at the
// confirmation it asks for, and the confirmation goes back only when the tester has ticked it.
//
// A secret is masked by the server and stays masked. There is no control here that reveals one.

import * as calls from './config-calls.js';
import {el, fill, clear, button, code, table, tableRow, heading, when, named} from './dom.js';
import {clearFieldErrors} from './forms.js';
import {kindMark, none, flag, sentences, refusal, during, counted, staleRefusal, addressWarnings} from './config-parts.js';

// The three kinds an environment can be declared as, with what each one brings. The words after
// the kind are this page's; the kinds are the ones the upload accepts.
const KINDS = [
    ['sandbox', 'A test environment of the vendor.'],
    ['uat', 'User acceptance testing. A banner stays at the top of every tab.'],
    ['prod', 'Production. A banner stays at the top of every tab and writes are blocked until they are unlocked.']
];

export function createEnvironmentsView(context) {
    let shownId = null;        // the environment whose variables are listed, when the tester chose one
    let removing = null;       // the id of the environment a removal is being confirmed for
    let asked = null;          // {file, kind}: what the server asked a confirmation for

    /* ---------- the upload form ---------- */

    const file = el('input', {type: 'file', id: 'config-environment-file', accept: '.json,application/json'});
    const kindChoices = KINDS.map(([kind, words]) => {
        const id = 'config-environment-kind-' + kind;
        const input = el('input', {type: 'radio', name: 'config-environment-kind', id, value: kind});
        return {kind, input, node: el('label', {class: 'choice config-kind-choice', htmlFor: id}, input, el('span', null, kindMark(kind), ' ', words))};
    });
    const confirm = el('input', {type: 'checkbox', id: 'config-environment-confirm'});
    const confirmWords = el('span');
    const confirmField = el('div', {class: 'field config-confirm', hidden: true, dataset: {field: 'acknowledgeNotProd'}},
        el('label', {class: 'choice', htmlFor: 'config-environment-confirm'}, confirm, confirmWords));
    const upload = el('button', {type: 'submit', class: 'button button--primary'}, 'Upload an environment');
    const problem = el('div', {class: 'form-problem-slot', aria: {live: 'polite'}});
    const outcome = el('div', {class: 'config-outcome', aria: {live: 'polite'}});
    const form = el('form', {class: 'config-upload', noValidate: true, attrs: {'aria-label': 'Upload a Postman environment'}},
        el('div', {class: 'config-upload__fields'},
            el('div', {class: 'field', dataset: {field: 'file'}},
                el('label', {htmlFor: 'config-environment-file'}, 'Postman environment file'), file,
                el('span', {class: 'field__hint'}, 'In Postman: Environments, the menu of the environment, Export.')),
            el('fieldset', {class: 'field', dataset: {field: 'kind'}},
                el('legend', {class: 'field__label'}, 'What kind of environment it is'),
                el('div', {class: 'config-kind-choices'}, kindChoices.map(choice => choice.node)))),
        confirmField,
        problem,
        el('div', {class: 'button-row'}, upload));

    form.addEventListener('submit', event => {
        event.preventDefault();
        during(upload, send);
    });
    // The confirmation belongs to one file declared as one kind. A change to either withdraws it,
    // together with the refusal it answered, which was about what was sent before.
    form.addEventListener('change', event => {
        if (event.target === confirm) {
            return;
        }
        withdrawConfirmation();
        clearFieldErrors(form);
        clear(problem);
    });

    function chosenKind() {
        const chosen = kindChoices.find(choice => choice.input.checked);
        return chosen ? chosen.kind : null;
    }

    function withdrawConfirmation() {
        asked = null;
        confirm.checked = false;
        confirmField.hidden = true;
    }

    async function send() {
        clearFieldErrors(form);
        clear(problem);
        clear(outcome);
        const kind = chosenKind();
        // Sent only when the server asked for it for this very file and kind, and the tester ticked it.
        const confirmed = Boolean(asked) && confirm.checked && asked.kind === kind && asked.file === file.files[0];
        if (file.files.length === 0) {
            // The server words every other problem. This one never reaches it: there is nothing to send.
            fill(problem, el('div', {class: 'form-problem', role: 'alert'},
                el('p', {class: 'form-problem__title'}, 'No file is chosen.'),
                el('p', null, 'Choose the environment file that Postman exported, then upload it.')));
            file.focus();
            return;
        }
        const answer = await calls.uploadEnvironment(file.files[0], kind, confirmed);
        if (answer.kind !== 'ok') {
            const wantsConfirmation = (answer.body.fieldErrors || []).some(item => item.field === 'acknowledgeNotProd');
            if (wantsConfirmation && kind) {
                // The field is put on screen first, so the server's sentence lands on it.
                asked = {file: file.files[0], kind};
                confirm.checked = false;
                fill(confirmWords, 'I confirm that this environment is not production. Store it as ', kindMark(kind), '.');
                confirmField.hidden = false;
            }
            fill(problem, refusal(answer, [form]));
            return;
        }
        const done = answer.body;
        const environment = done.environment;
        fill(outcome, uploaded(done));
        file.value = '';
        for (const choice of kindChoices) {
            choice.input.checked = false;
        }
        withdrawConfirmation();
        shownId = environment.id;
        await context.changed();
    }

    function uploaded(done) {
        const environment = done.environment;
        const warnings = done.warnings || [];
        return el('div', {class: 'config-done'},
            el('p', {class: 'config-done__title'}, 'Stored the environment "', named(environment.name), '" as ', kindMark(environment.kind), '.',
                done.replaced ? ' It replaced the one stored under that name.' : null),
            environment.active
                ? el('p', null, 'It is the active environment, so every tab uses it now.')
                : el('p', null, 'It is not active: uploading never switches the environment. Make it active in the list below when you want to use it.'),
            warnings.length > 0 ? el('div', {class: 'config-warnings', role: 'alert'},
                el('p', {class: 'sub-heading'}, warnings.length === 1 ? 'The tool has one warning about this file' : 'The tool has ' + warnings.length + ' warnings about this file'),
                sentences(warnings)) : null);
    }

    /* ---------- the list ---------- */

    const listHead = el('h2', {class: 'section-heading', tabIndex: -1});
    const list = el('div', {class: 'config-list'});
    const listStatus = el('div', {class: 'config-outcome', aria: {live: 'polite'}});
    const variables = el('section', {class: 'result__section'});

    // While production writes are unlocked, an upload ends the unlock, from this browser or any other.
    const uploadLocks = el('p', {class: 'config-switch config-switch--unlocked'});
    uploadLocks.hidden = true;

    const node = el('div', {class: 'config-view'},
        el('section', {class: 'result__section'}, heading(2, 'Upload an environment'), uploadLocks, form, outcome),
        el('section', {class: 'result__section'}, listHead, listStatus, list),
        variables);

    function draw() {
        const stored = context.stored();
        const access = (context.app.mode() || {}).writeAccess || {};
        uploadLocks.hidden = !(access.production === true && access.writesAllowed === true);
        fill(uploadLocks, el('strong', null, 'Production writes are unlocked now, and an upload locks them again. '),
            'Any change to the configuration does, made from any browser.');
        if (!stored.environments) {
            fill(listHead, 'Environments the tool holds');
            fill(list, el('p', {class: 'quiet'}, 'Reading the configuration from the tool.'));
            clear(variables);
            return;
        }
        const environments = stored.environments.environments || [];
        fill(listHead, 'Environments the tool holds ', el('span', {class: 'section-heading__count'}, '(' + environments.length + ')'));
        if (environments.length === 0) {
            fill(list, el('p', null, 'None yet. Choose the environment file the vendor gave you, say what kind it is, and upload it above.'));
            clear(variables);
            return;
        }
        if (removing && !environments.some(environment => environment.id === removing)) {
            removing = null;
        }
        const unlocked = access.production === true && access.writesAllowed === true;
        const body = [];
        for (const environment of environments) {
            body.push(row(environment));
            if ((environment.addressWarnings || []).length > 0) {
                body.push(addressRow(environment));
            }
            if (removing === environment.id) {
                body.push(removalRow(environment));
            }
        }
        fill(list,
            el('p', {class: unlocked ? 'config-switch config-switch--unlocked' : 'config-switch'},
                'Making an environment active takes effect at once, on every tab, with no restart. ',
                unlocked ? el('strong', null, 'Production writes are unlocked now, and switching locks them again. ') : null,
                'A switch always drops an unlock of production writes: an unlock is for one environment and never carries over to another.'),
            sentences(stored.environments.problems, 'config-warnings'),
            el('table', {class: 'grid config-environments'},
                el('thead', null, el('tr', null, ['Environment', 'Kind', 'Variables', 'Uploaded', 'In use'].map(text => el('th', {scope: 'col'}, text)))),
                el('tbody', null, body)),
            calls.canRemove() ? null : el('p', {class: 'quiet small'}, el('strong', null, 'Removing an environment is not offered here. '), calls.REMOVAL_NOT_OFFERED));
        drawVariables(environments);
    }

    function row(environment) {
        const all = environment.variables || [];
        const secrets = all.filter(variable => variable.secret).length;
        const signs = environment.productionSigns || [];
        const actions = [];
        if (environment.active) {
            actions.push(el('strong', null, 'Active'));
        } else {
            const control = button('Make active', () => during(control, () => activate(environment)),
                {class: 'button--quiet button--small', aria: {label: 'Make ' + named(environment.name) + ' the active environment'}});
            actions.push(control);
        }
        if (calls.canRemove()) {
            actions.push(button('Remove', () => { removing = environment.id; draw(); focusRemoval(); },
                {class: 'button--text', aria: {label: 'Remove the environment ' + named(environment.name), expanded: String(removing === environment.id)}}));
        }
        return tableRow({class: environment.active ? 'config-row config-row--active' : 'config-row', cells: [
            [el('strong', null, environment.name),
                el('div', {class: 'small quiet'}, 'from the file ', code(environment.originalFileName || 'with no name')),
                environment.acknowledgedNotProd ? el('div', {class: 'small'}, 'Confirmed as not production when it was uploaded.',
                    signs.length > 0 ? [' It reads like production because ', signs.join(', and '), '.'] : null) : null],
            kindMark(environment.kind),
            [counted(all.length, 'variable', 'variables'), secrets > 0 ? ', ' + secrets + ' secret' : null,
                // The visible words begin the accessible name, so a person who says what they see reaches it.
                el('div', null, button('List the variables',
                    () => { shownId = environment.id; draw(); focusVariables(); },
                    {class: 'button--text', aria: {label: 'List the variables of ' + named(environment.name)}}))],
            when(environment.uploadedAt),
            {class: 'config-actions', node: actions}
        ]});
    }

    /**
     * What the tool says about the addresses of one environment, in its own sentences, on a line of
     * its own beneath the environment, so it is read before the environment is made active: an
     * address no write can be sent to, one nothing can be sent to, one on the server of a
     * production environment. The sentences come with the list; this page does not sort them.
     */
    function addressRow(environment) {
        return el('tr', {class: 'config-address-row' + (environment.active ? ' config-row--active' : '')}, el('td', {colSpan: 5},
            addressWarnings(environment.name, environment.addressWarnings)));
    }

    async function activate(environment) {
        clear(listStatus);
        const answer = await calls.activateEnvironment(environment.id);
        if (answer.kind !== 'ok') {
            fill(listStatus, staleRefusal(answer, 'the environment "' + named(environment.name) + '"'));
        } else {
            // The variables listed are those of the active environment again, unless the tester chooses another.
            shownId = null;
            fill(listStatus, el('p', {class: 'config-done__title'}, '"', named(answer.body.environment.name), '" is now the active environment.'),
                (answer.body.addressWarnings || []).length > 0
                    ? el('div', {role: 'alert'}, addressWarnings(answer.body.environment.name, answer.body.addressWarnings)) : null);
        }
        await context.changed();
    }

    /* ---------- removing one, as a second and separate act ---------- */

    function removalRow(environment) {
        const keep = button('Keep it', () => { removing = null; draw(); }, {class: 'button--quiet', id: 'config-environment-keep'});
        const remove = button(['Remove the environment "', named(environment.name), '"'], () => during(remove, () => removeNow(environment)),
            {class: 'button--quiet'});
        return el('tr', {class: 'config-removal'}, el('td', {colSpan: 5},
            el('div', {class: 'config-removal__box', role: 'group', aria: {label: 'Remove the environment ' + named(environment.name)}},
                el('p', null, el('strong', null, 'Remove the environment "', named(environment.name), '"?'),
                    ' Its uploaded file is deleted from the tool. The file you uploaded it from is not touched, so it can be uploaded again.',
                    environment.active ? el('strong', null, ' It is the active environment: afterwards no environment is active.') : null),
                el('div', {class: 'button-row'}, keep, remove))));
    }

    /** The safe choice takes the focus, so that Enter pressed twice by habit keeps the environment. */
    function focusRemoval() {
        const keep = document.getElementById('config-environment-keep');
        if (keep) {
            keep.focus();
        }
    }

    async function removeNow(environment) {
        clear(listStatus);
        const answer = await calls.removeEnvironment(environment.id);
        removing = null;
        if (answer.kind !== 'ok') {
            fill(listStatus, staleRefusal(answer, 'the environment "' + named(environment.name) + '"'));
        } else {
            const done = answer.body;
            fill(listStatus, el('div', {class: 'config-done'},
                el('p', {class: 'config-done__title'}, 'Removed the environment "', done.removed, '".',
                    done.wasActive ? ' It was the active one, so no environment is active now.' : null),
                (done.warnings || []).length > 0 ? el('div', {class: 'config-warnings', role: 'alert'}, sentences(done.warnings)) : null));
            if (shownId === environment.id) {
                shownId = null;
            }
        }
        await context.changed();
        listHead.focus();
    }

    /* ---------- the variables of one environment ---------- */

    /** The keyboard follows the list it asked for: its heading takes the focus and comes into view. */
    function focusVariables() {
        const title = variables.querySelector('h2');
        if (title) {
            title.tabIndex = -1;
            title.scrollIntoView({block: 'start'});
            title.focus({preventScroll: true});
        }
    }

    /** The environment whose variables are listed: the one the tester chose, otherwise the active one, otherwise the first. */
    function shownEnvironment(environments) {
        return environments.find(environment => environment.id === shownId)
            || environments.find(environment => environment.active)
            || environments[0];
    }

    function drawVariables(environments) {
        const environment = shownEnvironment(environments);
        const all = environment.variables || [];
        fill(variables,
            el('h2', {class: 'section-heading'}, 'Variables of ', named(environment.name), ' ', kindMark(environment.kind),
                ' ', el('span', {class: 'section-heading__count'}, '(' + all.length + ')')),
            el('p', environment.active ? {class: 'quiet'} : null,
                environment.active ? 'This is the active environment. ' : el('strong', null, 'This is not the active environment. Nothing uses these values until it is made active. '),
                'The file is kept as it was uploaded, at ', code(environment.file), '. The value of a secret is never shown, here or anywhere in the tool.'),
            all.length === 0 ? el('p', null, 'The file defines no variables.')
                : table([{text: 'Variable', class: 'col-18'}, {text: 'Value', class: 'col-30'}, {text: 'Where the value is read from', class: 'col-22'}, 'What refers to it'],
                    all.map(variableRow), {class: 'config-variables'}));
    }

    function variableRow(variable) {
        const marks = [];
        if (!variable.enabled) {
            marks.push(flag('switched off', 'The export switches this variable off. It is never used, exactly as Postman ignores it.'));
        } else if (!variable.inForce) {
            marks.push(flag('replaced', 'A later definition of the same name in this file replaces this one. Only the later one is used.'));
        }
        const usedBy = variable.usedBy || [];
        return {class: variable.inForce ? null : 'config-row--idle', cells: [
            [code(variable.name), marks.length > 0 ? el('div', {class: 'config-marks'}, marks) : null],
            variable.set
                ? [code(variable.display), variable.secret ? [' ', flag('secret', 'The value of a secret is never shown. What stands here is the mask the tool puts in its place, or the reference the value is read through.')] : null]
                : el('span', {class: 'presence presence--empty', title: 'The file defines this variable with a blank value. A blank value does not count as a value: the next layer is asked.'}, 'blank'),
            variable.readsFrom ? variable.readsFrom : (variable.set ? 'the uploaded file' : none('nowhere: it is blank')),
            usedBy.length > 0 ? el('ul', {class: 'plain-list small'}, usedBy.map(where => el('li', null, where)))
                : none(variable.inForce ? 'nothing refers to it' : 'not in force, so nothing uses it')
        ]};
    }

    return {node, show() {}, draw};
}
