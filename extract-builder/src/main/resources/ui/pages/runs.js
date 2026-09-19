(function () {
  const { h, icon, fmt, pill, get, post, crumbs, registerPage, state, toast, modal, confirm } = App;

  registerPage('runs', {
    async render(root, params) {
      const defId = params.id;
      let def = null;
      if (defId) {
        def = await get('/api/v1/definitions/' + defId);
        crumbs([{ label: 'Definitions', href: '#/definitions' }, { label: def.definition.name, href: '#/definitions/' + defId }, { label: 'Runs' }]);
      } else crumbs([{ label: 'Runs' }]);
      const runs = defId ? await get('/api/v1/definitions/' + defId + '/runs') : await get('/api/v1/runs?limit=300');
      const prodVersion = def && def.definition.versions.find(v => v.status === 'PRODUCTION');

      const head = h('div', { class: 'page-head' }, h('div', {}, h('h1', {}, def ? def.definition.name + ' · runs' : 'Runs'), h('p', { class: 'lead' }, def ? (prodVersion ? 'v' + prodVersion.versionNo + ' in production · ' + scheduleText(prodVersion) + (def.watermark ? ' · watermark ' + def.watermark.value.replace('T', ' ') : '') : 'No version in production.') : 'Every sample and production run, newest first. Click a run for its SQL, parameters, checksum and delivery receipt.')),
        def && prodVersion ? h('div', { class: 'actions' },
          h('button', { class: 'btn', type: 'button', onclick: async () => { await post('/api/v1/definitions/' + defId + '/versions/' + prodVersion.versionNo + '/pause?paused=' + !prodVersion.schedulePaused); toast(prodVersion.schedulePaused ? 'Schedule resumed' : 'Schedule paused', 'ok'); App.render(); } }, icon(prodVersion.schedulePaused ? 'play' : 'pause'), prodVersion.schedulePaused ? 'Resume schedule' : 'Pause schedule'),
          h('button', { class: 'btn', type: 'button', onclick: () => rerunAsOf(defId) }, icon('history'), 'Re-run as of a date'),
          h('button', { class: 'btn primary', type: 'button', onclick: async () => { const r = await post('/api/v1/definitions/' + defId + '/run-now?trigger=SCHEDULE'); toast(outcome(r), r.status === 'FAILED' ? 'danger' : 'ok'); location.hash = '#/runs/' + r.id; } }, icon('play'), 'Simulate tonight\'s run')) : null);

      const tiles = def ? tilesFor(runs, def) : null;
      const filters = { mode: '', status: '' };
      const mode = h('select', { class: 'input', style: { width: '160px' }, onchange: e => { filters.mode = e.target.value; draw(); } }, h('option', { value: '' }, 'All modes'), h('option', { value: 'PRODUCTION' }, 'Production'), h('option', { value: 'SAMPLE' }, 'Samples'));
      const status = h('select', { class: 'input', style: { width: '180px' }, onchange: e => { filters.status = e.target.value; draw(); } }, h('option', { value: '' }, 'Any status'), ['TRANSFERRED', 'DELIVERED', 'HELD', 'FAILED', 'WRITTEN', 'SKIPPED_EMPTY', 'DELETED'].map(s => h('option', { value: s }, fmt.title(s))));
      const tbody = h('tbody');
      function draw() {
        App.clear(tbody);
        const list = runs.filter(r => (!filters.mode || r.mode === filters.mode) && (!filters.status || r.status === filters.status));
        if (!list.length) tbody.appendChild(h('tr', {}, h('td', { colspan: 9, class: 'empty' }, 'No runs match.')));
        for (const r of list) tbody.appendChild(h('tr', { class: 'click', onclick: () => location.hash = '#/runs/' + r.id },
          h('td', { class: 'mono' }, r.id), defId ? null : h('td', {}, h('div', { style: { fontWeight: 500 } }, r.definitionName), h('div', { class: 'sub mono' }, r.vendorCode)), h('td', {}, fmt.dt(r.startedAt)), h('td', {}, pill(r.mode.toLowerCase() === 'sample' ? 'sample' : 'info', r.mode === 'PRODUCTION' ? fmt.title(r.trigger) : 'Sample v' + r.versionNo)),
          h('td', { class: 'mono small muted' }, r.windowFrom ? r.windowFrom.slice(5, 16).replace('T', ' ') + ' → ' + r.windowTo.slice(5, 16).replace('T', ' ') : 'full'), h('td', { class: 'num' }, fmt.num(r.rowCount)), h('td', { class: 'mono small' }, r.fileName || h('span', { class: 'muted' }, r.error ? 'not written' : '—')),
          h('td', {}, pill(r.status === 'DELETED' ? 'retired' : r.status, r.status === 'DELETED' ? 'Deleted' : undefined), r.error && r.status !== 'DELETED' ? h('div', { class: 'sub clamp', style: { maxWidth: '260px' }, title: r.error }, r.error) : r.heldReason && r.status === 'HELD' ? h('div', { class: 'sub clamp', style: { maxWidth: '260px' } }, r.heldReason) : null),
          h('td', { class: 'right nowrap' }, r.status === 'HELD' ? h('button', { class: 'btn xs', type: 'button', onclick: async e => { e.stopPropagation(); if (await confirm('Release this file?', 'The file goes to the vendor as is.', 'Release')) { await post('/api/v1/runs/' + r.id + '/release'); toast('Released', 'ok'); App.render(); } } }, 'Release') :
            r.status === 'FAILED' && r.mode === 'PRODUCTION' ? h('button', { class: 'btn xs', type: 'button', onclick: async e => { e.stopPropagation(); const x = await post('/api/v1/runs/' + r.id + '/retry'); toast(outcome(x), x.status === 'FAILED' ? 'danger' : 'ok'); App.render(); } }, 'Retry') :
            (r.status === 'DELIVERED' || r.status === 'TRANSFERRED') && r.mode === 'PRODUCTION' && !r.archived ? h('button', { class: 'btn xs', type: 'button', onclick: async e => { e.stopPropagation(); await post('/api/v1/runs/' + r.id + '/redeliver'); toast('Re-delivered ' + r.fileName, 'ok'); App.render(); } }, 'Re-deliver') : r.archived ? h('span', { class: 'sub' }, 'file purged') : null)));
      }
      draw();
      root.append(head, tiles || '', h('div', { class: 'row mb' }, mode, status, h('span', { class: 'topbar-spacer' }), h('span', { class: 'sub' }, runs.length + ' runs')),
        h('div', { class: 'card' }, h('div', { class: 'table-wrap' }, h('table', { class: 'table compact' }, h('thead', {}, h('tr', {}, h('th', {}, 'Run'), defId ? null : h('th', {}, 'Feed'), h('th', {}, 'Started'), h('th', {}, 'Trigger'), h('th', {}, 'Window'), h('th', { class: 'right' }, 'Rows'), h('th', {}, 'File'), h('th', {}, 'Status'), h('th', {}))), tbody))),
        h('div', { class: 'small muted mt' }, 'Re-deliver resends the stored file under the same name. Re-run as of a date regenerates it for a past business date without moving the watermark. Files older than the retention window are purged; their run records stay.'));
    }
  });

  function scheduleText(v) {
    const s = v.productionConfig && v.productionConfig.schedule;
    if (!s) return '';
    return (v.schedulePaused ? 'paused · ' : '') + s.preset.toLowerCase() + ' at ' + s.time + ' ' + (s.timezone || '').replace('America/', '');
  }
  function tilesFor(runs, def) {
    const prod = runs.filter(r => r.mode === 'PRODUCTION');
    const last = prod.find(r => r.status === 'DELIVERED' || r.status === 'TRANSFERRED');
    const since = Date.now() - 30 * 86400000;
    const recent = prod.filter(r => new Date(r.startedAt).getTime() > since);
    const ok = recent.filter(r => r.status === 'DELIVERED' || r.status === 'TRANSFERRED').length;
    const failed = recent.filter(r => r.status === 'FAILED').length;
    return h('div', { class: 'grid4 mb' },
      tile('Last delivered', last ? fmt.dt(last.deliveredAt) : '—', last ? fmt.num(last.rowCount) + ' rows' + (last.transferredAt ? ' · acknowledged ' + fmt.time(last.transferredAt) : ' · awaiting pick-up') : 'no deliveries yet'),
      tile('Next run', def.schedule && def.schedule.nextFireAt ? fmt.dt(def.schedule.nextFireAt) : '—', def.schedule && def.schedule.note ? def.schedule.note : 'waits for the readiness gate'),
      tile('Last 30 days', ok + ' of ' + recent.length + ' delivered', failed + ' failed · ' + recent.filter(r => r.status === 'HELD').length + ' held'),
      tile('Watermark', def.watermark ? def.watermark.value.replace('T', ' ') : 'full refresh', def.watermark ? (def.watermark.elements || []).map(App.elementShort).join(', ') : 'every run sends the whole population'));
  }
  function tile(l, v, s) { return h('div', { class: 'card kpi' }, h('div', { class: 'l' }, l), h('div', { class: 'v', style: { fontSize: '18px' } }, v), h('div', { class: 's' }, s)); }
  function outcome(r) { return r.status === 'DELIVERED' ? 'Delivered ' + r.fileName + ' with ' + fmt.num(r.rowCount) + ' rows' : r.status === 'HELD' ? 'Held: ' + r.heldReason : r.status === 'SKIPPED_EMPTY' ? 'No rows in the window; skipped per policy' : fmt.title(r.status) + (r.error ? ': ' + r.error : ''); }
  function rerunAsOf(defId) {
    const date = h('input', { class: 'input', type: 'date', value: new Date(Date.now() - 86400000).toISOString().slice(0, 10) });
    const commit = h('input', { type: 'checkbox' });
    modal({ title: 'Re-run as of a business date', body: h('div', { class: 'stack' }, h('p', { class: 'small muted' }, 'Regenerates the file for a past business date, for a backfill or a vendor who lost a file. The file name carries that date. The watermark stays where it is unless you tick the box.'),
      h('div', { class: 'field' }, h('label', {}, 'Business date'), date), h('label', { class: 'check' }, commit, 'Move the watermark to this window')),
      actions: [{ label: 'Cancel' }, { label: 'Run', kind: 'primary', icon: 'play', onClick: async () => { const r = await post('/api/v1/definitions/' + defId + '/rerun?businessDate=' + date.value + '&commitWatermark=' + commit.checked); toast(outcome(r), r.status === 'FAILED' ? 'danger' : 'ok'); location.hash = '#/runs/' + r.id; } }] });
  }
})();
