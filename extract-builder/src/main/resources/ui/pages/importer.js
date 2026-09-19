(function () {
  const { h, icon, fmt, pill, get, post, crumbs, registerPage, state, toast, catalog, modal } = App;

  const SAMPLE = `Field,Description,Format,Length
Vendor Member ID,Member number assigned by Acme; use the crosswalk,AN,12
Subscriber ID,Policy holder identifier,AN,12
Member Name,Full name as LAST, FIRST,AN,40
DOB,Date of birth CCYYMMDD,N,8
Gender,1=Male 2=Female 0=Unknown,N,1
Relationship,SELF SPOUSE or DEP,AN,6
Effective Date,Coverage start MM/DD/YYYY,AN,10
Term Date,Coverage end MM/DD/YYYY or 12/31/9999,AN,10
Plan Code,Benefit plan,AN,6
Group Number,Employer group,AN,10
Zip,5 digit postal code,N,5
Phone,Home phone digits only,N,10
Record Type,Always ELIG,AN,4`;

  registerPage('importer', {
    async render(root) {
      crumbs([{ label: 'Import a vendor spec' }]);
      const c = await catalog();
      const partners = await get('/api/v1/partners');
      root.appendChild(h('div', { class: 'page-head' }, h('div', {}, h('h1', {}, 'Import a vendor spec'), h('p', { class: 'lead' }, 'Paste the vendor\'s layout table or upload their spreadsheet export. The app proposes a catalog element, a date pattern and a length for each line, with a confidence you review. Ten minutes of checking instead of a day of clicking.'))));
      const vendor = h('select', { class: 'input' }, h('option', { value: '' }, 'Choose a vendor'), partners.filter(p => p.status !== 'DISABLED').map(p => h('option', { value: p.code }, p.name + ' (' + p.code + ')')));
      const area = h('select', { class: 'input' }, h('option', { value: '' }, 'Detect from the fields'), c.subjectAreas.map(s => h('option', { value: s.id }, s.name)));
      const ta = h('textarea', { class: 'input mono', rows: 12, placeholder: 'Field, Description, Format, Length\nMember ID, ...\n\nor one field per line: Member DOB - date of birth CCYYMMDD' });
      const file = h('input', { type: 'file', accept: '.csv,.txt,.tsv', class: 'input', style: { padding: '6px' } });
      file.addEventListener('change', async () => { const f = file.files[0]; if (f) ta.value = await f.text(); });
      const result = h('div');
      const form = h('div', { class: 'card pad stack' },
        h('div', { class: 'grid3' }, h('div', { class: 'field' }, h('label', {}, 'Vendor'), vendor), h('div', { class: 'field' }, h('label', {}, 'Subject area'), area), h('div', { class: 'field' }, h('label', {}, 'Upload instead'), file)),
        h('div', { class: 'field' }, h('label', {}, 'The vendor\'s spec'), ta),
        h('div', { class: 'row' }, h('button', { class: 'btn primary', type: 'button', onclick: propose }, icon('wand'), 'Propose a layout'), h('button', { class: 'btn ghost', type: 'button', onclick: () => { ta.value = SAMPLE; if (!vendor.value) vendor.value = 'ACMEDENTAL'; } }, 'Load an example spec')));
      root.append(form, result);

      async function propose() {
        if (!ta.value.trim()) { toast('Paste the vendor spec first', 'danger'); return; }
        const p = await post('/api/v1/spec-import/propose', { text: ta.value, subjectArea: area.value || null, vendorCode: vendor.value || null });
        App.clear(result);
        const fields = p.fields;
        const name = h('input', { class: 'input', placeholder: 'Definition name', value: vendor.value ? (partners.find(x => x.code === vendor.value) || {}).name + ' ' + (p.subjectArea === 'MEMBER' ? 'eligibility' : p.subjectArea === 'CLAIM' ? 'claims' : 'provider directory') : '' });
        const tbody = h('tbody');
        const draw = () => {
          App.clear(tbody);
          fields.forEach((f, i) => {
            const conf = f.confidence;
            const elSel = h('select', { class: 'input sm', onchange: e => { const v = e.target.value; if (v === '__const') { f.inputs = []; f.rules = [{ type: 'CONSTANT', params: { token: 'LITERAL', value: '' } }]; f.combiner = null; } else if (v === '__skip') { f.skip = true; } else { f.skip = false; f.inputs = [{ element: v, rules: [] }]; f.combiner = null; const el = c.elementById[v]; f.rules = el && el.type === 'DATE' ? [{ type: 'FORMAT_DATE', params: { pattern: (f.rules.find(r => r.type === 'FORMAT_DATE') || { params: { pattern: 'yyyyMMdd' } }).params.pattern } }] : f.rules.filter(r => r.type !== 'FORMAT_DATE' && r.type !== 'LOOKUP'); f.confidence = 100; f.reason = 'Chosen by you'; } draw(); } },
              h('option', { value: '__skip', selected: f.skip }, '— leave out —'), h('option', { value: '__const', selected: !f.inputs.length && f.rules.some(r => r.type === 'CONSTANT') }, 'Constant value'),
              h('optgroup', { label: 'Suggested' }, (f.candidates || []).map(cd => h('option', { value: cd.element, selected: f.inputs.length === 1 && f.inputs[0].element === cd.element }, cd.name + ' (' + cd.entity + ') · ' + Math.round(cd.score * 100) + '%'))),
              h('optgroup', { label: 'All elements' }, c.elements.map(e => h('option', { value: e.id, selected: f.inputs.length === 1 && f.inputs[0].element === e.id && !(f.candidates || []).some(cd => cd.element === e.id) }, e.name + ' (' + e.entity + ')'))));
            const chips = [...(f.inputs.length > 1 && f.combiner ? [h('span', { class: 'chip' }, f.combiner.type + ' ' + (f.combiner.params.template || ''))] : []), ...f.rules.map(r => h('span', { class: 'chip' }, r.type + ' ' + summarize(r)))];
            tbody.appendChild(h('tr', { style: f.skip ? { opacity: .45 } : {} }, h('td', { class: 'mono sub' }, i + 1), h('td', {}, h('div', { style: { fontWeight: 500 } }, f.vendorField), h('div', { class: 'sub clamp', style: { maxWidth: '260px' }, title: f.description }, f.description || ''), f.format ? h('div', { class: 'sub mono' }, f.format + (f.maxLength ? ' · ' + f.maxLength : '')) : null),
              h('td', {}, h('input', { class: 'input sm mono', value: f.header, style: { width: '160px' }, onchange: e => { f.header = e.target.value.toUpperCase(); } })),
              h('td', {}, f.inputs.length > 1 ? h('div', { class: 'row', style: { gap: '4px' } }, f.inputs.map(x => h('span', { class: 'tag' }, App.elementShort(x.element)))) : elSel),
              h('td', {}, h('div', { class: 'row', style: { gap: '4px' } }, chips.length ? chips : h('span', { class: 'sub' }, 'pass through'))),
              h('td', {}, h('div', { class: 'row', style: { gap: '6px' } }, h('div', { class: 'bar', style: { width: '60px' } }, h('i', { style: { width: conf + '%', background: conf >= 80 ? 'var(--ok)' : conf >= 50 ? 'var(--warn)' : 'var(--danger)' } })), h('span', { class: 'small' }, conf + '%')), h('div', { class: 'sub clamp', style: { maxWidth: '220px' }, title: f.reason }, f.reason))));
          });
        };
        draw();
        result.append(h('div', { class: 'card mt' }, h('div', { class: 'card-head' }, h('h3', {}, 'Proposed layout'), pill(p.matched === p.lines ? 'ok' : 'warn', p.summary), h('span', { class: 'spacer' }), h('span', { class: 'sub' }, fmt.title(p.subjectArea) + ' · one row per ' + p.grain)),
          h('div', { class: 'table-wrap' }, h('table', { class: 'table compact' }, h('thead', {}, h('tr', {}, h('th', {}, '#'), h('th', {}, 'Vendor field'), h('th', {}, 'Header'), h('th', {}, 'Catalog element'), h('th', {}, 'Rules'), h('th', {}, 'Confidence'))), tbody)),
          h('div', { class: 'row', style: { padding: '12px 16px', borderTop: '1px solid var(--line-2)' } }, h('div', { class: 'field', style: { width: '320px' } }, h('label', {}, 'Definition name'), name), h('span', { class: 'topbar-spacer' }), h('button', { class: 'btn primary', type: 'button', onclick: async () => {
            if (!vendor.value) { toast('Choose a vendor', 'danger'); return; }
            if (!name.value.trim()) { toast('Name the definition', 'danger'); return; }
            const spec = { fields: fields.filter(f => !f.skip && (f.inputs.length || f.rules.some(r => r.type === 'CONSTANT'))).map((f, i) => ({ id: 'f' + (i + 1), position: i + 1, header: f.header, description: f.description, inputs: f.inputs, combiner: f.combiner || undefined, rules: f.rules, maxLength: f.maxLength || null, onOverflow: 'TRUNCATE' })), joins: [], filters: [], sort: [], scope: { mode: 'FULL', watermarkElements: [], lagMinutes: 15 }, fileFormat: { type: 'DELIMITED', delimiter: '|', quoteMode: 'NONE', headerRow: true, lineEnding: 'CRLF', encoding: 'UTF-8', nullText: '', delimiterInValue: 'STRIP', extension: 'txt' }, sample: { maxRows: 200, cohort: [], synthetic: false } };
            const d = await post('/api/v1/spec-import/accept', { name: name.value, vendorCode: vendor.value, subjectArea: p.subjectArea, grain: p.grain, spec });
            toast('Draft created from the vendor spec', 'ok');
            location.hash = '#/definitions/' + d.id;
          } }, icon('check'), 'Create the draft'))));
      }
    }
  });
  function summarize(r) { const p = r.params || {}; return r.type === 'FORMAT_DATE' ? p.pattern : r.type === 'LOOKUP' ? p.lookup : r.type === 'MAP_VALUES' ? Object.entries(p.map || {}).map(([k, v]) => k + '→' + v).join(' ') : r.type === 'CONSTANT' ? '"' + (p.value || '') + '"' : r.type === 'SUBSTRING' ? 'first ' + p.length : r.type === 'CLEAN_TEXT' ? (p.strip || p.textCase || '').toLowerCase() : ''; }
})();
