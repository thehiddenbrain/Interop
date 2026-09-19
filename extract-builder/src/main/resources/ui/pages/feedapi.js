(function () {
  const { h, icon, fmt, pill, get, crumbs, registerPage, state, toast } = App;

  registerPage('feedapi', {
    async render(root) {
      crumbs([{ label: 'Feed API' }]);
      const [feeds, partners] = await Promise.all([get('/api/v1/feeds'), get('/api/v1/partners')]);
      const byCode = Object.fromEntries(partners.map(p => [p.code, p]));
      root.appendChild(h('div', { class: 'page-head' }, h('div', {}, h('h1', {}, 'One definition, file or API'), h('p', { class: 'lead' }, 'A vendor that prefers pulling over receiving files calls the same production layout as JSON: same fields, rules, filters and audit. Enable it per partner and hand them the key.'))));
      const out = h('pre', { class: 'code wrap', style: { minHeight: '120px' } }, 'Pick a feed and call it.');
      const rows = feeds.map(f => {
        const p = byCode[f.vendorCode] || {};
        const key = h('input', { class: 'input sm mono', value: p.apiKey || '', placeholder: p.apiEnabled ? 'API key' : 'API disabled for this partner', style: { width: '260px' } });
        return h('tr', {}, h('td', {}, h('div', { style: { fontWeight: 500 } }, f.name), h('div', { class: 'sub mono' }, 'GET ' + f.path + '?page=1&size=25')), h('td', { class: 'mono' }, f.vendorCode), h('td', {}, f.apiEnabled ? pill('ok', 'Enabled') : pill('retired', 'Off')), h('td', { class: 'small' }, f.fields.slice(0, 6).join(', ') + (f.fields.length > 6 ? ' +' + (f.fields.length - 6) : '')), h('td', {}, h('div', { class: 'row', style: { gap: '6px', flexWrap: 'nowrap' } }, key, h('button', { class: 'btn sm', type: 'button', disabled: !f.apiEnabled, onclick: async () => {
          out.textContent = 'GET ' + f.path + '?page=1&size=25\nX-Api-Key: ' + key.value.slice(0, 8) + '…\n\n…';
          const res = await fetch(f.path + '?page=1&size=25', { headers: { 'X-Api-Key': key.value, 'X-Demo-User': state.user } });
          const data = await res.json();
          out.textContent = 'HTTP ' + res.status + '\n' + JSON.stringify(data, null, 2).slice(0, 6000);
        } }, icon('play', 12), 'Call'))));
      });
      root.append(h('div', { class: 'card' }, h('div', { class: 'table-wrap' }, h('table', { class: 'table compact' }, h('thead', {}, h('tr', {}, h('th', {}, 'Feed'), h('th', {}, 'Vendor'), h('th', {}, 'API'), h('th', {}, 'Fields'), h('th', {}, 'Try it'))), h('tbody', {}, rows.length ? rows : h('tr', {}, h('td', { colspan: 5, class: 'empty' }, 'No feeds in production.')))))),
        h('div', { class: 'grid2 mt' }, h('div', { class: 'card pad' }, h('div', { class: 'eyebrow mb' }, 'Response'), out),
          h('div', { class: 'card pad' }, h('div', { class: 'eyebrow mb' }, 'How it works'), h('ul', { class: 'small', style: { paddingLeft: '18px', margin: 0, color: 'var(--muted)' } }, h('li', {}, 'The production version is compiled and run through the same engine, without masking, and paginated.'), h('li', {}, 'The response carries the version number and spec hash, so the vendor can tell when the layout changed.'), h('li', {}, 'Every call is an audit event with the vendor as the actor and the PHI flag set.'), h('li', {}, 'Keys are per partner; rotate them from the partner record. Enable the API under MFT partners.'), h('li', {}, 'Full API reference: ', h('a', { href: '/swagger-ui.html', target: '_blank' }, 'Swagger UI'), '.')))));
    }
  });
})();
