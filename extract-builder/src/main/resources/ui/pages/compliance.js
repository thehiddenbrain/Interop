(function () {
  const { h, icon, fmt, pill, get, crumbs, registerPage, state, toast } = App;

  registerPage('compliance', {
    async render(root, params) {
      crumbs([{ label: 'Compliance' }]);
      const [summary, vendors] = await Promise.all([get('/api/v1/compliance/summary'), get('/api/v1/compliance/vendors')]);
      root.appendChild(h('div', { class: 'page-head' }, h('div', {}, h('h1', {}, 'Minimum necessary'), h('p', { class: 'lead' }, 'Because every layout is data, the app can answer the privacy office directly: which PHI elements each vendor receives, whether a BAA is on file, who pulled samples and whether they were masked, and what a contract end changes.')),
        h('div', { class: 'actions' }, h('button', { class: 'btn', type: 'button', onclick: exportCsv }, icon('download'), 'Export inventory (CSV)'))));
      root.appendChild(h('div', { class: 'grid4 mb' },
        kpi(summary.distinctPhiElementsShared, 'Distinct PHI elements shared', 'across all live and pending feeds'),
        kpi(summary.vendorsWithPhi + ' of ' + summary.vendors, 'Vendors receiving PHI', summary.vendorsWithoutBaa ? summary.vendorsWithoutBaa + ' without a BAA on file' : 'all with a BAA on file', summary.vendorsWithoutBaa ? 'danger' : 'accent'),
        kpi(summary.highRisk, 'High-risk vendors', 'no BAA, restricted unmasked, or contract ended', summary.highRisk ? 'danger' : 'accent'),
        kpi(summary.phiAccessEvents, 'PHI access events', 'unmasked previews, samples, downloads and API reads')));
      const tbody = h('tbody');
      for (const v of vendors) tbody.appendChild(h('tr', { class: 'click' + (params.code === v.vendorCode ? ' selected' : ''), onclick: () => location.hash = '#/compliance/' + v.vendorCode },
        h('td', {}, h('div', { style: { fontWeight: 500 } }, v.vendorName), h('div', { class: 'sub mono' }, v.vendorCode + (v.category ? ' · ' + v.category : ''))), h('td', { class: 'num' }, v.liveFeeds), h('td', { class: 'num' }, v.elementCount), h('td', { class: 'num ' + (v.phiCount ? 'risk MEDIUM' : '') }, v.phiCount), h('td', {}, v.baaOnFile ? pill('ok', 'On file') : v.phiCount ? pill('danger', 'Missing') : h('span', { class: 'sub' }, 'not needed')), h('td', {}, v.contractEnd || '—'), h('td', { class: 'num' }, v.sampleDownloads), h('td', { class: 'num ' + (v.unmaskedTouches ? 'risk MEDIUM' : '') }, v.unmaskedTouches), h('td', {}, h('span', { class: 'risk ' + v.risk, style: { fontWeight: 600 } }, v.risk))));
      root.appendChild(h('div', { class: 'card' }, h('div', { class: 'table-wrap' }, h('table', { class: 'table compact' }, h('thead', {}, h('tr', {}, h('th', {}, 'Vendor'), h('th', { class: 'right' }, 'Live feeds'), h('th', { class: 'right' }, 'Elements'), h('th', { class: 'right' }, 'PHI'), h('th', {}, 'BAA'), h('th', {}, 'Contract end'), h('th', { class: 'right' }, 'Sample downloads'), h('th', { class: 'right' }, 'Unmasked access'), h('th', {}, 'Risk'))), tbody))));
      if (params.code) {
        const v = vendors.find(x => x.vendorCode === params.code);
        if (v) root.appendChild(report(v));
      } else root.appendChild(h('div', { class: 'small muted mt' }, 'Click a vendor for its report: the elements it receives, the feeds that carry them and the flags to act on.'));
    }
  });

  function kpi(v, l, s, cls) { return h('div', { class: 'card kpi' }, h('div', { class: 'l' }, l), h('div', { class: 'v ' + (cls || '') }, v), h('div', { class: 's' }, s)); }
  function report(v) {
    return h('div', { class: 'card mt' }, h('div', { class: 'card-head' }, h('h3', {}, v.vendorName + ' · data sharing report'), h('span', { class: 'spacer' }), h('span', { class: 'risk ' + v.risk, style: { fontWeight: 600 } }, v.risk + ' risk'), h('a', { class: 'btn xs', href: '#/partners/' + v.vendorCode }, 'Partner'), h('a', { class: 'btn xs', href: '#/audit?vendor=' + v.vendorCode }, 'Audit trail')),
      h('div', { class: 'card-body' }, h('div', { class: 'grid2' },
        h('div', { class: 'stack' },
          v.flags.length ? h('div', { class: 'alert ' + (v.risk === 'HIGH' ? 'danger' : 'warn') }, icon('warn'), h('div', {}, h('b', {}, 'Flags'), h('ul', {}, v.flags.map(f => h('li', {}, f))))) : h('div', { class: 'alert ok' }, icon('check'), 'No flags. Sharing matches the agreement on file.'),
          kv('BAA', v.baaOnFile ? 'on file, signed ' + v.baaSignedDate : 'not on file'), kv('Contract', (v.contractStart || '?') + ' → ' + (v.contractEnd || 'open')), kv('PGP', v.pgpBy.toLowerCase()), kv('Sample downloads', v.sampleDownloads), kv('Unmasked PHI access', v.unmaskedTouches), kv('API reads', v.apiReads),
          h('div', { class: 'eyebrow', style: { marginTop: '8px' } }, 'Feeds'),
          v.feeds.map(f => h('div', { class: 'kv' }, h('span', {}, h('a', { href: '#/definitions/' + f.definitionId + '/v/' + f.versionNo }, f.name + ' v' + f.versionNo)), h('span', {}, pill(f.status), ' ', h('span', { class: 'sub' }, f.fields + ' fields, ' + f.phiFields + ' PHI' + (f.lastDelivered ? ' · last ' + fmt.date(f.lastDelivered) : '')))))),
        h('div', {}, h('div', { class: 'eyebrow mb' }, 'Elements received (' + v.elementCount + ')'), h('div', { class: 'table-wrap' }, h('table', { class: 'table compact' }, h('thead', {}, h('tr', {}, h('th', {}, 'Element'), h('th', {}, 'Sensitivity'), h('th', {}, 'Masked'), h('th', {}, 'Live'), h('th', {}, 'Feeds'))),
          h('tbody', {}, v.elements.map(e => h('tr', {}, h('td', {}, e.name, h('div', { class: 'sub mono' }, e.element)), h('td', {}, e.restricted ? h('span', { class: 'pill restricted' }, 'RESTRICTED') : e.phi ? h('span', { class: 'pill phi' }, 'PHI') : h('span', { class: 'sub' }, 'none')), h('td', {}, e.masked ? 'yes' : e.phi ? h('span', { class: e.restricted ? 'risk HIGH' : '' }, 'no') : '—'), h('td', {}, e.live ? 'yes' : 'pending'), h('td', { class: 'small' }, e.feeds.join(', ')))))))))));
  }
  function kv(k, v) { return h('div', { class: 'kv' }, h('span', {}, k), h('span', {}, v)); }
  async function exportCsv() {
    const res = await fetch('/api/v1/compliance/export.csv', { headers: { 'X-Demo-User': state.user } });
    const blob = await res.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'minimum-necessary-inventory.csv';
    a.click();
    toast('Exported; the export itself is in the audit log', 'ok');
  }
})();
