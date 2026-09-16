/* Search tab: member lookup by member id / demographics / id, IG search-option reference. */
(() => {
  'use strict';
  const P = window.PAW;
  const { $, $$, h, api, state, msg, clearMsgs, showError, badge } = P;

  function extraRow(name = '', value = '') {
    const tr = h('tr', {},
      h('td', {}, h('input', { name: 'x-name', list: 'patient-params', value: name, placeholder: 'parameter' })),
      h('td', {}, h('input', { name: 'x-value', value, placeholder: 'value' })),
      h('td', {}, h('button.small', { type: 'button', onclick: () => tr.remove() }, 'remove')));
    $('#search-extra tbody').append(tr);
  }

  function fillIdentifierSystems(env) {
    const sel = $('#search-form').elements.identifierSystem;
    sel.innerHTML = '';
    sel.append(h('option', { value: '' }, env && env.identifierSystems.length ? 'all default systems of the environment' : 'no system (identifier=value)'));
    if (env) {
      for (const s of env.identifierSystems) {
        sel.append(h('option', { value: s.system }, `${s.label || s.system}${s.typeCode ? ' (' + s.typeCode + ')' : ''}${s.defaultForMemberId ? '' : ' – not default'}`));
      }
    }
  }

  async function renderCatalog() {
    const resources = await P.loadCatalog();
    const spec = resources.Patient;
    const box = $('#search-catalog');
    box.innerHTML = '';
    if (!spec) return;
    const dl = h('datalist#patient-params');
    for (const p of spec.searchParams) {
      box.append(h('div.check-row', {}, badge(p.expectation), ' ', h('code', {}, p.name), ' ', h('span.muted', {}, `${p.type} · ${p.igs.join(', ')}`),
        p.description && !p.description.startsWith('http') ? h('div.muted', {}, P.fmt.short(p.description, 160)) : null));
      dl.append(h('option', { value: p.name }));
    }
    box.append(dl);
    if (spec.combos.length) {
      box.append(h('h3', {}, 'Required combinations'));
      for (const c of spec.combos) {
        box.append(h('div.check-row', {}, badge(c.expectation), ' ', h('code', {}, c.params.join(' + ')), ' ', h('span.muted', {}, c.igs.join(', '))));
      }
    }
    box.append(h('h3', {}, 'Profiles'));
    for (const p of spec.profiles) box.append(h('div.check-row', {}, h('code', {}, p.url), ' ', h('span.muted', {}, p.ig)));
    box.append(h('p.muted', {}, 'Member id searches run Patient?identifier=<system>|<value> for each default identifier system of the environment; when nothing matches, Coverage?identifier= is tried and the beneficiary resolved. Names use name=, or family= / given=, combined with birthdate= and gender=.'));
  }

  async function search(ev) {
    ev.preventDefault();
    const envId = P.requireEnv('#search-messages');
    if (!envId) return;
    const f = $('#search-form');
    clearMsgs('#search-messages');
    const extra = {};
    $$('#search-extra tbody tr').forEach(tr => {
      const k = tr.querySelector('[name=x-name]').value.trim();
      const v = tr.querySelector('[name=x-value]').value.trim();
      if (k) extra[k] = v;
    });
    const body = {
      memberId: f.elements.memberId.value.trim() || null,
      identifierSystem: f.elements.identifierSystem.value || null,
      name: f.elements.name.value.trim() || null,
      family: f.elements.family.value.trim() || null,
      given: f.elements.given.value.trim() || null,
      birthDate: f.elements.birthDate.value || null,
      gender: f.elements.gender.value || null,
      id: f.elements.id.value.trim() || null,
      extraParams: extra,
      coverageFallback: f.elements.coverageFallback.checked,
    };
    $('#search-busy').hidden = false;
    try {
      const r = await api.post(`/environments/${envId}/members/search`, body);
      renderResults(r);
    } catch (e) {
      showError('#search-messages', e);
    } finally {
      $('#search-busy').hidden = true;
    }
  }

  function renderResults(r) {
    const t = $('#search-results');
    const body = t.querySelector('tbody');
    body.innerHTML = '';
    t.hidden = false;
    (r.warnings || []).forEach(w => msg('#search-messages', 'warn', w));
    if (!r.patients.length) msg('#search-messages', 'info', 'No member found. Check the queries below: the exact URLs sent and what the server answered.');
    for (const p of r.patients) {
      body.append(h('tr', {},
        h('td', {}, p.display),
        h('td', {}, p.birthDate || ''),
        h('td', {}, p.gender || ''),
        h('td', {}, (p.identifiers || []).map(i => h('div', {}, badge(i.typeCode || 'id', 'MAY'), ' ', h('code', {}, i.value || ''), h('span.muted', {}, i.system ? ' ' + i.system : '')))),
        h('td', {}, h('code', {}, p.id || '')),
        h('td.muted', {}, p.foundBy || ''),
        h('td', {}, h('button.small.primary', { onclick: () => { P.setPatient(p.id, p); P.showTab('patient'); } }, 'Open'))));
    }
    const q = $('#search-queries');
    q.hidden = false;
    q.open = !r.patients.length;
    const qb = q.querySelector('tbody');
    qb.innerHTML = '';
    for (const x of r.queries) {
      qb.append(h('tr', {}, h('td', {}, x.description), h('td.url', {}, x.url), h('td', {}, x.status == null ? (x.error ? 'error' : '') : String(x.status)),
        h('td.num', {}, x.matches == null ? '' : String(x.matches) + (x.total != null ? ' / ' + x.total : '')), h('td.num', {}, x.durationMs + (x.requestId ? ' ' : '') , x.requestId ? P.historyLink(x.requestId, '↗') : null)));
      if (x.error) qb.append(h('tr', {}, h('td', { colspan: 5, class: 'muted' }, x.error)));
    }
  }

  P.modules.search = {
    init() {
      $('#search-form').addEventListener('submit', search);
      $('#btn-search-extra').addEventListener('click', () => extraRow());
      $('#btn-search-clear').addEventListener('click', () => { $('#search-form').reset(); $('#search-extra tbody').innerHTML = ''; $('#search-results').hidden = true; $('#search-queries').hidden = true; clearMsgs('#search-messages'); });
      P.on('env-changed', fillIdentifierSystems);
      P.on('tab', (t) => { if (t === 'search') { fillIdentifierSystems(P.currentEnv()); renderCatalog().catch(console.error); } });
    },
  };
})();
