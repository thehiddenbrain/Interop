(function () {
  const { h, icon, fmt, pill, get, post, crumbs, registerPage, state, toast, confirm, modal } = App;

  registerPage('run', {
    async render(root, params) {
      const run = await get('/api/v1/runs/' + params.id);
      const isSample = run.mode === 'SAMPLE';
      crumbs([{ label: 'Definitions', href: '#/definitions' }, { label: run.definitionName, href: '#/definitions/' + run.definitionId }, { label: (isSample ? 'Sample ' : 'Run ') + run.id }]);
      const def = await get('/api/v1/definitions/' + run.definitionId);
      const version = def.definition.versions.find(v => v.versionNo === run.versionNo) || {};

      const actions = h('div', { class: 'actions' });
      if (run.filePath || run.archived === false) {
        actions.appendChild(h('a', { class: 'btn', href: '/api/v1/runs/' + run.id + '/download', onclick: e => { e.preventDefault(); download(run); } }, icon('download'), 'Download file'));
      }
      if (isSample && run.status !== 'DELETED' && run.filePath) {
        actions.appendChild(h('button', { class: 'btn', type: 'button', onclick: async () => { const r = await post('/api/v1/runs/' + run.id + '/send-test-route'); toast('Sample dropped on the ' + r.vendorCode + ' test route', 'ok'); App.render(); } }, icon('send'), 'Send to vendor test route'));
        if (version.status === 'SAMPLED') actions.appendChild(h('button', { class: 'btn primary', type: 'button', onclick: () => requestApproval(run, version) }, icon('check'), 'Request approval'));
        if (version.status === 'DRAFT') actions.appendChild(h('a', { class: 'btn', href: '#/definitions/' + run.definitionId + '/v/' + run.versionNo }, 'Back to layout'));
      }
      if (run.status === 'HELD') actions.appendChild(h('button', { class: 'btn primary', type: 'button', onclick: async () => { if (await confirm('Release this file?', 'The file goes to the vendor as is. The quality findings stay on the run record with your name.', 'Release and deliver')) { await post('/api/v1/runs/' + run.id + '/release'); toast('Released and delivered', 'ok'); App.render(); } } }, icon('send'), 'Release and deliver'));
      if (run.status === 'FAILED' && run.mode === 'PRODUCTION') actions.appendChild(h('button', { class: 'btn primary', type: 'button', onclick: async () => { const r = await post('/api/v1/runs/' + run.id + '/retry'); toast('Retried as ' + r.id + ': ' + fmt.title(r.status), r.status === 'FAILED' ? 'danger' : 'ok'); location.hash = '#/runs/' + r.id; } }, icon('refresh'), 'Retry with the same window'));
      if ((run.status === 'DELIVERED' || run.status === 'TRANSFERRED') && run.mode === 'PRODUCTION' && run.filePath) actions.appendChild(h('button', { class: 'btn', type: 'button', onclick: async () => { if (await confirm('Re-deliver the stored file?', 'The same file goes to the drop folder again under the same name. Nothing is re-queried.', 'Re-deliver')) { await post('/api/v1/runs/' + run.id + '/redeliver'); toast('Re-delivered', 'ok'); App.render(); } } }, icon('refresh'), 'Re-deliver'));

      const status = run.status === 'DELETED' ? 'retired' : run.status;
      const head = h('div', { class: 'page-head' }, h('div', {}, h('div', { class: 'eyebrow' }, (isSample ? 'Sample' : run.mode === 'PREVIEW' ? 'Preview' : 'Production run') + ' · ' + run.definitionName + ' v' + run.versionNo),
        h('h1', { class: 'row' }, run.fileName || run.id, pill(status)), h('p', { class: 'lead' }, describe(run))), actions);

      const alerts = h('div', { class: 'stack' });
      if (run.error) alerts.appendChild(h('div', { class: 'alert danger' }, icon('warn'), h('div', {}, h('b', {}, run.status === 'DELETED' ? 'Sample deleted. ' : 'Failed. '), run.error)));
      if (run.heldReason && run.status === 'HELD') alerts.appendChild(h('div', { class: 'alert warn' }, icon('warn'), h('div', {}, h('b', {}, 'Held before delivery. '), run.heldReason, ' An approver or admin can release it, or fix the cause and run again.')));
      if (run.heldReason && run.status !== 'HELD' && isSample) alerts.appendChild(h('div', { class: 'alert info' }, icon('lock'), h('div', {}, run.heldReason + ' The sample was built masked.')));
      const highs = (run.warnings || []).filter(w => w.severity === 'HIGH');
      const meds = (run.warnings || []).filter(w => w.severity !== 'HIGH');
      if (run.warnings && run.warnings.length) alerts.appendChild(h('div', { class: 'alert ' + (highs.length ? 'danger' : 'warn') }, icon('warn'), h('div', {}, h('b', {}, run.warnings.length + (run.warnings.length === 1 ? ' warning' : ' warnings') + (highs.length ? '. Approval is blocked while a high warning stands.' : ' in this ' + (isSample ? 'sample.' : 'run.'))),
        h('ul', {}, run.warnings.map(w => h('li', {}, h('b', {}, w.field), ': ', w.count, ' ', fmt.status(w.code), ' (', w.example, ')'))))));

      const grid = gridOf(run);
      const left = h('div', { class: 'stack' }, alerts, grid,
        run.qualityFindings && run.qualityFindings.length ? h('div', { class: 'card' }, h('div', { class: 'card-head' }, h('h3', {}, 'Quality gate')), h('div', { class: 'card-body check-list' }, run.qualityFindings.map(f => h('div', { class: 'check-item ' + (f.severity === 'HOLD' ? 'bad' : 'ok') }, h('span', { class: 'mark' }, f.severity === 'HOLD' ? '!' : f.severity === 'WARN' ? '~' : '✓'), h('div', {}, h('b', {}, fmt.title(f.code)), h('span', {}, f.message)))))) : null,
        h('div', { class: 'card' }, h('div', { class: 'card-head' }, h('h3', {}, 'Field statistics'), h('span', { class: 'spacer' }), h('span', { class: 'sub' }, 'null rate, truncation and width per field')),
          h('div', { class: 'table-wrap' }, h('table', { class: 'table compact' }, h('thead', {}, h('tr', {}, h('th', {}, 'Field'), h('th', { class: 'right' }, 'Empty'), h('th', { class: 'right' }, 'Null rate'), h('th', { class: 'right' }, 'Truncated'), h('th', { class: 'right' }, 'Max width'), h('th', { class: 'right' }, 'Sum'))),
            h('tbody', {}, Object.values(run.fieldStats || {}).map(s => h('tr', {}, h('td', { class: 'mono' }, s.header), h('td', { class: 'num' }, fmt.num(s.nulls)), h('td', { class: 'num ' + (s.nullRatePct > 40 ? 'risk HIGH' : '') }, s.nullRatePct + '%'), h('td', { class: 'num ' + (s.truncated ? 'risk MEDIUM' : '') }, fmt.num(s.truncated)), h('td', { class: 'num' }, s.maxLength), h('td', { class: 'num' }, s.sum || '—'))))))));

      const right = h('div', { class: 'stack' },
        h('div', { class: 'card pad' }, h('div', { class: 'eyebrow mb' }, isSample ? 'File as the vendor receives it' : 'File'),
          kv('Name', h('span', { class: 'mono' }, run.fileName || '—')), kv('Rows', fmt.num(run.rowCount)), kv('Size', fmt.bytes(run.bytes)), kv('SHA-256', h('span', { class: 'mono', title: run.sha256 }, run.sha256 ? run.sha256.slice(0, 12) + '…' : '—')),
          kv('Masked fields', run.masked ? (run.maskedFields || []).length + ' of ' + Object.keys(run.fieldStats || {}).length : 'none (production)'), run.synthetic ? kv('Data', 'synthetic') : null,
          run.deliveryPath ? kv('Delivered to', h('span', { class: 'mono small' }, run.deliveryPath.replace(/^.*mft[\\/]/, 'mft/'))) : null, run.transferredAt ? kv('Vendor acknowledged', fmt.dt(run.transferredAt) + ' · ' + fmt.num(run.vendorReceivedCount) + ' records') : null),
        h('div', { class: 'card pad' }, h('div', { class: 'eyebrow mb' }, 'What ran'),
          kv('Trigger', fmt.title(run.trigger) + ' by ' + run.startedBy), kv('Started', fmt.dt(run.startedAt)), kv('Took', run.durationMs ? (run.durationMs / 1000).toFixed(1) + ' s' : '—'), kv('Business date', run.businessDate),
          run.windowFrom ? kv('Window', h('span', { class: 'mono small' }, run.windowFrom.replace('T', ' ') + ' → ' + run.windowTo.replace('T', ' '))) : kv('Scope', 'full' + (run.scanned != null ? ', ' + fmt.num(run.scanned) + ' scanned, ' + fmt.num(run.matched) + ' matched' : '')),
          run.gate ? kv('Readiness gate', run.gate) : null, kv('Spec hash', h('span', { class: 'mono' }, run.specHash || '—')), kv('Engine', run.engineVersion + ' · catalog ' + (run.catalogChecksum || '')),
          Object.keys(run.lookupSnapshots || {}).length ? kv('Lookups used', Object.entries(run.lookupSnapshots).map(([k, v]) => k + ' (' + v + ')').join(', ')) : null,
          run.retryOfRunId ? kv('Retry of', h('a', { href: '#/runs/' + run.retryOfRunId }, run.retryOfRunId)) : null,
          run.sqlText ? h('details', { class: 'mt' }, h('summary', { style: { cursor: 'pointer', color: 'var(--accent-ink)', fontSize: '13px' } }, 'Show the SQL that ran'), h('pre', { class: 'sql mt' }, run.sqlText), run.boundParams ? h('div', { class: 'small muted mt' }, 'Bound parameters: ' + JSON.stringify(run.boundParams)) : null) : null),
        run.attempts && run.attempts.length ? h('div', { class: 'card pad' }, h('div', { class: 'eyebrow mb' }, 'Delivery attempts'), run.attempts.map(a => kv('#' + a.attemptNo + ' ' + fmt.time(a.at), h('span', { class: a.status === 'OK' ? 'risk LOW' : 'risk HIGH' }, a.status + (a.receipt ? ' · ' + a.receipt.replace(/^.*mft[\\/]/, 'mft/') : '') + (a.message ? ' · ' + a.message : ''))))) : null,
        isSample && version.status === 'SAMPLED' ? h('div', { class: 'card pad', style: { background: 'var(--accent-soft-2)', borderColor: 'var(--accent)' } }, h('div', { class: 'eyebrow mb' }, 'Next'), h('p', { class: 'small' }, 'Send this file to the vendor\'s test route and wait for their sign-off, then request approval. Any edit to the layout deletes this sample and takes v' + run.versionNo + ' back to draft.')) : null,
        isSample ? h('div', { class: 'small muted' }, 'Every preview and download is recorded in the audit log with your name, the row count and whether it was masked.') : null);

      root.append(head, h('div', { class: 'split', style: { gridTemplateColumns: 'minmax(0,1fr) 360px' } }, left, right));
    }
  });

  function kv(k, v) { return h('div', { class: 'kv' }, h('span', {}, k), h('span', {}, v)); }
  function describe(run) {
    if (run.mode === 'SAMPLE') return 'Built ' + fmt.ago(run.startedAt) + ' from ' + (run.synthetic ? 'synthetic data' : 'real data') + (run.masked ? ', PHI masked with format-preserving pseudonyms' : ', unmasked') + '. ' + fmt.num(run.rowCount) + ' rows.';
    if (run.status === 'TRANSFERRED') return 'Delivered ' + fmt.ago(run.deliveredAt) + ' and acknowledged by the vendor through Axway.';
    if (run.status === 'DELIVERED') return 'In the Axway drop folder since ' + fmt.time(run.deliveredAt) + ', waiting for pick-up.';
    if (run.status === 'HELD') return 'The quality gate stopped this file before delivery.';
    if (run.status === 'FAILED') return 'The run stopped. Nothing was sent to the vendor and the watermark did not move.';
    if (run.status === 'SKIPPED_EMPTY') return 'No rows in the window; the zero-row policy skipped delivery.';
    return fmt.title(run.status) + ' · started ' + fmt.ago(run.startedAt);
  }
  function gridOf(run) {
    const rows = run.previewRows || [];
    const masked = new Set(run.maskedFields || []);
    if (!rows.length) return h('div', { class: 'card' }, h('div', { class: 'empty' }, run.status === 'DELETED' ? 'The file was deleted when the layout changed.' : run.archived ? 'The file was purged by retention; the run record is kept for audit.' : 'No rows to show.'));
    const headers = Object.keys(rows[0]);
    let showRaw = false;
    const body = h('div');
    const draw = () => {
      App.clear(body);
      if (showRaw) body.appendChild(h('div', { class: 'preview-lines' }, (run.previewLines || []).join('\n')));
      else body.appendChild(h('div', { class: 'table-wrap' }, h('table', { class: 'table grid compact' }, h('thead', {}, h('tr', {}, h('th', { class: 'sub' }, '#'), headers.map(x => h('th', {}, x)))),
        h('tbody', {}, rows.slice(0, 25).map((r, i) => h('tr', {}, h('td', { class: 'sub' }, i + 1), headers.map(x => h('td', { class: masked.has(x) ? 'masked' : '' }, r[x] === null || r[x] === undefined ? '' : r[x]))))))));
    };
    draw();
    return h('div', { class: 'card' }, h('div', { class: 'card-head' }, h('h3', {}, 'First ' + Math.min(25, rows.length) + ' of ' + fmt.num(run.rowCount) + ' rows'), h('span', { class: 'spacer' }), masked.size ? h('span', { class: 'sub' }, 'Shaded cells are masked; widths and code sets are preserved.') : null, h('button', { class: 'btn xs', type: 'button', onclick: () => { showRaw = !showRaw; draw(); } }, showRaw ? 'Show as grid' : 'Show as raw lines')), body);
  }
  async function download(run) {
    const res = await fetch('/api/v1/runs/' + run.id + '/download', { headers: { 'X-Demo-User': state.user } });
    if (!res.ok) { const e = await res.json().catch(() => ({})); toast(e.error || 'Download failed', 'danger'); return; }
    const blob = await res.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = run.fileName;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
    toast('Downloaded ' + run.fileName + (run.masked ? ' (masked)' : ' (unmasked, audited)'), 'ok');
  }
  function requestApproval(run, version) {
    const note = h('textarea', { class: 'input', rows: 3, placeholder: 'What changed and why, for the approver' });
    const acc = h('input', { class: 'input', placeholder: 'e.g. Email from vendor contact, 09/18' });
    modal({ title: 'Request approval for v' + run.versionNo, body: h('div', { class: 'stack' }, h('p', { class: 'small muted' }, 'The approver sees the layout, the diff against the live version and this sample. The layout is frozen from here; any change becomes a new version.'),
      h('div', { class: 'field' }, h('label', {}, 'Note to the approver'), note), h('div', { class: 'field' }, h('label', {}, 'Vendor acceptance'), acc, h('div', { class: 'help' }, 'How the vendor confirmed the sample. Recorded on the version.'))),
      actions: [{ label: 'Cancel' }, { label: 'Send for approval', kind: 'primary', icon: 'send', onClick: async () => { await post('/api/v1/definitions/' + run.definitionId + '/versions/' + run.versionNo + '/request-approval', { note: note.value, vendorAcceptance: acc.value }); toast('Sent for approval', 'ok'); location.hash = '#/definitions/' + run.definitionId + '/v/' + run.versionNo; } }] });
  }
  App.requestApproval = requestApproval;
  App.downloadRun = download;
})();
