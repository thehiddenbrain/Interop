// Every call the Config tab makes, named for what it does, so that no view writes an address.
//
// All of them go through api.js, the one script that talks to the server, and come back in its
// one shape. This file adds nothing to that: it only says which address a thing lives at.
//
// Seam: api.js offers get, postJson, postForm and post. Changing a mapping is a PUT and removing
// an environment or a collection is a DELETE, and api.js has neither yet. Until it exports
// putJson(path, payload) and del(path), each with the answer shape of the others, this tab does
// not offer those three actions: canChangeMapping() and canRemove() say so, and the screen puts
// one sentence where a control that could do nothing would have been. Nothing else here changes
// when the two functions arrive.

import * as api from './api.js';

const part = encodeURIComponent;

/* ---------- reading what is stored and what is in force ---------- */

export const environments = () => api.get('/api/config/environments');
export const collections = () => api.get('/api/config/collections');
export const effective = () => api.get('/api/config/effective');

export function requestInFull(collectionId, requestId) {
    return api.get('/api/config/collections/' + part(collectionId) + '/requests/' + part(requestId));
}

/* ---------- environments ---------- */

/**
 * Uploads a Postman environment. The kind goes as the tester chose it, or not at all when none
 * was chosen: the server refuses an upload with no kind, and says so at the field.
 * acknowledged is sent only when the tester has confirmed that the environment is not production.
 */
export function uploadEnvironment(file, kind, acknowledged) {
    const data = new FormData();
    data.append('file', file);
    if (kind) {
        data.append('kind', kind);
    }
    if (acknowledged) {
        data.append('acknowledgeNotProd', 'true');
    }
    return api.postForm('/api/config/environments', data);
}

/** The id is used in the address, not the name: a name can hold a slash, and the server takes either. */
export function activateEnvironment(environmentId) {
    return api.post('/api/config/environments/' + part(environmentId) + '/activate');
}

/* ---------- collections ---------- */

export function uploadCollection(file) {
    const data = new FormData();
    data.append('file', file);
    return api.postForm('/api/config/collections', data);
}

/* ---------- production writes ---------- */

/** The name goes exactly as it was typed. The server compares it and decides. */
export function unlockWrites(typedName) {
    return api.postJson('/api/config/writes', {action: 'unlock', environment: typedName});
}

export function lockWrites() {
    return api.postJson('/api/config/writes', {action: 'lock'});
}

/* ---------- what a request would be, and sending one as written ---------- */

export const resolve = body => api.postJson('/api/config/resolve', body);
export const previewAsWritten = body => api.postJson('/api/raw/preview', body);
export const sendAsWritten = body => api.postJson('/api/raw', body);

/* ---------- the self-checks ---------- */

export const ledgerHealth = () => api.get('/api/runs/health');
export const codeListSelfCheck = () => api.get('/api/procedure-codes/self-check');

/* ---------- the three calls that wait for api.js ---------- */

export const canChangeMapping = () => typeof api.putJson === 'function';
export const canRemove = () => typeof api.del === 'function';

/** What the screen says where an action is not offered. One sentence, the same everywhere. */
export const NOT_OFFERED = 'This page cannot send that change yet: the script every tab uses to talk to the tool has no call for it.';

export function changeMapping(collectionId, requestId, operation) {
    return api.putJson('/api/config/collections/' + part(collectionId) + '/mapping', {requestId, operation});
}

export function removeEnvironment(environmentId) {
    return api.del('/api/config/environments/' + part(environmentId));
}

export function removeCollection(collectionId) {
    return api.del('/api/config/collections/' + part(collectionId));
}
