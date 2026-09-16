/* Prior auth tab: CMS-0057-F view of the member's PDex PriorAuthorization EOBs. */
(() => {
  'use strict';
  const P = window.PAW;
  const { $, h, api, state, msg, clearMsgs, showError, badge, fmt } = P;

  let loadedFor = null;

  async function load() {
    const envId = P.requireEnv('#pa-messages');
    clearMsgs('#pa-messages');
    $('#pa-list').innerHTML = '';
    $('#pa-checklist').hidden = true;
    if (!envId) return;
    if (!state.patientId) { msg('#pa-messages', 'info', 'Open a member first (Search tab).'); return; }
    $('#pa-title').textContent = `Prior authorizations of ${state.patient ? state.patient.display : 'Patient/' + state.patientId}`;
    $('#pa-busy').hidden = false;
    try {
      const since = $('#pa-since').value;
      const r = await api.get(`/environments/${envId}/patients/${encodeURIComponent(state.patientId)}/prior-auth` + (since ? '?since=' + since : ''));
      loadedFor = state.patientId;
      render(r);
    } catch (e) {
      showError('#pa-messages', e);
    } finally {
      $('#pa-busy').hidden = true;
    }
  }

  function render(r) {
    (r.notes || []).forEach(n => msg('#pa-messages', r.fallbackUsed ? 'warn' : 'info', n));
    msg('#pa-messages', 'info', h('span', {}, `${r.items.length} prior authorization(s) · ${r.pages} page(s)` + (r.truncated ? ' (truncated)' : '') + ' · ', h('code', {}, r.urls[0]), ' ', (r.requestIds || []).map(id => P.historyLink(id, '↗'))).outerHTML ? '' : '');
    // the msg helper takes text; append the rich line separately
    const last = $('#pa-messages').lastElementChild;
    last.innerHTML = '';
    last.append(`${r.items.length} prior authorization(s) · ${r.pages} page(s)` + (r.truncated ? ' (truncated)' : '') + ' · ', h('code', {}, r.urls[0]), ' ', ...(r.requestIds || []).map(id => P.historyLink(id, '↗')));
    renderChecklist(r.items);
    const list = $('#pa-list');
    for (const pa of r.items) list.append(card(pa));
  }

  /** CMS-0057-F data elements a member must be able to see, counted across the PAs. */
  function renderChecklist(items) {
    const box = $('#pa-checklist');
    box.hidden = false;
    box.innerHTML = '';
    if (!items.length) { box.hidden = true; return; }
    const n = items.length;
    const rows = [
      ['Status / decision expressed', items.filter(p => p.decision && !p.decision.startsWith('UNKNOWN')).length, 'review action (X12 306 code), denial reason, or outcome'],
      ['Decision date', items.filter(p => p.decisionDate).length, 'item preAuthIssueDate or when-adjudicated extension'],
      ['Authorization period / end', items.filter(p => p.validFrom || p.validTo).length, 'preAuthRefPeriod or item preAuthPeriod'],
      ['Items and services listed', items.filter(p => p.items && p.items.length).length, 'item.productOrService'],
      ['Quantities approved / used', items.filter(p => (p.items || []).some(i => i.allowedUnits || i.consumedUnits) || (p.totals || []).some(t => t.utilization)).length, 'allowedunits / consumedunits adjudication, PriorAuthorizationUtilization'],
      ['Denial reason on denied PAs', (() => { const d = items.filter(p => p.decision === 'DENIED'); return d.length ? d.filter(p => p.denialReasons && p.denialReasons.length).length + ' of ' + d.length : 'n/a'; })(), 'denialreason adjudication with X12 CARC/RARC'],
      ['PDex PriorAuthorization profile declared', items.filter(p => p.pdexProfileDeclared).length, 'meta.profile'],
      ['Last updated present', items.filter(p => p.lastUpdated).length, 'meta.lastUpdated (one business day rule)'],
    ];
    box.append(h('h3', {}, 'CMS-0057-F prior authorization data elements (' + n + ' PA' + (n > 1 ? 's' : '') + ')'));
    const t = h('table.grid-table', {}, h('thead', {}, h('tr', {}, h('th', {}, 'Element'), h('th', {}, 'Present'), h('th', {}, 'Where in the PDex EOB'))));
    const body = h('tbody');
    for (const [label, count, where] of rows) {
      const ok = typeof count === 'number' ? count === n : !count.startsWith('0 ');
      body.append(h('tr', {}, h('td', {}, label), h('td', {}, badge(typeof count === 'number' ? `${count} / ${n}` : count, ok ? 'PASS' : (count === 0 || String(count).startsWith('0 ') ? 'FAIL' : 'WARN'))), h('td.muted', {}, where)));
    }
    t.append(body);
    box.append(t);
  }

  function card(pa) {
    const c = h('div.pa-card');
    c.append(h('h3', {}, badge(pa.decision || 'UNKNOWN', (pa.decision || 'UNKNOWN').split(' ')[0]), 'ExplanationOfBenefit/' + pa.id,
      h('span.muted', {}, pa.identifiers && pa.identifiers.length ? '· ' + pa.identifiers.join(', ') : '')));
    const kv = h('div.kv');
    const add = (k, v) => { if (v != null && v !== '' && !(Array.isArray(v) && !v.length)) kv.append(h('div', {}, k), h('div', {}, Array.isArray(v) ? v.join('; ') : String(v))); };
    add('Decision basis', pa.decisionBasis);
    add('Status / outcome', `${pa.status || '?'} / ${pa.outcome || '?'}`);
    add('Type', pa.type);
    add('Level of service', pa.levelOfService);
    add('Requested (created)', pa.created);
    add('Decision date', pa.decisionDate);
    add('Valid', (pa.validFrom || pa.validTo) ? `${pa.validFrom || '?'} → ${pa.validTo || 'open'}` : null);
    add('Last updated', pa.lastUpdated ? fmt.ts(pa.lastUpdated) : null);
    add('Insurer', pa.insurer);
    add('Provider', pa.provider);
    add('Facility', pa.facility);
    add('Pre-auth references', pa.preAuthRef);
    add('Diagnoses', pa.diagnoses);
    add('Procedures', pa.procedures);
    add('Denial reasons', pa.denialReasons);
    add('Process notes', pa.processNotes);
    c.append(kv);
    if (pa.items && pa.items.length) {
      const t = h('table.grid-table', {}, h('thead', {}, h('tr', {}, ['#', 'Category', 'Item / service', 'Serviced', 'Qty', 'Decision', 'Allowed', 'Used', 'Auth period', 'Issued', 'Trace / auth #', 'Denial reasons'].map(x => h('th', {}, x)))));
      const b = h('tbody');
      for (const it of pa.items) {
        b.append(h('tr', {}, h('td', {}, String(it.sequence)), h('td', {}, it.category || ''), h('td', {}, [it.productOrService || '', it.modifiers && it.modifiers.length ? ' (' + it.modifiers.join(', ') + ')' : ''].join('')),
          h('td', {}, it.serviced || ''), h('td', {}, it.quantity || ''), h('td', {}, it.decision || ''), h('td', {}, it.allowedUnits || ''), h('td', {}, it.consumedUnits || ''),
          h('td', {}, it.preAuthPeriod || ''), h('td', {}, it.preAuthIssueDate || ''),
          h('td', {}, [...(it.traceNumbers || []), it.previousAuthorizationNumber ? 'prev auth ' + it.previousAuthorizationNumber : null, it.administrationReferenceNumber ? 'admin ref ' + it.administrationReferenceNumber : null].filter(Boolean).join('; ')),
          h('td', {}, (it.denialReasons || []).join('; '))));
      }
      t.append(b);
      c.append(t);
    }
    if (pa.totals && pa.totals.length) {
      c.append(h('div', {}, 'Totals: ', pa.totals.map(t => h('span', {}, badge(t.categoryDisplay || t.category, 'MAY'), ' ', t.amount || '', t.utilization ? ' (used ' + t.utilization + ')' : '', '  '))));
    }
    if (pa.issues && pa.issues.length) c.append(h('ul.issues', {}, pa.issues.map(i => h('li.warning', {}, i))));
    const json = P.jsonBlock(pa.resource);
    json.hidden = true;
    c.append(h('div.toolbar', {}, h('button.small', { onclick: () => { json.hidden = !json.hidden; } }, 'Raw EOB JSON'), h('span.muted', {}, (pa.profiles || []).join(' '))), json);
    return c;
  }

  P.modules.priorauth = {
    init() {
      $('#btn-pa-refresh').addEventListener('click', load);
      P.on('tab', (t) => { if (t === 'priorauth' && (loadedFor !== state.patientId || !$('#pa-list').children.length)) load(); });
      P.on('env-changed', () => { loadedFor = null; });
    },
  };
})();
