(function () {
  const { h, icon, fmt, pill, get, post, put, crumbs, registerPage, state, toast, catalog } = App;

  registerPage('productionalize', {
    async render(root, params) {
      const c = await catalog();
      const full = await get('/api/v1/definitions/' + params.id);
      const def = full.definition;
      const ver = def.versions.find(v => v.versionNo === +params.no);
      crumbs([{ label: 'Definitions', href: '#/definitions' }, { label: def.name, href: '#/definitions/' + def.id }, { label: 'v' + ver.versionNo, href: '#/definitions/' + def.id + '/v/' + ver.versionNo }, { label: 'Productionalize' }]);
      const partner = await get('/api/v1/partners/' + def.vendorCode).catch(() => null);
      const prev = def.versions.find(v => v.status === 'PRODUCTION');
      const cfg = ver.productionConfig || (prev && prev.productionConfig ? JSON.parse(JSON.stringify(prev.productionConfig)) : defaultConfig(def));
      cfg.schedule = cfg.schedule || {};
      const isLive = ver.status === 'PRODUCTION';
      const canGo = ver.status === 'APPROVED';
      let step = 0;
      let dry = null;
      const steps = ['File name and format', 'Schedule', 'Scope and watermark', 'Delivery route', 'Quality and notifications', 'Review and confirm'];

      const head = h('div', { class: 'page-head' }, h('div', {}, h('div', { class: 'eyebrow' }, def.name + ' · v' + ver.versionNo), h('h1', { class: 'row' }, isLive ? 'Production settings' : 'Productionalize v' + ver.versionNo, pill(ver.status)),
        h('p', { class: 'lead' }, isLive ? 'This version is live. Operational settings can change without a new version; every run snapshots the settings it used and every edit is audited.' : canGo ? 'Approved ' + fmt.date(ver.approvedAt) + ' by ' + ver.approvedBy + (prev ? '. Confirming replaces v' + prev.versionNo + ' in production.' : '. Nothing goes live until the last step.') : 'Only an approved version can go live. You can still prepare the settings.')));
      const nav = h('div', { class: 'card stepper', style: { padding: '10px' } });
      const body = h('div', { class: 'card', style: { padding: '22px 26px', minHeight: '520px', display: 'flex', flexDirection: 'column' } });
      const side = h('div', { class: 'stack' });
      root.append(head, h('div', { class: 'split', style: { gridTemplateColumns: '240px minmax(0,1fr) 300px' } }, nav, body, side));

      function drawNav() {
        App.clear(nav);
        steps.forEach((s, i) => nav.appendChild(h('button', { class: 'step' + (i === step ? ' on' : i < step ? ' done' : ''), type: 'button', onclick: () => { step = i; draw(); } }, h('span', { class: 'n' }, i < step ? '✓' : i + 1), s)));
      }
      const field = (label, ctl, help) => h('div', { class: 'field' }, h('label', {}, label), ctl, help ? h('div', { class: 'help' }, help) : null);
      const sel = (obj, key, options, onchange) => h('select', { class: 'input', onchange: e => { obj[key] = e.target.value; if (onchange) onchange(); } }, options.map(o => h('option', { value: o[0], selected: String(obj[key]) === o[0] }, o[1])));
      const txt = (obj, key, attrs) => h('input', Object.assign({ class: 'input', value: obj[key] ?? '', onchange: e => { obj[key] = attrs && attrs.type === 'number' ? +e.target.value : e.target.value; if (attrs && attrs.after) attrs.after(); } }, attrs || {}));
      const chk = (obj, key, label) => h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: !!obj[key], onchange: e => { obj[key] = e.target.checked; } }), label);

      function draw() {
        drawNav();
        App.clear(body);
        const content = h('div', { class: 'stack', style: { gap: '18px', flexGrow: 1 } });
        const title = (t, s) => h('div', {}, h('h2', {}, t), h('p', { class: 'small muted' }, s));
        if (step === 0) {
          const fn = cfg.fileName;
          const example = h('span', { class: 'mono' });
          const upd = () => { example.textContent = (fn.pattern || '').replace('{VENDOR}', def.vendorCode).replace('{SUBJECT}', def.subjectArea === 'MEMBER' ? 'ELIG' : def.subjectArea === 'CLAIM' ? 'CLAIMS' : 'PROV').replace(/\{DATE(?::([^}]+))?}/, (m, p) => today(p || 'yyyyMMdd')).replace(/\{TIME(?::([^}]+))?}/, '020000').replace(/\{SEQ(?::(\d+))?}/, (m, n) => '1'.padStart(+(n || 3), '0')).replace('{VERSION}', 'v' + ver.versionNo); };
          content.append(title('How is the file named and packaged?', 'The name comes from the business date, so a re-run for the same day produces the same name.'),
            h('div', { class: 'grid2' }, field('File name pattern', txt(fn, 'pattern', { class: 'input mono', after: upd }), 'Tokens: {VENDOR} {SUBJECT} {DATE:yyyyMMdd} {TIME:HHmmss} {SEQ:2} {VERSION}'), field('Date in the name', sel(fn, 'dateSource', [['BUSINESS_DATE', 'Business date'], ['RUN_TIME', 'Wall-clock run time']]))),
            h('div', { class: 'alert info' }, icon('file'), h('div', {}, 'Tonight\'s file would be ', example)),
            h('div', { class: 'grid2' }, field('Compression', sel(cfg, 'compression', [['NONE', 'None'], ['GZIP', 'gzip']])), field('PGP encryption', sel(cfg, 'pgpBy', [['AXWAY', 'Applied by Axway per partner'], ['APP', 'Applied by this app with the vendor key'], ['NONE', 'None']]), partner && partner.pgpKeyId ? 'Vendor key on file: ' + partner.pgpKeyId + (partner.pgpKeyExpires ? ', expires ' + partner.pgpKeyExpires : '') : 'No vendor key registered.')),
            h('div', { class: 'small muted' }, 'Layout, delimiter and header or trailer records are part of the approved version and cannot change here.'));
          upd();
        }
        if (step === 1) {
          const s = cfg.schedule;
          const preview = h('div', { class: 'stack', style: { gap: '4px' } });
          const refresh = async () => { App.clear(preview); try { const runs = await post('/api/v1/definitions/schedule-preview?count=6', s, { silent: true }); runs.forEach(r => preview.appendChild(h('div', { class: 'kv' }, h('span', {}, r.error ? 'Invalid' : fmt.dt(r.scheduled)), h('span', { class: r.note ? 'risk MEDIUM' : '' }, r.error || (r.effective ? (r.note ? r.note : 'runs') : r.note))))); } catch (e) { preview.appendChild(h('div', { class: 'risk HIGH small' }, 'Invalid schedule')); } };
          const presetSel = h('div', { class: 'grid4', style: { gridTemplateColumns: 'repeat(5,minmax(0,1fr))' } }, [['DAILY', 'Daily'], ['WEEKDAYS', 'Weekdays'], ['WEEKLY', 'Weekly'], ['MONTHLY', 'Monthly'], ['CUSTOM', 'Custom cron']].map(([v, l]) => h('label', { class: 'opt' + (s.preset === v ? ' on' : '') }, h('input', { type: 'radio', name: 'preset', checked: s.preset === v, onchange: () => { s.preset = v; draw(); } }), l)));
          content.append(title('When should this file be produced?', 'Times are in the vendor\'s timezone. The run waits for the nightly load to finish before it queries.'),
            field('Frequency', presetSel),
            h('div', { class: 'grid3' }, s.preset === 'CUSTOM' ? field('Cron (6 fields, Spring)', txt(s, 'cron', { class: 'input mono', after: refresh }), 'sec min hour day month weekday') : field('Time', h('input', { class: 'input', type: 'time', value: s.time || '02:00', onchange: e => { s.time = e.target.value; refresh(); } })),
              s.preset === 'WEEKLY' ? field('Day of week', sel(s, 'dayOfWeek', ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'].map(d => [d, d]), refresh)) : s.preset === 'MONTHLY' ? field('Day of month', txt(s, 'dayOfMonth', { type: 'number', min: 1, max: 28, after: refresh })) : null,
              field('Timezone', sel(s, 'timezone', [['America/Chicago', 'America/Chicago (CT)'], ['America/New_York', 'America/New_York (ET)'], ['America/Denver', 'America/Denver (MT)'], ['America/Los_Angeles', 'America/Los_Angeles (PT)'], ['UTC', 'UTC']], refresh))),
            h('div', { class: 'grid3' }, field('Business-day calendar', sel(s, 'calendar', c.calendars.map(k => [k.id, k.name]), refresh)), field('On a holiday', sel(s, 'holidayPolicy', [['NEXT_BUSINESS_DAY', 'Run on the next business day'], ['SKIP', 'Skip that run'], ['RUN_ANYWAY', 'Run anyway']], refresh)), field('If the app was down at run time', sel(s, 'misfire', [['RUN_ONCE', 'Run once as soon as it is back'], ['SKIP', 'Skip until the next scheduled time']]))),
            h('div', {}, h('div', { class: 'eyebrow mb' }, 'Next runs with these settings'), preview));
          refresh();
        }
        if (step === 2) {
          const scope = ver.spec.scope || {};
          const inc = scope.mode === 'INCREMENTAL';
          const wm = full.watermark;
          content.append(title('What goes in each file?', 'Scope is part of the approved layout. Here you set where an incremental feed starts.'),
            h('div', { class: 'alert ' + (inc ? 'info' : 'ok') }, icon(inc ? 'history' : 'check'), h('div', {}, inc ? [h('b', {}, 'Incremental. '), 'Each run sends rows whose ', (scope.watermarkElements || []).map(App.elementName).join(' or '), ' changed since the previous delivered run, with a lag of ' + scope.lagMinutes + ' minutes so late-committing rows are not missed.'] : [h('b', {}, 'Full refresh. '), 'Every run sends the whole filtered population.'])),
            inc ? h('div', { class: 'grid2' }, field('First window starts at', h('input', { class: 'input', type: 'datetime-local', value: (cfg.initialWatermark || scope.initialWatermark || '').slice(0, 16), onchange: e => { cfg.initialWatermark = e.target.value; } }), wm ? 'The current watermark ' + wm.value.replace('T', ' ') + ' carries over when the watermark columns are unchanged; this value is used only when they changed.' : 'Used for the first production run. Leave empty for 30 days before the first run.'),
              field('Zero rows in a window', sel(cfg.delivery, 'zeroRowPolicy', [['SEND_EMPTY', 'Send an empty file'], ['SKIP', 'Skip the delivery'], ['FAIL', 'Fail the run']]))) : field('Zero rows', sel(cfg.delivery, 'zeroRowPolicy', [['SEND_EMPTY', 'Send an empty file'], ['SKIP', 'Skip the delivery'], ['FAIL', 'Fail the run']])),
            h('div', {}, h('div', { class: 'eyebrow mb' }, 'Filters in this version'), ver.spec.filters.length ? h('ul', { class: 'small' }, ver.spec.filters.map(f => h('li', {}, f.template ? (c.templateById[f.template] ? c.templateById[f.template].label : f.template) + (f.params && Object.keys(f.params).length ? ' (' + Object.entries(f.params).map(([k, v]) => k + ' = ' + (typeof v === 'object' ? 'run date' + ((v && v.offsetDays) ? ' ' + v.offsetDays + ' days' : '') : v)).join(', ') + ')' : '') : App.elementName(f.element) + ' ' + (f.op || 'EQ').toLowerCase().replace('_', ' ') + ' ' + (Array.isArray(f.value) ? f.value.join(', ') : typeof f.value === 'object' && f.value ? 'run date' : f.value)))) : h('div', { class: 'small muted' }, 'No filters: every row of the grain.')));
        }
        if (step === 3) {
          const d = cfg.delivery;
          const routeInfo = h('div', { class: 'stack', style: { gap: '6px' } });
          const test = h('div');
          const drawRoute = () => { App.clear(routeInfo); if (!partner) { routeInfo.appendChild(h('div', { class: 'alert danger' }, icon('warn'), 'No MFT partner is registered for ' + def.vendorCode + '. Add one under MFT partners.')); return; } const r = d.route === 'TEST' ? partner.test : partner.prod; routeInfo.append(kv('Partner', partner.name + ' (' + partner.code + ')'), kv('Mode', r.mode === 'AXWAY_REST' ? 'Axway REST upload' : 'Drop folder polled by Axway'), kv('Folder', h('span', { class: 'mono' }, 'mft/' + r.folder + '/out')), kv('Axway account', r.axwayAccount || '—'), kv('Transport to vendor', r.transport || 'SFTP'), kv('Acknowledgement expected', r.ackExpected ? 'yes' : 'no'), kv('BAA on file', partner.baaOnFile ? 'yes, ' + partner.baaSignedDate : h('span', { class: 'risk HIGH' }, 'no')), kv('Contract', (partner.contractStart || '?') + ' → ' + (partner.contractEnd || 'open'))); };
          content.append(title('Where does the file go?', 'A partner has a test route and a production route. Samples go to test; production runs go here.'),
            h('div', { class: 'grid2' }, field('Route', sel(d, 'route', [['PROD', 'Production route'], ['TEST', 'Test route (for a pilot)']], drawRoute)), field('Retries on a transient delivery error', h('div', { class: 'row', style: { gap: '6px' } }, txt(d, 'retries', { type: 'number', min: 0, max: 10, style: { width: '90px' } }), h('span', { class: 'sub' }, 'times, every'), txt(d, 'backoffSeconds', { type: 'number', min: 10, style: { width: '110px' } }), h('span', { class: 'sub' }, 'seconds')))),
            chk(d, 'controlFile', 'Write a .done control file after the data file (tells Axway the file is complete)'),
            h('div', { class: 'card pad', style: { background: 'var(--surface-2)' } }, routeInfo),
            h('div', { class: 'row' }, h('button', { class: 'btn sm', type: 'button', onclick: async () => { const r = await post('/api/v1/partners/' + def.vendorCode + '/test-route?route=' + d.route); App.clear(test); test.appendChild(h('div', { class: 'alert ' + (r.ok ? 'ok' : 'danger') }, icon(r.ok ? 'check' : 'warn'), h('div', {}, r.message, ' ', h('span', { class: 'mono small' }, r.folder)))); } }, icon('bolt', 13), 'Test the route now'), test));
          drawRoute();
        }
        if (step === 4) {
          const q = cfg.quality, n = cfg.notifications;
          const list = (key, label, help) => field(label, h('input', { class: 'input', value: (n[key] || []).join(', '), placeholder: 'email, email', onchange: e => { n[key] = e.target.value.split(',').map(s => s.trim()).filter(Boolean); } }), help);
          content.append(title('Quality gate and who to tell', 'A file that looks wrong is held before delivery. An approver releases it or the analyst fixes the cause and runs again.'),
            chk(q, 'enabled', 'Check every production file before delivery'),
            h('div', { class: 'grid3' }, field('Row count may move up to', h('div', { class: 'row', style: { gap: '6px' } }, txt(q, 'rowCountVariancePct', { type: 'number', min: 0, max: 500, style: { width: '90px' } }), h('span', { class: 'sub' }, '% vs the last delivered run'))), field('Hold if any field is empty on more than', h('div', { class: 'row', style: { gap: '6px' } }, txt(q, 'nullRateMaxPct', { type: 'number', min: 0, max: 100, style: { width: '90px' } }), h('span', { class: 'sub' }, '% of rows'))), field('Lookup misses allowed', txt(q, 'lookupMissMax', { type: 'number', min: 0 }))),
            chk(q, 'holdOnBreach', 'Hold the file when a check fails (otherwise only warn)'),
            h('div', { class: 'grid2' }, list('onFailure', 'On failure', 'Always sent. Also the fallback for the other events.'), list('onHold', 'When a file is held'), list('onDelivered', 'On every delivery', 'Leave empty to stay quiet.'), list('onSlaMissed', 'When the delivery window is missed')),
            h('div', { class: 'grid2' }, field('Alert if not delivered by', h('input', { class: 'input', type: 'time', value: cfg.sla.deliverBy || '07:00', onchange: e => { cfg.sla.deliverBy = e.target.value; } }), 'In the schedule timezone, on days the feed runs.'), field('Note for operations', txt(cfg.sla, 'note', { placeholder: 'e.g. Vendor loads the file at 07:30 CT' }))));
        }
        if (step === 5) {
          const checks = h('div', { class: 'check-list' }, h('div', { class: 'sk', style: { height: '60px' } }));
          const nextRuns = h('div');
          content.append(title('Review and confirm', canGo ? 'The dry run checks the layout, the schedule, the partner route, the contract, the BAA and the file name before anything changes.' : isLive ? 'Save updates the live settings; the next scheduled run uses them.' : 'This version is not approved yet; you can save the settings for later.'),
            h('div', { class: 'grid2' }, h('div', { class: 'card pad' }, h('div', { class: 'eyebrow mb' }, 'Summary'), kv('File', h('span', { class: 'mono', style: { wordBreak: 'break-all' } }, cfg.fileName.pattern)), kv('Schedule', cfg.schedule.preset.toLowerCase() + (cfg.schedule.preset === 'CUSTOM' ? ' ' + cfg.schedule.cron : ' at ' + cfg.schedule.time) + ' ' + cfg.schedule.timezone), kv('Holidays', (cfg.schedule.holidayPolicy || '').toLowerCase().replace(/_/g, ' ')), kv('Route', cfg.delivery.route + (partner ? ' · ' + partner.name : '')), kv('Zero rows', cfg.delivery.zeroRowPolicy.toLowerCase().replace('_', ' ')), kv('Quality gate', cfg.quality.enabled ? 'on, ±' + cfg.quality.rowCountVariancePct + '%, ' + cfg.quality.lookupMissMax + ' misses' : 'off'), kv('SLA', 'by ' + cfg.sla.deliverBy), kv('PGP', cfg.pgpBy.toLowerCase()), prev ? kv('Replaces', 'v' + prev.versionNo + ', live since ' + fmt.date(prev.productionizedAt)) : null),
              h('div', {}, h('div', { class: 'eyebrow mb' }, 'Dry run'), checks, nextRuns)));
          post('/api/v1/definitions/' + def.id + '/versions/' + ver.versionNo + '/dry-run', cfg).then(r => {
            dry = r;
            App.clear(checks);
            r.checks.forEach(k => checks.appendChild(h('div', { class: 'check-item ' + (k.ok ? 'ok' : 'bad') }, h('span', { class: 'mark' }, k.ok ? '✓' : '!'), h('div', {}, h('b', {}, k.name), h('span', {}, k.detail)))));
            App.clear(nextRuns);
            nextRuns.appendChild(h('div', { class: 'small muted mt' }, 'Next runs: ' + (r.nextRuns || []).slice(0, 3).map(x => fmt.dt(x.effective || x.scheduled)).join(', ')));
            drawFoot();
          }).catch(() => { App.clear(checks); checks.appendChild(h('div', { class: 'risk HIGH' }, 'Dry run failed')); });
        }
        body.appendChild(content);
        const foot = h('div', { id: 'wizFoot', class: 'row', style: { borderTop: '1px solid var(--line-2)', paddingTop: '14px', marginTop: '18px' } });
        body.appendChild(foot);
        drawFoot();
        drawSide();
      }
      function drawFoot() {
        const foot = document.getElementById('wizFoot');
        if (!foot) return;
        App.clear(foot);
        App.append(foot, [step > 0 ? h('button', { class: 'btn', type: 'button', onclick: () => { step--; draw(); } }, icon('arrowLeft'), 'Back') : h('a', { class: 'btn', href: '#/definitions/' + def.id + '/v/' + ver.versionNo }, 'Cancel'), h('span', { class: 'topbar-spacer' }),
          h('button', { class: 'btn', type: 'button', onclick: async () => { await put('/api/v1/definitions/' + def.id + '/versions/' + ver.versionNo + '/production-config', cfg); toast(isLive ? 'Live settings updated' : 'Settings saved', 'ok'); } }, isLive ? 'Save live settings' : 'Save for later'),
          step < steps.length - 1 ? h('button', { class: 'btn primary', type: 'button', onclick: () => { step++; draw(); } }, 'Continue', icon('arrowRight')) :
            canGo ? h('button', { class: 'btn primary', type: 'button', disabled: !(dry && dry.ok), onclick: async () => { await post('/api/v1/definitions/' + def.id + '/versions/' + ver.versionNo + '/productionalize', cfg); toast(def.name + ' v' + ver.versionNo + ' is live', 'ok'); location.hash = '#/definitions/' + def.id + '/runs'; } }, icon('bolt'), prev ? 'Go live and replace v' + prev.versionNo : 'Go live') : null]);
      }
      function drawSide() {
        App.clear(side);
        side.append(h('div', { class: 'card pad' }, h('div', { class: 'eyebrow mb' }, 'This version'), kv('Fields', ver.spec.fields.length), kv('Grain', 'one row per ' + (c.entityById[def.grain] ? c.entityById[def.grain].name.toLowerCase() : def.grain)), kv('Scope', (ver.spec.scope && ver.spec.scope.mode || 'FULL').toLowerCase()), kv('Format', (ver.spec.fileFormat.type === 'FIXED_WIDTH' ? 'fixed width' : 'delimited "' + ver.spec.fileFormat.delimiter + '"') + (ver.spec.fileFormat.trailerRecord ? ', trailer' : '')), kv('Approved', ver.approvedBy ? fmt.date(ver.approvedAt) + ' · ' + ver.approvedBy : '—'), kv('Spec hash', h('span', { class: 'mono' }, ver.specHash))),
          h('div', { class: 'card pad' }, h('div', { class: 'eyebrow mb' }, 'What happens at the last step'), h('ol', { class: 'small', style: { paddingLeft: '18px', margin: 0, color: 'var(--muted)' } }, h('li', {}, 'The dry run must pass.'), h('li', {}, prev ? 'v' + prev.versionNo + ' is retired in the same step; its schedule stops.' : 'The schedule is registered.'), h('li', {}, 'The first run fires at the next scheduled time, or use Run now.'), h('li', {}, 'Every run pins this version, these settings and the catalog it compiled against.'))));
      }
      function kv(k, v) { return h('div', { class: 'kv' }, h('span', {}, k), h('span', {}, v)); }
      function today(p) { const d = new Date(); const y = d.getFullYear(), m = String(d.getMonth() + 1).padStart(2, '0'), dd = String(d.getDate()).padStart(2, '0'); return p.replace('yyyy', y).replace('MM', m).replace('dd', dd); }
      function defaultConfig(def) {
        return { fileName: { pattern: '{VENDOR}_{SUBJECT}_{DATE:yyyyMMdd}.txt', dateSource: 'BUSINESS_DATE' }, compression: 'NONE', pgpBy: 'AXWAY',
          schedule: { preset: 'WEEKDAYS', time: '02:00', timezone: 'America/Chicago', cron: '', calendar: 'plan-2026', holidayPolicy: 'NEXT_BUSINESS_DAY', misfire: 'RUN_ONCE', enabled: true, dayOfWeek: 'MON', dayOfMonth: 1 },
          delivery: { route: 'PROD', zeroRowPolicy: 'SEND_EMPTY', retries: 3, backoffSeconds: 300, controlFile: true }, quality: { enabled: true, rowCountVariancePct: 25, nullRateMaxPct: 40, lookupMissMax: 0, holdOnBreach: true },
          notifications: { onFailure: ['mft-ops@plan.example'], onHold: [], onDelivered: [], onSlaMissed: [] }, sla: { deliverBy: '07:00', note: '' }, initialWatermark: '' };
      }
      draw();
    }
  });
})();
