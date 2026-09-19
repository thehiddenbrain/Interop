(function () {
  const { h, icon, fmt, get, post, put, crumbs, registerPage, state, toast, confirm } = App;

  registerPage('settings', {
    async render(root) {
      crumbs([{ label: 'Settings' }]);
      const s = await get('/api/v1/settings');
      const isAdmin = state.me.role === 'ADMIN';
      root.appendChild(h('div', { class: 'page-head' }, h('div', {}, h('h1', {}, 'Settings'), h('p', { class: 'lead' }, 'Demo switches and the assumptions behind the dashboard. In production these come from configuration and the identity provider.'))));
      const f = (label, ctl, help) => h('div', { class: 'field' }, h('label', {}, label), ctl, help ? h('div', { class: 'help' }, help) : null);
      const num = (key, attrs) => h('input', Object.assign({ class: 'input', type: 'number', value: s[key], disabled: !isAdmin, onchange: e => { s[key] = +e.target.value; } }, attrs || {}));
      const chk = (key, label) => h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: !!s[key], disabled: !isAdmin, onchange: e => { s[key] = e.target.checked; } }), label);
      root.append(h('div', { class: 'grid2' },
        h('div', { class: 'card pad stack' }, h('div', { class: 'eyebrow' }, 'Axway simulator'), h('p', { class: 'small muted' }, 'Stands in for the Axway MFT poller: picks files up from the drop folders, moves them to sent, writes an acknowledgement and marks the run transferred.'),
          chk('axwaySimulator', 'Simulate Axway pick-up'), f('Pick-up delay', h('div', { class: 'row', style: { gap: '6px' } }, num('axwayPickupSeconds', { min: 1, max: 600, style: { width: '110px' } }), h('span', { class: 'sub' }, 'seconds after the file lands'))),
          h('div', { class: 'eyebrow', style: { marginTop: '8px' } }, 'Scheduler'), chk('schedulerEnabled', 'Fire production runs on their schedule'), h('div', { class: 'small muted' }, 'The tick runs every 15 seconds and checks each live feed against its cron in the partner timezone.')),
        h('div', { class: 'card pad stack' }, h('div', { class: 'eyebrow' }, 'Governance'), chk('allowSelfApproval', 'Allow the person who requested approval to approve it'), h('div', { class: 'small muted' }, 'Off by default: a second person approves. Turning it on is itself audited.'),
          f('Sample retention', h('div', { class: 'row', style: { gap: '6px' } }, num('sampleRetentionDays', { min: 1, max: 365, style: { width: '110px' } }), h('span', { class: 'sub' }, 'days before sample files are purged'))),
          h('div', { class: 'eyebrow', style: { marginTop: '8px' } }, 'Dashboard assumptions'), f('Developer hours per hand-built extract', num('hoursPerHandBuiltExtract', { min: 1, max: 400 }), 'Drives the "hours saved" tile: feeds built × this, plus a quarter of it per layout change.'))));
      root.append(h('div', { class: 'row mt' }, isAdmin ? h('button', { class: 'btn primary', type: 'button', onclick: async () => { await put('/api/v1/settings', s); toast('Settings saved', 'ok'); } }, icon('check'), 'Save settings') : h('span', { class: 'sub' }, 'Switch to Morgan Chen (admin) to change settings.'),
        h('span', { class: 'topbar-spacer' }), isAdmin ? h('button', { class: 'btn danger', type: 'button', onclick: async () => { if (await confirm('Reset the demo?', 'Definitions, partners, runs, samples, drop folders and the audit log go back to the seed. Takes a few seconds.', 'Reset', 'danger')) { await post('/api/v1/demo/reset'); toast('Demo data reset', 'ok'); App.state.catalog = null; location.hash = '#/'; App.render(); } } }, icon('refresh'), 'Reset demo data') : null));
      root.append(h('div', { class: 'card pad mt' }, h('div', { class: 'eyebrow mb' }, 'Demo users'), h('div', { class: 'table-wrap' }, h('table', { class: 'table compact' }, h('thead', {}, h('tr', {}, h('th', {}, 'Name'), h('th', {}, 'Role'), h('th', {}, 'Title'), h('th', {}, 'PHI unmasked'))), h('tbody', {}, state.users.map(u => h('tr', {}, h('td', {}, u.name), h('td', {}, fmt.title(u.role)), h('td', { class: 'sub' }, u.title), h('td', {}, u.phiUnmasked ? 'yes' : 'no'))))))),
        h('div', { class: 'small muted mt' }, 'Analysts create, edit and sample. Approvers approve, reject, release held files and productionalize. Admins maintain partners, the catalog and these settings. The PHI privilege is separate from the role.'));
    }
  });
})();
