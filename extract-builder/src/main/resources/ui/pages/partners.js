(function () {
  const { h, icon, fmt, pill, get, post, put, crumbs, registerPage, state, toast, modal, confirm } = App;

  registerPage('partners', {
    async render(root, params) {
      crumbs([{ label: 'MFT partners' }]);
      const partners = await get('/api/v1/partners');
      const isAdmin = state.me.role === 'ADMIN';
      root.appendChild(h('div', { class: 'page-head' }, h('div', {}, h('h1', {}, 'MFT partners'), h('p', { class: 'lead' }, 'Vendors as Axway partners: a test route and a production route, the BAA and contract on file, who applies PGP, and API access. Feeds stop by themselves when a contract ends.')),
        h('div', { class: 'actions' }, isAdmin ? h('button', { class: 'btn primary', type: 'button', onclick: () => edit(null) }, icon('plus'), 'New partner') : h('span', { class: 'sub' }, 'Admins edit partners'))));
      const today = new Date();
      root.appendChild(h('div', { class: 'card' }, h('div', { class: 'table-wrap' }, h('table', { class: 'table' }, h('thead', {}, h('tr', {}, h('th', {}, 'Partner'), h('th', {}, 'Routes'), h('th', {}, 'BAA'), h('th', {}, 'Contract'), h('th', {}, 'PGP'), h('th', {}, 'API'), h('th', {}, 'Status'), h('th'))),
        h('tbody', {}, partners.map(p => {
          const days = p.contractEnd ? Math.round((new Date(p.contractEnd) - today) / 86400000) : null;
          return h('tr', { class: 'click', onclick: () => detail(p) },
            h('td', {}, h('div', { style: { fontWeight: 500 } }, p.name), h('div', { class: 'sub' }, h('span', { class: 'mono' }, p.code), ' · ', p.category || '')),
            h('td', { class: 'small' }, h('div', {}, 'prod: ', h('span', { class: 'mono' }, p.prod.folder), ' · ', p.prod.mode === 'AXWAY_REST' ? 'REST' : 'drop folder'), h('div', { class: 'sub' }, 'test: ', h('span', { class: 'mono' }, p.test.folder))),
            h('td', {}, p.baaOnFile ? pill('ok', 'On file ' + (p.baaSignedDate || '')) : pill('danger', 'Missing')),
            h('td', {}, h('div', {}, (p.contractStart || '?') + ' → ' + (p.contractEnd || 'open')), days !== null && days <= 30 ? h('div', { class: 'small risk ' + (days < 0 ? 'HIGH' : 'MEDIUM') }, days < 0 ? 'ended ' + (-days) + ' days ago' : 'ends in ' + days + ' days') : null),
            h('td', { class: 'small' }, p.pgpBy === 'NONE' ? 'none' : (p.pgpBy === 'APP' ? 'app' : 'Axway') + (p.pgpKeyId ? ' · ' + p.pgpKeyId : '')),
            h('td', {}, p.apiEnabled ? pill('ok', 'Enabled') : h('span', { class: 'sub' }, 'off')),
            h('td', {}, pill(p.status === 'ACTIVE' ? 'ok' : 'retired', fmt.title(p.status))),
            h('td', { class: 'right' }, isAdmin ? h('button', { class: 'btn xs', type: 'button', onclick: e => { e.stopPropagation(); edit(p); } }, 'Edit') : null));
        }))))));
      if (params.code) { const p = partners.find(x => x.code === params.code); if (p) detail(p); }
    }
  });

  async function detail(p) {
    const mft = await get('/api/v1/partners/' + p.code + '/mft');
    const files = (list) => list.length ? h('ul', { class: 'small mono', style: { margin: 0, paddingLeft: '16px' } }, list.map(f => h('li', {}, f.name, ' ', h('span', { class: 'muted' }, fmt.bytes(f.bytes))))) : h('div', { class: 'small muted' }, 'empty');
    App.drawer(p.name, h('div', { class: 'stack' },
      h('div', { class: 'small muted' }, 'What Axway sees. Files in out are waiting for pick-up; files in sent were transferred and acknowledged.'),
      ['PROD', 'TEST'].map(r => h('div', { class: 'card pad' }, h('div', { class: 'eyebrow mb' }, r + ' route · ' + h('span', {}).textContent + mft[r].folder), h('div', { class: 'small', style: { fontWeight: 600 } }, 'out/'), files(mft[r].out), h('div', { class: 'small mt', style: { fontWeight: 600 } }, 'sent/'), files(mft[r].sent))),
      h('a', { class: 'btn sm', href: '#/compliance/' + p.code }, icon('shield', 13), 'Compliance report'), h('a', { class: 'btn sm', href: '#/audit?vendor=' + p.code }, icon('audit', 13), 'Audit trail')));
  }

  function edit(p) {
    const isNew = !p;
    p = p ? JSON.parse(JSON.stringify(p)) : { code: '', name: '', category: '', contactEmail: '', status: 'ACTIVE', baaOnFile: false, baaSignedDate: '', contractStart: '', contractEnd: '', pgpBy: 'AXWAY', pgpKeyId: '', pgpKeyExpires: '', test: { mode: 'DROP_FOLDER', folder: '', axwayAccount: '', ackExpected: true, transport: 'SFTP' }, prod: { mode: 'DROP_FOLDER', folder: '', axwayAccount: '', ackExpected: true, transport: 'SFTP' }, apiEnabled: false, apiKey: '', allowUnmaskedSamples: false, notes: '' };
    const f = (label, ctl, help) => h('div', { class: 'field' }, h('label', {}, label), ctl, help ? h('div', { class: 'help' }, help) : null);
    const txt = (obj, key, attrs) => h('input', Object.assign({ class: 'input', value: obj[key] || '', onchange: e => { obj[key] = e.target.value; } }, attrs || {}));
    const sel = (obj, key, options) => h('select', { class: 'input', onchange: e => { obj[key] = e.target.value; } }, options.map(o => h('option', { value: o[0], selected: obj[key] === o[0] }, o[1])));
    const chk = (obj, key, label) => h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: !!obj[key], onchange: e => { obj[key] = e.target.checked; } }), label);
    const route = (r, label) => h('div', { class: 'card pad', style: { background: 'var(--surface-2)' } }, h('div', { class: 'eyebrow mb' }, label), h('div', { class: 'grid2' }, f('Mode', sel(r, 'mode', [['DROP_FOLDER', 'Drop folder polled by Axway'], ['AXWAY_REST', 'Axway REST upload']])), f('Folder', txt(r, 'folder', { class: 'input mono', placeholder: p.code + '/prod' })), f('Axway account', txt(r, 'axwayAccount')), f('Transport to vendor', sel(r, 'transport', [['SFTP', 'SFTP'], ['AS2', 'AS2'], ['HTTPS', 'HTTPS']]))), h('div', { class: 'mt' }, chk(r, 'ackExpected', 'Vendor acknowledgement expected')));
    modal({ title: isNew ? 'New MFT partner' : 'Edit ' + p.name, wide: true, body: h('div', { class: 'stack' },
      h('div', { class: 'grid3' }, f('Vendor code', txt(p, 'code', { class: 'input mono', disabled: !isNew, placeholder: 'ACMEDENTAL' })), f('Name', txt(p, 'name')), f('Category', txt(p, 'category', { placeholder: 'e.g. Dental carve-out' }))),
      h('div', { class: 'grid3' }, f('Contact email', txt(p, 'contactEmail')), f('Status', sel(p, 'status', [['ACTIVE', 'Active'], ['DISABLED', 'Disabled']])), f('Notes', txt(p, 'notes'))),
      h('div', { class: 'grid3' }, f('Contract start', txt(p, 'contractStart', { type: 'date' })), f('Contract end', txt(p, 'contractEnd', { type: 'date' }), 'Feeds stop after this date.'), f('BAA signed', txt(p, 'baaSignedDate', { type: 'date' }))),
      h('div', { class: 'row' }, chk(p, 'baaOnFile', 'Business associate agreement on file'), chk(p, 'allowUnmaskedSamples', 'Allow unmasked samples (with the PHI privilege)')),
      h('div', { class: 'grid3' }, f('PGP applied by', sel(p, 'pgpBy', [['AXWAY', 'Axway'], ['APP', 'This app'], ['NONE', 'None']])), f('Vendor PGP key id', txt(p, 'pgpKeyId')), f('Key expires', txt(p, 'pgpKeyExpires', { type: 'date' }))),
      route(p.prod, 'Production route'), route(p.test, 'Test route'),
      h('div', { class: 'row' }, chk(p, 'apiEnabled', 'Feed API enabled'), p.apiKey ? h('span', { class: 'small mono muted' }, 'key ' + p.apiKey.slice(0, 12) + '…') : null)),
      actions: [{ label: 'Cancel' }, { label: isNew ? 'Create' : 'Save', kind: 'primary', onClick: async () => { if (!p.code || !p.name) { toast('Code and name are required', 'danger'); return false; } await (isNew ? post('/api/v1/partners', p) : put('/api/v1/partners/' + p.code, p)); toast('Partner saved', 'ok'); App.render(); } }] });
  }
})();
