/* Patient tab: overview cards, per-data-class tables with profile checks, advanced raw requests. */
(() => {
  'use strict';
  const P = window.PAW;
  const { $, $$, h, api, state, msg, clearMsgs, showError, badge, fmt } = P;

  let overview = null;
  let overviewEnvId = null;
  let activeClass = null;
  let loadSeq = 0;

  function checksBadge(c) {
    if (!c || !c.profile) return badge('no rules', 'SKIP');
    if (c.errors) return badge(`${c.errors} error${c.errors > 1 ? 's' : ''}`, 'FAIL');
    if (c.warnings) return badge(`${c.warnings} warn`, 'WARN');
    return badge('ok', 'PASS');
  }
  function issuesList(c) {
    if (!c || !c.issues || !c.issues.length) return null;
    return h('ul.issues', {}, c.issues.map(i => h('li', { class: i.severity }, `${i.severity}: ${i.path ? i.path + ' – ' : ''}${i.message}`)));
  }

  async function load(pid) {
    const envId = P.requireEnv('#patient-checks');
    if (!envId || !pid) return;
    $('#patient-empty').hidden = true;
    $('#patient-view').hidden = false;
    clearMsgs('#patient-checks');
    $('#patient-summary').innerHTML = '';
    $('#patient-summary').append(h('span.busy', {}, 'loading patient…'));
    $('#patient-classes').innerHTML = '';
    const seq = ++loadSeq;
    try {
      const o = await api.get(`/environments/${envId}/patients/${encodeURIComponent(pid)}/overview`);
      if (seq !== loadSeq) return; // a newer load superseded this one
      overview = o;
      overviewEnvId = envId;
      renderHead(overview);
      renderCards(overview);
      if (activeClass) selectClass(activeClass);
    } catch (e) {
      if (seq !== loadSeq) return;
      $('#patient-summary').innerHTML = '';
      showError('#patient-checks', e);
    }
  }

  function renderHead(o) {
    const p = o.patient;
    const s = $('#patient-summary');
    s.innerHTML = '';
    s.append(h('h2', {}, p.display, ' ', h('span.muted.mono', {}, 'Patient/' + p.id)),
      h('div', {}, p.birthDate ? 'born ' + p.birthDate + ' · ' : '', p.gender || '', p.lastUpdated ? ' · updated ' + fmt.ts(p.lastUpdated) : ''),
      h('div', {}, (p.identifiers || []).map(i => h('span', {}, badge(i.typeCode || 'id', 'MAY'), ' ', h('code', {}, i.value || ''), ' ', h('span.muted', {}, i.system || ''), '  '))),
      h('div', {}, 'Profiles: ', (p.profiles || []).length ? p.profiles.map(x => h('code', {}, x + ' ')) : h('span.muted', {}, 'none declared')),
      h('div', {}, 'Profile check: ', checksBadge(o.patientChecks), ' ', h('span.muted', {}, o.patientChecks && o.patientChecks.profile ? o.patientChecks.profileName : '')));
    const iss = issuesList(o.patientChecks);
    if (iss) s.append(h('details', {}, h('summary', {}, 'Profile issues'), iss));
    $('#patient-json').textContent = fmt.json(o.patientResource);
    $('#patient-json').hidden = true;
    if (state.patientId !== p.id || !state.patient) P.setPatient(p.id, p);
  }

  function renderCards(o) {
    const box = $('#patient-classes');
    box.innerHTML = '';
    for (const c of o.dataClasses) {
      const err = c.error != null;
      const count = err ? '–' : (c.count == null ? '?' : String(c.count) + (c.more ? '+' : '') + (c.total != null && c.total !== c.count ? ' / ' + c.total : ''));
      const card = h('div.card', { class: (err ? 'err ' : '') + (activeClass === c.key ? 'active' : ''), title: err ? c.error : c.url,
        onclick: () => selectClass(c.key) },
        h('div.count', {}, count), h('div.label', {}, c.label), err ? h('div.muted', {}, 'HTTP ' + (c.status || '?')) : h('div.muted', {}, c.durationMs + ' ms'));
      card.dataset.key = c.key;
      box.append(card);
    }
  }

  function dataClass(key) {
    return (overview ? overview.dataClasses : []).find(d => d.key === key);
  }

  async function selectClass(key) {
    activeClass = key;
    $$('#patient-classes .card').forEach(c => c.classList.toggle('active', c.dataset.key === key));
    const dc = dataClass(key);
    $('#patient-class-title').textContent = dc ? dc.label : key;
    const filters = $('#patient-class-filters');
    filters.innerHTML = '';
    clearMsgs('#patient-class-messages');
    $('#patient-table-wrap').innerHTML = '';
    if (key === 'priorAuth') { P.showTab('priorauth'); return; }
    if (key === 'claims') {
      const type = h('select', { name: 'type' }, h('option', { value: '' }, 'all types'), ['institutional', 'professional', 'pharmacy', 'oral', 'vision'].map(t => h('option', { value: t }, t)));
      const since = h('input', { type: 'date', name: 'since' });
      filters.append(h('label.field.inline', {}, h('span', {}, 'type'), type), h('label.field.inline', {}, h('span', {}, '_lastUpdated ≥'), since),
        h('button.small', { onclick: () => loadClass(key, { type: type.value, since: since.value }) }, 'Apply'));
    } else if (key !== 'coverage') {
      const extra = h('input', { placeholder: 'extra params, e.g. category=vital-signs&date=ge2024-01-01', size: 44 });
      filters.append(extra, h('button.small', { onclick: () => loadClass(key, { extra: extra.value }) }, 'Apply'));
    }
    await loadClass(key, {});
  }

  async function loadClass(key, opts) {
    const envId = state.envId;
    const pid = state.patientId;
    const busy = $('#patient-busy');
    busy.hidden = false;
    clearMsgs('#patient-class-messages');
    try {
      let list;
      if (key === 'coverage') {
        list = await api.get(`/environments/${envId}/patients/${encodeURIComponent(pid)}/coverage`);
      } else if (key === 'claims') {
        const q = new URLSearchParams();
        if (opts.type) q.set('type', opts.type);
        if (opts.since) q.set('since', opts.since);
        list = await api.get(`/environments/${envId}/patients/${encodeURIComponent(pid)}/claims?` + q);
      } else {
        const dc = dataClass(key);
        const q = new URLSearchParams(opts.extra || '');
        list = await api.get(`/environments/${envId}/patients/${encodeURIComponent(pid)}/clinical/${dc ? dc.resourceType : key}?` + q);
      }
      renderList(list);
    } catch (e) {
      showError('#patient-class-messages', e);
    } finally {
      busy.hidden = true;
    }
  }

  function renderList(list) {
    const wrap = $('#patient-table-wrap');
    wrap.innerHTML = '';
    (list.warnings || []).forEach(w => msg('#patient-class-messages', 'warn', w));
    const info = h('div.muted', {}, `${list.rows.length} ${list.resourceType} resource(s)` + (list.total != null ? ` (server total ${list.total})` : '') + ` in ${list.pages} page(s)` + (list.truncated ? ', truncated' : '') + ' · ',
      (list.urls || []).map(u => h('code', {}, u + ' ')), ' ', (list.requestIds || []).map(id => P.historyLink(id, '↗')));
    wrap.append(info);
    wrap.append(rowsTable(list.rows));
    if (list.included && list.included.length) {
      wrap.append(h('h3', {}, 'Included resources'), rowsTable(list.included));
    }
  }

  function rowsTable(rows) {
    const cols = [];
    for (const r of rows) for (const k of Object.keys(r.columns || {})) if (!cols.includes(k)) cols.push(k);
    const t = h('table.grid-table');
    t.append(h('thead', {}, h('tr', {}, h('th', {}, 'id'), cols.map(c => h('th', {}, c)), h('th', {}, 'profile check'), h('th', {}, 'updated'), h('th', {}))));
    const body = h('tbody');
    if (!rows.length) body.append(h('tr', {}, h('td', { colspan: cols.length + 4, class: 'muted' }, 'no resources')));
    for (const r of rows) {
      const tr = h('tr', {}, h('td', {}, h('code', {}, r.id || '')), cols.map(c => h('td', {}, r.columns[c] == null ? '' : String(r.columns[c]))),
        h('td', {}, checksBadge(r.checks)), h('td', {}, fmt.ts(r.lastUpdated)),
        h('td', {}, h('button.small', { onclick: () => toggleDetail(tr, r, cols.length + 4) }, 'details')));
      body.append(tr);
    }
    t.append(body);
    return h('div.table-wrap', {}, t);
  }

  function toggleDetail(tr, r, span) {
    const next = tr.nextElementSibling;
    if (next && next.classList.contains('detail-row')) { next.remove(); return; }
    const cell = h('td', { colspan: span });
    if (r.checks && r.checks.profile) {
      cell.append(h('div', {}, 'Checked against ', h('code', {}, r.checks.profile), r.checks.declared ? ' (declared)' : ' (not declared in meta.profile)'));
      const iss = issuesList(r.checks);
      if (iss) cell.append(iss); else cell.append(h('div.muted', {}, 'no issues'));
    } else if (r.checks && r.checks.issues && r.checks.issues.length) {
      cell.append(h('div.muted', {}, r.checks.issues[0].message));
    }
    if (r.profiles && r.profiles.length) cell.append(h('div', {}, 'meta.profile: ', r.profiles.map(p => h('code', {}, p + ' '))));
    const fullBox = h('div');
    cell.append(h('div.toolbar', {}, h('button.small', { onclick: () => fullValidate(r, fullBox) }, 'Full HL7 validation'),
      h('span.muted', {}, 'HAPI validator; profiles need the IG packages in the packages folder')), fullBox);
    cell.append(P.jsonBlock(r.resource));
    tr.after(h('tr.detail-row', {}, cell));
  }

  async function fullValidate(r, box) {
    box.innerHTML = '';
    box.append(h('span.busy', {}, 'validating…'));
    try {
      const profile = r.checks && r.checks.profile ? r.checks.profile : (r.profiles && r.profiles[0]) || '';
      const res = await api.post('/validation', { resource: r.resource, profile: profile || null });
      box.innerHTML = '';
      box.append(h('div', {}, res.valid ? badge('valid', 'PASS') : badge(`${res.errors} error(s)`, 'FAIL'), ` ${res.warnings} warning(s), ${res.infos} info · profile ${res.profile || '(base)'} · packages: ${(res.packages || []).join(', ') || 'none'}`));
      if (res.issues.length) box.append(h('ul.issues', {}, res.issues.map(i => h('li', { class: i.severity === 'error' || i.severity === 'fatal' ? 'error' : i.severity }, `${i.severity}: ${i.location || ''} – ${i.message}`))));
    } catch (e) {
      box.innerHTML = '';
      showError(box, e);
    }
  }

  async function rawRequest(ev) {
    ev.preventDefault();
    const envId = P.requireEnv('#raw-messages');
    if (!envId) return;
    clearMsgs('#raw-messages');
    const type = $('#raw-type').value;
    let query = $('#raw-query').value.trim();
    const auth = $('#raw-form').elements.authenticated.checked;
    let path;
    if (query.startsWith('/')) {
      path = `/environments/${envId}/fhir/${type}/${encodeURIComponent(query.slice(1))}?authenticated=${auth}`;
    } else if (query.startsWith('http')) {
      path = `/environments/${envId}/fhir/page?url=${encodeURIComponent(query)}&authenticated=${auth}`;
    } else {
      const spec = state.resources && state.resources[type];
      const hasPatientParam = !!(spec && spec.searchParams.some(p => p.name === 'patient'));
      if (hasPatientParam && state.patientId && !query.includes('patient=') && !query.includes('_id=')) {
        query = 'patient=' + encodeURIComponent(state.patientId) + (query ? '&' + query : '');
      } else if (type === 'Patient' && !query && state.patientId) {
        query = '_id=' + encodeURIComponent(state.patientId);
      }
      const params = new URLSearchParams(query);
      params.set('authenticated', String(auth));
      path = `/environments/${envId}/fhir/${type}?` + params;
    }
    try {
      const r = await api.get(path);
      (r.warnings || []).forEach(w => msg('#raw-messages', 'warn', w));
      $('#raw-meta').innerHTML = '';
      $('#raw-meta').append(`HTTP ${r.status} · ${r.durationMs} ms · ${r.contentType || ''} · `, h('code', {}, r.url), ' ', P.historyLink(r.requestId, 'history ↗'));
      if (r.body && r.body.resourceType === 'Bundle') {
        const next = (r.body.link || []).find(l => l.relation === 'next');
        if (next) $('#raw-meta').append(' ', h('button.small', { onclick: () => { $('#raw-query').value = next.url; rawRequest(new Event('submit')); } }, 'next page'));
      }
      $('#raw-json').textContent = r.body ? fmt.json(r.body) : (r.text || '');
    } catch (e) {
      showError('#raw-messages', e);
    }
  }

  async function fillTypes() {
    const resources = await P.loadCatalog();
    const sel = $('#raw-type');
    if (sel.options.length) return;
    for (const t of Object.keys(resources)) sel.append(h('option', { value: t }, t));
    sel.value = 'ExplanationOfBenefit';
  }

  P.modules.patient = {
    init() {
      $('#patient-open-form').addEventListener('submit', (ev) => { ev.preventDefault(); const pid = ev.target.elements.pid.value.trim(); if (pid) { P.setPatient(pid, null); load(pid); } });
      $('#btn-patient-refresh').addEventListener('click', () => load(state.patientId));
      $('#btn-patient-priorauth').addEventListener('click', () => P.showTab('priorauth'));
      $('#btn-patient-conformance').addEventListener('click', () => { $('#conf-patient').value = state.patientId || ''; P.showTab('conformance'); });
      $('#btn-patient-json').addEventListener('click', () => { const j = $('#patient-json'); j.hidden = !j.hidden; });
      $('#raw-form').addEventListener('submit', rawRequest);
      P.on('tab', (t) => {
        if (t !== 'patient') return;
        fillTypes().catch(console.error);
        if (state.patientId && (!overview || overview.patient.id !== state.patientId || overviewEnvId !== state.envId)) load(state.patientId);
        else if (!state.patientId) { $('#patient-empty').hidden = false; $('#patient-view').hidden = true; }
      });
      P.on('env-changed', () => { if (overviewEnvId !== state.envId) { overview = null; if (P.currentTab() === 'patient' && state.patientId) load(state.patientId); } });
    },
    reload: () => load(state.patientId),
  };
})();
