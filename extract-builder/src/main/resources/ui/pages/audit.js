(function () {
  const { h, icon, fmt, pill, get, crumbs, registerPage, state } = App;

  registerPage('audit', {
    async render(root, params, query) {
      crumbs([{ label: 'Audit log' }]);
      const filters = { vendor: query.vendor || '', actor: '', phi: '', q: '' };
      const users = state.users;
      const head = h('div', { class: 'page-head' }, h('div', {}, h('h1', {}, 'Audit log'), h('p', { class: 'lead' }, 'Append-only. Every layout edit, preview, sample, download, approval, run, delivery and settings change, with the person, the role and whether PHI was touched.')));
      const vendorSel = h('select', { class: 'input', style: { width: '180px' }, onchange: e => { filters.vendor = e.target.value; load(); } }, h('option', { value: '' }, 'All vendors'));
      const actorSel = h('select', { class: 'input', style: { width: '180px' }, onchange: e => { filters.actor = e.target.value; load(); } }, h('option', { value: '' }, 'Anyone'), users.map(u => h('option', { value: u.id }, u.name)), h('option', { value: 'system' }, 'Scheduler'));
      const phiSel = h('select', { class: 'input', style: { width: '160px' }, onchange: e => { filters.phi = e.target.value; load(); } }, h('option', { value: '' }, 'All events'), h('option', { value: 'true' }, 'PHI touched'), h('option', { value: 'false' }, 'No PHI'));
      const q = h('input', { class: 'input', type: 'search', placeholder: 'Search action or details', style: { width: '260px' }, oninput: e => { filters.q = e.target.value.toLowerCase(); draw(); } });
      const count = h('span', { class: 'sub' });
      const tbody = h('tbody');
      let events = [];
      async function load() {
        const qs = new URLSearchParams({ limit: 400 });
        if (filters.vendor) qs.set('vendorCode', filters.vendor);
        if (filters.actor) qs.set('actor', filters.actor);
        if (filters.phi) qs.set('phi', filters.phi);
        events = await get('/api/v1/audit?' + qs);
        const vendors = [...new Set(events.map(e => e.vendorCode).filter(Boolean))].sort();
        if (vendorSel.children.length === 1) vendors.forEach(v => vendorSel.appendChild(h('option', { value: v, selected: v === filters.vendor }, v)));
        draw();
      }
      function draw() {
        App.clear(tbody);
        const list = events.filter(e => !filters.q || e.action.toLowerCase().includes(filters.q) || JSON.stringify(e.details || {}).toLowerCase().includes(filters.q) || (e.actorName || '').toLowerCase().includes(filters.q));
        count.textContent = list.length + ' events';
        if (!list.length) tbody.appendChild(h('tr', {}, h('td', { colspan: 6, class: 'empty' }, 'No events match.')));
        for (const e of list) tbody.appendChild(h('tr', {}, h('td', { class: 'nowrap' }, fmt.dt(e.at)), h('td', {}, h('div', {}, e.actorName), h('div', { class: 'sub' }, fmt.title(e.role))), h('td', {}, h('span', { class: 'chip ' + (e.phi ? '' : 'muted') }, fmt.status(e.action))), h('td', {}, e.definitionId && !e.definitionId.startsWith('partner:') ? h('a', { href: '#/definitions/' + e.definitionId }, e.targetType === 'run' ? 'run ' + e.targetId : e.targetId) : h('span', { class: 'mono small' }, e.targetId || '')), h('td', { class: 'mono small' }, e.vendorCode || ''), h('td', { class: 'small muted' }, e.phi ? [h('span', { class: 'pill phi' }, 'PHI'), ' '] : null, Object.entries(e.details || {}).filter(([k]) => k !== 'seeded').map(([k, v]) => k + ': ' + (typeof v === 'object' ? JSON.stringify(v) : v)).join(' · '))));
      }
      root.append(head, h('div', { class: 'row mb' }, q, vendorSel, actorSel, phiSel, h('span', { class: 'topbar-spacer' }), count),
        h('div', { class: 'card' }, h('div', { class: 'table-wrap' }, h('table', { class: 'table compact' }, h('thead', {}, h('tr', {}, h('th', {}, 'When'), h('th', {}, 'Who'), h('th', {}, 'Action'), h('th', {}, 'Target'), h('th', {}, 'Vendor'), h('th', {}, 'Details'))), tbody))));
      await load();
    }
  });
})();
