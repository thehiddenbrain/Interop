(function () {
  const { h, icon, fmt, pill, get, post, crumbs, registerPage, state, catalog, toast } = App;

  registerPage('catalog', {
    async render(root, params) {
      crumbs([{ label: 'Catalog' }]);
      const c = await catalog(true);
      root.appendChild(h('div', { class: 'page-head' }, h('div', {}, h('h1', {}, 'Catalog'), h('p', { class: 'lead' }, 'What analysts can pick from and how it maps to the source. Developers maintain it as a file under version control, so code review is the approval workflow. Version ' + c.version + ', checksum ' + c.checksum + '.')),
        h('div', { class: 'actions' }, state.me.role === 'ADMIN' ? h('button', { class: 'btn', type: 'button', onclick: async () => { const r = await post('/api/v1/catalog/reload'); toast(r.message, 'ok'); App.render(); } }, icon('refresh'), 'Reload from file') : null)));
      let entity = params.element ? (c.elementById[params.element] || {}).entity || c.entities[0].id : c.entities[0].id;
      const tabs = h('div', { class: 'tabs' });
      const body = h('div');
      const draw = () => {
        App.clear(tabs);
        for (const en of c.entities) tabs.appendChild(h('button', { class: 'tab' + (en.id === entity ? ' on' : ''), type: 'button', onclick: () => { entity = en.id; draw(); } }, en.name, h('span', { class: 'cnt' }, en.elements)));
        App.clear(body);
        const en = c.entityById[entity];
        const els = c.elements.filter(e => e.entity === entity);
        body.append(h('div', { class: 'card-body' }, h('div', { class: 'grid4' }, kv('Source table', h('span', { class: 'mono' }, en.table)), kv('Demo file', h('span', { class: 'mono' }, en.source + ' · ' + fmt.num(en.rows) + ' rows')), kv('Primary key', en.primaryKey.join(', ')), kv('Watermark column', en.watermarkColumn || 'none'), en.readinessCheck ? kv('Readiness gate', en.readinessCheck) : null)),
          h('div', { class: 'table-wrap' }, h('table', { class: 'table compact' }, h('thead', {}, h('tr', {}, h('th', {}, 'Element'), h('th', {}, 'Column'), h('th', {}, 'Type'), h('th', {}, 'Sensitivity'), h('th', {}, 'Filterable'), h('th', {}, 'Example'), h('th', { class: 'right' }, 'Used by'), h('th'))),
            h('tbody', {}, els.map(e => h('tr', { class: params.element === e.id ? 'selected' : '' }, h('td', {}, h('div', { style: { fontWeight: 500 } }, e.name), e.description ? h('div', { class: 'sub' }, e.description) : null, e.aliases && e.aliases.length ? h('div', { class: 'sub' }, 'also: ' + e.aliases.slice(0, 5).join(', ')) : null), h('td', { class: 'mono small' }, e.column), h('td', {}, e.type.toLowerCase()), h('td', {}, e.restricted ? h('span', { class: 'pill restricted' }, 'RESTRICTED') : e.phi ? h('span', { class: 'pill phi' }, 'PHI') : h('span', { class: 'sub' }, 'none')), h('td', {}, e.filterable ? 'yes' + (e.values ? ' · ' + e.values.join(', ') : '') : h('span', { class: 'sub' }, 'no')), h('td', { class: 'mono small muted' }, e.example || ''), h('td', { class: 'num' }, e.usedBy), h('td', { class: 'right' }, h('button', { class: 'btn xs', type: 'button', onclick: () => impact(e) }, 'Impact'))))))));
      };
      draw();
      root.appendChild(h('div', { class: 'card' }, h('div', { style: { padding: '0 14px' } }, tabs), body));
      root.appendChild(h('div', { class: 'grid2 mt' },
        h('div', { class: 'card' }, h('div', { class: 'card-head' }, h('h3', {}, 'Join paths'), h('span', { class: 'spacer' }), h('span', { class: 'sub' }, 'how entities reach each other')), h('div', { class: 'table-wrap' }, h('table', { class: 'table compact' }, h('thead', {}, h('tr', {}, h('th', {}, 'From'), h('th', {}, 'To'), h('th', {}, 'Cardinality'), h('th', {}, 'Selectors'))), h('tbody', {}, c.joinPaths.map(j => h('tr', {}, h('td', { class: 'mono small' }, j.from), h('td', { class: 'mono small' }, j.to + (j.via ? ' via ' + j.via : '')), h('td', {}, j.cardinality === 'MANY' ? pill('warn', 'many') : pill('ok', 'one')), h('td', { class: 'small' }, (j.selectors || []).map(s => s.label).join(' · ') || (j.label || '—')))))))),
        h('div', { class: 'card' }, h('div', { class: 'card-head' }, h('h3', {}, 'Lookups'), h('span', { class: 'spacer' }), h('span', { class: 'sub' }, 'crosswalks and reference tables')), h('div', { class: 'table-wrap' }, h('table', { class: 'table compact' }, h('thead', {}, h('tr', {}, h('th', {}, 'Lookup'), h('th', {}, 'Key'), h('th', {}, 'Returns'), h('th', {}, 'Owner'), h('th'))), h('tbody', {}, c.lookups.map(l => h('tr', {}, h('td', {}, h('div', { style: { fontWeight: 500 } }, l.name), h('div', { class: 'sub mono' }, l.id + (l.refresh ? ' · ' + l.refresh : ''))), h('td', { class: 'mono small' }, l.keyColumns.join(', ')), h('td', { class: 'mono small' }, l.columns.join(', ')), h('td', { class: 'small' }, l.owner || ''), h('td', { class: 'right' }, h('button', { class: 'btn xs', type: 'button', onclick: async () => { const rows = await get('/api/v1/catalog/lookups/' + l.id + '/preview'); App.modal({ title: l.name, body: h('div', { class: 'table-wrap' }, h('table', { class: 'table grid compact' }, h('thead', {}, h('tr', {}, Object.keys(rows[0] || {}).map(k => h('th', {}, k)))), h('tbody', {}, rows.map(r => h('tr', {}, Object.values(r).map(v => h('td', {}, String(v)))))))), actions: [{ label: 'Close' }] }); } }, 'Peek')))))))),
        h('div', { class: 'card' }, h('div', { class: 'card-head' }, h('h3', {}, 'Filter templates'), h('span', { class: 'spacer' }), h('span', { class: 'sub' }, 'developer-written predicates analysts can apply')), h('div', { class: 'card-body' }, c.filterTemplates.map(t => h('div', { class: 'kv' }, h('span', {}, h('b', {}, t.label), h('div', { class: 'sub' }, t.description)), h('span', { class: 'mono small' }, t.sql))))),
        h('div', { class: 'card' }, h('div', { class: 'card-head' }, h('h3', {}, 'Rule set'), h('span', { class: 'spacer' }), h('span', { class: 'sub' }, c.rules.length + ' closed rules')), h('div', { class: 'card-body' }, c.rules.map(r => h('div', { class: 'kv' }, h('span', {}, h('span', { class: 'chip' }, r.type), ' ', r.label), h('span', { class: 'small muted', style: { maxWidth: '60%' } }, r.description)))))));
      if (params.element && c.elementById[params.element]) impact(c.elementById[params.element]);
    }
  });

  function kv(k, v) { return h('div', { class: 'kv' }, h('span', {}, k), h('span', {}, v)); }
  async function impact(e) {
    const r = await get('/api/v1/catalog/impact/' + e.id);
    App.drawer('Change impact · ' + e.name, h('div', { class: 'stack' },
      h('div', { class: 'alert ' + (r.liveFeeds ? 'warn' : 'ok') }, icon(r.liveFeeds ? 'warn' : 'check'), h('div', {}, r.liveFeeds ? h('b', {}, r.liveFeeds + ' live feed' + (r.liveFeeds === 1 ? '' : 's') + ' to ' + r.vendors + ' vendor' + (r.vendors === 1 ? '' : 's') + ' would break') : h('b', {}, 'No live feed uses this element'), h('div', { class: 'small' }, 'if ' + e.column + ' on ' + e.entity + ' were renamed or retired. Element keys are stable, so a source rename only needs the catalog mapping updated.'))),
      r.usages.length ? r.usages.map(u => h('div', { class: 'list-item' }, pill(u.status), h('div', {}, h('div', { class: 't' }, h('a', { href: '#/definitions/' + u.definitionId + '/v/' + u.versionNo }, u.name + ' v' + u.versionNo)), h('div', { class: 'd' }, u.vendorCode + ' · ' + u.where.join(', '))))) : h('div', { class: 'empty' }, 'Not used by any open or live version.')));
  }
})();
