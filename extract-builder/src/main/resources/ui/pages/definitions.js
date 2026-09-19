(function () {
  const { h, icon, fmt, pill, get, post, crumbs, registerPage, state, modal, toast, catalog } = App;

  registerPage('definitions', {
    async render(root, params, query) {
      crumbs([{ label: 'Definitions' }]);
      const [defs, partners] = await Promise.all([get('/api/v1/definitions'), get('/api/v1/partners')]);
      const partnerByCode = Object.fromEntries(partners.map(p => [p.code, p]));
      const templates = defs.filter(d => d.template);
      const feeds = defs.filter(d => !d.template);
      const filters = { q: '', area: '', status: '' };

      const head = h('div', { class: 'page-head' },
        h('div', {}, h('h1', {}, 'Extract definitions'), h('p', { class: 'lead' }, 'Every vendor file layout, its versions and where each one is in its life. A layout is data: pick elements, order them, add rules, sample, approve, go live.')),
        h('div', { class: 'actions' }, h('a', { class: 'btn', href: '#/import' }, icon('wand'), 'Import a vendor spec'), h('button', { class: 'btn primary', type: 'button', onclick: () => newDefinition(partners, templates) }, icon('plus'), 'New definition')));

      const q = h('input', { class: 'input', type: 'search', placeholder: 'Search by name or vendor', style: { width: '300px' }, oninput: e => { filters.q = e.target.value.toLowerCase(); draw(); } });
      const area = h('select', { class: 'input', style: { width: '180px' }, onchange: e => { filters.area = e.target.value; draw(); } }, h('option', { value: '' }, 'All subject areas'), h('option', { value: 'MEMBER' }, 'Member'), h('option', { value: 'CLAIM' }, 'Claim'), h('option', { value: 'PROVIDER' }, 'Provider'));
      const st = h('select', { class: 'input', style: { width: '190px' }, onchange: e => { filters.status = e.target.value; draw(); } }, h('option', { value: '' }, 'Any status'), ['DRAFT', 'SAMPLED', 'PENDING_APPROVAL', 'APPROVED', 'PRODUCTION', 'RETIRED'].map(s => h('option', { value: s }, fmt.title(s))));
      const counts = h('span', { class: 'sub' });
      const bar = h('div', { class: 'row', style: { marginBottom: '14px' } }, q, area, st, h('span', { class: 'topbar-spacer' }), counts);

      const tbody = h('tbody');
      const table = h('div', { class: 'card' }, h('div', { class: 'table-wrap' }, h('table', { class: 'table' }, h('thead', {}, h('tr', {}, h('th', {}, 'Definition'), h('th', {}, 'Vendor'), h('th', {}, 'Subject area'), h('th', {}, 'Versions'), h('th', {}, 'Last run'), h('th', {}, 'Next run'), h('th', {}, 'Owner'))), tbody)));

      function draw() {
        App.clear(tbody);
        let rows = 0, live = 0;
        for (const d of feeds) {
          if (filters.q && !(d.name.toLowerCase().includes(filters.q) || d.vendorCode.toLowerCase().includes(filters.q) || (partnerByCode[d.vendorCode] && partnerByCode[d.vendorCode].name.toLowerCase().includes(filters.q)))) continue;
          if (filters.area && d.subjectArea !== filters.area) continue;
          if (filters.status && !d.versions.some(v => v.status === filters.status)) continue;
          rows++;
          if (d.productionVersion) live++;
          const versions = d.versions.filter(v => v.status !== 'RETIRED');
          const retired = d.versions.length - versions.length;
          const partner = partnerByCode[d.vendorCode];
          const lr = d.lastRun;
          tbody.appendChild(h('tr', { class: 'click', onclick: () => location.hash = '#/definitions/' + d.id },
            h('td', {}, h('div', { style: { fontWeight: 500 } }, d.name), h('div', { class: 'sub clamp', style: { maxWidth: '380px' } }, d.description || '')),
            h('td', {}, h('div', { class: 'mono' }, d.vendorCode), partner ? h('div', { class: 'sub' }, partner.name) : null),
            h('td', {}, fmt.title(d.subjectArea), h('div', { class: 'sub' }, grainLabel(d))),
            h('td', {}, h('div', { class: 'row', style: { gap: '6px' } }, versions.map(v => h('span', { class: 'pill ' + v.status.toLowerCase(), title: v.changeNote || '' }, 'v' + v.versionNo + ' · ' + fmt.title(v.status))), retired ? h('span', { class: 'sub' }, '+' + retired + ' retired') : null)),
            h('td', {}, lr ? [pill(lr.status), h('div', { class: 'sub' }, fmt.ago(lr.at) + (lr.rows != null ? ' · ' + fmt.num(lr.rows) + ' rows' : ''))] : h('span', { class: 'sub' }, '—')),
            h('td', {}, d.nextFireAt ? fmt.dt(d.nextFireAt) : h('span', { class: 'sub' }, d.productionVersion ? 'scheduled' : '—')),
            h('td', { class: 'sub' }, d.owner || '')));
        }
        if (!rows) tbody.appendChild(h('tr', {}, h('td', { colspan: 7, class: 'empty' }, 'No definitions match.')));
        counts.textContent = rows + ' definitions · ' + live + ' in production';
      }
      draw();

      const tplCards = h('div', { class: 'grid3' }, templates.map(t => h('div', { class: 'card pad', style: { display: 'flex', flexDirection: 'column', gap: '8px' } },
        h('div', { class: 'eyebrow' }, fmt.title(t.subjectArea) + ' · ' + grainLabel(t)), h('h3', {}, t.name), h('p', { class: 'small muted', style: { flexGrow: 1 } }, t.description),
        h('div', { class: 'row' }, h('span', { class: 'sub' }, t.versions[0].fields + ' fields'), h('span', { class: 'topbar-spacer' }), h('button', { class: 'btn sm', type: 'button', onclick: () => newDefinition(partners, templates, t.id) }, 'Start from this'), h('a', { class: 'btn sm ghost', href: '#/definitions/' + t.id }, 'View')))));

      root.append(head, bar, table, h('div', { class: 'section-title', style: { marginTop: '28px' } }, h('h2', {}, 'Templates'), h('span', { class: 'muted small' }, 'Start a new vendor from a layout that already works.')), tplCards);
      if (params && location.hash.endsWith('/new')) newDefinition(partners, templates, query && query.template);
    }
  });

  function grainLabel(d) {
    const c = state.catalog;
    if (!c) return d.grain;
    const sa = c.subjectAreas.find(s => s.id === d.subjectArea);
    const g = sa && sa.grains.find(g => g.entity === d.grain);
    return g ? g.label.toLowerCase() : d.grain;
  }

  async function newDefinition(partners, templates, fromId) {
    const c = await catalog();
    const name = h('input', { class: 'input', placeholder: 'e.g. Acme Dental eligibility' });
    const vendor = h('select', { class: 'input' }, h('option', { value: '' }, 'Choose a vendor'), partners.filter(p => p.status !== 'DISABLED').map(p => h('option', { value: p.code }, p.name + ' (' + p.code + ')')));
    const area = h('select', { class: 'input' }, c.subjectAreas.map(s => h('option', { value: s.id }, s.name)));
    const grain = h('select', { class: 'input' });
    const from = h('select', { class: 'input' }, h('option', { value: '' }, 'Empty layout'), templates.map(t => h('option', { value: t.id }, 'Template: ' + t.name)));
    const desc = h('textarea', { class: 'input', rows: 2, placeholder: 'What this feed is for, in a sentence' });
    const drawGrains = () => { App.clear(grain); const sa = c.subjectAreas.find(s => s.id === area.value); sa.grains.forEach(g => grain.appendChild(h('option', { value: g.entity }, g.label))); };
    area.addEventListener('change', drawGrains);
    drawGrains();
    const applyTemplate = () => { const t = templates.find(x => x.id === from.value); if (t) { area.value = t.subjectArea; drawGrains(); grain.value = t.grain; } area.disabled = grain.disabled = !!t; };
    from.addEventListener('change', applyTemplate);
    if (fromId) { from.value = fromId; applyTemplate(); }
    modal({ title: 'New definition', body: h('div', { class: 'stack' },
      h('div', { class: 'field' }, h('label', {}, 'Name'), name),
      h('div', { class: 'field' }, h('label', {}, 'Vendor'), vendor, h('div', { class: 'help' }, 'Vendors are MFT partners. Add one under Configure › MFT partners.')),
      h('div', { class: 'field' }, h('label', {}, 'Start from'), from),
      h('div', { class: 'grid2' }, h('div', { class: 'field' }, h('label', {}, 'Subject area'), area), h('div', { class: 'field' }, h('label', {}, 'One row per'), grain, h('div', { class: 'help' }, 'The grain: what one output row stands for.'))),
      h('div', { class: 'field' }, h('label', {}, 'Description'), desc)),
      actions: [{ label: 'Cancel' }, { label: 'Create draft', kind: 'primary', icon: 'plus', onClick: async () => {
        if (!name.value.trim()) { toast('Give the definition a name', 'danger'); return false; }
        if (!vendor.value) { toast('Pick a vendor', 'danger'); return false; }
        const d = await post('/api/v1/definitions', { name: name.value, vendorCode: vendor.value, subjectArea: area.value, grain: grain.value, description: desc.value, fromDefinitionId: from.value || null });
        toast('Draft created', 'ok');
        location.hash = '#/definitions/' + d.id;
      } }] });
  }
})();
