/* Patient Access API Workbench – shared core: API client, state, DOM helpers, messages, modal, tabs, routing. */
(() => {
  'use strict';

  const API = '/api/v1';
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

  const listeners = {};
  const state = { environments: [], envId: null, patientId: null, patient: null, token: null, catalog: null, resources: null };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  /** h('tag.class#id', {attr: v, onclick: fn}, children...) */
  function h(spec, attrs, ...children) {
    const m = spec.match(/^([a-z0-9]+)?((?:[.#][\w-]+)*)$/i);
    const el = document.createElement((m && m[1]) || 'div');
    if (m && m[2]) {
      m[2].match(/[.#][\w-]+/g).forEach(t => t[0] === '.' ? el.classList.add(t.slice(1)) : (el.id = t.slice(1)));
    }
    if (attrs && typeof attrs === 'object' && !(attrs instanceof Node) && !Array.isArray(attrs)) {
      for (const [k, v] of Object.entries(attrs)) {
        if (v == null || v === false) continue;
        if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
        else if (k === 'class') String(v).split(/\s+/).filter(Boolean).forEach(c => el.classList.add(c));
        else if (k === 'html') el.innerHTML = v;
        else if (k === 'text') el.textContent = v;
        else if (k === 'hidden') el.hidden = !!v;
        else if (k === 'checked') el.checked = !!v;
        else if (k === 'disabled') el.disabled = !!v;
        else if (k === 'value') el.value = v;
        else el.setAttribute(k, v === true ? '' : v);
      }
    } else if (attrs != null) {
      children.unshift(attrs);
    }
    for (const c of children.flat(Infinity)) {
      if (c == null || c === false) continue;
      el.append(c instanceof Node ? c : document.createTextNode(String(c)));
    }
    return el;
  }

  async function request(method, path, body, opts = {}) {
    const init = { method, headers: {} };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    const res = await fetch(API + path, init);
    if (opts.text) {
      const text = await res.text();
      if (!res.ok) throw apiError(res.status, text);
      return text;
    }
    const text = await res.text();
    let data = null;
    if (text) {
      try { data = JSON.parse(text); } catch (e) { data = text; }
    }
    if (!res.ok) throw apiError(res.status, data);
    return data;
  }

  function apiError(status, data) {
    const e = new Error((data && data.message) || (typeof data === 'string' && data) || ('HTTP ' + status));
    e.status = status;
    e.code = data && data.code;
    e.details = (data && data.details) || [];
    e.upstream = data && data.upstream;
    return e;
  }

  const api = {
    get: (p) => request('GET', p),
    post: (p, b) => request('POST', p, b === undefined ? {} : b),
    put: (p, b) => request('PUT', p, b),
    del: (p) => request('DELETE', p),
    text: (p) => request('GET', p, undefined, { text: true }),
    url: (p) => API + p,
  };

  // ---------------------------------------------------------------- messages
  function msg(container, kind, text, extra = {}) {
    const box = typeof container === 'string' ? $(container) : container;
    const el = h('div.msg.' + kind, {}, text);
    if (extra.details && extra.details.length) {
      el.append(h('ul', {}, extra.details.map(d => h('li', {}, typeof d === 'string' ? d : ((d.field ? d.field + ': ' : '') + d.message)))));
    }
    if (extra.pre) el.append(h('pre', {}, extra.pre));
    if (extra.html) el.append(h('div', { html: extra.html }));
    box.append(el);
    return el;
  }
  function clearMsgs(container) {
    const box = typeof container === 'string' ? $(container) : container;
    box.innerHTML = '';
  }
  function showError(container, e) {
    const details = (e.details || []).slice();
    let pre = null;
    if (e.upstream) {
      details.push('upstream: ' + (e.upstream.httpStatus || '-') + ' ' + (e.upstream.url || ''));
      if (e.upstream.body) pre = e.upstream.body.length > 2000 ? e.upstream.body.slice(0, 2000) + '…' : e.upstream.body;
      if (e.upstream.requestId) details.push('history entry: ' + e.upstream.requestId);
    }
    return msg(container, 'error', (e.code ? e.code + ': ' : '') + e.message, { details, pre });
  }

  // ---------------------------------------------------------------- modal
  function modal(title, body, { okLabel = 'OK', cancelLabel = 'Cancel', validate } = {}) {
    return new Promise(resolve => {
      const box = $('#modal');
      $('#modal-title').textContent = title;
      const bodyEl = $('#modal-body');
      bodyEl.innerHTML = '';
      bodyEl.append(body);
      const ok = $('#modal-ok');
      const cancel = $('#modal-cancel');
      ok.textContent = okLabel;
      cancel.textContent = cancelLabel;
      const close = (v) => { box.hidden = true; ok.onclick = null; cancel.onclick = null; resolve(v); };
      ok.onclick = () => { if (!validate || validate(bodyEl)) close(true); };
      cancel.onclick = () => close(false);
      box.hidden = false;
      const first = bodyEl.querySelector('input, textarea, select');
      if (first) first.focus();
    });
  }
  async function confirm(title, text) {
    return modal(title, h('p', {}, text), { okLabel: 'Yes' });
  }
  async function prompt(title, label, value = '', multiline = false) {
    const input = multiline ? h('textarea', { rows: 6 }) : h('input', { type: 'text' });
    input.value = value;
    const ok = await modal(title, h('label.field', {}, h('span', {}, label), input));
    return ok ? input.value : null;
  }

  // ---------------------------------------------------------------- formatting
  const fmt = {
    ts: (s) => s ? new Date(s).toLocaleString() : '',
    ms: (n) => n == null ? '' : n + ' ms',
    json: (o) => JSON.stringify(o, null, 2),
    short: (s, n = 80) => s && s.length > n ? s.slice(0, n) + '…' : (s || ''),
  };

  function badge(text, cls) {
    return h('span.badge.' + (cls || text), {}, text);
  }

  // ---------------------------------------------------------------- events / routing
  function on(evt, fn) { (listeners[evt] = listeners[evt] || []).push(fn); }
  function emit(evt, data) { (listeners[evt] || []).forEach(fn => { try { fn(data); } catch (e) { console.error(e); } }); }

  function showTab(name) {
    $$('.tab-btn').forEach(b => b.classList.toggle('active', b.dataset.tab === name));
    $$('.tab').forEach(t => t.classList.toggle('active', t.id === 'tab-' + name));
    updateHash();
    emit('tab', name);
  }
  function currentTab() {
    const b = $('.tab-btn.active');
    return b ? b.dataset.tab : 'environments';
  }
  function updateHash() {
    const parts = [];
    parts.push('tab=' + currentTab());
    if (state.envId) parts.push('env=' + encodeURIComponent(state.envId));
    if (state.patientId) parts.push('patient=' + encodeURIComponent(state.patientId));
    history.replaceState(null, '', '#' + parts.join('&'));
  }
  function readHash() {
    const out = {};
    location.hash.replace(/^#/, '').split('&').forEach(p => {
      const [k, v] = p.split('=');
      if (k) out[k] = decodeURIComponent(v || '');
    });
    return out;
  }

  // ---------------------------------------------------------------- environments (shared)
  async function refreshEnvironments(keepId) {
    state.environments = await api.get('/environments');
    const sel = $('#env-select');
    const wanted = keepId !== undefined ? keepId : state.envId;
    sel.innerHTML = '';
    sel.append(h('option', { value: '' }, state.environments.length ? '— choose —' : '— none configured —'));
    for (const e of state.environments) {
      sel.append(h('option', { value: e.id }, `${e.name} (${e.tier})`));
    }
    const exists = state.environments.some(e => e.id === wanted);
    setEnvironment(exists ? wanted : (state.environments.length === 1 ? state.environments[0].id : null), true);
  }
  function currentEnv() {
    return state.environments.find(e => e.id === state.envId) || null;
  }
  async function setEnvironment(id, silent) {
    const changed = state.envId !== id;
    state.envId = id || null;
    $('#env-select').value = state.envId || '';
    updateHash();
    await refreshToken();
    if (changed || !silent) emit('env-changed', currentEnv());
  }
  async function refreshToken() {
    const pill = $('#token-pill');
    if (!state.envId) { state.token = null; pill.textContent = 'no environment'; pill.className = 'pill muted'; return; }
    try {
      state.token = await api.get(`/environments/${state.envId}/auth/status`);
      const t = state.token;
      if (t.mode === 'NONE') { pill.textContent = 'open server'; pill.className = 'pill muted'; }
      else if (!t.present) { pill.textContent = t.mode + ': no token'; pill.className = 'pill'; }
      else if (t.expired && !t.refreshable) { pill.textContent = 'token expired'; pill.className = 'pill err'; }
      else { pill.textContent = 'token ' + (t.expired ? 'refreshable' : 'ok') + (t.expiresAt ? ' · ' + new Date(t.expiresAt).toLocaleTimeString() : ''); pill.className = 'pill ok'; }
      pill.title = t.scope ? 'scope: ' + t.scope : '';
    } catch (e) {
      pill.textContent = 'token ?'; pill.className = 'pill err';
    }
    emit('token-changed', state.token);
  }
  function requireEnv(container) {
    if (!state.envId) {
      if (container) { clearMsgs(container); msg(container, 'warn', 'Choose an environment in the top bar first.'); }
      return null;
    }
    return state.envId;
  }

  async function loadCatalog() {
    if (!state.resources) {
      state.resources = await api.get('/catalog/resources');
      state.catalog = await api.get('/catalog');
    }
    return state.resources;
  }

  function setPatient(id, summary) {
    state.patientId = id || null;
    state.patient = summary || null;
    updateHash();
    emit('patient-changed', { id: state.patientId, summary: state.patient });
  }

  // ---------------------------------------------------------------- table helper
  function table(columns, rows, { onRow, empty = 'nothing to show' } = {}) {
    const t = h('table.grid-table');
    t.append(h('thead', {}, h('tr', {}, columns.map(c => h('th', {}, c.label || c)))));
    const body = h('tbody');
    if (!rows.length) body.append(h('tr', {}, h('td', { colspan: columns.length, class: 'muted' }, empty)));
    for (const r of rows) {
      const tr = h('tr', { class: onRow ? 'selectable' : '' }, columns.map(c => {
        const key = c.key || c;
        const v = typeof c.render === 'function' ? c.render(r) : r[key];
        return h('td', { class: c.class || '' }, v instanceof Node ? v : (v == null ? '' : String(v)));
      }));
      if (onRow) tr.addEventListener('click', () => onRow(r, tr));
      body.append(tr);
    }
    t.append(body);
    return t;
  }

  function jsonBlock(obj) {
    return h('pre.json', {}, typeof obj === 'string' ? obj : fmt.json(obj));
  }

  function historyLink(requestId, label) {
    if (!requestId) return null;
    return h('a.link', { href: '#', onclick: (ev) => { ev.preventDefault(); showTab('history'); emit('history-open', requestId); } }, label || requestId);
  }

  window.PAW = { api, state, $, $$, h, esc, msg, clearMsgs, showError, modal, confirm, prompt, fmt, badge, on, emit, showTab, currentTab,
    readHash, updateHash, refreshEnvironments, currentEnv, setEnvironment, refreshToken, requireEnv, loadCatalog, setPatient, table, jsonBlock,
    historyLink, modules: {} };
})();
