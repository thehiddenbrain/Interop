// Configuration > Advanced > CDS discovery: asks the CRD service which CDS Hooks services it
// offers (GET /api/crd/discover) and lists them as the service declared them. Discovery is not a
// test run, so nothing is recorded; it is asked for with the button, never on opening the page,
// because on live it is a call to the vendor.
//
// Every value is shown with its presence: a field the service did not return, returned as null or
// returned empty is said in those words, and everything the page did not read is listed as
// returned but not interpreted, so nothing the service sent is silently dropped.

import {call} from './api.js';
import {app} from './app.js';
import {el, fill, clear, chip, button, fold, kv} from './dom.js';
import {errorPanel} from './fault.js';
import {jsonView} from './json-view.js';
import {pageHead, section, part, during, none, counted} from './cfg-parts.js';

const PRESENCE_WORDS = {ABSENT: 'not returned', NULL: 'returned as null', EMPTY: 'returned empty'};
const mono = text => el('span', {class: 'mono'}, text);

/** A value the service sent, or the words for its absence; mono for an id or a template. */
export function shown(value, literal) {
    if (!value || typeof value !== 'object' || value.presence === undefined) {
        return chip('not returned', {dashed: true});
    }
    if (value.presence !== 'VALUE') {
        return chip(PRESENCE_WORDS[value.presence] || String(value.presence), {dashed: true});
    }
    return literal ? mono(String(value.value)) : String(value.value);
}

/** Draws the page into root. route: the parts of the address after #configuration. */
export function mount(root, route) {
    let gone = false;
    const busy = el('span', {class: 'muted small', role: 'status'});
    const ask = button('Ask for the CDS services', () => during(ask, busy, 'Asking the service which CDS services it offers', discover),
        {primary: true});
    const answer = el('div');
    const page = section(pageHead('CDS discovery', 'The CDS Hooks services the CRD service of the active environment offers.'),
        el('p', {class: 'small muted'}, 'Nothing is recorded. On live this calls the service.'),
        el('div', {class: 'form-foot'}, ask, busy), answer);
    root.append(page);
    const off = app.on('config', () => {
        if (answer.childNodes.length > 0) {
            fill(answer, el('p', null, el('strong', null, 'The configuration changed, so the list shown here was taken away. '),
                'Ask again to see what the active environment offers.'));
        }
    });

    async function discover() {
        const got = await call('GET', '/api/crd/discover');
        if (gone) {
            return;
        }
        if (!got.ok) {
            fill(answer, errorPanel(got.error));
            return;
        }
        fill(answer, discovered(got.data || {}));
    }

    return {
        update() {},
        unmount() {
            gone = true;
            off();
            clear(answer);
            page.remove();
        }
    };
}

function discovered(found) {
    const services = Array.isArray(found.services) ? found.services : [];
    const url = found.url || {};
    const additional = Array.isArray(found.additionalFields) ? found.additionalFields : [];
    return part(counted(services.length, 'service', 'services') + ' listed',
        found.maskedReading ? el('p', {class: 'masked-reading'}, found.maskedReading) : null,
        found.note ? el('p', null, el('strong', null, found.note)) : null,
        kv([
            {key: 'Url as configured', value: url.template ? mono(url.template) : none('none')},
            {key: 'Url it resolves to', value: url.display ? mono(url.display) : none('none')},
            {key: 'Where the url comes from', value: url.sourceLabel || url.source || none('not stated')}
        ]),
        services.length === 0 ? el('p', null, 'The answer listed no services.') : services.map(service => el('div', {class: 'panel__section'},
            el('p', null, el('strong', null, shown(service.title)), ' ', shown(service.id, true), ' ', chip('hook', {mono: true}), ' ',
                shown(service.hook, true)),
            el('p', {class: 'small'}, shown(service.description)),
            Array.isArray(service.prefetch) && service.prefetch.length > 0
                ? kv(service.prefetch.map(entry => ({key: mono(entry.key || ''), value: shown(entry.template, true)})))
                : el('p', {class: 'small muted'}, 'No prefetch declared.'))),
        found.error ? errorPanel(found.error) : null,
        additional.length > 0 ? fold({summary: 'Returned but not interpreted', hint: String(additional.length),
            body: () => kv(additional.map(entry => ({key: mono(entry.path || ''), value: entry.presence === 'VALUE'
                ? mono(String(entry.value)) : chip(PRESENCE_WORDS[entry.presence] || String(entry.presence), {dashed: true})})))}) : null,
        found.wire ? fold({summary: 'Wire traffic', hint: 'request and response', body: () => wireBody(found.wire)}) : null);
}

/** The request and the response as they went, secrets already masked by the server. */
function wireBody(wire) {
    const request = wire.request;
    const response = wire.response;
    const headers = list => (Array.isArray(list) && list.length > 0
        ? kv(list.map(header => ({key: mono(header.name || ''), value: mono(header.value || '')})))
        : el('p', {class: 'small muted'}, 'Headers: none.'));
    return el('div', {class: 'wire'},
        request ? el('div', null,
            el('p', {class: 'notes__title'}, 'Request'),
            request.note ? el('p', {class: 'small'}, request.note) : null,
            kv([
                {key: 'Method', value: mono(request.method || '')},
                {key: 'Url', value: request.url ? mono(request.url) : none('none')},
                {key: 'Sent', value: request.sent ? 'Yes, sent' : 'Not sent'}
            ]),
            headers(request.headers),
            typeof request.body === 'string' ? jsonView(request.body, {label: 'Request body'}) : null) : null,
        response ? el('div', null,
            el('p', {class: 'notes__title'}, 'Response'),
            kv([
                {key: 'Status', value: typeof response.status === 'number' ? 'HTTP ' + response.status : none('no response arrived')},
                {key: 'Read as JSON', value: response.parsed ? 'yes' : 'no'}
            ]),
            response.maskNote ? el('p', {class: 'small masked-reading'}, response.maskNote) : null,
            headers(response.headers),
            typeof response.body === 'string' ? jsonView(response.body, {label: 'Response body'}) : null)
            : el('p', {class: 'small muted'}, 'No response arrived.'));
}
