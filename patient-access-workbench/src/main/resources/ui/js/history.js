/* History tab: outbound requests with redacted headers, bodies and cURL. */
(() => {
  'use strict';
  const P = window.PAW;
  const { $, h, api, state, showError, fmt } = P;

  let selected = null;

  async function load() {
    const q = new URLSearchParams();
    if ($('#hist-env-only').checked && state.envId) q.set('environmentId', state.envId);
    if ($('#hist-purpose').value) q.set('purpose', $('#hist-purpose').value);
    if ($('#hist-correlation').value.trim()) q.set('correlationId', $('#hist-correlation').value.trim());
    q.set('limit', '200');
    const rows = await api.get('/history?' + q);
    const body = $('#hist-table tbody');
    body.innerHTML = '';
    if (!rows.length) body.append(h('tr', {}, h('td', { colspan: 6, class: 'muted' }, 'no requests recorded')));
    for (const r of rows) {
      body.append(h('tr.selectable', { class: selected === r.id ? 'selected' : '', onclick: () => open(r.id) },
        h('td', {}, new Date(r.at).toLocaleTimeString()), h('td', {}, r.method), h('td.url', {}, fmt.short(r.url, 110)),
        h('td', {}, r.status == null ? (r.error ? 'error' : '') : String(r.status)), h('td.num', {}, String(r.durationMs)), h('td', {}, r.purpose || '')));
    }
  }

  async function open(id) {
    selected = id;
    const box = $('#hist-detail');
    box.innerHTML = '';
    try {
      const r = await api.get('/history/' + id);
      const curl = await api.text('/history/' + id + '/curl');
      box.append(h('div.toolbar', {}, h('strong', {}, `${r.method} ${r.status == null ? '' : r.status}`), h('span.spacer'), h('span.muted', {}, `${r.durationMs} ms · ${fmt.ts(r.at)} · ${r.purpose || ''}` + (r.correlationId ? ' · ' + r.correlationId : ''))));
      box.append(h('div.url.mono', {}, r.url));
      if (r.error) box.append(h('div.msg.error', {}, r.error));
      if (r.summary) box.append(h('div.muted', {}, r.summary));
      const kv = (title, obj) => {
        const d = h('details', { open: true }, h('summary', {}, title));
        const g = h('div.kv');
        for (const [k, v] of Object.entries(obj || {})) g.append(h('div', {}, k), h('div.mono', {}, Array.isArray(v) ? v.join(', ') : String(v)));
        d.append(g);
        return d;
      };
      box.append(kv('Request headers', r.requestHeaders));
      if (r.requestBody) box.append(h('details', { open: true }, h('summary', {}, 'Request body'), h('pre.json', {}, pretty(r.requestBody))));
      box.append(kv('Response headers', r.responseHeaders));
      if (r.responseBody != null) box.append(h('details', { open: true }, h('summary', {}, 'Response body' + (r.responseBodyTruncated ? ' (truncated)' : '')), h('pre.json', {}, pretty(r.responseBody))));
      const curlBox = h('pre.json', {}, curl);
      box.append(h('details', {}, h('summary', {}, 'cURL'), h('div.toolbar', {}, h('button.small', { onclick: () => navigator.clipboard && navigator.clipboard.writeText(curl) }, 'Copy')), curlBox));
    } catch (e) {
      showError(box, e);
    }
    load();
  }

  function pretty(text) {
    try { return JSON.stringify(JSON.parse(text), null, 2); } catch (e) { return text; }
  }

  P.modules.history = {
    init() {
      $('#btn-hist-refresh').addEventListener('click', load);
      $('#btn-hist-clear').addEventListener('click', async () => { await api.del('/history'); selected = null; $('#hist-detail').textContent = 'History cleared.'; load(); });
      $('#hist-purpose').addEventListener('change', load);
      $('#hist-env-only').addEventListener('change', load);
      $('#hist-correlation').addEventListener('change', load);
      P.on('tab', (t) => { if (t === 'history') load().catch(console.error); });
      P.on('history-open', (id) => { $('#hist-env-only').checked = false; open(id); });
    },
  };
})();
