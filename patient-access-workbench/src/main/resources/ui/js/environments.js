/* Environments tab: list, editor form, connection test, tokens, SMART login. */
(() => {
  'use strict';
  const P = window.PAW;
  const { $, $$, h, api, state, msg, clearMsgs, showError, badge, fmt } = P;

  let editing = null; // EnvironmentView being edited (null = new)

  function form() { return $('#env-form'); }
  function field(name) { return form().elements[name]; }

  function parseKv(text) {
    const out = {};
    (text || '').split('\n').forEach(line => {
      const i = line.indexOf('=');
      if (i > 0) out[line.slice(0, i).trim()] = line.slice(i + 1).trim();
    });
    return out;
  }
  function kvText(obj) {
    return Object.entries(obj || {}).map(([k, v]) => k + '=' + v).join('\n');
  }

  function renderList() {
    const body = $('#env-table tbody');
    body.innerHTML = '';
    if (!state.environments.length) {
      body.append(h('tr', {}, h('td', { colspan: 5, class: 'muted' }, 'No environments yet. Create one, or add the demo environment to try the workbench.')));
    }
    for (const e of state.environments) {
      const tr = h('tr.selectable', { class: editing && editing.id === e.id ? 'selected' : '' },
        h('td', {}, e.name, e.enabled ? '' : ' (disabled)'),
        h('td', {}, badge(e.tier, 'tier-' + e.tier)),
        h('td', {}, e.auth.mode),
        h('td.url', {}, e.fhirBaseUrl),
        h('td.actions', {}, h('button.small', { onclick: (ev) => { ev.stopPropagation(); P.setEnvironment(e.id); } }, 'Use')));
      tr.addEventListener('click', () => edit(e));
      body.append(tr);
    }
  }

  function applyModeVisibility() {
    const mode = field('auth.mode').value;
    const show = (cls, on) => $$('.' + cls, form()).forEach(el => { el.hidden = !on; });
    show('auth-not-none', mode !== 'NONE' && mode !== 'STATIC_TOKEN');
    show('auth-code', mode === 'SMART_AUTHORIZATION_CODE');
    show('auth-client', mode === 'CLIENT_CREDENTIALS' || mode === 'BACKEND_SERVICES' || mode === 'SMART_AUTHORIZATION_CODE');
    show('auth-secret', mode === 'CLIENT_CREDENTIALS' || mode === 'SMART_AUTHORIZATION_CODE');
    show('auth-static', mode === 'STATIC_TOKEN');
    show('auth-jwk', mode === 'BACKEND_SERVICES');
    field('fhir.trustAllCertificates').disabled = field('tier').value === 'PROD';
    if (field('tier').value === 'PROD') field('fhir.trustAllCertificates').checked = false;
  }

  function secretState(name, view) {
    const el = $(`em.secret-state[data-secret="${name}"]`, form());
    if (!el) return;
    el.innerHTML = '';
    el.textContent = view && view.set ? `(set ${view.hint || ''}) ` : '(not set)';
    el.style.color = view && view.set ? 'var(--ok)' : 'var(--muted)';
    if (view && view.set) {
      el.append(h('label', { style: 'color:var(--muted);font-weight:normal;margin-left:6px' }, h('input', { type: 'checkbox', name: 'clear:' + name }), ' clear'));
    }
  }

  function headerRow(hd = {}) {
    const tr = h('tr', {},
      h('td', {}, h('input', { name: 'h-name', value: hd.name || '', placeholder: 'x-api-key' })),
      h('td', {}, h('input', { name: 'h-value', type: hd.secret ? 'password' : 'text', value: hd.secret ? '' : (hd.value || ''),
        placeholder: hd.secret && hd.secretValue && hd.secretValue.set ? 'set ' + (hd.secretValue.hint || '') + ' — blank keeps it' : '' })),
      h('td', {}, h('input', { type: 'checkbox', name: 'h-secret', checked: !!hd.secret, onchange: (ev) => { tr.querySelector('[name=h-value]').type = ev.target.checked ? 'password' : 'text'; } })),
      h('td', {}, h('button.small', { type: 'button', onclick: () => tr.remove() }, 'remove')));
    $('#env-headers tbody').append(tr);
  }
  function idSystemRow(s = {}) {
    const tr = h('tr', {},
      h('td', {}, h('input', { name: 'i-label', value: s.label || '', placeholder: 'Member ID' })),
      h('td', {}, h('input', { name: 'i-system', value: s.system || '', placeholder: 'https://payer.example.com/fhir/memberid' })),
      h('td', {}, h('input', { name: 'i-type', value: s.typeCode || '', placeholder: 'MB', size: 4 })),
      h('td', {}, h('input', { type: 'checkbox', name: 'i-default', checked: s.defaultForMemberId !== false })),
      h('td', {}, h('button.small', { type: 'button', onclick: () => tr.remove() }, 'remove')));
    $('#env-idsystems tbody').append(tr);
  }

  function edit(e) {
    editing = e;
    const f = form();
    f.hidden = false;
    $('#env-empty').hidden = true;
    clearMsgs('#env-form-messages');
    $('#env-form-title').textContent = e ? `Environment: ${e.name}` : 'New environment';
    $('#btn-env-delete').hidden = !e;
    $('#btn-env-duplicate').hidden = !e;
    $('#btn-env-test').hidden = !e;
    f.reset();
    $('#env-headers tbody').innerHTML = '';
    $('#env-idsystems tbody').innerHTML = '';
    const v = e || { auth: { mode: 'CLIENT_CREDENTIALS', discoverEndpoints: true, usePkce: true, clientAuthMethod: 'CLIENT_SECRET_BASIC', signingAlgorithm: 'RS384' },
      fhir: { sendCountParam: true }, headers: [], identifierSystems: [], implementationGuides: ['c4bb', 'pdex', 'uscore', 'usdf'], tier: 'UAT', vendor: 'Onyx SAFHIR', enabled: true };
    field('id').value = v.id || '';
    field('version').value = v.version == null ? '' : v.version;
    field('name').value = v.name || '';
    field('vendor').value = v.vendor || '';
    field('tier').value = v.tier || 'UAT';
    field('fhirBaseUrl').value = v.fhirBaseUrl || '';
    field('notes').value = v.notes || '';
    field('enabled').checked = v.enabled !== false;
    ['c4bb', 'pdex', 'usdf', 'plannet'].forEach(k => { field('ig.' + k).value = (v.igBaseUrls && v.igBaseUrls[k]) || ''; });
    const a = v.auth || {};
    field('auth.mode').value = a.mode || 'NONE';
    field('auth.discoverEndpoints').checked = a.discoverEndpoints !== false;
    field('auth.tokenEndpoint').value = a.tokenEndpoint || '';
    field('auth.authorizationEndpoint').value = a.authorizationEndpoint || '';
    field('auth.clientId').value = a.clientId || '';
    field('auth.clientAuthMethod').value = a.clientAuthMethod || 'CLIENT_SECRET_BASIC';
    field('auth.scopes').value = a.scopes || '';
    field('auth.audience').value = a.audience || '';
    field('auth.signingAlgorithm').value = a.signingAlgorithm || 'RS384';
    field('auth.redirectUri').value = a.redirectUri || '';
    field('auth.usePkce').checked = a.usePkce !== false;
    field('auth.extraTokenParams').value = kvText(a.extraTokenParams);
    field('auth.extraAuthorizeParams').value = kvText(a.extraAuthorizeParams);
    ['auth.clientSecret', 'auth.staticToken', 'auth.privateKeyJwk'].forEach(n => { field(n).value = ''; });
    secretState('auth.clientSecret', a.clientSecret);
    secretState('auth.staticToken', a.staticToken);
    secretState('auth.privateKeyJwk', a.privateKeyJwk);
    (v.headers || []).forEach(headerRow);
    (v.identifierSystems || []).forEach(idSystemRow);
    const fo = v.fhir || {};
    field('fhir.pageSize').value = fo.pageSize || '';
    field('fhir.maxPages').value = fo.maxPages || '';
    field('fhir.acceptHeader').value = fo.acceptHeader || '';
    field('fhir.connectTimeoutMs').value = fo.connectTimeoutMs || '';
    field('fhir.readTimeoutMs').value = fo.readTimeoutMs || '';
    field('fhir.sendCountParam').checked = fo.sendCountParam !== false;
    field('fhir.preferHandlingLenient').checked = !!fo.preferHandlingLenient;
    field('fhir.allowNextLinkHostMismatch').checked = !!fo.allowNextLinkHostMismatch;
    field('fhir.trustAllCertificates').checked = !!fo.trustAllCertificates;
    $$('input[name=ig]', f).forEach(cb => { cb.checked = (v.implementationGuides || []).includes(cb.value); });
    applyModeVisibility();
    renderList();
    renderTokenPanel();
    $('#env-test-result').innerHTML = '';
  }

  function collect() {
    const f = form();
    const num = (n) => field(n).value === '' ? null : Number(field(n).value);
    // blank keeps the stored secret; the "clear" checkbox next to the field sends "" so the API removes it
    const secret = (n) => { const clear = form().querySelector(`input[name="clear:${n}"]`); if (clear && clear.checked) return ''; return field(n).value === '' ? null : field(n).value; };
    const headers = $$('#env-headers tbody tr').map(tr => ({
      name: tr.querySelector('[name=h-name]').value.trim(),
      value: tr.querySelector('[name=h-value]').value === '' && tr.querySelector('[name=h-secret]').checked ? null : tr.querySelector('[name=h-value]').value,
      secret: tr.querySelector('[name=h-secret]').checked,
    })).filter(x => x.name);
    const identifierSystems = $$('#env-idsystems tbody tr').map(tr => ({
      label: tr.querySelector('[name=i-label]').value.trim(),
      system: tr.querySelector('[name=i-system]').value.trim(),
      typeCode: tr.querySelector('[name=i-type]').value.trim() || null,
      defaultForMemberId: tr.querySelector('[name=i-default]').checked,
    })).filter(x => x.system);
    return {
      name: field('name').value.trim(),
      vendor: field('vendor').value.trim(),
      tier: field('tier').value,
      fhirBaseUrl: field('fhirBaseUrl').value.trim(),
      notes: field('notes').value,
      enabled: field('enabled').checked,
      auth: {
        mode: field('auth.mode').value,
        discoverEndpoints: field('auth.discoverEndpoints').checked,
        tokenEndpoint: field('auth.tokenEndpoint').value.trim() || '',
        authorizationEndpoint: field('auth.authorizationEndpoint').value.trim() || '',
        clientId: field('auth.clientId').value.trim() || '',
        clientSecret: secret('auth.clientSecret'),
        clientAuthMethod: field('auth.clientAuthMethod').value,
        scopes: field('auth.scopes').value.trim() || '',
        audience: field('auth.audience').value.trim() || '',
        staticToken: secret('auth.staticToken'),
        privateKeyJwk: secret('auth.privateKeyJwk'),
        signingAlgorithm: field('auth.signingAlgorithm').value,
        redirectUri: field('auth.redirectUri').value.trim() || '',
        usePkce: field('auth.usePkce').checked,
        extraTokenParams: parseKv(field('auth.extraTokenParams').value),
        extraAuthorizeParams: parseKv(field('auth.extraAuthorizeParams').value),
      },
      headers,
      identifierSystems,
      igBaseUrls: Object.fromEntries(['c4bb', 'pdex', 'usdf', 'plannet'].map(k => [k, field('ig.' + k).value.trim()])),
      fhir: {
        pageSize: num('fhir.pageSize'), maxPages: num('fhir.maxPages'),
        acceptHeader: field('fhir.acceptHeader').value.trim() || 'application/fhir+json',
        sendCountParam: field('fhir.sendCountParam').checked,
        trustAllCertificates: field('fhir.trustAllCertificates').checked,
        allowNextLinkHostMismatch: field('fhir.allowNextLinkHostMismatch').checked,
        connectTimeoutMs: num('fhir.connectTimeoutMs'), readTimeoutMs: num('fhir.readTimeoutMs'),
        preferHandlingLenient: field('fhir.preferHandlingLenient').checked,
      },
      implementationGuides: $$('input[name=ig]:checked', f).map(cb => cb.value),
      version: field('version').value === '' ? null : Number(field('version').value),
    };
  }

  async function save(ev) {
    ev.preventDefault();
    clearMsgs('#env-form-messages');
    const input = collect();
    try {
      const saved = editing ? await api.put(`/environments/${editing.id}`, input) : await api.post('/environments', input);
      await P.refreshEnvironments(editing ? state.envId : (state.envId || saved.id));
      edit(state.environments.find(e => e.id === saved.id));
      msg('#env-form-messages', 'ok', `Saved "${saved.name}" (version ${saved.version}).`);
    } catch (e) {
      showError('#env-form-messages', e);
      (e.details || []).forEach(d => { const el = field(d.field) ; if (el) el.classList.add('invalid'); });
    }
  }

  async function test() {
    if (!editing) return;
    const box = $('#env-test-result');
    box.innerHTML = '';
    msg(box, 'info', 'Testing connection…');
    try {
      const r = await api.post(`/environments/${editing.id}/test`);
      box.innerHTML = '';
      msg(box, r.ok ? 'ok' : 'warn', r.ok ? 'Connection OK' : 'Connection problems', { html: '' });
      for (const s of r.steps) {
        const kind = s.status === 'PASS' ? 'ok' : s.status === 'FAIL' ? 'error' : s.status === 'WARN' ? 'warn' : 'info';
        const m = msg(box, kind, `${s.name}: ${s.message}` + (s.httpStatus ? ` (HTTP ${s.httpStatus}${s.durationMs != null ? ', ' + s.durationMs + ' ms' : ''})` : ''));
        const details = Object.entries(s.details || {}).filter(([, v]) => v && String(v).length && String(v) !== '[]');
        if (details.length) m.append(h('ul', {}, details.map(([k, v]) => h('li', {}, k + ': ' + (Array.isArray(v) ? v.join(', ') : v)))));
        if (s.requestId) m.append(h('div', {}, P.historyLink(s.requestId, 'request ' + s.requestId)));
      }
      await P.refreshToken();
      renderTokenPanel();
    } catch (e) {
      box.innerHTML = '';
      showError(box, e);
    }
  }

  function renderTokenPanel() {
    const panel = $('#env-token-panel');
    if (!editing) { panel.hidden = true; return; }
    panel.hidden = false;
    const kv = $('#env-token-status');
    kv.innerHTML = '';
    api.get(`/environments/${editing.id}/auth/status`).then(t => {
      const rows = [['Mode', t.mode], ['Token', t.present ? (t.expired ? 'expired' : 'present') + (t.tokenHint ? ' ' + t.tokenHint : '') : 'none'],
        ['Obtained', fmt.ts(t.obtainedAt)], ['Expires', fmt.ts(t.expiresAt)], ['Scope', t.scope || ''], ['Patient (from token)', t.patient || ''],
        ['Refresh token', t.refreshable ? 'yes' : 'no'], ['Source', t.source || '']];
      rows.forEach(([k, v]) => { if (v) kv.append(h('div', {}, k), h('div', {}, v)); });
      if (t.context && t.context.access_token_claims) {
        kv.append(h('div', {}, 'Access token claims'), h('div.mono', {}, JSON.stringify(t.context.access_token_claims)));
      }
      $('#btn-token-smart').hidden = t.mode !== 'SMART_AUTHORIZATION_CODE';
      $('#btn-token-obtain').hidden = t.mode === 'NONE' || t.mode === 'STATIC_TOKEN';
    }).catch(e => { kv.append(h('div', {}, 'Error'), h('div', {}, e.message)); });
    $('#env-endpoints').innerHTML = '';
  }

  async function obtainToken() {
    const box = $('#env-test-result');
    box.innerHTML = '';
    try {
      const t = await api.post(`/environments/${editing.id}/auth/token`);
      msg(box, 'ok', `Token obtained (${t.source}), expires ${fmt.ts(t.expiresAt)}.`);
    } catch (e) { showError(box, e); }
    await P.refreshToken();
    renderTokenPanel();
  }

  async function smartLogin() {
    const box = $('#env-test-result');
    box.innerHTML = '';
    try {
      const r = await api.post(`/environments/${editing.id}/auth/smart/start`);
      const m = msg(box, 'info', 'Sign in at the vendor\'s authorization server (opens in a new tab): ');
      m.append(h('a.link', { href: r.authorizeUrl, target: '_blank', rel: 'noopener' }, 'open the login page'), h('div.muted', {}, `Redirect URI registered with the vendor must be: ${r.redirectUri}`));
      window.open(r.authorizeUrl, '_blank', 'noopener');
      msg(box, 'info', 'After signing in, the callback page shows the outcome; the token panel here refreshes when you return to this tab (or press "Refresh status").');
    } catch (e) { showError(box, e); }
  }

  async function pasteToken() {
    const body = h('div', {},
      h('label.field', {}, h('span', {}, 'Access token'), h('textarea', { name: 'tok', rows: 4 })),
      h('label.field', {}, h('span', {}, 'Expires in seconds (default 3600)'), h('input', { name: 'exp', type: 'number' })),
      h('label.field', {}, h('span', {}, 'Patient id the token is for (optional)'), h('input', { name: 'pat' })));
    const ok = await P.modal('Paste a bearer token', body, { okLabel: 'Store' });
    if (!ok) return;
    const box = $('#env-test-result');
    box.innerHTML = '';
    try {
      await api.post(`/environments/${editing.id}/auth/token/manual`, { accessToken: body.querySelector('[name=tok]').value.trim(),
        expiresInSeconds: body.querySelector('[name=exp]').value ? Number(body.querySelector('[name=exp]').value) : null,
        patient: body.querySelector('[name=pat]').value.trim() || null });
      msg(box, 'ok', 'Token stored (encrypted).');
    } catch (e) { showError(box, e); }
    await P.refreshToken();
    renderTokenPanel();
  }

  async function forgetToken() {
    await api.del(`/environments/${editing.id}/auth/token`);
    await P.refreshToken();
    renderTokenPanel();
  }

  async function showEndpoints() {
    const kv = $('#env-endpoints');
    kv.innerHTML = '';
    try {
      const e = await api.get(`/environments/${editing.id}/auth/endpoints?refresh=true`);
      const rows = [['Source', e.source], ['Authorization endpoint', e.authorizationEndpoint], ['Token endpoint', e.tokenEndpoint], ['Issuer', e.issuer],
        ['JWKS', e.jwksUri], ['Registration', e.registrationEndpoint], ['Capabilities', (e.capabilities || []).join(', ')],
        ['PKCE methods', (e.codeChallengeMethods || []).join(', ')], ['Grant types', (e.grantTypesSupported || []).join(', ')],
        ['Scopes supported', (e.scopesSupported || []).join(' ')]];
      rows.forEach(([k, v]) => { if (v) kv.append(h('div', {}, k), h('div.mono', {}, v)); });
      const r = await api.get(`/environments/${editing.id}/auth/smart/redirect-uri`);
      kv.append(h('div', {}, 'Redirect URI (register with vendor)'), h('div.mono', {}, r.redirectUri));
    } catch (e) { kv.append(h('div', {}, 'Error'), h('div', {}, e.message)); }
  }

  async function remove() {
    if (!editing) return;
    if (!(await P.confirm('Delete environment', `Delete "${editing.name}" and its stored secrets and tokens?`))) return;
    await api.del(`/environments/${editing.id}`);
    editing = null;
    form().hidden = true;
    $('#env-empty').hidden = false;
    $('#env-token-panel').hidden = true;
    await P.refreshEnvironments(state.envId === (editing && editing.id) ? null : state.envId);
    renderList();
  }

  async function duplicate() {
    const name = await P.prompt('Duplicate environment', 'Name of the copy', editing.name + ' (copy)');
    if (name == null) return;
    try {
      const copy = await api.post(`/environments/${editing.id}/duplicate?name=${encodeURIComponent(name)}`);
      await P.refreshEnvironments(state.envId);
      edit(state.environments.find(e => e.id === copy.id));
    } catch (e) { showError('#env-form-messages', e); }
  }

  /** Fills the form from the documented Onyx SAFHIR layout: one host per tier, one base per IG, /v1/authorize + /v1/token. */
  async function onyxPreset() {
    const host = await P.prompt('Onyx SAFHIR preset', 'Tenant host of this tier (e.g. https://api-<tenant>-uat.safhir.io)', field('fhirBaseUrl').value.replace(/\/v1.*$/, '') || 'https://api-');
    if (host == null) return;
    const root = host.trim().replace(/\/+$/, '');
    if (!/^https?:\/\//.test(root)) { msg('#env-form-messages', 'error', 'Enter the tenant host including https://'); return; }
    field('fhirBaseUrl').value = root + '/v1/api/pdex';
    field('ig.c4bb').value = root + '/v1/api/carin-bb';
    field('ig.pdex').value = root + '/v1/api/pdex';
    field('ig.usdf').value = root + '/v1/api/formulary';
    field('ig.plannet').value = root + '/v1/api/provider-directory';
    if (!field('vendor').value) field('vendor').value = 'Onyx SAFHIR';
    if (/-prd\./.test(root)) field('tier').value = 'PROD'; else if (/-uat\./.test(root)) field('tier').value = 'UAT';
    field('auth.mode').value = 'SMART_AUTHORIZATION_CODE';
    field('auth.discoverEndpoints').checked = false;
    field('auth.authorizationEndpoint').value = root + '/v1/authorize';
    field('auth.tokenEndpoint').value = root + '/v1/token';
    field('auth.audience').value = root + '/v1';
    field('auth.clientAuthMethod').value = 'CLIENT_SECRET_POST';
    if (!field('auth.scopes').value) field('auth.scopes').value = 'launch/patient openid fhirUser offline_access patient/*.read';
    field('fhir.allowNextLinkHostMismatch').checked = true;
    if (!field('fhir.pageSize').value) field('fhir.pageSize').value = '50';
    applyModeVisibility();
    clearMsgs('#env-form-messages');
    msg('#env-form-messages', 'info', 'Preset applied from the public SAFHIR documentation. Confirm the token endpoint, client id/secret, redirect URI and scopes against the Application Credentials in the Onyx developer portal, then Save and Test connection. See docs/onyx-safhir.md.');
  }

  async function addDemo() {
    clearMsgs('#global-messages');
    try {
      const env = await api.post('/demo/environment');
      await P.refreshEnvironments(env.id);
      edit(state.environments.find(e => e.id === env.id));
      msg('#env-form-messages', 'ok', 'Demo environment ready: it points at the workbench\'s built-in sample Patient Access API (client credentials demo-client / demo-secret). Try "Test connection", then search a demo member.');
    } catch (e) {
      msg('#global-messages', 'error', 'The demo server is not enabled on this workbench (paw.demo.enabled=false). ' + e.message);
    }
  }

  P.modules.environments = {
    init() {
      form().addEventListener('submit', save);
      field('auth.mode').addEventListener('change', applyModeVisibility);
      field('tier').addEventListener('change', applyModeVisibility);
      $('#btn-env-new').addEventListener('click', () => edit(null));
      $('#btn-env-refresh').addEventListener('click', () => P.refreshEnvironments(state.envId));
      $('#btn-env-demo').addEventListener('click', addDemo);
      $('#btn-env-onyx').addEventListener('click', onyxPreset);
      const guarded = (fn) => () => Promise.resolve().then(fn).catch(e => { clearMsgs('#env-form-messages'); showError('#env-form-messages', e); });
      $('#btn-env-test').addEventListener('click', guarded(test));
      $('#btn-env-delete').addEventListener('click', guarded(remove));
      $('#btn-env-duplicate').addEventListener('click', guarded(duplicate));
      $('#btn-header-add').addEventListener('click', () => headerRow());
      $('#btn-idsystem-add').addEventListener('click', () => idSystemRow());
      $('#btn-token-obtain').addEventListener('click', guarded(obtainToken));
      $('#btn-token-smart').addEventListener('click', guarded(smartLogin));
      $('#btn-token-paste').addEventListener('click', guarded(pasteToken));
      $('#btn-token-forget').addEventListener('click', guarded(forgetToken));
      $('#btn-env-endpoints').addEventListener('click', showEndpoints);
      $('#btn-token-status').addEventListener('click', () => { P.refreshToken().then(renderTokenPanel).catch(console.error); });
      document.addEventListener('visibilitychange', () => { if (!document.hidden && editing && !$('#env-token-panel').hidden) P.refreshToken().then(renderTokenPanel).catch(() => {}); });
      P.on('env-changed', (env) => { renderList(); if (env && (!editing || editing.id !== env.id) && !form().hidden) edit(env); else if (env && form().hidden) edit(env); });
      P.on('tab', (t) => { if (t === 'environments') { renderList(); if (editing) renderTokenPanel(); } });
    },
  };
})();
