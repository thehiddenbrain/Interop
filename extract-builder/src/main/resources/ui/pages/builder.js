(function () {
  const { h, icon, fmt, pill, get, post, put, del, crumbs, registerPage, state, toast, modal, confirm, promptText, catalog, elementPicker, dropdown, clear } = App;

  registerPage('builder', {
    async render(root, params) {
      const c = await catalog();
      const full = await get('/api/v1/definitions/' + params.id);
      const def = full.definition;
      let ver = params.no ? def.versions.find(v => v.versionNo === +params.no) : (def.versions.find(v => !['PRODUCTION', 'RETIRED'].includes(v.status)) || def.versions.find(v => v.status === 'PRODUCTION') || def.versions[def.versions.length - 1]);
      if (!ver) { root.appendChild(h('div', { class: 'empty' }, 'Version not found')); return; }
      crumbs([{ label: 'Definitions', href: '#/definitions' }, { label: def.name, href: '#/definitions/' + def.id }, { label: 'v' + ver.versionNo }]);

      const B = {
        def, ver, c, spec: JSON.parse(JSON.stringify(ver.spec)), selected: null, tab: 'fields', validation: null, dirty: false, saving: false, editConfirmed: ver.status === 'DRAFT',
        editable: ver.status === 'DRAFT' || ver.status === 'SAMPLED', runs: full && [], partner: null, saveTimer: null,
      };
      try { B.partner = await get('/api/v1/partners/' + def.vendorCode, { silent: true }); } catch (e) { B.partner = null; }
      B.runs = await get('/api/v1/definitions/' + def.id + '/runs');
      const grainEntity = c.entityById[def.grain];
      B.reachable = [def.grain, ...c.joinPaths.filter(j => j.from === def.grain).map(j => j.to)];
      B.spec.fields.forEach((f, i) => { if (!f.id) f.id = 'f' + (i + 1); f.position = i + 1; });

      const headEl = h('div', { class: 'builder-head' });
      const catalogCol = h('div', { class: 'col' });
      const centerCol = h('div', { class: 'col' });
      const editorCol = h('div', { class: 'col editor' });
      const splitEl = h('div', { class: 'split builder-split mode-browse' }, catalogCol, centerCol, editorCol);
      root.append(headEl, splitEl);
      function setMode() { const edit = B.tab === 'fields' && !!B.spec.fields.find(x => x.id === B.selected); splitEl.className = 'split builder-split ' + (edit ? 'mode-edit' : 'mode-browse'); catalogCol.hidden = edit; editorCol.hidden = !edit; }

      /* ---------- validation & save ---------- */
      async function validate(silent) {
        try {
          B.validation = await post('/api/v1/definitions/' + def.id + '/versions/' + ver.versionNo + '/validate', B.spec, { silent: true });
        } catch (e) { if (!silent) toast(e.message, 'danger'); }
        return B.validation;
      }
      async function save(opts) {
        if (!B.editable) return;
        if (B.saving) { B.dirty = true; return; }
        B.saving = true;
        try {
          const r = await put('/api/v1/definitions/' + def.id + '/versions/' + ver.versionNo + '/spec', B.spec);
          B.validation = r.validation;
          const statusBefore = ver.status;
          Object.assign(ver, r.version);
          B.spec.fields.forEach((f, i) => { const s = r.version.spec.fields[i]; if (s && s.id !== f.id) { if (B.selected === f.id) B.selected = s.id; f.id = s.id; } });
          B.dirty = false;
          if (statusBefore !== ver.status) { toast('Layout changed: v' + ver.versionNo + ' is back to draft and the sample was deleted', ''); B.runs = await get('/api/v1/definitions/' + def.id + '/runs'); }
          drawHead();
          if (!(opts && opts.quiet)) drawEditor();
        } finally { B.saving = false; drawStatus(); }
        if (B.dirty) scheduleSave();
      }
      function scheduleSave() {
        clearTimeout(B.saveTimer);
        B.saveTimer = setTimeout(() => save({ quiet: true }), 700);
      }
      async function changed(opts) {
        if (!B.editable) return;
        if (!B.editConfirmed) {
          const ok = await confirm('Edit a sampled layout?', 'Version ' + ver.versionNo + ' has a sample the vendor may be looking at. Editing deletes that sample and takes the version back to draft. You can build a new sample afterwards.', 'Edit anyway');
          if (!ok) { B.spec = JSON.parse(JSON.stringify(ver.spec)); drawAll(); return; }
          B.editConfirmed = true;
        }
        B.dirty = true;
        drawStatus();
        const redraw = opts ? opts.redraw : undefined;
        if (redraw === 'center') refreshRow();
        else if (redraw !== false) { drawCenter(); drawEditor(); }
        scheduleSave();
      }

      /* ---------- header ---------- */
      function drawHead() {
        clear(headEl);
        const st = ver.status;
        const versions = h('select', { class: 'input sm', style: { width: 'auto' }, onchange: e => location.hash = '#/definitions/' + def.id + '/v/' + e.target.value }, def.versions.map(v => h('option', { value: v.versionNo, selected: v.versionNo === ver.versionNo }, 'v' + v.versionNo + ' · ' + fmt.title(v.status))));
        const actions = h('div', { class: 'actions' }, h('span', { class: 'sub', id: 'saveStatus' }));
        if (B.editable) {
          actions.append(
            h('button', { class: 'btn', type: 'button', onclick: async () => { await save(); await validate(); B.selected = null; drawEditor(); toast(B.validation && B.validation.ok ? 'Layout compiles: ' + B.validation.fields.length + ' fields, ' + B.validation.joins.length + ' joins' : 'The layout has problems; see the panel on the right', B.validation && B.validation.ok ? 'ok' : 'danger'); } }, icon('check'), 'Validate'),
            h('button', { class: 'btn', type: 'button', onclick: preview }, icon('eye'), 'Preview 20 rows'),
            h('button', { class: 'btn primary', type: 'button', onclick: generateSample }, icon('play'), 'Generate sample'));
          if (st === 'SAMPLED') actions.append(h('button', { class: 'btn primary', type: 'button', onclick: () => { const s = latestSample(); if (s) App.requestApproval(s, ver); else toast('Build a sample first', 'danger'); } }, icon('send'), 'Request approval'));
        }
        if (st === 'PENDING_APPROVAL') actions.append(h('a', { class: 'btn', href: '#/approvals' }, icon('approvals'), 'Waiting for approval'), h('button', { class: 'btn ghost', type: 'button', onclick: async () => { await post('/api/v1/definitions/' + def.id + '/versions/' + ver.versionNo + '/withdraw'); toast('Withdrawn; back to sampled', 'ok'); App.render(); } }, 'Withdraw'));
        if (st === 'APPROVED') actions.append(h('a', { class: 'btn primary', href: '#/definitions/' + def.id + '/v/' + ver.versionNo + '/productionalize' }, icon('bolt'), 'Productionalize'), h('button', { class: 'btn ghost', type: 'button', onclick: async () => { await post('/api/v1/definitions/' + def.id + '/versions/' + ver.versionNo + '/withdraw'); toast('Approval withdrawn', 'ok'); App.render(); } }, 'Withdraw approval'));
        if (st === 'PRODUCTION') actions.append(h('a', { class: 'btn', href: '#/definitions/' + def.id + '/runs' }, icon('runs'), 'Runs'), h('button', { class: 'btn primary', type: 'button', onclick: async () => { const r = await post('/api/v1/definitions/' + def.id + '/run-now'); toast(r.status === 'DELIVERED' ? 'Delivered ' + fmt.num(r.rowCount) + ' rows' : fmt.title(r.status) + (r.heldReason ? ': ' + r.heldReason : r.error ? ': ' + r.error : ''), r.status === 'FAILED' ? 'danger' : 'ok'); location.hash = '#/runs/' + r.id; } }, icon('play'), 'Run now'));
        const more = [];
        if (!def.template && !def.versions.some(v => !['PRODUCTION', 'RETIRED'].includes(v.status))) more.push({ label: 'New version from ' + (def.versions.find(v => v.status === 'PRODUCTION') ? 'the live layout' : 'the latest layout'), icon: 'plus', onClick: async () => { const note = await promptText('New version', 'What is changing?', 'e.g. Vendor asked for the middle initial', { okLabel: 'Create draft' }); if (note !== null) { const v = await post('/api/v1/definitions/' + def.id + '/versions', { changeNote: note }); location.hash = '#/definitions/' + def.id + '/v/' + v.versionNo; } } });
        if (def.template) more.push({ label: 'Start a definition from this template', icon: 'plus', onClick: () => location.hash = '#/definitions/new?template=' + def.id });
        more.push({ label: 'Clone as a new definition', icon: 'copy', onClick: () => location.hash = '#/definitions/new?template=' + def.id });
        more.push({ label: 'Rename or describe', onClick: async () => { const name = await promptText('Rename', 'Name', def.name, { okLabel: 'Save' }); if (name) { await App.patch('/api/v1/definitions/' + def.id, { name }); App.render(); } } });
        more.push('hr');
        if (st === 'PRODUCTION') more.push({ label: ver.schedulePaused ? 'Resume schedule' : 'Pause schedule', icon: ver.schedulePaused ? 'play' : 'pause', onClick: async () => { await post('/api/v1/definitions/' + def.id + '/versions/' + ver.versionNo + '/pause?paused=' + !ver.schedulePaused); App.render(); } });
        if (st === 'PRODUCTION' || st === 'APPROVED') more.push({ label: 'Retire this version', icon: 'x', kind: 'danger', onClick: async () => { const reason = await promptText('Retire v' + ver.versionNo, 'Reason', 'e.g. Contract ended', { required: true, okLabel: 'Retire', okKind: 'danger' }); if (reason) { await post('/api/v1/definitions/' + def.id + '/versions/' + ver.versionNo + '/retire', { reason }); toast('Retired', 'ok'); App.render(); } } });
        if (B.editable && !def.template) more.push({ label: def.versions.length === 1 ? 'Delete this definition' : 'Discard this draft', icon: 'trash', kind: 'danger', onClick: async () => { if (await confirm('Discard?', def.versions.length === 1 ? 'The definition and its samples are deleted.' : 'Version ' + ver.versionNo + ' and its samples are deleted.', 'Discard', 'danger')) { await del('/api/v1/definitions/' + def.id + '/versions/' + ver.versionNo); location.hash = def.versions.length === 1 ? '#/definitions' : '#/definitions/' + def.id; } } });
        actions.append(dropdown(h('button', { class: 'btn', type: 'button', 'aria-label': 'More actions' }, '⋯'), more));
        const partnerName = B.partner ? B.partner.name : def.vendorCode;
        headEl.append(
          h('div', { class: 'title' }, h('div', { class: 'row', style: { gap: '8px' } }, h('h1', {}, def.name), pill(st, 'v' + ver.versionNo + ' · ' + fmt.title(st)), def.template ? pill('info', 'Template') : null),
            h('div', { class: 'meta' }, h('span', { class: 'mono' }, def.vendorCode), h('span', {}, '·'), h('span', {}, partnerName), h('span', {}, '·'), h('span', {}, fmt.title(def.subjectArea) + ', one row per ' + (grainEntity ? grainEntity.name.toLowerCase() : def.grain)), h('span', {}, '·'), versions, ver.changeNote ? h('span', { class: 'clamp', style: { maxWidth: '360px' }, title: ver.changeNote }, '“' + ver.changeNote + '”') : null)),
          actions);
        drawStatus();
      }
      function drawStatus() {
        const el = document.getElementById('saveStatus');
        if (!el) return;
        el.textContent = !B.editable ? 'Frozen · read only' : B.saving ? 'Saving…' : B.dirty ? 'Unsaved changes' : 'Saved ' + fmt.ago(ver.updatedAt || ver.createdAt);
      }
      function latestSample() { return B.runs.find(r => r.mode === 'SAMPLE' && r.versionNo === ver.versionNo && r.status !== 'DELETED'); }

      /* ---------- catalog column ---------- */
      function drawCatalog() {
        clear(catalogCol);
        const used = new Set();
        for (const f of B.spec.fields) for (const i of f.inputs) if (i.element) used.add(i.element);
        const search = h('input', { class: 'input sm', type: 'search', placeholder: 'Search ' + c.elements.filter(e => B.reachable.includes(e.entity)).length + ' elements' });
        const tabs = h('div', { class: 'tabs', style: { marginBottom: '6px' } });
        let entity = def.grain;
        const list = h('div', { style: { maxHeight: 'calc(100vh - 330px)', overflowY: 'auto' } });
        const drawList = () => {
          clear(list);
          const q = search.value.trim().toLowerCase();
          const els = c.elements.filter(e => (q ? B.reachable.includes(e.entity) : e.entity === entity) && (!q || e.name.toLowerCase().includes(q) || e.id.includes(q) || (e.aliases || []).some(a => a.includes(q))));
          if (!els.length) list.appendChild(h('div', { class: 'empty' }, 'No elements match.'));
          for (const e of els) {
            const isUsed = used.has(e.id);
            list.appendChild(h('div', { class: 'el' + (isUsed ? ' used' : ''), role: 'button', tabindex: 0, title: (e.description || '') + (e.example ? ' · e.g. ' + e.example : ''), onclick: () => addField(e), onkeydown: ev => { if (ev.key === 'Enter') addField(e); } },
              h('div', { class: 'grow' }, h('div', { class: 'n' }, e.name), h('div', { class: 'c' }, (q ? e.entity + '.' : '') + e.column + ' · ' + e.type.toLowerCase())),
              e.phi ? h('span', { class: 'pill ' + (e.restricted ? 'restricted' : 'phi') }, e.restricted ? 'RESTRICTED' : 'PHI') : null, e.watermark ? h('span', { class: 'tag' }, 'wm') : null,
              isUsed ? h('span', { class: 'sub' }, 'in use') : B.editable ? h('span', { class: 'btn xs', 'aria-hidden': 'true' }, '+') : null));
          }
        };
        for (const en of B.reachable) {
          const e = c.entityById[en];
          tabs.appendChild(h('button', { class: 'tab' + (en === entity ? ' on' : ''), type: 'button', style: { height: '32px', padding: '0 10px', fontSize: '13px' }, onclick: ev => { entity = en; [...tabs.children].forEach(t => t.classList.remove('on')); ev.currentTarget.classList.add('on'); drawList(); } }, e ? e.name : en));
        }
        search.addEventListener('input', drawList);
        drawList();
        catalogCol.appendChild(h('div', { class: 'card sticky' }, h('div', { class: 'card-body', style: { paddingBottom: '6px' } }, h('div', { class: 'eyebrow mb' }, 'Catalog · ' + fmt.title(def.subjectArea)), tabs, search), h('div', { style: { padding: '4px 8px 8px' } }, list),
          h('div', { style: { padding: '8px 14px', borderTop: '1px solid var(--line-2)', fontSize: '12px', color: 'var(--muted)' } }, B.editable ? 'Click an element to add it as a field. Example values in the catalog are synthetic; real data appears only in the masked preview.' : 'This version is frozen. Create a new version to change the layout.')));
      }
      function addField(e) {
        if (!B.editable) return;
        const f = { id: 'f' + (Date.now() % 100000), position: B.spec.fields.length + 1, header: e.column.toUpperCase(), inputs: [{ element: e.id, rules: [] }], rules: [], onOverflow: 'TRUNCATE' };
        if (e.type === 'DATE') f.rules.push({ type: 'FORMAT_DATE', params: { pattern: 'yyyyMMdd' } });
        if (e.type === 'BOOLEAN') f.rules.push({ type: 'FORMAT_NUMBER', params: { booleanStyle: 'YN' } });
        if (B.spec.fileFormat.type === 'FIXED_WIDTH') { f.width = e.type === 'DATE' ? 8 : 20; f.align = 'LEFT'; f.padChar = ' '; }
        B.spec.fields.push(f);
        B.flash = f.id;
        changed();
        drawCatalog();
      }

      /* ---------- center column ---------- */
      function drawCenter() {
        clear(centerCol);
        const tabs = [['fields', 'Fields', B.spec.fields.length], ['joins', 'Joins', B.spec.joins.length || null], ['filters', 'Filters', B.spec.filters.length], ['sort', 'Sort', B.spec.sort.length || null], ['format', 'File format', null], ['sample', 'Sample settings', null], ['history', 'History', null]];
        const tabBar = h('div', { class: 'tabs' }, tabs.map(([id, label, cnt]) => h('button', { class: 'tab' + (B.tab === id ? ' on' : ''), type: 'button', onclick: () => { B.tab = id; B.selected = null; drawCenter(); drawEditor(); } }, label, cnt ? h('span', { class: 'cnt' }, cnt) : null)));
        const body = h('div');
        const card = h('div', { class: 'card' }, h('div', { style: { padding: '0 14px' } }, tabBar), body);
        centerCol.appendChild(validationStrip());
        centerCol.appendChild(card);
        ({ fields: drawFields, joins: drawJoins, filters: drawFilters, sort: drawSort, format: drawFormat, sample: drawSampleTab, history: drawHistory })[B.tab](body);
      }

      function ruleChips(f) {
        const chips = [];
        for (const inp of f.inputs) for (const r of inp.rules || []) chips.push(chip(r, 'shape'));
        if (f.inputs.length > 1) chips.push(f.combiner ? chip(f.combiner) : h('span', { class: 'chip muted' }, 'needs a combiner'));
        else if (f.combiner && f.combiner.type === 'CONCAT') chips.push(chip(f.combiner));
        for (const r of f.rules) chips.push(chip(r));
        if (f.defaultValue) chips.push(h('span', { class: 'chip muted' }, 'empty → ' + f.defaultValue));
        if (!chips.length) chips.push(h('span', { class: 'sub' }, 'pass through'));
        return chips;
      }
      function chip(r, prefix) { return h('span', { class: 'chip' }, prefix ? h('b', {}, prefix) : null, r.type, ' ', h('span', { class: 'sub', style: { color: 'inherit', opacity: .8 } }, summarize(r))); }
      function summarize(r) {
        const p = r.params || {};
        switch (r.type) {
          case 'CONSTANT': return p.token && p.token !== 'LITERAL' ? p.token.toLowerCase().replace('_', ' ') : '"' + (p.value || '') + '"';
          case 'CONCAT': return p.template ? '"' + p.template + '"' : 'sep "' + (p.separator || '') + '"';
          case 'COALESCE': return 'first non-blank';
          case 'LOOKUP': return (p.lookup || '?') + ' · miss ' + (p.onMiss || 'BLANK').toLowerCase();
          case 'MAP_VALUES': { const m = p.map || {}; const ks = Object.keys(m); return ks.slice(0, 3).map(k => k + '→' + m[k]).join(' · ') + (ks.length > 3 ? ' …' : '') + (p.defaultValue ? ' · else ' + p.defaultValue : ''); }
          case 'CONDITIONAL': return ((p.rules || []).length) + ' rule(s) · else ' + (p.elseKind || 'KEEP').toLowerCase();
          case 'FORMAT_DATE': return p.pattern === 'CUSTOM' ? p.customPattern : (p.pattern || 'yyyyMMdd');
          case 'DATE_MATH': return (p.op || '').toLowerCase().replace(/_/g, ' ') + (String(p.op).startsWith('ADD') ? ' ' + (p.n || 1) : '');
          case 'ARITHMETIC': return (p.op || '').toLowerCase() + ' ' + (p.operandKind === 'CONSTANT' ? p.operandValue : App.elementShort(p.operandElement || ''));
          case 'FORMAT_NUMBER': return p.booleanStyle && !p.decimals ? 'boolean ' + p.booleanStyle : (p.impliedDecimal ? 'implied ' : '') + (p.decimals ?? 2) + ' dec' + (p.leadingZeros ? ' · zeros ' + p.leadingZeros : '');
          case 'CLEAN_TEXT': return [p.textCase && p.textCase !== 'NONE' ? p.textCase.toLowerCase() : null, p.strip && p.strip !== 'NONE' ? 'strip ' + p.strip.toLowerCase().replace('_', ' ') : null, p.asciiOnly ? 'ascii' : null, p.find ? 'replace "' + p.find + '"' : null].filter(Boolean).join(' · ') || 'trim';
          case 'SUBSTRING': return p.mode === 'TOKEN' ? 'token ' + (p.tokenIndex || 1) + ' by "' + (p.delimiter || ',') + '"' : p.fromEnd ? 'last ' + p.length : 'from ' + (p.start || 1) + (p.length ? ' for ' + p.length : '');
          case 'SEQUENCE': return p.scope === 'GROUP' ? 'within ' + App.elementShort(p.groupElement || '') : 'file';
          case 'MASK': return (p.style || 'LAST4').toLowerCase().replace(/_/g, ' ');
          default: return '';
        }
      }
      function sourceTags(f) {
        if (!f.inputs.length) return h('span', { class: 'tag', style: { background: 'var(--warn-soft)', color: 'var(--warn)' } }, 'no source');
        return f.inputs.map(i => h('span', { class: 'tag', title: i.element || 'constant' }, i.element ? App.elementShort(i.element) : '"' + (i.constant || '') + '"'));
      }
      function drawFields(body) {
        const tbody = h('tbody');
        let dragId = null;
        /* Selection happens on mouse-up, not click: a blur caused by the mouse-down can change the editor and shift
           the row under the pointer, and the browser then drops the click. */
        let pressedId = null;
        const selectField = id => { B.selected = id; drawCenter(); drawEditor(); };
        tbody.addEventListener('mouseup', () => { if (pressedId) { const id = pressedId; pressedId = null; selectField(id); } });
        tbody.addEventListener('mouseleave', () => { pressedId = null; });
        B.spec.fields.forEach((f, idx) => {
          const el = c.elementById[(f.inputs[0] || {}).element];
          const phi = f.inputs.some(i => { const e = c.elementById[i.element]; return e && e.phi; });
          const headerCell = B.editable ? h('input', { class: 'input sm mono', value: f.header || '', style: { width: '165px', fontWeight: 600 }, onclick: e => e.stopPropagation(), oninput: e => { f.header = e.target.value.toUpperCase().replace(/[^A-Z0-9_ .-]/g, ''); e.target.value = f.header; changed({ redraw: false }); const hl = centerCol.querySelector('.header-line'); if (hl) hl.textContent = headerLineText(); const eh = editorCol.querySelector('input.input.mono'); if (eh && B.selected === f.id) eh.value = f.header; } }) : h('span', { class: 'field-row-header' }, f.header);
          const tr = h('tr', { class: 'click' + (B.selected === f.id ? ' selected' : ''), draggable: B.editable, onmousedown: e => { pressedId = e.button === 0 && !e.target.closest('input,button,select,a') ? f.id : null; }, onkeydown: e => { if (e.key === 'Enter' && e.target === e.currentTarget) selectField(f.id); }, tabindex: 0 },
            h('td', { style: { width: '26px' } }, B.editable ? h('span', { class: 'drag-handle', title: 'Drag to reorder' }, icon('grip', 14)) : null),
            h('td', { class: 'mono sub', style: { width: '30px' } }, idx + 1),
            h('td', {}, headerCell, phi ? h('span', { class: 'pill phi', style: { marginLeft: '6px' } }, 'PHI') : null),
            h('td', {}, h('div', { class: 'row', style: { gap: '4px' } }, sourceTags(f))),
            h('td', {}, h('div', { class: 'row', style: { gap: '5px' } }, ruleChips(f))),
            h('td', { class: 'mono sub right' }, B.spec.fileFormat.type === 'FIXED_WIDTH' ? (f.width || '?') + 'w' : f.maxLength || '—'),
            h('td', { class: 'right', style: { width: '80px' } }, B.editable ? h('span', { class: 'row', style: { gap: '2px', justifyContent: 'flex-end' } },
              h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Move up', style: { width: '26px', height: '26px' }, disabled: idx === 0, onclick: e => { e.stopPropagation(); move(idx, idx - 1); } }, icon('up', 13)),
              h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Move down', style: { width: '26px', height: '26px' }, disabled: idx === B.spec.fields.length - 1, onclick: e => { e.stopPropagation(); move(idx, idx + 1); } }, icon('down', 13))) : null));
          if (B.editable) {
            tr.addEventListener('dragstart', e => { dragId = f.id; tr.classList.add('dragging'); e.dataTransfer.effectAllowed = 'move'; });
            tr.addEventListener('dragend', () => { tr.classList.remove('dragging'); [...tbody.children].forEach(x => x.classList.remove('drop-before', 'drop-after')); });
            tr.addEventListener('dragover', e => { e.preventDefault(); const r = tr.getBoundingClientRect(); const before = e.clientY < r.top + r.height / 2; tr.classList.toggle('drop-before', before); tr.classList.toggle('drop-after', !before); });
            tr.addEventListener('dragleave', () => tr.classList.remove('drop-before', 'drop-after'));
            tr.addEventListener('drop', e => {
              e.preventDefault();
              if (!dragId || dragId === f.id) return;
              const from = B.spec.fields.findIndex(x => x.id === dragId);
              let to = B.spec.fields.findIndex(x => x.id === f.id);
              const r = tr.getBoundingClientRect();
              const before = e.clientY < r.top + r.height / 2;
              const [moved] = B.spec.fields.splice(from, 1);
              to = B.spec.fields.findIndex(x => x.id === f.id) + (before ? 0 : 1);
              B.spec.fields.splice(to, 0, moved);
              renumber();
              changed();
            });
          }
          if (B.flash === f.id) { tr.classList.add('flash'); B.flash = null; setTimeout(() => { tr.scrollIntoView({ block: 'nearest' }); }, 0); }
          tbody.appendChild(tr);
        });
        if (!B.spec.fields.length) tbody.appendChild(h('tr', {}, h('td', { colspan: 7, class: 'empty' }, h('b', {}, 'No fields yet'), 'Click elements in the catalog on the left, or add a constant field.')));
        const sampleLine = B.spec.fields.length ? h('div', { class: 'row', style: { padding: '10px 14px', borderTop: '1px solid var(--line-2)', background: 'var(--surface-2)', borderRadius: '0 0 10px 10px' } }, h('span', { class: 'eyebrow' }, 'Header line'), h('span', { class: 'mono small clamp header-line' }, headerLineText())) : null;
        App.append(body, [h('div', { class: 'table-wrap' }, h('table', { class: 'table compact fields-table' }, h('colgroup', {}, [26, 30, 200, '22%', null, 64, 80].map(w => h('col', w === null ? {} : { style: { width: typeof w === 'number' ? w + 'px' : w } }))), h('thead', {}, h('tr', {}, h('th'), h('th', {}, '#'), h('th', {}, 'Header'), h('th', {}, 'Source'), h('th', {}, 'Rules'), h('th', { class: 'right' }, B.spec.fileFormat.type === 'FIXED_WIDTH' ? 'Width' : 'Max'), h('th'))), tbody)),
          B.editable ? h('div', { class: 'row', style: { padding: '10px 14px' } }, h('button', { class: 'btn sm', type: 'button', onclick: () => pickElementModal(e => addField(e)) }, icon('plus', 13), 'Add field from catalog'), h('button', { class: 'btn sm', type: 'button', onclick: () => { const f = { id: 'f' + (Date.now() % 100000), header: 'RECORD_TYPE', inputs: [], rules: [{ type: 'CONSTANT', params: { token: 'LITERAL', value: 'D' } }], onOverflow: 'TRUNCATE' }; B.spec.fields.push(f); B.selected = f.id; changed(); } }, 'Add constant field'), h('span', { class: 'topbar-spacer' }), h('span', { class: 'sub' }, 'Drag rows or use the arrows to set the vendor\'s order.')) : null, sampleLine]);
      }
      function headerLineText() { return B.spec.fileFormat.type === 'FIXED_WIDTH' ? B.spec.fields.map(f => (f.header || '').padEnd(f.width || 8).slice(0, f.width || 8)).join('') : B.spec.fields.map(f => f.header).join(B.spec.fileFormat.delimiter || '|'); }
      /* Update the selected row in place: rebuilding the table on a blur would swallow the click that caused the blur. */
      function refreshRow() {
        const f = B.spec.fields.find(x => x.id === B.selected);
        const tr = centerCol.querySelector('tr.selected');
        if (!f || !tr || tr.children.length < 6) return;
        const cells = tr.children;
        clear(cells[3]); cells[3].appendChild(h('div', { class: 'row', style: { gap: '4px' } }, sourceTags(f)));
        clear(cells[4]); cells[4].appendChild(h('div', { class: 'row', style: { gap: '5px' } }, ruleChips(f)));
        cells[5].textContent = B.spec.fileFormat.type === 'FIXED_WIDTH' ? (f.width || '?') + 'w' : f.maxLength || '—';
        const hl = centerCol.querySelector('.header-line');
        if (hl) hl.textContent = headerLineText();
      }
      function move(from, to) { const [x] = B.spec.fields.splice(from, 1); B.spec.fields.splice(to, 0, x); renumber(); changed(); }
      function renumber() { B.spec.fields.forEach((f, i) => f.position = i + 1); }
      function pickElementModal(onPick, entities) {
        const close = modal({ title: 'Pick an element', body: elementPicker(e => { close(); onPick(e); }, { entities: entities || B.reachable }), actions: [{ label: 'Cancel' }] });
      }

      function drawJoins(body) {
        const paths = c.joinPaths.filter(j => j.from === def.grain);
        const rows = paths.map(j => {
          const to = c.entityById[j.to];
          const cur = B.spec.joins.find(x => x.path === j.id);
          const usedByFields = B.spec.fields.some(f => f.inputs.some(i => i.element && i.element.startsWith(j.to + '.')));
          const sel = h('select', { class: 'input sm', disabled: !B.editable, onchange: e => { const x = B.spec.joins.find(y => y.path === j.id); if (x) x.select = e.target.value; changed(); } }, (j.selectors || []).map(s => h('option', { value: s.id, selected: cur && cur.select === s.id }, s.label)));
          const cb = h('input', { type: 'checkbox', checked: !!cur, disabled: !B.editable || usedByFields, onchange: e => { if (e.target.checked) B.spec.joins.push({ path: j.id, select: j.selectors && j.selectors.length ? j.selectors[0].id : null }); else B.spec.joins = B.spec.joins.filter(x => x.path !== j.id); changed(); } });
          return h('tr', {}, h('td', {}, h('label', { class: 'check' }, cb, h('b', {}, to ? to.name : j.to))), h('td', {}, j.label || (j.cardinality === 'MANY' ? 'many per ' + (grainEntity ? grainEntity.name.toLowerCase() : def.grain) : 'one per ' + (grainEntity ? grainEntity.name.toLowerCase() : def.grain))), h('td', {}, j.cardinality === 'MANY' ? sel : h('span', { class: 'sub' }, 'direct')), h('td', { class: 'sub' }, usedByFields ? 'used by fields' : cur ? 'included' : 'not included'));
        });
        body.append(h('div', { class: 'card-body' }, h('p', { class: 'small muted mb' }, 'Entities the grain can reach through catalog join paths. A many-to-one entity needs a selector so the file stays at one row per ' + (grainEntity ? grainEntity.name.toLowerCase() : def.grain) + '. Fields that use an entity include it automatically.'),
          h('div', { class: 'table-wrap' }, h('table', { class: 'table compact' }, h('thead', {}, h('tr', {}, h('th', {}, 'Entity'), h('th', {}, 'Relationship'), h('th', {}, 'Which row'), h('th', {}, 'Status'))), h('tbody', {}, rows.length ? rows : h('tr', {}, h('td', { colspan: 4, class: 'empty' }, 'No join paths from this grain.')))))));
      }

      const OPS = [['EQ', 'equals'], ['NE', 'not equal'], ['IN', 'in list'], ['NOT_IN', 'not in list'], ['GT', 'after / greater'], ['GTE', 'on or after / at least'], ['LT', 'before / less'], ['LTE', 'on or before / at most'], ['BETWEEN', 'between'], ['IS_NULL', 'is empty'], ['IS_NOT_NULL', 'is not empty'], ['STARTS_WITH', 'starts with'], ['CONTAINS', 'contains']];
      function drawFilters(body) {
        const list = h('div', { class: 'stack' });
        B.spec.filters.forEach((f, idx) => {
          const remove = B.editable ? h('button', { class: 'btn xs ghost', type: 'button', onclick: () => { B.spec.filters.splice(idx, 1); changed(); } }, 'Remove') : null;
          if (f.template) {
            const t = c.templateById[f.template];
            const params = (t && t.params || []).map(p => h('div', { class: 'field' }, h('label', {}, p.label), valueInput(f.params && f.params[p.name] !== undefined ? f.params[p.name] : p.default, p.type, v => { f.params = f.params || {}; f.params[p.name] = v; changed({ redraw: false }); })));
            list.appendChild(h('div', { class: 'rule-card' }, h('div', { class: 'rc-head' }, icon('filter', 14), h('span', { class: 'lbl' }, t ? t.label : f.template), h('span', { class: 'tag' }, 'catalog template'), h('span', { class: 'spacer' }), remove), t ? h('div', { class: 'small muted' }, t.description) : null, params.length ? h('div', { class: 'rule-form' }, params) : null, t ? h('div', { class: 'small muted mono' }, t.sql) : null));
            return;
          }
          const el = c.elementById[f.element];
          const opSel = h('select', { class: 'input sm', disabled: !B.editable, onchange: e => { f.op = e.target.value; changed(); } }, OPS.map(([v, l]) => h('option', { value: v, selected: (f.op || 'EQ') === v }, l)));
          const needsValue = !['IS_NULL', 'IS_NOT_NULL'].includes(f.op || 'EQ');
          const isList = ['IN', 'NOT_IN', 'BETWEEN'].includes(f.op || 'EQ');
          list.appendChild(h('div', { class: 'rule-card' }, h('div', { class: 'rc-head' }, icon('filter', 14), h('span', { class: 'lbl' }, el ? el.name : f.element), h('span', { class: 'tag' }, f.element), h('span', { class: 'spacer' }), remove),
            h('div', { class: 'rule-form' }, h('div', { class: 'field' }, h('label', {}, 'Condition'), opSel), needsValue ? h('div', { class: 'field' }, h('label', {}, isList ? 'Values, comma separated' : 'Value'), valueInput(f.value, el ? el.type : 'STRING', v => { f.value = v; changed({ redraw: false }); }, { list: isList, values: el && el.values })) : null)));
        });
        if (!B.spec.filters.length) list.appendChild(h('div', { class: 'empty' }, h('b', {}, 'No filters'), 'Every row of the grain is included.'));
        const add = B.editable ? h('div', { class: 'row' },
          h('button', { class: 'btn sm', type: 'button', onclick: () => pickElementModal(e => { B.spec.filters.push({ element: e.id, op: e.values ? 'IN' : 'EQ', value: e.values ? [e.values[0]] : '' }); changed(); }) }, icon('plus', 13), 'Add a condition'),
          dropdown(h('button', { class: 'btn sm', type: 'button' }, 'Add a catalog template ▾'), c.filterTemplates.filter(t => B.reachable.includes(t.entity)).map(t => ({ label: t.label, onClick: () => { B.spec.filters.push({ template: t.id, params: Object.fromEntries((t.params || []).map(p => [p.name, p.default])) }); changed(); } })))) : null;
        body.append(h('div', { class: 'card-body stack' }, h('p', { class: 'small muted' }, 'Which rows go to the vendor. Conditions are built from catalog elements and a fixed operator list; a date value can be the run date plus or minus days. Catalog templates cover predicates that need real SQL, such as "active as of a date".'), list, add));
      }
      function valueInput(value, type, onChange, opts) {
        const isToken = value && typeof value === 'object' && value.kind === 'TOKEN';
        if (type === 'DATE' || type === 'TIMESTAMP') {
          const wrap = h('div', { class: 'row', style: { gap: '6px' } });
          const mode = h('select', { class: 'input sm', style: { width: '150px' }, disabled: !B.editable }, h('option', { value: 'DATE', selected: !isToken }, 'A date'), h('option', { value: 'TOKEN', selected: isToken }, 'Run date ± days'));
          const dateIn = h('input', { class: 'input sm', type: 'date', value: !isToken && value ? String(value).slice(0, 10) : '', disabled: !B.editable, hidden: isToken, onchange: e => onChange(e.target.value) });
          const off = h('input', { class: 'input sm', type: 'number', style: { width: '90px' }, value: isToken ? (value.offsetDays || 0) : 0, disabled: !B.editable, hidden: !isToken, onchange: e => onChange({ kind: 'TOKEN', token: 'RUN_DATE', offsetDays: +e.target.value }) });
          const lbl = h('span', { class: 'sub', hidden: !isToken }, 'days from the run date');
          mode.addEventListener('change', () => { const t = mode.value === 'TOKEN'; dateIn.hidden = t; off.hidden = !t; lbl.hidden = !t; onChange(t ? { kind: 'TOKEN', token: 'RUN_DATE', offsetDays: +off.value } : dateIn.value); });
          wrap.append(mode, dateIn, off, lbl);
          return wrap;
        }
        if (opts && opts.values && !(opts.list)) {
          return h('select', { class: 'input sm', disabled: !B.editable, onchange: e => onChange(e.target.value) }, opts.values.map(v => h('option', { value: v, selected: v === value }, v)));
        }
        if (opts && opts.list) {
          return h('input', { class: 'input sm', value: Array.isArray(value) ? value.join(', ') : (value || ''), disabled: !B.editable, placeholder: opts.values ? opts.values.join(', ') : '', onchange: e => onChange(e.target.value.split(',').map(s => s.trim()).filter(Boolean)) });
        }
        if (type === 'BOOLEAN') return h('select', { class: 'input sm', disabled: !B.editable, onchange: e => onChange(e.target.value === 'true') }, h('option', { value: 'true', selected: value === true || value === 'true' }, 'true'), h('option', { value: 'false', selected: value === false || value === 'false' }, 'false'));
        return h('input', { class: 'input sm', type: type === 'DECIMAL' || type === 'INTEGER' ? 'number' : 'text', value: value === null || value === undefined ? '' : (typeof value === 'object' ? JSON.stringify(value) : value), disabled: !B.editable, onchange: e => onChange(e.target.value) });
      }

      function drawSort(body) {
        const list = h('div', { class: 'stack' });
        B.spec.sort.forEach((s, idx) => {
          const el = c.elementById[s.element];
          list.appendChild(h('div', { class: 'rule-card' }, h('div', { class: 'rc-head' }, icon('sort', 14), h('span', { class: 'lbl' }, el ? el.name : s.element), h('span', { class: 'tag' }, s.element), h('span', { class: 'spacer' }),
            h('select', { class: 'input sm', style: { width: '130px' }, disabled: !B.editable, onchange: e => { s.direction = e.target.value; changed({ redraw: false }); } }, h('option', { value: 'ASC', selected: s.direction !== 'DESC' }, 'ascending'), h('option', { value: 'DESC', selected: s.direction === 'DESC' }, 'descending')),
            B.editable ? h('button', { class: 'btn xs ghost', type: 'button', onclick: () => { B.spec.sort.splice(idx, 1); changed(); } }, 'Remove') : null)));
        });
        if (!B.spec.sort.length) list.appendChild(h('div', { class: 'empty' }, h('b', {}, 'Default order'), 'Rows are ordered by the grain\'s primary key. Vendors often want subscriber, then relationship.'));
        body.append(h('div', { class: 'card-body stack' }, h('p', { class: 'small muted' }, 'The order of rows in the file. Group sequence numbers depend on it: the group element must come first here.'), list,
          B.editable ? h('button', { class: 'btn sm', type: 'button', style: { alignSelf: 'flex-start' }, onclick: () => pickElementModal(e => { B.spec.sort.push({ element: e.id, direction: 'ASC' }); changed(); }) }, icon('plus', 13), 'Add a sort key') : null));
      }

      function drawFormat(body) {
        const ff = B.spec.fileFormat;
        const sel = (key, options, label, help) => h('div', { class: 'field' }, h('label', {}, label), h('select', { class: 'input', disabled: !B.editable, onchange: e => { ff[key] = e.target.value; changed(); } }, options.map(o => h('option', { value: o[0], selected: ff[key] === o[0] }, o[1]))), help ? h('div', { class: 'help' }, help) : null);
        const txt = (key, label, help, mono) => h('div', { class: 'field' }, h('label', {}, label), h('input', { class: 'input' + (mono ? ' mono' : ''), value: ff[key] || '', disabled: !B.editable, onchange: e => { ff[key] = e.target.value; changed({ redraw: false }); } }), help ? h('div', { class: 'help' }, help) : null);
        const chk = (key, label) => h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: !!ff[key], disabled: !B.editable, onchange: e => { ff[key] = e.target.checked; changed(); } }), label);
        body.append(h('div', { class: 'card-body stack' },
          h('div', { class: 'grid3' }, sel('type', [['DELIMITED', 'Delimited'], ['FIXED_WIDTH', 'Fixed width']], 'Layout', 'Fixed width needs a width on every field.'),
            ff.type === 'DELIMITED' ? sel('delimiter', [['|', 'Pipe |'], [',', 'Comma ,'], ['\t', 'Tab'], [';', 'Semicolon ;'], ['~', 'Tilde ~']], 'Delimiter') : null,
            ff.type === 'DELIMITED' ? sel('quoteMode', [['NONE', 'Never'], ['MINIMAL', 'When needed'], ['ALL', 'Always']], 'Quote values') : null),
          h('div', { class: 'grid3' }, sel('lineEnding', [['CRLF', 'CRLF (Windows)'], ['LF', 'LF (Unix)']], 'Line ending'), sel('encoding', [['UTF-8', 'UTF-8'], ['ISO-8859-1', 'ISO-8859-1'], ['windows-1252', 'Windows-1252'], ['US-ASCII', 'ASCII']], 'Encoding'), txt('extension', 'File extension', 'Used when the file name pattern has none.')),
          h('div', { class: 'grid3' }, txt('nullText', 'Empty values as', 'Text written when a value is empty.'), ff.type === 'DELIMITED' ? sel('delimiterInValue', [['STRIP', 'Remove the delimiter'], ['REPLACE', 'Replace with a space'], ['FAIL', 'Fail the run']], 'Delimiter inside a value') : null, h('div', { class: 'field' }, h('label', {}, 'Rows'), h('div', { class: 'row', style: { height: '36px' } }, chk('headerRow', 'Write a header row')))),
          h('div', { class: 'grid2' }, txt('headerRecord', 'Header record', 'Optional first line. Tokens: {RUN_DATE:yyyyMMdd} {RUN_TIME:HHmmss} {VENDOR} {VERSION} {FILE_SEQ}', true), txt('trailerRecord', 'Trailer record', 'Optional last line. Tokens: {RECORD_COUNT} {SUM:HEADER} plus the header tokens', true)),
          h('div', { class: 'small muted' }, 'Example trailer: T|{RECORD_COUNT}|{SUM:PAID} writes the row count and a hash total of the PAID column.')));
      }

      function drawSampleTab(body) {
        const s = B.spec.sample;
        body.append(h('div', { class: 'card-body stack' },
          h('p', { class: 'small muted' }, 'Samples run at full scope with a row limit, never through the incremental window, so a new feed always shows rows. PHI is masked with pseudonyms that keep widths and formats; unmasked samples need the PHI privilege, a BAA and the partner flag.'),
          h('div', { class: 'grid3' }, h('div', { class: 'field' }, h('label', {}, 'Rows'), h('input', { class: 'input', type: 'number', min: 1, max: 5000, value: s.maxRows, disabled: !B.editable, onchange: e => { s.maxRows = Math.max(1, +e.target.value || 200); changed({ redraw: false }); } })),
            h('div', { class: 'field' }, h('label', {}, 'Data'), h('select', { class: 'input', disabled: !B.editable, onchange: e => { s.synthetic = e.target.value === 'synthetic'; changed(); } }, h('option', { value: 'real', selected: !s.synthetic }, 'Real data, masked'), h('option', { value: 'synthetic', selected: s.synthetic }, 'Synthetic, from catalog examples')), h('div', { class: 'help' }, 'Synthetic is for vendors without a BAA yet.'))),
          h('div', { class: 'field' }, h('label', {}, 'Test cohort (optional)'), h('textarea', { class: 'input mono', rows: 2, placeholder: 'Root keys, comma separated, e.g. M100231, M100232', disabled: !B.editable, onchange: e => { s.cohort = e.target.value.split(/[\s,]+/).filter(Boolean); changed({ redraw: false }); } }, (s.cohort || []).join(', ')), h('div', { class: 'help' }, '"Send me these 25 test members." Only these ' + (grainEntity ? grainEntity.name.toLowerCase() + 's' : 'rows') + ' go into the sample.')),
          h('div', { class: 'section-title' }, h('h2', {}, 'Samples built for v' + ver.versionNo)),
          samplesTable()));
      }
      function samplesTable() {
        const rows = B.runs.filter(r => r.mode === 'SAMPLE' && r.versionNo === ver.versionNo);
        if (!rows.length) return h('div', { class: 'empty' }, 'No samples yet.');
        return h('div', { class: 'table-wrap' }, h('table', { class: 'table compact' }, h('thead', {}, h('tr', {}, h('th', {}, 'Built'), h('th', {}, 'File'), h('th', { class: 'right' }, 'Rows'), h('th', {}, 'Masked'), h('th', {}, 'Status'), h('th'))),
          h('tbody', {}, rows.map(r => h('tr', { class: 'click', onclick: () => location.hash = '#/runs/' + r.id }, h('td', {}, fmt.dt(r.startedAt) + ' · ' + r.startedBy), h('td', { class: 'mono small' }, r.fileName || '—'), h('td', { class: 'num' }, fmt.num(r.rowCount)), h('td', {}, r.masked ? 'yes' : 'no'), h('td', {}, pill(r.status === 'DELETED' ? 'retired' : r.status, r.status === 'DELETED' ? 'Deleted' : undefined)), h('td', { class: 'right' }, h('a', { class: 'btn xs', href: '#/runs/' + r.id }, 'Open')))))));
      }

      async function drawHistory(body) {
        const audit = await get('/api/v1/audit?definitionId=' + def.id + '&limit=40');
        body.append(h('div', { class: 'card-body stack' },
          h('div', { class: 'table-wrap' }, h('table', { class: 'table compact' }, h('thead', {}, h('tr', {}, h('th', {}, 'Version'), h('th', {}, 'Status'), h('th', {}, 'Change note'), h('th', {}, 'Created'), h('th', {}, 'Approved'), h('th', {}, 'Live'))),
            h('tbody', {}, def.versions.slice().reverse().map(v => h('tr', { class: 'click' + (v.versionNo === ver.versionNo ? ' selected' : ''), onclick: () => location.hash = '#/definitions/' + def.id + '/v/' + v.versionNo }, h('td', { class: 'mono' }, 'v' + v.versionNo), h('td', {}, pill(v.status)), h('td', {}, v.changeNote || h('span', { class: 'sub' }, '—'), v.rejectNote ? h('div', { class: 'sub' }, 'Rejected: ' + v.rejectNote) : null), h('td', { class: 'sub' }, fmt.date(v.createdAt) + ' · ' + v.createdBy), h('td', { class: 'sub' }, v.approvedBy ? fmt.date(v.approvedAt) + ' · ' + v.approvedBy : '—'), h('td', { class: 'sub' }, v.productionizedAt ? fmt.date(v.productionizedAt) + (v.retiredAt ? ' → ' + fmt.date(v.retiredAt) : '') : '—')))))),
          h('div', { class: 'section-title' }, h('h2', {}, 'Audit trail')),
          h('div', { class: 'list' }, audit.map(a => h('div', { class: 'list-item' }, h('div', {}, h('div', { class: 't' }, a.actorName, ' ', h('span', { class: 'muted', style: { fontWeight: 400 } }, fmt.status(a.action))), h('div', { class: 'd' }, Object.entries(a.details || {}).filter(([k]) => k !== 'seeded').map(([k, v]) => k + ': ' + v).join(' · '))), h('div', { class: 'when' }, fmt.dt(a.at)))))));
      }

      /* ---------- right column: field editor / validation ---------- */
      function drawEditor() {
        clear(editorCol);
        const f = B.spec.fields.find(x => x.id === B.selected);
        if (B.tab === 'fields' && f) editorCol.appendChild(fieldEditor(f));
        setMode();
        const strip = centerCol.querySelector('.vstrip');
        if (strip) strip.replaceWith(validationStrip());
      }
      function validationStrip() {
        const v = B.validation;
        const errors = v ? v.problems.filter(p => p.severity === 'ERROR') : [];
        const warns = v ? v.problems.filter(p => p.severity !== 'ERROR') : [];
        const text = !v ? 'Not checked yet' : errors.length ? errors.length + ' problem' + (errors.length === 1 ? '' : 's') + ' to fix' + (warns.length ? ', ' + warns.length + ' note' + (warns.length === 1 ? '' : 's') : '') : 'Compiles · ' + v.fields.length + ' fields · ' + v.joins.length + ' join' + (v.joins.length === 1 ? '' : 's') + ' · ' + v.elementsNeeded.length + ' source columns' + (warns.length ? ' · ' + warns.length + ' note' + (warns.length === 1 ? '' : 's') : '');
        const strip = h('div', { class: 'vstrip row', style: { marginBottom: '10px', gap: '10px' } }, v ? pill(errors.length ? 'danger' : 'ok', errors.length ? 'Problems' : 'Compiles') : pill('info', 'Unchecked'), h('span', { class: 'small' }, text), h('span', { class: 'topbar-spacer' }),
          B.selected ? h('button', { class: 'btn xs', type: 'button', onclick: () => { B.selected = null; drawCenter(); drawEditor(); } }, icon('catalog', 12), 'Show catalog') : null,
          h('button', { class: 'btn xs', type: 'button', onclick: async () => { if (!v) await validate(); App.drawer('Validation', validationPanel()); } }, 'Details and SQL'));
        return strip;
      }
      function validationPanel() {
        const v = B.validation;
        const box = h('div', {}, h('div', { class: 'row mb' }, v ? pill(v.ok ? 'ok' : 'danger', v.ok ? 'Compiles' : 'Problems') : null, h('span', { class: 'spacer' })));
        const body = h('div', { class: 'card-body stack' });
        if (!v) { body.appendChild(h('p', { class: 'small muted' }, 'Select a field to edit it, or check the layout to see problems, the joins the engine will use and the SQL it would run.')); }
        else {
          const errors = v.problems.filter(p => p.severity === 'ERROR'), warns = v.problems.filter(p => p.severity !== 'ERROR');
          if (!errors.length && !warns.length) body.appendChild(h('div', { class: 'alert ok' }, icon('check'), h('div', {}, 'No problems. ' + v.fields.length + ' fields, ' + v.joins.length + ' joins, ' + v.elementsNeeded.length + ' source columns.')));
          if (errors.length) body.appendChild(h('div', {}, h('div', { class: 'eyebrow mb' }, errors.length + ' to fix'), errors.map(p => h('div', { class: 'problem' }, h('span', { class: 'w' }, p.where), h('span', { class: 'risk HIGH' }, p.message)))));
          if (warns.length) body.appendChild(h('div', {}, h('div', { class: 'eyebrow mb' }, 'Notes'), warns.map(p => h('div', { class: 'problem' }, h('span', { class: 'w' }, p.where), h('span', {}, p.message)))));
          if (v.joins.length) body.appendChild(h('div', {}, h('div', { class: 'eyebrow mb' }, 'Joins'), v.joins.map(j => h('div', { class: 'small' }, h('span', { class: 'tag' }, j.path), ' ', j.cardinality === 'MANY' ? 'pick ' + j.selector.toLowerCase().replace(/_/g, ' ') : 'one to one'))));
          body.appendChild(h('details', {}, h('summary', { style: { cursor: 'pointer', color: 'var(--accent-ink)', fontSize: '13px' } }, 'SQL the engine would run'), h('pre', { class: 'sql mt' }, v.sqlText)));
        }
        if (ver.status === 'PRODUCTION' && full.watermark) body.appendChild(h('div', { class: 'kv' }, h('span', {}, 'Watermark'), h('span', { class: 'mono small' }, full.watermark.value.replace('T', ' '))));
        box.appendChild(body);
        return box;
      }
      function fieldEditor(f) {
        const ro = !B.editable;
        const box = h('div', { class: 'card sticky', style: { maxHeight: 'calc(100vh - 90px)', overflowY: 'auto' } });
        const idx = B.spec.fields.indexOf(f);
        box.appendChild(h('div', { class: 'card-head' }, h('span', { class: 'eyebrow' }, 'Field ' + (idx + 1)), h('span', { class: 'mono', style: { fontWeight: 600 } }, f.header), h('span', { class: 'spacer' }), ro ? null : h('button', { class: 'btn xs ghost', type: 'button', onclick: () => { B.spec.fields.splice(idx, 1); renumber(); B.selected = null; changed(); drawCatalog(); } }, 'Remove field'), h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Close', style: { width: '28px', height: '28px' }, onclick: () => { B.selected = null; drawCenter(); drawEditor(); } }, icon('x', 14))));
        const body = h('div', { class: 'card-body stack' });
        body.appendChild(h('div', { class: 'grid2' }, h('div', { class: 'field' }, h('label', {}, 'Output header'), h('input', { class: 'input mono', value: f.header || '', disabled: ro, oninput: e => { f.header = e.target.value.toUpperCase().replace(/[^A-Z0-9_ .-]/g, ''); e.target.value = f.header; changed({ redraw: false }); const c2 = centerCol.querySelector('tr.selected input'); if (c2) c2.value = f.header; const hl = centerCol.querySelector('.header-line'); if (hl) hl.textContent = headerLineText(); } })),
          h('div', { class: 'field' }, h('label', {}, 'Description for the vendor'), h('input', { class: 'input', value: f.description || '', disabled: ro, placeholder: 'optional', onchange: e => { f.description = e.target.value; changed({ redraw: 'center' }); } }))));

        /* inputs */
        const inputs = h('div', { class: 'stack', style: { gap: '6px' } });
        f.inputs.forEach((inp, i) => {
          const el = c.elementById[inp.element];
          const card = h('div', { class: 'input-card' }, h('div', { class: 'ic-head' }, h('span', { class: 'num' }, i + 1), h('span', { class: 'name' }, el ? el.name : inp.element ? inp.element : 'Constant "' + (inp.constant || '') + '"'), el ? h('span', { class: 'tag' }, el.type.toLowerCase()) : null, el && el.phi ? h('span', { class: 'pill phi' }, 'PHI') : null,
            ro ? null : h('span', { class: 'row', style: { gap: '2px' } }, i > 0 ? h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Move input up', style: { width: '24px', height: '24px' }, onclick: () => { f.inputs.splice(i - 1, 0, f.inputs.splice(i, 1)[0]); changed(); } }, icon('up', 12)) : null,
              h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Change element', style: { width: '24px', height: '24px' }, title: 'Change element', onclick: () => pickElementModal(e => { inp.element = e.id; delete inp.constant; changed(); drawCatalog(); }) }, icon('refresh', 12)),
              h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Remove input', style: { width: '24px', height: '24px' }, onclick: () => { f.inputs.splice(i, 1); if (f.inputs.length <= 1) delete f.combiner; changed(); drawCatalog(); } }, icon('x', 12)))));
          if (inp.element === undefined && !ro) card.appendChild(h('input', { class: 'input sm', value: inp.constant || '', placeholder: 'constant text', onchange: e => { inp.constant = e.target.value; changed({ redraw: 'center' }); } }));
          const pre = inp.rules || (inp.rules = []);
          if (pre.length || f.inputs.length > 1) {
            card.appendChild(h('div', { class: 'row', style: { gap: '6px' } }, h('span', { class: 'sub' }, 'shaped first:'), pre.map((r, ri) => h('span', { class: 'chip', title: 'Edit', style: { cursor: ro ? 'default' : 'pointer' }, onclick: () => { if (!ro) editRuleModal(r, el ? el.type : 'TEXT', () => changed(), () => { pre.splice(ri, 1); changed(); }); } }, r.type, ' ', h('span', { style: { opacity: .8 } }, summarize(r)))), ro ? null : dropdown(h('button', { class: 'btn xs ghost', type: 'button' }, '+ shape'), c.rules.filter(r => r.category === 'VALUE').map(r => ({ label: r.label, onClick: () => { const nr = { type: r.type, params: defaults(r) }; pre.push(nr); changed(); editRuleModal(nr, el ? el.type : 'TEXT', () => changed(), () => { pre.splice(pre.indexOf(nr), 1); changed(); }); } })))));
          }
          inputs.appendChild(card);
        });
        body.appendChild(h('div', { class: 'field' }, h('label', {}, 'Inputs · in order'), inputs, ro ? null : h('div', { class: 'row', style: { gap: '6px' } }, h('button', { class: 'btn xs', type: 'button', onclick: () => pickElementModal(e => { f.inputs.push({ element: e.id, rules: [] }); if (f.inputs.length > 1 && !f.combiner) f.combiner = { type: 'CONCAT', params: { separator: ' ', skipBlank: true } }; changed(); drawCatalog(); }) }, icon('plus', 12), 'Add input'), h('button', { class: 'btn xs', type: 'button', onclick: () => { f.inputs.push({ constant: '', rules: [] }); if (f.inputs.length > 1 && !f.combiner) f.combiner = { type: 'CONCAT', params: { separator: '', skipBlank: true } }; changed(); } }, 'Add constant'))));

        /* combiner */
        if (f.inputs.length > 1) {
          if (!f.combiner) f.combiner = { type: 'CONCAT', params: { separator: ' ', skipBlank: true } };
          const rule = c.ruleByType[f.combiner.type];
          body.appendChild(h('div', { class: 'field' }, h('label', {}, 'How to combine'),
            h('div', { class: 'rule-card on' }, h('div', { class: 'rc-head' }, h('select', { class: 'input sm', style: { width: '170px' }, disabled: ro, onchange: e => { f.combiner = { type: e.target.value, params: defaults(c.ruleByType[e.target.value]) }; changed(); } }, c.rules.filter(r => r.category === 'COMBINER').map(r => h('option', { value: r.type, selected: r.type === f.combiner.type }, r.label))), h('span', { class: 'small muted' }, rule ? rule.description : '')),
              ruleForm(rule, f.combiner.params, ro, () => changed({ redraw: 'center' })))));
        }

        /* rules */
        const rulesBox = h('div', { class: 'stack', style: { gap: '8px' } });
        f.rules.forEach((r, ri) => {
          const rule = c.ruleByType[r.type];
          rulesBox.appendChild(h('div', { class: 'rule-card' }, h('div', { class: 'rc-head' }, h('span', { class: 'chip' }, r.type), h('span', { class: 'lbl' }, rule ? rule.label : ''), h('span', { class: 'spacer' }), ro ? null : h('span', { class: 'row', style: { gap: '2px' } },
            ri > 0 ? h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Move rule up', style: { width: '24px', height: '24px' }, onclick: () => { f.rules.splice(ri - 1, 0, f.rules.splice(ri, 1)[0]); changed(); } }, icon('up', 12)) : null,
            h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Remove rule', style: { width: '24px', height: '24px' }, onclick: () => { f.rules.splice(ri, 1); changed(); } }, icon('x', 12)))),
            rule ? h('div', { class: 'small muted' }, rule.description) : null, ruleForm(rule, r.params, ro, () => changed({ redraw: 'center' }))));
        });
        body.appendChild(h('div', { class: 'field' }, h('label', {}, 'Rules · applied in order'), rulesBox, ro ? null : dropdown(h('button', { class: 'btn sm', type: 'button', style: { alignSelf: 'flex-start' } }, icon('plus', 12), 'Add rule ▾'), c.rules.filter(r => r.category === 'VALUE' || r.type === 'CONCAT').map(r => ({ label: r.label + (r.type === 'CONCAT' ? ' (prefix, suffix)' : ''), onClick: () => { f.rules.push({ type: r.type, params: defaults(r) }); changed(); } })))));

        /* field settings */
        const fixed = B.spec.fileFormat.type === 'FIXED_WIDTH';
        body.appendChild(h('div', { class: 'field' }, h('label', {}, 'Field settings'), h('div', { class: 'rule-form' },
          h('div', { class: 'field' }, h('label', {}, 'Empty value becomes'), h('input', { class: 'input sm', value: f.defaultValue || '', disabled: ro, placeholder: 'leave empty', onchange: e => { f.defaultValue = e.target.value || null; changed({ redraw: 'center' }); } })),
          h('div', { class: 'field' }, h('label', {}, 'Max length'), h('input', { class: 'input sm', type: 'number', min: 0, value: f.maxLength || '', disabled: ro, placeholder: 'none', onchange: e => { f.maxLength = +e.target.value || null; changed({ redraw: 'center' }); } })),
          h('div', { class: 'field' }, h('label', {}, 'If longer'), h('select', { class: 'input sm', disabled: ro, onchange: e => { f.onOverflow = e.target.value; changed({ redraw: 'center' }); } }, h('option', { value: 'TRUNCATE', selected: f.onOverflow !== 'FAIL' }, 'Truncate'), h('option', { value: 'FAIL', selected: f.onOverflow === 'FAIL' }, 'Fail the run'))),
          fixed ? h('div', { class: 'field' }, h('label', {}, 'Width'), h('input', { class: 'input sm', type: 'number', min: 1, value: f.width || '', disabled: ro, onchange: e => { f.width = +e.target.value || null; changed({ redraw: 'center' }); } })) : null,
          fixed ? h('div', { class: 'field' }, h('label', {}, 'Align'), h('select', { class: 'input sm', disabled: ro, onchange: e => { f.align = e.target.value; changed({ redraw: 'center' }); } }, h('option', { value: 'LEFT', selected: f.align !== 'RIGHT' }, 'Left'), h('option', { value: 'RIGHT', selected: f.align === 'RIGHT' }, 'Right'))) : null,
          fixed ? h('div', { class: 'field' }, h('label', {}, 'Pad with'), h('select', { class: 'input sm', disabled: ro, onchange: e => { f.padChar = e.target.value; changed({ redraw: 'center' }); } }, h('option', { value: ' ', selected: f.padChar !== '0' }, 'spaces'), h('option', { value: '0', selected: f.padChar === '0' }, 'zeros'))) : null)));

        /* live preview */
        const prev = h('div', { class: 'small muted' }, 'Save, then preview to see this field on real rows.');
        body.appendChild(h('div', { style: { borderTop: '1px solid var(--line-2)', paddingTop: '10px' } }, h('div', { class: 'row' }, h('span', { class: 'eyebrow' }, 'Live preview · masked'), h('span', { class: 'topbar-spacer' }), h('button', { class: 'btn xs', type: 'button', onclick: async () => { await save({ quiet: true }); try { const r = await post('/api/v1/definitions/' + def.id + '/versions/' + ver.versionNo + '/preview?rows=6'); clear(prev); if (r.status === 'FAILED') { prev.appendChild(h('span', { class: 'risk HIGH' }, r.error)); return; } prev.appendChild(h('div', { class: 'mono', style: { fontSize: '13px', fontWeight: 500 } }, r.previewRows.map(row => h('div', {}, row[f.header] === null || row[f.header] === '' || row[f.header] === undefined ? h('span', { class: 'muted' }, '(empty)') : row[f.header])))); const w = r.warnings.filter(x => x.field === f.header); prev.appendChild(h('div', { class: 'small muted mt' }, w.length ? w.map(x => x.count + ' ' + fmt.status(x.code) + ' (' + x.example + ')').join('; ') : 'No warnings on this field in ' + r.rowCount + ' rows.')); } catch (e) { clear(prev); prev.appendChild(h('span', { class: 'risk HIGH' }, e.message + (e.details && e.details.length ? ': ' + e.details[0] : ''))); } } }, icon('eye', 12), 'Preview 6 values')), prev));
        box.appendChild(body);
        return box;
      }
      function defaults(rule) {
        const p = {};
        for (const prm of rule.params || []) if (prm.defaultValue !== undefined && prm.defaultValue !== null) p[prm.name] = prm.defaultValue;
        return p;
      }
      function ruleForm(rule, params, ro, onChange) {
        if (!rule) return h('div', { class: 'small risk HIGH' }, 'Unknown rule');
        const form = h('div', { class: 'rule-form' });
        const draw = () => {
          clear(form);
          for (const p of rule.params) {
            if (p.showWhen) { const [k, vals] = p.showWhen.split('='); if (!vals.split(',').includes(String(params[k] ?? defaultOf(rule, k) ?? ''))) continue; }
            form.appendChild(paramControl(p, params, ro, () => { onChange(); if (['enum', 'boolean', 'element', 'lookup'].includes(p.kind)) draw(); }));
          }
        };
        draw();
        return form;
      }
      function defaultOf(rule, name) { const p = rule.params.find(x => x.name === name); return p ? p.defaultValue : undefined; }
      function paramControl(p, params, ro, onChange) {
        const val = params[p.name] !== undefined ? params[p.name] : p.defaultValue;
        const wrap = h('div', { class: 'field' + (['map', 'ranges', 'conditions'].includes(p.kind) ? ' full' : '') }, h('label', {}, p.label + (p.required ? ' *' : '')));
        let ctl;
        switch (p.kind) {
          case 'enum': ctl = h('select', { class: 'input sm', disabled: ro, onchange: e => { params[p.name] = e.target.value; onChange(); } }, (p.options || []).map(o => h('option', { value: o, selected: String(val) === o }, o))); break;
          case 'int': ctl = h('input', { class: 'input sm', type: 'number', value: val ?? '', disabled: ro, onchange: e => { params[p.name] = e.target.value === '' ? null : +e.target.value; onChange(); } }); break;
          case 'boolean': ctl = h('label', { class: 'check', style: { height: '30px' } }, h('input', { type: 'checkbox', checked: !!val, disabled: ro, onchange: e => { params[p.name] = e.target.checked; onChange(); } }), val ? 'on' : 'off'); break;
          case 'element': ctl = h('select', { class: 'input sm', disabled: ro, onchange: e => { params[p.name] = e.target.value; onChange(); } }, h('option', { value: '' }, '— none —'), c.elements.filter(e => B.reachable.includes(e.entity)).map(e => h('option', { value: e.id, selected: val === e.id }, e.name + ' (' + e.entity + ')'))); break;
          case 'lookup': ctl = h('select', { class: 'input sm', disabled: ro, onchange: e => { params[p.name] = e.target.value; onChange(); } }, h('option', { value: '' }, '— pick a lookup —'), c.lookups.map(l => h('option', { value: l.id, selected: val === l.id }, l.name + ' (' + l.id + ')'))); break;
          case 'map': ctl = mapGrid(params, p.name, ro, onChange); break;
          case 'ranges': ctl = rangesGrid(params, p.name, ro, onChange); break;
          case 'conditions': ctl = conditionsEditor(params, p.name, ro, onChange); break;
          default: ctl = h('input', { class: 'input sm' + (['template', 'customPattern', 'inputPattern', 'value', 'find', 'replaceWith', 'separator', 'delimiter'].includes(p.name) ? ' mono' : ''), value: val ?? '', disabled: ro, onchange: e => { params[p.name] = e.target.value; onChange(); } });
        }
        wrap.appendChild(ctl);
        if (p.help) wrap.appendChild(h('div', { class: 'help' }, p.help));
        return wrap;
      }
      function mapGrid(params, name, ro, onChange) {
        const map = params[name] && typeof params[name] === 'object' ? params[name] : (params[name] = {});
        const tbody = h('tbody');
        const addRow = (k, v) => tbody.appendChild(h('tr', {}, h('td', {}, h('input', { class: 'input mono', value: k, placeholder: 'source', disabled: ro, onchange: () => rebuild() })), h('td', { style: { width: '20px', textAlign: 'center' } }, '→'), h('td', {}, h('input', { class: 'input mono', value: v, placeholder: 'output', disabled: ro, onchange: () => rebuild() }))));
        Object.entries(map).forEach(([k, v]) => addRow(k, v));
        if (!ro) addRow('', '');
        const table = h('table', { class: 'mini-table' }, tbody);
        /* rows grow in place so the analyst can tab from cell to cell without losing focus */
        function rebuild() { const m = {}; for (const tr of tbody.children) { const [a, b] = tr.querySelectorAll('input'); if (a.value.trim() !== '') m[a.value.trim()] = b.value; } params[name] = m; const last = tbody.lastElementChild && tbody.lastElementChild.querySelector('input'); if (!ro && last && last.value.trim() !== '') addRow('', ''); onChange(); }
        return table;
      }
      function rangesGrid(params, name, ro, onChange) {
        const list = Array.isArray(params[name]) ? params[name] : (params[name] = []);
        const tbody = h('tbody');
        const addRow = r => tbody.appendChild(h('tr', {}, h('td', {}, h('input', { class: 'input mono', value: r.from ?? '', disabled: ro, onchange: () => rebuild() })), h('td', {}, h('input', { class: 'input mono', value: r.to ?? '', disabled: ro, onchange: () => rebuild() })), h('td', {}, h('input', { class: 'input mono', value: r.value ?? '', disabled: ro, onchange: () => rebuild() }))));
        list.forEach(addRow);
        if (!ro) addRow({ from: '', to: '', value: '' });
        const table = h('table', { class: 'mini-table' }, h('thead', {}, h('tr', {}, h('td', { class: 'sub' }, 'from ≥'), h('td', { class: 'sub' }, 'to <'), h('td', { class: 'sub' }, 'output'))), tbody);
        function rebuild() { const out = []; for (const tr of tbody.children) { const [a, b, v] = tr.querySelectorAll('input'); if (a.value !== '' || b.value !== '') out.push({ from: a.value, to: b.value, value: v.value }); } params[name] = out; const lastTr = tbody.lastElementChild; const [la, lb] = lastTr ? lastTr.querySelectorAll('input') : []; if (!ro && lastTr && (la.value !== '' || lb.value !== '')) addRow({ from: '', to: '', value: '' }); onChange(); }
        return table;
      }
      function conditionsEditor(params, name, ro, onChange) {
        const rules = Array.isArray(params[name]) ? params[name] : (params[name] = []);
        const box = h('div', { class: 'stack', style: { gap: '6px' } });
        const structural = () => { onChange(); draw(); };
        const draw = () => { clear(box);
        const opts = [['EQ', 'equals'], ['NE', 'not equal'], ['IN', 'in list'], ['NOT_IN', 'not in'], ['BLANK', 'is empty'], ['NOT_BLANK', 'is not empty'], ['GT', '>'], ['GTE', '≥'], ['LT', '<'], ['LTE', '≤'], ['BETWEEN', 'between'], ['STARTS_WITH', 'starts with'], ['CONTAINS', 'contains']];
        rules.forEach((r, ri) => {
          r.conditions = r.conditions || [{ compare: 'CURRENT', op: 'EQ', values: [] }];
          const card = h('div', { class: 'input-card' }, h('div', { class: 'ic-head' }, h('span', { class: 'num' }, ri + 1), h('span', { class: 'name' }, ri === 0 ? 'If' : 'Else if'), h('select', { class: 'input sm', style: { width: '80px' }, disabled: ro, onchange: e => { r.match = e.target.value; onChange(); } }, h('option', { value: 'ALL', selected: r.match !== 'ANY' }, 'all'), h('option', { value: 'ANY', selected: r.match === 'ANY' }, 'any')), h('span', { class: 'sub' }, 'of'), ro ? null : h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Remove rule', style: { width: '24px', height: '24px' }, onclick: () => { rules.splice(ri, 1); structural(); } }, icon('x', 12))));
          r.conditions.forEach((cd, ci) => {
            card.appendChild(h('div', { class: 'row', style: { gap: '4px', flexWrap: 'nowrap' } },
              h('select', { class: 'input sm', style: { width: '40%' }, disabled: ro, onchange: e => { cd.compare = e.target.value; onChange(); } }, h('option', { value: 'CURRENT', selected: cd.compare === 'CURRENT' || !cd.compare }, 'this value'), c.elements.filter(e => B.reachable.includes(e.entity)).map(e => h('option', { value: e.id, selected: cd.compare === e.id }, e.name))),
              h('select', { class: 'input sm', style: { width: '28%' }, disabled: ro, onchange: e => { cd.op = e.target.value; onChange(); } }, opts.map(([v, l]) => h('option', { value: v, selected: (cd.op || 'EQ') === v }, l))),
              h('input', { class: 'input sm mono', style: { width: '32%' }, value: (cd.values || []).join(', '), placeholder: 'values', disabled: ro, onchange: e => { cd.values = e.target.value.split(',').map(s => s.trim()).filter(Boolean); onChange(); } }),
              ro ? null : h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Remove condition', style: { width: '24px', height: '24px', flexShrink: 0 }, onclick: () => { r.conditions.splice(ci, 1); structural(); } }, icon('x', 12))));
          });
          if (!ro) card.appendChild(h('button', { class: 'btn xs ghost', type: 'button', style: { alignSelf: 'flex-start' }, onclick: () => { r.conditions.push({ compare: 'CURRENT', op: 'EQ', values: [] }); structural(); } }, '+ and'));
          card.appendChild(h('div', { class: 'row', style: { gap: '4px', flexWrap: 'nowrap' } }, h('span', { class: 'sub', style: { width: '40px' } }, 'then'), h('select', { class: 'input sm', style: { width: '30%' }, disabled: ro, onchange: e => { r.thenKind = e.target.value; structural(); } }, [['CONSTANT', 'constant'], ['KEEP', 'keep value'], ['ELEMENT', 'another element'], ['BLANK', 'blank']].map(([v, l]) => h('option', { value: v, selected: (r.thenKind || 'CONSTANT') === v }, l))),
            (r.thenKind || 'CONSTANT') === 'ELEMENT' ? h('select', { class: 'input sm', disabled: ro, onchange: e => { r.thenValue = e.target.value; onChange(); } }, c.elements.filter(e => B.reachable.includes(e.entity)).map(e => h('option', { value: e.id, selected: r.thenValue === e.id }, e.name))) : (r.thenKind || 'CONSTANT') === 'CONSTANT' ? h('input', { class: 'input sm mono', value: r.thenValue ?? '', disabled: ro, onchange: e => { r.thenValue = e.target.value; onChange(); } }) : null));
          box.appendChild(card);
        });
        if (!ro) box.appendChild(h('button', { class: 'btn xs', type: 'button', style: { alignSelf: 'flex-start' }, onclick: () => { rules.push({ conditions: [{ compare: 'CURRENT', op: 'EQ', values: [] }], match: 'ALL', thenKind: 'CONSTANT', thenValue: '' }); structural(); } }, icon('plus', 12), (rules.length ? 'Else if' : 'If')));
        };
        draw();
        return box;
      }
      function editRuleModal(r, inputType, onChange, onRemove) {
        const rule = c.ruleByType[r.type];
        modal({ title: rule.label + ' · shape this input first', body: h('div', { class: 'stack' }, h('p', { class: 'small muted' }, rule.description), ruleForm(rule, r.params, false, onChange)),
          actions: [{ label: 'Remove', kind: 'danger', onClick: () => { onRemove(); } }, { label: 'Done', kind: 'primary', onClick: () => { onChange(); } }] });
      }

      /* ---------- preview & sample ---------- */
      async function preview() {
        await save({ quiet: true });
        try {
          const r = await post('/api/v1/definitions/' + def.id + '/versions/' + ver.versionNo + '/preview?rows=20');
          const rows = r.previewRows || [];
          const headers = rows.length ? Object.keys(rows[0]) : B.spec.fields.map(f => f.header);
          const masked = new Set(r.maskedFields || []);
          modal({ title: 'Preview · first ' + rows.length + ' of ' + fmt.num(r.matched) + ' matching rows', wide: true, body: h('div', { class: 'stack' },
            r.status === 'FAILED' ? h('div', { class: 'alert danger' }, icon('warn'), r.error) : null,
            h('div', { class: 'small muted' }, 'PHI is masked in previews for everyone. Shaded cells are masked; widths and formats are real. ' + fmt.num(r.scanned) + ' rows scanned, ' + fmt.num(r.matched) + ' matched the filters.'),
            r.warnings && r.warnings.length ? h('div', { class: 'alert warn' }, icon('warn'), h('ul', {}, r.warnings.map(w => h('li', {}, h('b', {}, w.field), ': ' + w.count + ' ' + fmt.status(w.code) + ' (' + w.example + ')')))) : null,
            h('div', { class: 'table-wrap' }, h('table', { class: 'table grid compact' }, h('thead', {}, h('tr', {}, h('th', {}, '#'), headers.map(x => h('th', {}, x)))), h('tbody', {}, rows.map((row, i) => h('tr', {}, h('td', { class: 'sub' }, i + 1), headers.map(x => h('td', { class: masked.has(x) ? 'masked' : '' }, row[x] ?? ''))))))),
            h('details', {}, h('summary', { style: { cursor: 'pointer', color: 'var(--accent-ink)', fontSize: '13px' } }, 'Raw lines as written'), h('div', { class: 'preview-lines mt' }, (r.previewLines || []).join('\n')))),
            actions: [{ label: 'Close' }, { label: 'Generate sample', kind: 'primary', icon: 'play', onClick: () => { generateSample(); } }] });
        } catch (e) { /* toast shown */ }
      }
      async function generateSample() {
        await save({ quiet: true });
        const canUnmask = state.me.phiUnmasked && B.partner && B.partner.baaOnFile && B.partner.allowUnmaskedSamples;
        const unmasked = h('input', { type: 'checkbox', disabled: !canUnmask });
        const rows = h('input', { class: 'input', type: 'number', min: 1, max: 5000, value: B.spec.sample.maxRows || 200 });
        modal({ title: 'Generate a sample', body: h('div', { class: 'stack' },
          h('p', { class: 'small muted' }, 'Runs the layout against real data with a row limit and writes the file exactly as production would, named as the vendor will see it. The version moves to Sampled.'),
          h('div', { class: 'grid2' }, h('div', { class: 'field' }, h('label', {}, 'Rows'), rows), h('div', { class: 'field' }, h('label', {}, 'Masking'), h('label', { class: 'check', style: { height: '36px' } }, unmasked, 'Unmasked (real PHI)'), h('div', { class: 'help' }, canUnmask ? 'You hold the PHI privilege and ' + B.partner.name + ' has a BAA on file. Every download is audited.' : !state.me.phiUnmasked ? 'Your account cannot see unmasked PHI; the sample is masked.' : 'Needs a BAA on file and the partner flag; the sample is masked.'))),
          B.spec.sample.cohort && B.spec.sample.cohort.length ? h('div', { class: 'small muted' }, 'Cohort: ' + B.spec.sample.cohort.join(', ')) : null),
          actions: [{ label: 'Cancel' }, { label: 'Build sample', kind: 'primary', icon: 'play', onClick: async () => {
            const r = await post('/api/v1/definitions/' + def.id + '/versions/' + ver.versionNo + '/sample', { unmasked: unmasked.checked, maxRows: +rows.value || 200, synthetic: !!B.spec.sample.synthetic, cohort: B.spec.sample.cohort || [] });
            toast(r.status === 'WRITTEN' ? 'Sample built: ' + fmt.num(r.rowCount) + ' rows' : 'Sample failed: ' + r.error, r.status === 'WRITTEN' ? 'ok' : 'danger');
            location.hash = '#/runs/' + r.id;
          } }] });
      }

      function drawAll() { drawHead(); drawCatalog(); drawCenter(); drawEditor(); }
      drawAll();
      validate(true).then(() => drawEditor());
    }
  });
})();
