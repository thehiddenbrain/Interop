(function () {
  const { h, icon, fmt, pill, get, post, crumbs, registerPage, state, toast } = App;

  registerPage('dashboard', {
    async render(root) {
      crumbs([{ label: 'Dashboard' }]);
      const d = await get('/api/v1/dashboard');
      state.pendingApprovals = d.pendingApprovals;
      App.render_nav && App.render_nav();
      const head = h('div', { class: 'page-head' },
        h('div', {}, h('div', { class: 'eyebrow' }, 'Vendor data exchange'), h('h1', {}, 'Good ' + partOfDay() + ', ' + state.me.name.split(' ')[0]),
          h('p', { class: 'lead' }, d.live + ' feeds are live for ' + d.vendors + ' vendors. ' + (d.attention.length ? d.attention.length + ' item' + (d.attention.length === 1 ? '' : 's') + ' need a person.' : 'Nothing needs a person right now.'))),
        h('div', { class: 'actions' }, h('a', { class: 'btn', href: '#/import' }, icon('wand'), 'Import a vendor spec'), h('a', { class: 'btn primary', href: '#/definitions/new' }, icon('plus'), 'New definition')));

      const kpis = h('div', { class: 'grid4' },
        kpi(d.live, 'Live feeds', d.definitions + ' definitions · ' + d.pendingApprovals + ' waiting for approval', 'accent'),
        kpi(d.onTimePct + '%', 'Delivered on time, 30 days', fmt.num(d.delivered30d) + ' deliveries · ' + fmt.num(d.rowsDelivered30d) + ' rows', d.onTimePct >= 95 ? 'accent' : 'warn'),
        kpi(d.held + d.failed30d, 'Held or failed', d.held + ' held before delivery · ' + d.failed30d + ' failed in 30 days', d.held + d.failed30d ? 'warn' : ''),
        kpi(fmt.num(d.hoursSaved) + ' h', 'Developer hours saved', d.feedsBuilt + ' feeds and ' + d.changesShipped + ' layout changes without a developer', 'accent'));

      const attention = h('div', { class: 'card' }, h('div', { class: 'card-head' }, h('h3', {}, 'Needs a person'), h('span', { class: 'spacer' }), h('span', { class: 'sub' }, d.attention.length + ' open')),
        h('div', { class: 'card-body' }, d.attention.length ? h('div', { class: 'list' }, d.attention.map(a => h('div', { class: 'list-item' }, pill(a.kind === 'HELD' ? 'held' : a.kind === 'FAILED' ? 'failed' : a.kind === 'APPROVAL' ? 'pending_approval' : 'warn', a.kind === 'APPROVAL' ? 'Approval' : a.kind === 'CONTRACT' ? 'Contract' : fmt.title(a.kind)),
          h('div', { style: { minWidth: 0 } }, h('div', { class: 't' }, a.title), h('div', { class: 'd' }, a.detail || '')),
          h('div', { class: 'when' }, a.runId ? h('a', { class: 'btn xs', href: '#/runs/' + a.runId }, 'Open run') : a.kind === 'APPROVAL' ? h('a', { class: 'btn xs', href: '#/approvals' }, 'Review') : a.definitionId ? h('a', { class: 'btn xs', href: '#/definitions/' + a.definitionId }, 'Open') : h('a', { class: 'btn xs', href: '#/partners' }, 'Partners'))))) :
          h('div', { class: 'empty' }, h('b', {}, 'All clear'), 'No holds, failures or approvals waiting.')));

      const upcoming = h('div', { class: 'card' }, h('div', { class: 'card-head' }, h('h3', {}, 'Live feeds and next runs'), h('span', { class: 'spacer' }), h('a', { class: 'btn xs', href: '#/runs' }, 'All runs')),
        h('div', { class: 'table-wrap' }, h('table', { class: 'table compact' }, h('thead', {}, h('tr', {}, h('th', {}, 'Feed'), h('th', {}, 'Vendor'), h('th', {}, 'Last run'), h('th', {}, 'Next run'), h('th', {}, 'SLA'), h('th', {}))),
          h('tbody', {}, d.upcoming.length ? d.upcoming.map(u => h('tr', { class: 'click', onclick: () => location.hash = '#/definitions/' + u.definitionId },
            h('td', {}, h('b', {}, u.name), ' ', h('span', { class: 'tag' }, 'v' + u.versionNo)), h('td', { class: 'mono' }, u.vendorCode),
            h('td', {}, u.lastRun ? [pill(u.lastRun.status), ' ', h('span', { class: 'sub' }, fmt.ago(u.lastRun.at) + (u.lastRun.rows ? ' · ' + fmt.num(u.lastRun.rows) + ' rows' : ''))] : h('span', { class: 'sub' }, 'never')),
            h('td', {}, u.paused ? pill('warn', 'Paused') : h('span', {}, fmt.dt(u.nextFireAt))), h('td', { class: 'sub' }, u.sla ? 'by ' + u.sla : '—'),
            h('td', { class: 'right' }, h('button', { class: 'btn xs', type: 'button', onclick: async e => { e.stopPropagation(); await runNow(u.definitionId); } }, icon('play', 12), 'Run now')))) :
            h('tr', {}, h('td', { colspan: 6, class: 'empty' }, 'No live feeds yet.'))))));

      const days = d.deliveriesByDay;
      const max = Math.max(1, ...days.map(x => x.delivered + x.failed + x.held));
      const chart = h('div', { class: 'card pad' }, h('div', { class: 'section-title' }, h('h2', {}, 'Deliveries, last 30 days'), h('span', { class: 'spacer' }), h('div', { class: 'legend' }, h('span', {}, h('i'), 'delivered'), h('span', {}, h('i', { class: 'h' }), 'held'), h('span', {}, h('i', { class: 'f' }), 'failed'))),
        h('div', { class: 'spark' }, days.map(x => {
          const total = x.delivered + x.failed + x.held;
          const cls = x.failed ? 'f' : x.held ? 'h' : '';
          return h('i', { class: cls, title: x.day + ': ' + x.delivered + ' delivered' + (x.failed ? ', ' + x.failed + ' failed' : '') + (x.held ? ', ' + x.held + ' held' : ''), style: { height: Math.max(4, Math.round(44 * total / max)) + 'px', opacity: total ? '' : '.18' } });
        })),
        h('div', { class: 'row small muted', style: { justifyContent: 'space-between', marginTop: '6px' } }, h('span', {}, fmt.date(days[0].day)), h('span', {}, 'today')));

      const comp = d.compliance;
      const compliance = h('div', { class: 'card pad' }, h('div', { class: 'section-title' }, h('h2', {}, 'Minimum necessary'), h('span', { class: 'spacer' }), h('a', { class: 'btn xs', href: '#/compliance' }, 'Compliance')),
        h('div', { class: 'grid2' },
          h('div', { class: 'kv' }, h('span', {}, 'Distinct PHI elements shared'), h('span', {}, comp.distinctPhiElementsShared)),
          h('div', { class: 'kv' }, h('span', {}, 'Vendors receiving PHI'), h('span', {}, comp.vendorsWithPhi + ' of ' + comp.vendors)),
          h('div', { class: 'kv' }, h('span', {}, 'PHI without a BAA'), h('span', { class: comp.vendorsWithoutBaa ? 'risk HIGH' : '' }, comp.vendorsWithoutBaa)),
          h('div', { class: 'kv' }, h('span', {}, 'PHI access events logged'), h('span', {}, comp.phiAccessEvents))));

      const activity = h('div', { class: 'card' }, h('div', { class: 'card-head' }, h('h3', {}, 'Recent activity'), h('span', { class: 'spacer' }), h('a', { class: 'btn xs', href: '#/audit' }, 'Audit log')),
        h('div', { class: 'card-body' }, h('div', { class: 'list' }, d.recentActivity.map(a => h('div', { class: 'list-item' }, h('span', { class: 'avatar', style: { width: '26px', height: '26px', fontSize: '11px' } }, (a.actorName || '?').split(' ').map(s => s[0]).join('').slice(0, 2)),
          h('div', { style: { minWidth: 0 } }, h('div', { class: 't' }, a.actorName, ' ', h('span', { class: 'muted', style: { fontWeight: 400 } }, fmt.status(a.action))), h('div', { class: 'd' }, (a.vendorCode || '') + (a.phi ? ' · PHI' : ''))),
          h('div', { class: 'when' }, fmt.ago(a.at)))))));

      root.append(head, kpis, h('div', { class: 'grid2 mt' }, attention, upcoming), h('div', { class: 'grid2 mt' }, chart, compliance), h('div', { class: 'mt' }, activity));
    }
  });

  function kpi(v, l, s, cls) { return h('div', { class: 'card kpi' }, h('div', { class: 'l' }, l), h('div', { class: 'v ' + (cls || '') }, v), h('div', { class: 's' }, s)); }
  function partOfDay() { const hr = new Date().getHours(); return hr < 12 ? 'morning' : hr < 17 ? 'afternoon' : 'evening'; }
  async function runNow(id) {
    const r = await post('/api/v1/definitions/' + id + '/run-now');
    toast(r.status === 'DELIVERED' ? 'Delivered ' + r.fileName + ' with ' + fmt.num(r.rowCount) + ' rows' : r.status === 'HELD' ? 'Held before delivery: ' + r.heldReason : r.status + (r.error ? ': ' + r.error : ''), r.status === 'DELIVERED' ? 'ok' : r.status === 'FAILED' ? 'danger' : '');
    location.hash = '#/runs/' + r.id;
  }
})();
