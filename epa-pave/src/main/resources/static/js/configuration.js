// The Configuration view: the left sub-navigation and the page it opens (plan 3.4).
//
// Each page is a module of its own with one contract (plan 9.1): mount(root, route) returns
// {update(route), unmount()}, where route is the parts of the address after #configuration. A page
// is loaded the first time it opens, so a defect in one page leaves the sub-navigation and every
// other page working. Moving between two addresses of the same module updates it in place; moving
// to another module unmounts the one on screen first, so no page keeps listening once it is gone.

import {el, fill} from './dom.js';
import {PAGE_FAULT_LINE} from './fault.js';

/** The pages of the sub-navigation, in its order. Advanced holds a list of its own. */
export const PAGES = [
    {id: 'environments', label: 'Environments', module: 'config-environments.js', updates: true},
    {id: 'collections', label: 'Collections', module: 'cfg-collections.js'},
    {id: 'members', label: 'Members', module: 'cfg-lists.js'},
    {id: 'providers', label: 'Providers', module: 'cfg-lists.js'},
    {id: 'access-token', label: 'Access token', module: 'cfg-token.js'},
    {id: 'production-writes', label: 'Production writes', module: 'cfg-writes.js'},
    {id: 'advanced', label: 'Advanced', module: null}
];

/** The pages under Advanced, at #configuration/advanced/<id>. The first opens when none is named. */
export const ADVANCED = [
    {id: 'in-force', label: 'In force now', module: 'cfg-inforce.js'},
    {id: 'trail', label: 'Trail', module: 'cfg-trail.js'},
    {id: 'send-as-written', label: 'Send as written', module: 'adv-raw.js'},
    {id: 'try-a-value', label: 'Try a value', module: 'adv-try.js'},
    {id: 'check-this-installation', label: 'Check this installation', module: 'adv-checks.js'},
    {id: 'cds-discovery', label: 'CDS discovery', module: 'adv-discovery.js'},
    {id: 'compare', label: 'Compare with a request that works', module: 'adv-compare.js'}
];

// Each module by a literal import, so the build can see that every page the navigation names exists.
const LOADERS = {
    'config-environments.js': () => import('./config-environments.js')
};

// A page of the navigation whose module is not in this build yet says so in one line, the words
// the stubs of those pages use, rather than the line for a fault.
const NOT_YET = {
    mount(root) {
        fill(root, el('p', {class: 'stub-line'}, 'This page comes in the next release.'));
        return {update() {}, unmount() {}};
    }
};

/** Draws the view into root. route: the parts of the address after #configuration. */
export function mount(root, route) {
    return mountWith(root, route, name => {
        const load = LOADERS[name];
        return load ? load() : Promise.resolve(NOT_YET);
    });
}

/**
 * The view with the way its page modules are loaded given, so the routing can be run on its own.
 * load(name) returns a promise of the module.
 */
export function mountWith(root, route, load) {
    const nav = el('nav', {class: 'subnav', 'aria-label': 'Configuration'});
    const content = el('div', {class: 'config-page'});
    fill(root, el('div', {class: 'config'}, nav, content));
    const state = {route: [], module: null, handle: null, host: null, showing: 0, gone: false};

    function show(next) {
        state.route = normal(next);
        drawNav();
        const page = pageOf(state.route);
        // A page that redraws itself on update() keeps what it read; any other is mounted afresh
        // for its new address, since its update() is not bound to redraw.
        if (page.module === state.module && state.handle && page.updates === true) {
            guarded(() => state.handle.update(state.route));
            return;
        }
        leave();
        state.showing += 1;
        const showing = state.showing;
        state.module = page.module;
        const host = el('div', {class: 'config-page__body'});
        state.host = host;
        fill(content, host);
        load(page.module).then(module => {
            if (state.gone || showing !== state.showing) {
                return;
            }
            guarded(() => {
                const handle = module.mount(host, state.route);
                state.handle = handle && typeof handle === 'object' ? handle : null;
            });
        }, () => {
            if (!state.gone && showing === state.showing) {
                fill(host, el('p', {class: 'stub-line'}, PAGE_FAULT_LINE));
            }
        });
    }

    function leave() {
        if (state.handle && typeof state.handle.unmount === 'function') {
            const leaving = state.handle;
            guarded(() => leaving.unmount());
        }
        state.handle = null;
        state.module = null;
    }

    function guarded(work) {
        try {
            work();
        } catch (defect) {
            // A page's own fault stays inside its part of the screen; the sub-navigation keeps working.
            if (state.host) {
                fill(state.host, el('p', {class: 'stub-line'}, PAGE_FAULT_LINE));
            }
        }
    }

    function drawNav() {
        const [first, second] = state.route;
        const items = [];
        for (const page of PAGES) {
            const current = page.id === first;
            const address = page.id === 'advanced' ? '#configuration/advanced/' + ADVANCED[0].id : '#configuration/' + page.id;
            items.push(el('a', {class: 'subnav__item', href: address, 'aria-current': current && page.id !== 'advanced' ? 'page' : null}, page.label));
            if (page.id === 'advanced' && current) {
                items.push(el('div', {class: 'config-subnav-list', role: 'group', 'aria-label': 'Advanced'},
                    ADVANCED.map(each => el('a', {
                        class: 'subnav__item config-subnav-list__item', href: '#configuration/advanced/' + each.id,
                        'aria-current': each.id === second ? 'page' : null
                    }, each.label))));
            }
        }
        fill(nav, items);
    }

    show(route);
    return {
        update: next => show(next),
        unmount() {
            state.gone = true;
            leave();
        }
    };
}

/** The route as this view writes it: a page it has, Environments when none or an unknown one is named. */
export function normal(route) {
    const parts = Array.isArray(route) ? route.map(String) : [];
    const [first, second] = parts;
    if (first === 'advanced') {
        return ADVANCED.some(each => each.id === second) ? parts : ['advanced', ADVANCED[0].id].concat(parts.slice(2));
    }
    return PAGES.some(each => each.id === first) ? parts : ['environments'];
}

function pageOf(route) {
    const [first, second] = route;
    if (first === 'advanced') {
        return ADVANCED.find(each => each.id === second);
    }
    return PAGES.find(each => each.id === first);
}
