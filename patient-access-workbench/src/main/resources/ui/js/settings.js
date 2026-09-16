/* Settings tab: effective settings, catalog versions, demo server info. */
(() => {
  'use strict';
  const P = window.PAW;
  const { $, h, api } = P;

  async function load() {
    const info = await api.get('/info');
    const settings = await api.get('/settings');
    const kv = $('#settings-info');
    kv.innerHTML = '';
    const rows = [['Version', info.version + (info.buildTime ? ' (' + P.fmt.ts(info.buildTime) + ')' : '')], ['Data folder', settings.dataDir + (settings.dataDirPresent ? '' : ' (will be created)')],
      ['Master key', settings.masterKeySource], ['Public base URL', settings.publicBaseUrl || '(derived from requests)'], ['Basic auth', settings.basicAuthEnabled ? 'on' : 'off'],
      ['HTTP timeouts', `connect ${settings.http.connectTimeout}, read ${settings.http.readTimeout}, retries ${settings.http.maxRetries}`],
      ['History', `${settings.history.maxEntries} entries in memory, bodies ≤ ${settings.history.maxBodyBytes} bytes, persisted: ${settings.history.persist}`],
      ['Search', `page size ${settings.search.pageSize}, max pages ${settings.search.maxPages}`],
      ['Conformance', `max pages ${settings.conformance.maxPages}, concurrency ${settings.conformance.concurrency}, slow > ${settings.conformance.slowWarnMs} ms`],
      ['IG packages folder', settings.validationPackagesDir + (settings.validationPackagesPresent ? ' (present)' : ' (absent: lite profile checks only)')]];
    rows.forEach(([k, v]) => kv.append(h('div', {}, k), h('div.mono', {}, v)));
    const igs = $('#settings-igs');
    igs.innerHTML = '';
    for (const ig of Object.values(info.igs || {})) igs.append(h('div', {}, ig.name), h('div.mono', {}, `${ig.version || ''} · ${ig.canonical}`));
    const demo = $('#settings-demo');
    demo.innerHTML = '';
    if (!info.demo) { demo.append(h('p.muted', {}, 'Disabled (paw.demo.enabled=false).')); return; }
    try {
      const members = await api.get('/demo/members');
      demo.append(h('p', {}, 'Enabled at ', h('code', {}, location.origin + '/demo/fhir'), '. Client credentials: demo-client / demo-secret. Demo members:'));
      demo.append(P.table([{ key: 'id', label: 'Patient id' }, { key: 'name', label: 'Name' }, { label: 'Identifiers', render: m => (m.identifiers || []).map(i => `${i.type || ''} ${i.value} (${i.system})`).join('; ') },
        { label: 'Data', render: m => [m.coverages ? 'coverage' : null, m.claims ? 'claims' : null, m.priorAuths ? 'prior auth' : null].filter(Boolean).join(', ') }], members));
    } catch (e) {
      demo.append(h('p.muted', {}, 'Enabled; member list unavailable: ' + e.message));
    }
  }

  P.modules.settings = {
    init() { P.on('tab', (t) => { if (t === 'settings') load().catch(console.error); }); },
  };
})();
