(function () {
  const { h, icon, fmt, pill, get, post, crumbs, registerPage, state, toast, modal, promptText } = App;

  registerPage('approvals', {
    async render(root) {
      crumbs([{ label: 'Approvals' }]);
      const defs = await get('/api/v1/definitions');
      const pending = [];
      for (const d of defs) for (const v of d.versions) if (v.status === 'PENDING_APPROVAL') pending.push({ d, v });
      const approved = [];
      for (const d of defs) for (const v of d.versions) if (v.status === 'APPROVED') approved.push({ d, v });
      state.pendingApprovals = pending.length;
      const isApprover = state.me.role === 'APPROVER' || state.me.role === 'ADMIN';
      root.appendChild(h('div', { class: 'page-head' }, h('div', {}, h('h1', {}, 'Approvals'), h('p', { class: 'lead' }, 'A version arrives here after the vendor accepted a sample. Approving freezes the layout; productionalizing puts it on a schedule.'))));
      if (!isApprover) root.appendChild(h('div', { class: 'alert info mb' }, icon('info'), h('div', {}, 'You are signed in as an analyst. Switch to Dev Patel (approver) from the user menu to approve or reject.')));

      root.appendChild(h('div', { class: 'section-title' }, h('h2', {}, 'Waiting for approval'), h('span', { class: 'sub' }, pending.length)));
      if (!pending.length) root.appendChild(h('div', { class: 'card' }, h('div', { class: 'empty' }, h('b', {}, 'Nothing waiting'), 'Analysts request approval from a sampled version.')));
      for (const { d, v } of pending) root.appendChild(await card(d, v, isApprover));

      root.appendChild(h('div', { class: 'section-title', style: { marginTop: '24px' } }, h('h2', {}, 'Approved, not yet live'), h('span', { class: 'sub' }, approved.length)));
      if (!approved.length) root.appendChild(h('div', { class: 'card' }, h('div', { class: 'empty' }, 'Everything approved is already in production.')));
      for (const { d, v } of approved) root.appendChild(h('div', { class: 'card pad mb', style: { display: 'flex', gap: '14px', alignItems: 'center', flexWrap: 'wrap' } },
        h('div', { style: { flexGrow: 1 } }, h('div', { class: 'row' }, h('b', {}, d.name), h('span', { class: 'tag' }, 'v' + v.versionNo), pill('approved')), h('div', { class: 'sub' }, 'Approved by ' + v.approvedBy + ' · ' + d.vendorCode)),
        h('a', { class: 'btn primary', href: '#/definitions/' + d.id + '/v/' + v.versionNo + '/productionalize' }, icon('bolt'), 'Productionalize'), h('a', { class: 'btn', href: '#/definitions/' + d.id + '/v/' + v.versionNo }, 'Open')));
    }
  });

  async function card(d, v, isApprover) {
    const [full, diff, runs] = await Promise.all([get('/api/v1/definitions/' + d.id), get('/api/v1/definitions/' + d.id + '/versions/' + v.versionNo + '/diff'), get('/api/v1/definitions/' + d.id + '/runs')]);
    const ver = full.definition.versions.find(x => x.versionNo === v.versionNo);
    const sample = runs.find(r => r.mode === 'SAMPLE' && r.versionNo === v.versionNo && r.status !== 'DELETED');
    const changed = (diff.fields || []).filter(f => f.change !== 'same');
    const restricted = [];
    for (const f of ver.spec.fields) for (const inp of f.inputs) { const el = state.catalog && state.catalog.elementById[inp.element]; if (el && el.restricted && !f.rules.some(r => r.type === 'MASK') && !(inp.rules || []).some(r => r.type === 'MASK')) restricted.push(f.header + ' (' + el.name + ')'); }
    const phiCount = ver.spec.fields.filter(f => f.inputs.some(i => { const el = state.catalog && state.catalog.elementById[i.element]; return el && el.phi; })).length;
    return h('div', { class: 'card mb' },
      h('div', { class: 'card-head' }, h('h3', {}, d.name), h('span', { class: 'tag' }, 'v' + v.versionNo), pill('pending_approval'), h('span', { class: 'spacer' }), h('span', { class: 'sub' }, 'Requested ' + fmt.ago(ver.approvalRequestedAt) + ' by ' + ver.approvalRequestedBy)),
      h('div', { class: 'card-body' }, h('div', { class: 'grid2' },
        h('div', { class: 'stack' },
          ver.approvalNote ? h('div', { class: 'alert info' }, icon('info'), h('div', {}, h('b', {}, 'From the analyst: '), ver.approvalNote)) : null,
          h('div', { class: 'kv' }, h('span', {}, 'Vendor acceptance'), h('span', {}, ver.vendorAcceptance || 'not recorded')),
          h('div', { class: 'kv' }, h('span', {}, 'Change vs live'), h('span', {}, diff.summary)),
          h('div', { class: 'kv' }, h('span', {}, 'Fields'), h('span', {}, ver.spec.fields.length + ' · ' + phiCount + ' carry PHI')),
          h('div', { class: 'kv' }, h('span', {}, 'Sample'), h('span', {}, sample ? [h('a', { href: '#/runs/' + sample.id }, sample.fileName), ' · ' + fmt.num(sample.rowCount) + ' rows' + (sample.masked ? ', masked' : ', unmasked') + (sample.warnings.length ? ' · ' + sample.warnings.length + ' warnings' : '')] : 'no sample on file')),
          h('div', { class: 'kv' }, h('span', {}, 'Spec hash'), h('span', { class: 'mono' }, ver.specHash + (sample && sample.specHash === ver.specHash ? ' · matches the sample' : ' · sample hash differs'))),
          restricted.length ? h('div', { class: 'alert warn' }, icon('warn'), h('div', {}, h('b', {}, 'Restricted elements go out unmasked: '), restricted.join(', '), '. Privacy sign-off is needed.')) : null),
        h('div', {}, h('div', { class: 'eyebrow mb' }, 'Layout changes'), changed.length ? h('div', { class: 'table-wrap' }, h('table', { class: 'table compact' }, h('thead', {}, h('tr', {}, h('th', {}, '#'), h('th', {}, 'Header'), h('th', {}, 'Change'))),
          h('tbody', {}, changed.map(f => h('tr', {}, h('td', { class: 'mono' }, f.position || f.oldPosition), h('td', { class: 'mono' }, f.header), h('td', {}, pill(f.change === 'added' ? 'ok' : f.change === 'removed' ? 'danger' : 'warn', f.change + (f.change === 'moved' ? ' from ' + f.oldPosition : '')))))))) : h('div', { class: 'small muted' }, diff.baseVersion ? 'No field changes; filters, sort or format may differ.' : 'First version of this feed.'))),
        h('div', { class: 'row mt', style: { justifyContent: 'flex-end' } },
          h('a', { class: 'btn', href: '#/definitions/' + d.id + '/v/' + v.versionNo }, icon('eye'), 'Open layout'),
          isApprover ? h('button', { class: 'btn danger', type: 'button', onclick: async () => { const note = await promptText('Reject v' + v.versionNo, 'Tell the analyst what to change', 'e.g. Header for DOB must be BIRTH_DT per the vendor guide', { multiline: true, required: true, okLabel: 'Reject', okKind: 'danger' }); if (note) { await post('/api/v1/definitions/' + d.id + '/versions/' + v.versionNo + '/reject', { note }); toast('Sent back to the analyst', 'ok'); App.render(); } } }, icon('x'), 'Reject') : null,
          isApprover ? h('button', { class: 'btn primary', type: 'button', onclick: async () => { const note = await promptText('Approve v' + v.versionNo + ' of ' + d.name, 'Approval note (optional)', 'e.g. Reviewed sample and vendor acceptance', { multiline: true, okLabel: 'Approve' }); if (note !== null) { await post('/api/v1/definitions/' + d.id + '/versions/' + v.versionNo + '/approve', { note }); toast('Approved. It can be productionalized now.', 'ok'); App.render(); } } }, icon('check'), 'Approve') : null)));
  }
})();
