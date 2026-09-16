/* Conformance tab: start runs, poll progress, show grouped results, list previous runs. */
(() => {
  'use strict';
  const P = window.PAW;
  const { $, $$, h, api, state, msg, clearMsgs, showError, badge, fmt } = P;

  let groups = [];
  let current = null;
  let timer = null;

  async function loadGroups() {
    if (groups.length) return;
    groups = await api.get('/conformance/groups');
    const box = $('#conf-groups');
    box.innerHTML = '';
    for (const g of groups) {
      box.append(h('label.check', { title: g.description }, h('input', { type: 'checkbox', name: 'group', value: g.key, checked: true }),
        `${g.label} `, h('span.muted', {}, `(${g.checks}${g.needsPatient ? ', needs patient' : ''})`)));
    }
    const checks = await api.get('/conformance/checks');
    const cl = $('#conf-checklist');
    cl.innerHTML = '';
    for (const c of checks) {
      cl.append(h('div.check-row', {}, badge(c.severity), ' ', h('b', {}, c.title), ' ', h('code.muted', {}, c.id), h('div.muted', {}, c.description), c.citation ? h('div.muted', {}, 'Source: ' + c.citation) : null));
    }
  }

  async function start(ev) {
    ev.preventDefault();
    const envId = P.requireEnv('#conf-messages');
    if (!envId) return;
    clearMsgs('#conf-messages');
    const selected = $$('#conf-groups input:checked').map(cb => cb.value);
    const patientId = $('#conf-patient').value.trim() || null;
    if (!patientId && groups.some(g => selected.includes(g.key) && g.needsPatient)) {
      msg('#conf-messages', 'warn', 'No patient id: member-level checks will be skipped. Open a member on the Patient tab or type an id.');
    }
    try {
      const run = await api.post('/conformance/runs', { environmentId: envId, patientId, groups: selected });
      await loadRuns();
      show(run.id);
    } catch (e) {
      showError('#conf-messages', e);
    }
  }

  async function loadRuns() {
    const runs = await api.get('/conformance/runs' + (state.envId ? '?environmentId=' + encodeURIComponent(state.envId) : ''));
    const body = $('#conf-runs tbody');
    body.innerHTML = '';
    if (!runs.length) body.append(h('tr', {}, h('td', { colspan: 6, class: 'muted' }, 'no runs yet')));
    for (const r of runs) {
      const c = r.counts || {};
      body.append(h('tr.selectable', { class: current && current.id === r.id ? 'selected' : '', onclick: () => show(r.id) },
        h('td', {}, fmt.ts(r.startedAt)), h('td', {}, r.environmentName), h('td', {}, r.patientId || ''), h('td', {}, badge(r.status, r.status === 'DONE' ? 'PASS' : r.status === 'RUNNING' ? 'INFO' : 'SKIP')),
        h('td', {}, `${r.completedChecks}/${r.totalChecks} · `, badge('FAIL ' + (c.FAIL || 0), 'FAIL'), ' ', badge('WARN ' + (c.WARN || 0), 'WARN'), ' ', badge('PASS ' + (c.PASS || 0), 'PASS')),
        h('td', {}, h('button.small', { onclick: async (ev) => { ev.stopPropagation(); if (await P.confirm('Delete run', 'Delete this run and its results?')) { await api.del('/conformance/runs/' + r.id); if (current && current.id === r.id) current = null; loadRuns(); } } }, 'delete'))));
    }
  }

  async function show(id) {
    if (timer) { clearInterval(timer); timer = null; }
    const ok = await refresh(id);
    if (ok && current && current.status === 'RUNNING') {
      $('#conf-busy').hidden = false;
      const t = setInterval(async () => {
        const alive = await refresh(id);
        if (t !== timer) { clearInterval(t); return; } // another run was selected meanwhile
        if (!alive || !current || current.id !== id || current.status !== 'RUNNING') {
          clearInterval(t); timer = null; $('#conf-busy').hidden = true; loadRuns().catch(() => {});
        }
      }, 1500);
      timer = t;
    } else {
      $('#conf-busy').hidden = true;
    }
  }

  /** Loads a run; false when it cannot be loaded (polling stops, the error is shown once). */
  async function refresh(id) {
    try {
      const run = await api.get('/conformance/runs/' + id);
      if (current && current.id !== id && timer) { return true; }
      current = run;
    } catch (e) { clearMsgs('#conf-messages'); showError('#conf-messages', e); return false; }
    render(current);
    return true;
  }

  function render(run) {
    $('#conf-run-title').textContent = `${run.environmentName} · ${fmt.ts(run.startedAt)} · ${run.status}` + (run.patientId ? ' · patient ' + run.patientId : '');
    $('#conf-report').hidden = false;
    $('#conf-report').href = api.url(`/conformance/runs/${run.id}/report`);
    $('#conf-json').hidden = false;
    $('#conf-json').href = api.url(`/conformance/runs/${run.id}`);
    $('#btn-conf-cancel').hidden = run.status !== 'RUNNING';
    $('#btn-conf-cancel').onclick = () => api.post(`/conformance/runs/${run.id}/cancel`);
    const prog = $('#conf-progress');
    prog.hidden = run.status !== 'RUNNING';
    prog.firstElementChild.style.width = (run.totalChecks ? Math.round(100 * run.completedChecks / run.totalChecks) : 0) + '%';
    const sum = $('#conf-summary');
    sum.innerHTML = '';
    for (const [k, v] of Object.entries(run.counts || {})) sum.append(badge(`${k} ${v}`, k));
    if (run.error) msg('#conf-messages', 'error', run.error);
    const filter = $('#conf-filter').value;
    const box = $('#conf-results');
    box.innerHTML = '';
    const byGroup = {};
    for (const r of run.results) (byGroup[r.group] = byGroup[r.group] || []).push(r);
    for (const g of groups) {
      const rs = (byGroup[g.key] || []).filter(r => !filter || r.status === filter);
      if (!rs.length) continue;
      box.append(h('h3', {}, g.label, ' ', h('span.muted', {}, `(${rs.length})`)));
      for (const r of rs) box.append(resultRow(r));
    }
    if (!run.results.length) box.append(h('p.muted', {}, run.status === 'RUNNING' ? 'waiting for the first results…' : 'no results'));
  }

  function resultRow(r) {
    const body = h('div.body', { hidden: r.status === 'PASS' });
    body.append(h('div', {}, r.message));
    if (r.details && r.details.length) body.append(h('ul', {}, r.details.map(d => h('li', {}, d))));
    if (r.requestIds && r.requestIds.length) body.append(h('div.muted', {}, 'evidence: ', r.requestIds.map(id => h('span', {}, P.historyLink(id, id), ' '))));
    body.append(h('div.muted', {}, `${r.severity} · ${r.durationMs} ms`));
    const head = h('div.head', { onclick: () => { body.hidden = !body.hidden; } }, badge(r.status), h('span.title', {}, r.title), h('span.id', {}, r.checkId));
    return h('div.result', {}, head, body);
  }

  P.modules.conformance = {
    init() {
      $('#conf-form').addEventListener('submit', start);
      $('#btn-conf-all').addEventListener('click', () => $$('#conf-groups input').forEach(cb => { cb.checked = true; }));
      $('#btn-conf-none').addEventListener('click', () => $$('#conf-groups input').forEach(cb => { cb.checked = false; }));
      $('#conf-filter').addEventListener('change', () => { if (current) render(current); });
      P.on('tab', (t) => { if (t === 'conformance') { loadGroups().then(loadRuns).catch(e => showError('#conf-messages', e)); if (state.patientId && !$('#conf-patient').value) $('#conf-patient').value = state.patientId; } });
      P.on('patient-changed', (p) => { if (p.id) $('#conf-patient').value = p.id; });
      P.on('env-changed', () => { if (P.currentTab() === 'conformance') loadRuns().catch(() => {}); });
    },
  };
})();
