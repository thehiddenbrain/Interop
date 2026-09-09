/* CMS-1500 Test Console. Plain browser JavaScript; talks to the service's own REST API. */
(() => {
  'use strict';

  const API = '/api/v1';
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

  // ---------------------------------------------------------------- catalog
  // Every field of the claim contract (see cms1500-claim.xsd), grouped by form item.
  const YES_NO = [['', '—'], ['true', 'Yes'], ['false', 'No']];
  const f = (path, label, type = 'text', extra = {}) => Object.assign({ path, label, type }, extra);
  const withBlank = (values) => ['', ...values];
  const NAME = (p, req) => [
    f(p + '.name.lastName', 'Last name', 'text', { maxlength: 28, required: req }),
    f(p + '.name.firstName', 'First name', 'text', { maxlength: 28, required: req }),
    f(p + '.name.middleInitial', 'Middle initial', 'text', { maxlength: 1 })];
  const ADDRESS = (p, req) => [
    f(p + '.address.street', 'Street', 'text', { maxlength: 29, required: req }),
    f(p + '.address.city', 'City', 'text', { maxlength: 24, required: req }),
    f(p + '.address.state', 'State', 'text', { maxlength: 2, required: req }),
    f(p + '.address.zip', 'ZIP', 'text', { maxlength: 10, required: req }),
    f(p + '.address.phoneAreaCode', 'Phone area code', 'text', { maxlength: 3 }),
    f(p + '.address.phoneNumber', 'Phone number (7 digits)', 'text', { maxlength: 7 })];

  const SECTIONS = [
    { title: 'Claim', fields: [
      f('claimNumber', 'Claim number (names the bundle, selects attachments)', 'text', { required: true, maxlength: 64, wide: true }),
      f('insuranceType', '1. Insurance type', 'select', { required: true, options: withBlank(['MEDICARE', 'MEDICAID', 'TRICARE', 'CHAMPVA', 'GROUP_HEALTH_PLAN', 'FECA_BLACK_LUNG', 'OTHER']) }),
      f('insuredId', "1a. Insured's ID number", 'text', { maxlength: 29 })] },
    { title: 'Carrier (top-right block)', fields: [
      f('carrier.name', 'Payer name', 'text', { maxlength: 40 }), f('carrier.street', 'Street', 'text', { maxlength: 40 }),
      f('carrier.street2', 'Street 2', 'text', { maxlength: 40 }), f('carrier.city', 'City', 'text', { maxlength: 24 }),
      f('carrier.state', 'State', 'text', { maxlength: 2 }), f('carrier.zip', 'ZIP', 'text', { maxlength: 10 })] },
    { title: 'Patient (items 2, 3, 5, 6, 12)', fields: [
      ...NAME('patient', true),
      f('patient.dateOfBirth', '3. Date of birth', 'date', { required: true }),
      f('patient.sex', '3. Sex', 'select', { required: true, options: withBlank(['M', 'F']) }),
      ...ADDRESS('patient', true),
      f('patient.relationshipToInsured', '6. Relationship to insured', 'select', { required: true, options: withBlank(['SELF', 'SPOUSE', 'CHILD', 'OTHER']) }),
      f('patient.signatureOnFile', '12. Signature on file', 'bool'),
      f('patient.signatureDate', '12. Signature date', 'date')] },
    { title: 'Insured (items 4, 7, 11, 13) — optional when relationship is SELF', fields: [
      ...NAME('insured', false), ...ADDRESS('insured', false),
      f('insured.policyGroupNumber', '11. Policy / group number', 'text', { maxlength: 29 }),
      f('insured.dateOfBirth', '11a. Date of birth', 'date'),
      f('insured.sex', '11a. Sex', 'select', { options: withBlank(['M', 'F']) }),
      f('insured.otherClaimIdQualifier', '11b. Other claim ID qualifier', 'select', { options: withBlank(['Y4']) }),
      f('insured.otherClaimId', '11b. Other claim ID', 'text', { maxlength: 28 }),
      f('insured.planName', '11c. Plan or program name', 'text', { maxlength: 29 }),
      f('insured.anotherHealthBenefitPlan', '11d. Another health benefit plan?', 'bool'),
      f('insured.signatureOnFile', '13. Signature on file', 'bool')] },
    { title: 'Other insured (items 9, 9a, 9d) — required when 11d is Yes', fields: [
      ...NAME('otherInsured', false),
      f('otherInsured.policyGroupNumber', '9a. Policy / group number', 'text', { maxlength: 28 }),
      f('otherInsured.planName', '9d. Plan or program name', 'text', { maxlength: 28 })] },
    { title: 'Condition (item 10)', fields: [
      f('condition.employmentRelated', '10a. Employment related', 'bool'),
      f('condition.autoAccident', '10b. Auto accident', 'bool'),
      f('condition.autoAccidentState', '10b. Accident state', 'text', { maxlength: 2 }),
      f('condition.otherAccident', '10c. Other accident', 'bool'),
      f('condition.claimCodes', '10d. Claim codes', 'text', { maxlength: 19 })] },
    { title: 'Dates and referring provider (items 14-18)', fields: [
      f('dateOfCurrentIllness', '14. Date of current illness', 'date'),
      f('dateOfCurrentIllnessQualifier', '14. Qualifier', 'select', { options: withBlank(['431', '484']) }),
      f('otherDate', '15. Other date', 'date'),
      f('otherDateQualifier', '15. Qualifier', 'select', { options: withBlank(['454', '304', '453', '439', '455', '471', '090', '091', '444', '050', '054']) }),
      f('unableToWorkFrom', '16. Unable to work from', 'date'), f('unableToWorkTo', '16. Unable to work to', 'date'),
      f('referringProvider.qualifier', '17. Qualifier', 'select', { options: withBlank(['DN', 'DK', 'DQ']) }),
      f('referringProvider.name', '17. Referring provider name', 'text', { maxlength: 26 }),
      f('referringProvider.otherIdQualifier', '17a. Other ID qualifier', 'select', { options: withBlank(['0B', '1G', 'G2', 'LU']) }),
      f('referringProvider.otherId', '17a. Other ID', 'text', { maxlength: 17 }),
      f('referringProvider.npi', '17b. NPI', 'text', { maxlength: 10 }),
      f('hospitalizationFrom', '18. Hospitalization from', 'date'), f('hospitalizationTo', '18. Hospitalization to', 'date')] },
    { title: 'Items 19-23', fields: [
      f('additionalClaimInfo', '19. Additional claim information', 'text', { maxlength: 71, wide: true }),
      f('outsideLab', '20. Outside lab', 'bool'), f('outsideLabCharges', '20. Outside lab charges', 'number'),
      f('icdIndicator', '21. ICD indicator', 'select', { options: withBlank(['ICD10', 'ICD9']) }),
      f('resubmissionCode', '22. Resubmission code', 'text', { maxlength: 11 }),
      f('originalReferenceNumber', '22. Original reference number', 'text', { maxlength: 18 }),
      f('priorAuthorizationNumber', '23. Prior authorization number', 'text', { maxlength: 29 })] },
    { title: 'Diagnoses (item 21, A-L in order)', diagnoses: 12 },
    { title: 'Service lines (item 24) — more than six continue on extra pages', lines: true },
    { title: 'Items 25-29', fields: [
      f('federalTaxId', '25. Federal tax ID (9 digits)', 'text', { maxlength: 9 }),
      f('federalTaxIdType', '25. SSN / EIN', 'select', { options: withBlank(['SSN', 'EIN']) }),
      f('patientAccountNumber', "26. Patient's account number", 'text', { maxlength: 14 }),
      f('acceptAssignment', '27. Accept assignment', 'bool'),
      f('totalCharge', '28. Total charge (computed when blank)', 'number'),
      f('amountPaid', '29. Amount paid', 'number')] },
    { title: 'Physician signature (item 31)', fields: [
      f('physicianSignature.name', 'Name and credentials', 'text', { maxlength: 26 }),
      f('physicianSignature.onFile', 'Signature on file', 'bool'),
      f('physicianSignature.date', 'Date', 'date')] },
    { title: 'Service facility (item 32)', fields: [
      f('serviceFacility.name', 'Name', 'text', { maxlength: 26 }), f('serviceFacility.street', 'Street', 'text', { maxlength: 26 }),
      f('serviceFacility.city', 'City', 'text', { maxlength: 24 }), f('serviceFacility.state', 'State', 'text', { maxlength: 2 }),
      f('serviceFacility.zip', 'ZIP', 'text', { maxlength: 10 }), f('serviceFacility.npi', '32a. NPI', 'text', { maxlength: 10 }),
      f('serviceFacility.otherId', '32b. Other ID', 'text', { maxlength: 14 })] },
    { title: 'Billing provider (item 33)', fields: [
      f('billingProvider.name', 'Name', 'text', { maxlength: 29, required: true }),
      f('billingProvider.street', 'Street', 'text', { maxlength: 29, required: true }),
      f('billingProvider.city', 'City', 'text', { maxlength: 24, required: true }),
      f('billingProvider.state', 'State', 'text', { maxlength: 2, required: true }),
      f('billingProvider.zip', 'ZIP', 'text', { maxlength: 10, required: true }),
      f('billingProvider.phoneAreaCode', 'Phone area code', 'text', { maxlength: 3 }),
      f('billingProvider.phoneNumber', 'Phone number', 'text', { maxlength: 7 }),
      f('billingProvider.npi', '33a. NPI', 'text', { maxlength: 10, required: true }),
      f('billingProvider.otherId', '33b. Other ID', 'text', { maxlength: 17 })] }
  ];

  const LINE_FIELDS = [
    f('dateFrom', '24A From', 'date', { required: true }), f('dateTo', '24A To', 'date'),
    f('placeOfService', '24B Place', 'text', { maxlength: 2, required: true }),
    f('emergency', '24C EMG', 'bool'),
    f('procedureCode', '24D CPT/HCPCS', 'text', { maxlength: 5, required: true }),
    f('modifiers[0]', 'Modifier 1', 'text', { maxlength: 2 }), f('modifiers[1]', 'Modifier 2', 'text', { maxlength: 2 }),
    f('modifiers[2]', 'Modifier 3', 'text', { maxlength: 2 }), f('modifiers[3]', 'Modifier 4', 'text', { maxlength: 2 }),
    f('diagnosisPointers', '24E Pointers (e.g. AB)', 'text', { maxlength: 4, required: true }),
    f('charges', '24F Charges', 'number', { required: true }), f('units', '24G Days or units', 'number', { required: true }),
    f('epsdt', '24H EPSDT', 'select', { options: withBlank(['AV', 'S2', 'ST', 'NU']) }),
    f('familyPlanning', '24H Family planning', 'bool'),
    f('renderingProviderIdQualifier', '24I Qualifier', 'select', { options: withBlank(['0B', '1G', 'G2', 'LU', 'ZZ']) }),
    f('renderingProviderOtherId', '24J Other ID', 'text', { maxlength: 11 }),
    f('renderingProviderNpi', '24J NPI', 'text', { maxlength: 10 }),
    f('supplementalInfo', 'Supplemental info (shaded row)', 'text', { maxlength: 61, wide: true })
  ];

  // ---------------------------------------------------------------- path helpers
  function parsePath(path) {
    const parts = [];
    path.replace(/([^.\[\]]+)|\[(\d+)\]/g, (m, key, idx) => { parts.push(idx !== undefined ? Number(idx) : key); return ''; });
    return parts;
  }
  function setPath(obj, path, value) {
    const parts = parsePath(path);
    let cur = obj;
    for (let i = 0; i < parts.length - 1; i++) {
      const key = parts[i];
      if (cur[key] === undefined || cur[key] === null) cur[key] = typeof parts[i + 1] === 'number' ? [] : {};
      cur = cur[key];
    }
    cur[parts[parts.length - 1]] = value;
  }
  function getPath(obj, path) {
    let cur = obj;
    for (const key of parsePath(path)) {
      if (cur === undefined || cur === null) return undefined;
      cur = cur[key];
    }
    return cur;
  }
  /** Drops blanks, empty objects and array holes so optional items are simply absent from the JSON. */
  function prune(value) {
    if (Array.isArray(value)) {
      const out = value.map(prune).filter((v) => v !== undefined);
      return out.length ? out : undefined;
    }
    if (value && typeof value === 'object') {
      const out = {};
      for (const [k, v] of Object.entries(value)) {
        const p = prune(v);
        if (p !== undefined) out[k] = p;
      }
      return Object.keys(out).length ? out : undefined;
    }
    if (value === '' || value === null || value === undefined) return undefined;
    return value;
  }

  // ---------------------------------------------------------------- claim form
  const form = $('#claim-form');
  let lineCount = 0;

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
  }

  function renderField(def, prefix = '') {
    const path = prefix + def.path;
    const label = el('label', 'field' + (def.wide ? ' wide' : ''));
    label.appendChild(el('span', null, def.label + (def.required ? ' *' : '')));
    let input;
    if (def.type === 'select' || def.type === 'bool') {
      input = document.createElement('select');
      const options = def.type === 'bool' ? YES_NO : def.options.map((o) => [o, o === '' ? '—' : o]);
      for (const [value, text] of options) {
        const option = document.createElement('option');
        option.value = value;
        option.textContent = text;
        input.appendChild(option);
      }
    } else {
      input = document.createElement('input');
      input.type = def.type === 'number' ? 'number' : def.type === 'date' ? 'date' : 'text';
      if (def.type === 'number') { input.step = '0.01'; input.min = '0'; }
      if (def.maxlength) input.maxLength = def.maxlength;
    }
    input.id = 'f-' + path.replace(/[^a-zA-Z0-9]/g, '_');
    input.dataset.path = path;
    input.dataset.type = def.type;
    input.addEventListener('input', () => input.classList.remove('invalid'));
    label.appendChild(input);
    return label;
  }

  function renderForm() {
    form.innerHTML = '';
    for (const section of SECTIONS) {
      const fieldset = el('fieldset');
      fieldset.appendChild(el('legend', null, section.title));
      if (section.diagnoses) {
        const grid = el('div', 'grid');
        for (let i = 0; i < section.diagnoses; i++) {
          grid.appendChild(renderField(f('diagnoses[' + i + ']', String.fromCharCode(65 + i), 'text', { maxlength: 8 })));
        }
        fieldset.appendChild(grid);
      } else if (section.lines) {
        const lines = el('div');
        lines.id = 'lines';
        fieldset.appendChild(lines);
        const add = el('button', null, '+ Add service line');
        add.type = 'button';
        add.id = 'btn-add-line';
        add.addEventListener('click', () => { const claim = readForm(); writeForm(claim, lineCount + 1); });
        fieldset.appendChild(add);
      } else {
        const grid = el('div', 'grid');
        section.fields.forEach((def) => grid.appendChild(renderField(def)));
        fieldset.appendChild(grid);
      }
      form.appendChild(fieldset);
    }
    renderLines(1);
  }

  function renderLines(count) {
    const container = $('#lines');
    container.innerHTML = '';
    for (let i = 0; i < count; i++) {
      const line = el('div', 'line');
      line.dataset.line = String(i);
      const head = el('div', 'line-head');
      head.appendChild(el('span', null, 'Line ' + (i + 1)));
      const remove = el('button', null, 'Remove');
      remove.type = 'button';
      remove.className = 'btn-remove-line';
      remove.disabled = count === 1;
      remove.addEventListener('click', () => {
        const claim = readForm();
        if (Array.isArray(claim.serviceLines)) claim.serviceLines.splice(i, 1);
        writeForm(claim, Math.max(1, lineCount - 1));
      });
      head.appendChild(remove);
      line.appendChild(head);
      const grid = el('div', 'grid');
      LINE_FIELDS.forEach((def) => grid.appendChild(renderField(def, 'serviceLines[' + i + '].')));
      line.appendChild(grid);
      container.appendChild(line);
    }
    lineCount = count;
  }

  /** The form as a (possibly sparse) object; use collect() for the request body. */
  function readForm() {
    const claim = {};
    for (const input of $$('[data-path]', form)) {
      const raw = input.value;
      if (raw === '' || raw === null || raw === undefined) continue;
      let value = raw;
      if (input.dataset.type === 'number') {
        value = Number(raw);
        if (Number.isNaN(value)) value = raw;
      } else if (input.dataset.type === 'bool') {
        value = raw === 'true';
      } else {
        value = raw.trim();
      }
      setPath(claim, input.dataset.path, value);
    }
    if (!Array.isArray(claim.serviceLines)) claim.serviceLines = [];
    while (claim.serviceLines.length < lineCount) claim.serviceLines.push({});
    return claim;
  }

  function collect() {
    return prune(readForm()) || {};
  }

  function writeForm(claim, minLines) {
    const lines = Array.isArray(claim.serviceLines) ? claim.serviceLines.length : 0;
    renderLines(Math.max(minLines || 1, lines));
    for (const input of $$('[data-path]', form)) {
      const value = getPath(claim, input.dataset.path);
      input.value = value === undefined || value === null ? '' : String(value);
      input.classList.remove('invalid');
    }
  }

  function clearForm() {
    writeForm({}, 1);
    $('#json-text').value = '';
    clearMessages();
  }

  // ---------------------------------------------------------------- JSON mode
  const jsonText = $('#json-text');
  const jsonStatus = $('#json-status');
  function isJsonMode() { return $('input[name="mode"]:checked').value === 'json'; }
  function setStatus(node, text, kind) {
    node.textContent = text || '';
    node.className = 'status' + (kind ? ' ' + kind : '');
  }
  function formToJson() {
    jsonText.value = JSON.stringify(collect(), null, 2);
    setStatus(jsonStatus, '');
  }
  function parseJson() {
    const claim = JSON.parse(jsonText.value || '{}');
    if (!claim || typeof claim !== 'object' || Array.isArray(claim)) throw new Error('the JSON must be an object');
    return claim;
  }
  function applyJson() {
    try {
      writeForm(parseJson());
      setStatus(jsonStatus, 'Applied to the form.', 'ok');
      return true;
    } catch (e) {
      setStatus(jsonStatus, 'Invalid JSON: ' + e.message, 'error');
      return false;
    }
  }
  function switchMode(mode) {
    if (mode === 'json') {
      formToJson();
      form.hidden = true;
      $('#json-editor').hidden = false;
    } else {
      if (jsonText.value.trim() && !applyJson()) {
        $('input[name="mode"][value="json"]').checked = true;
        return;
      }
      form.hidden = false;
      $('#json-editor').hidden = true;
    }
  }

  /** The request body for the next action: the form, or the JSON text verbatim in JSON mode. */
  function currentClaim() {
    if (isJsonMode()) {
      try {
        return parseJson();
      } catch (e) {
        renderFault({ code: 'INVALID_JSON', message: 'The JSON text cannot be parsed: ' + e.message }, null);
        return null;
      }
    }
    return collect();
  }

  // ---------------------------------------------------------------- messages
  const messages = $('#messages');
  function clearMessages() {
    messages.innerHTML = '';
    $$('.invalid', form).forEach((n) => n.classList.remove('invalid'));
  }
  function fieldFor(path) {
    return form.querySelector('[data-path="' + path + '"]')
        || form.querySelector('[data-path^="' + path + '."]')
        || form.querySelector('[data-path^="' + path + '["]');
  }
  function markInvalid(path) {
    const input = fieldFor(path);
    if (input) input.classList.add('invalid');
  }
  function focusField(path) {
    const input = fieldFor(path);
    if (!input) return;
    if (isJsonMode()) {
      $('input[name="mode"][value="form"]').checked = true;
      switchMode('form');
    }
    input.scrollIntoView({ block: 'center' });
    input.focus();
  }
  function detailList(details) {
    const ul = el('ul');
    for (const d of details) {
      const li = el('li');
      const a = el('a', null, d.field);
      a.href = '#';
      a.addEventListener('click', (e) => { e.preventDefault(); focusField(d.field); });
      li.appendChild(a);
      li.appendChild(document.createTextNode(': ' + d.message));
      ul.appendChild(li);
      markInvalid(d.field);
    }
    return ul;
  }
  function renderFault(fault, status) {
    clearMessages();
    const box = el('div', 'msg error');
    box.appendChild(el('strong', null, (fault.code || 'ERROR') + (status ? ' (HTTP ' + status + ')' : '')));
    box.appendChild(el('div', null, fault.message || ''));
    if (Array.isArray(fault.details) && fault.details.length) box.appendChild(detailList(fault.details));
    messages.appendChild(box);
  }
  function warningList(warnings) {
    const box = el('div', 'msg warn');
    box.appendChild(el('strong', null, 'Warnings'));
    const ul = el('ul');
    warnings.forEach((w) => ul.appendChild(el('li', null, w)));
    box.appendChild(ul);
    return box;
  }
  function renderValidation(report) {
    clearMessages();
    if (report.valid) {
      messages.appendChild(el('div', 'msg ok', 'Valid: the claim passes every CMS-1500 rule.'));
    } else {
      const box = el('div', 'msg error');
      box.appendChild(el('strong', null, 'Not valid: ' + report.errors.length + ' error(s)'));
      box.appendChild(detailList(report.errors));
      messages.appendChild(box);
    }
    if (report.warnings && report.warnings.length) messages.appendChild(warningList(report.warnings));
  }
  function renderResult(result) {
    clearMessages();
    const box = el('div', 'msg ok');
    box.appendChild(el('strong', null, result.status + ': ' + result.fileName + (result.replacedExisting ? ' (replaced the previous bundle)' : '')));
    const table = el('table');
    const rows = [['Bundle path', result.bundlePath], ['Pages', result.totalPages + ' (' + result.formPages + ' form + attachments)'],
      ['Attachments', String(result.attachmentCount)], ['Generated at', result.generatedAt]];
    for (const [k, v] of rows) {
      const tr = el('tr');
      tr.appendChild(el('th', null, k));
      tr.appendChild(el('td', null, v));
      table.appendChild(tr);
    }
    box.appendChild(table);
    if (result.attachments && result.attachments.length) {
      const at = el('table');
      const head = el('tr');
      ['#', 'File', 'Type', 'Pages'].forEach((h) => head.appendChild(el('th', null, h)));
      at.appendChild(head);
      for (const a of result.attachments) {
        const tr = el('tr');
        [a.index, a.fileName, a.type, a.pages].forEach((v) => tr.appendChild(el('td', null, String(v))));
        at.appendChild(tr);
      }
      box.appendChild(at);
    }
    messages.appendChild(box);
    if (result.warnings && result.warnings.length) messages.appendChild(warningList(result.warnings));
  }

  // ---------------------------------------------------------------- HTTP
  async function callJson(method, url, body) {
    const init = { method, headers: {} };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    const res = await fetch(url, init);
    const text = await res.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch (e) {
      data = { status: 'ERROR', code: 'HTTP_' + res.status, message: text.slice(0, 500) };
    }
    return { ok: res.ok, status: res.status, data };
  }

  const viewers = {};
  async function showPdf(viewerId, url, init, title) {
    const viewer = $('#' + viewerId);
    const titleNode = $('#' + viewerId + '-title');
    const open = $('#' + viewerId + '-open');
    const res = await fetch(url, init);
    if (!res.ok) {
      let fault;
      try { fault = await res.json(); } catch (e) { fault = { code: 'HTTP_' + res.status, message: res.statusText }; }
      return { ok: false, status: res.status, fault };
    }
    const blob = await res.blob();
    if (viewers[viewerId]) URL.revokeObjectURL(viewers[viewerId]);
    viewers[viewerId] = URL.createObjectURL(blob);
    viewer.src = viewers[viewerId];
    titleNode.textContent = title + ' — ' + Math.round(blob.size / 1024) + ' KB';
    open.href = viewers[viewerId];
    open.hidden = false;
    return { ok: true };
  }

  let busyCount = 0;
  async function busy(work) {
    busyCount++;
    $('#busy').hidden = false;
    $$('.actions button').forEach((b) => { b.disabled = true; });
    try {
      return await work();
    } finally {
      busyCount--;
      if (busyCount === 0) {
        $('#busy').hidden = true;
        $$('.actions button').forEach((b) => { b.disabled = false; });
      }
    }
  }

  // ---------------------------------------------------------------- claim actions
  async function loadSample() {
    const { ok, data } = await callJson('GET', '/ui/samples/claim-full.json');
    if (!ok) { renderFault({ code: 'SAMPLE_UNAVAILABLE', message: 'cannot load the sample claim' }, null); return; }
    writeForm(data);
    if (isJsonMode()) formToJson();
    clearMessages();
  }

  async function validate() {
    const claim = currentClaim();
    if (!claim) return;
    await busy(async () => {
      const { ok, status, data } = await callJson('POST', API + '/claims/cms1500/validate', claim);
      if (ok) renderValidation(data); else renderFault(data || {}, status);
    });
  }

  async function preview() {
    const claim = currentClaim();
    if (!claim) return;
    await busy(async () => {
      const result = await showPdf('viewer', API + '/claims/cms1500/preview',
          { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(claim) },
          'Form preview (not saved, no attachments)');
      if (!result.ok) { renderFault(result.fault, result.status); return; }
      const validation = await callJson('POST', API + '/claims/cms1500/validate', claim);
      if (validation.ok) {
        renderValidation(validation.data);
      } else {
        clearMessages();
      }
    });
  }

  async function generate() {
    const claim = currentClaim();
    if (!claim) return;
    await busy(async () => {
      const { ok, status, data } = await callJson('POST', API + '/claims/cms1500', claim);
      if (!ok) { renderFault(data || {}, status); return; }
      renderResult(data);
      await showPdf('viewer', API + '/claims/' + encodeURIComponent(data.claimNumber) + '/bundle?inline=true', {},
          'Bundle ' + data.fileName + ' (' + data.totalPages + ' pages)');
    });
  }

  // ---------------------------------------------------------------- bundles tab
  function formatSize(bytes) {
    return bytes < 0 ? '?' : bytes < 1024 * 1024 ? Math.max(1, Math.round(bytes / 1024)) + ' KB' : (bytes / 1024 / 1024).toFixed(1) + ' MB';
  }
  async function loadBundles() {
    const tbody = $('#bundles-table tbody');
    tbody.innerHTML = '';
    const { ok, status, data } = await callJson('GET', API + '/claims');
    if (!ok) {
      const tr = el('tr');
      tr.appendChild(el('td', 'warn', (data && data.message) || ('HTTP ' + status)));
      tbody.appendChild(tr);
      return;
    }
    if (!data.length) {
      const tr = el('tr');
      const td = el('td', 'muted', 'No bundles in the output folder yet.');
      td.colSpan = 5;
      tr.appendChild(td);
      tbody.appendChild(tr);
    }
    for (const b of data) {
      const tr = el('tr');
      tr.appendChild(el('td', null, b.claimNumber));
      tr.appendChild(el('td', null, b.fileName));
      tr.appendChild(el('td', null, formatSize(b.sizeBytes)));
      tr.appendChild(el('td', null, new Date(b.modifiedAt).toLocaleString()));
      const actions = el('td');
      const view = el('button', 'btn-view', 'View');
      view.type = 'button';
      view.addEventListener('click', async () => {
        const result = await showPdf('bundle-viewer', API + '/claims/' + encodeURIComponent(b.claimNumber) + '/bundle?inline=true', {},
            'Bundle ' + b.fileName);
        if (!result.ok) $('#bundle-viewer-title').textContent = 'Cannot load ' + b.fileName + ': ' + (result.fault.message || result.status);
      });
      const download = el('a', null, 'Download');
      download.href = API + '/claims/' + encodeURIComponent(b.claimNumber) + '/bundle';
      download.className = 'link';
      const attachments = el('button', 'btn-attachments', 'Attachments');
      attachments.type = 'button';
      attachments.addEventListener('click', () => { $('#att-claim').value = b.claimNumber; lookupAttachments(); });
      actions.append(view, attachments, download);
      tr.appendChild(actions);
      tbody.appendChild(tr);
    }
  }
  async function lookupAttachments() {
    const claim = $('#att-claim').value.trim();
    const tbody = $('#attachments-table tbody');
    tbody.innerHTML = '';
    if (!claim) { setStatus($('#att-status'), 'Enter a claim number.', 'error'); return; }
    const { ok, status, data } = await callJson('GET', API + '/claims/' + encodeURIComponent(claim) + '/attachments');
    if (!ok) { setStatus($('#att-status'), (data && data.message) || ('HTTP ' + status), 'error'); return; }
    setStatus($('#att-status'), data.length + ' file(s) match ' + claim + '_<n>.<ext>', data.length ? 'ok' : '');
    for (const a of data) {
      const tr = el('tr');
      tr.appendChild(el('td', null, String(a.index)));
      tr.appendChild(el('td', null, a.fileName));
      tr.appendChild(el('td', null, a.type));
      tr.appendChild(el('td', null, a.supported ? 'yes' : 'no (' + a.extension + ' not allowed)'));
      tr.appendChild(el('td', null, formatSize(a.sizeBytes)));
      tbody.appendChild(tr);
    }
  }

  // ---------------------------------------------------------------- settings tab
  const settingsForm = $('#settings-form');
  function badge(node, text, kind) {
    node.textContent = text;
    node.className = 'badge ' + kind;
  }
  function showSettings(view) {
    $('#s-template').value = view.template;
    $('#s-attachmentsRoot').value = view.attachments.root;
    $('#s-allowedExtensions').value = view.attachments.allowedExtensions.join(', ');
    $('#s-unsupported').value = view.attachments.unsupported;
    $('#s-whenNone').value = view.attachments.whenNone;
    $('#s-outputRoot').value = view.output.root;
    $('#s-overwrite').checked = view.output.overwrite;
    $('#s-uppercase').checked = view.form.uppercase;
    $('#s-stripDiagnosisPeriods').checked = view.form.stripDiagnosisPeriods;
    $('#s-continuationMarker').value = view.form.continuationMarker || '';
    badge($('#s-attachments-status'), view.attachments.rootExists ? 'folder exists' : 'folder missing', view.attachments.rootExists ? 'ok' : 'bad');
    badge($('#s-output-status'), view.output.rootExists ? (view.output.writable ? 'folder exists, writable' : 'folder exists, NOT writable')
        : 'will be created', view.output.rootExists ? (view.output.writable ? 'ok' : 'bad') : 'info');
    $('#s-file').textContent = view.overridesFile;
    badge($('#s-overridden'), view.overridden ? 'overrides active' : 'application.yaml values', view.overridden ? 'info' : 'ok');
    $('#s-note').textContent = view.note || '';
    $('#bundles-folder').textContent = 'Output folder: ' + view.output.root;
    $$('.invalid', settingsForm).forEach((n) => n.classList.remove('invalid'));
    $('#settings-errors').innerHTML = '';
  }
  async function loadSettings() {
    const { ok, status, data } = await callJson('GET', API + '/settings');
    if (ok) {
      showSettings(data);
      setStatus($('#settings-status'), '');
    } else {
      setStatus($('#settings-status'), 'Settings API unavailable (HTTP ' + status + ')', 'error');
    }
  }
  function readSettings() {
    return {
      template: $('#s-template').value,
      attachmentsRoot: $('#s-attachmentsRoot').value,
      allowedExtensions: $('#s-allowedExtensions').value.split(',').map((s) => s.trim()).filter((s) => s.length),
      unsupported: $('#s-unsupported').value,
      whenNone: $('#s-whenNone').value,
      outputRoot: $('#s-outputRoot').value,
      overwrite: $('#s-overwrite').checked,
      uppercase: $('#s-uppercase').checked,
      stripDiagnosisPeriods: $('#s-stripDiagnosisPeriods').checked,
      continuationMarker: $('#s-continuationMarker').value
    };
  }
  async function saveSettings(event) {
    event.preventDefault();
    setStatus($('#settings-status'), 'Saving…');
    const { ok, status, data } = await callJson('PUT', API + '/settings', readSettings());
    if (ok) {
      showSettings(data);
      setStatus($('#settings-status'), 'Saved and applied.', 'ok');
    } else {
      setStatus($('#settings-status'), 'Not saved.', 'error');
      const box = $('#settings-errors');
      box.innerHTML = '';
      const msg = el('div', 'msg error');
      msg.appendChild(el('strong', null, (data && data.code) || ('HTTP ' + status)));
      msg.appendChild(el('div', null, (data && data.message) || ''));
      if (data && Array.isArray(data.details)) {
        const ul = el('ul');
        for (const d of data.details) {
          ul.appendChild(el('li', null, d.field + ': ' + d.message));
          const input = settingsForm.querySelector('[data-setting="' + d.field.replace(/\[\d+\]$/, '') + '"]');
          if (input) input.classList.add('invalid');
        }
        msg.appendChild(ul);
      }
      box.appendChild(msg);
    }
  }
  async function resetSettings() {
    if (!window.confirm('Drop the stored overrides and return to the application.yaml values?')) return;
    const { ok, status, data } = await callJson('DELETE', API + '/settings/overrides');
    if (ok) {
      showSettings(data);
      setStatus($('#settings-status'), 'Reset to application.yaml values.', 'ok');
    } else {
      setStatus($('#settings-status'), (data && data.message) || ('HTTP ' + status), 'error');
    }
  }

  // ---------------------------------------------------------------- tabs, health, wiring
  function showTab(name) {
    $$('.tab-btn').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
    $$('.tab').forEach((t) => t.classList.toggle('active', t.id === 'tab-' + name));
    if (name === 'bundles') {
      const claim = form.querySelector('[data-path="claimNumber"]').value.trim();
      if (claim && !$('#att-claim').value) $('#att-claim').value = claim;
      loadBundles();
      loadSettings();
    }
    if (name === 'settings') loadSettings();
  }
  async function checkHealth() {
    const pill = $('#health');
    try {
      const { ok, data } = await callJson('GET', '/actuator/health');
      pill.textContent = ok && data && data.status === 'UP' ? 'service UP' : 'service ' + ((data && data.status) || 'DOWN');
      pill.className = 'pill ' + (ok ? 'up' : 'down');
    } catch (e) {
      pill.textContent = 'service unreachable';
      pill.className = 'pill down';
    }
  }

  renderForm();
  $$('.tab-btn').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));
  $$('input[name="mode"]').forEach((r) => r.addEventListener('change', () => switchMode(r.value)));
  $('#btn-sample').addEventListener('click', loadSample);
  $('#btn-clear').addEventListener('click', clearForm);
  $('#btn-validate').addEventListener('click', validate);
  $('#btn-preview').addEventListener('click', preview);
  $('#btn-generate').addEventListener('click', generate);
  $('#btn-json-apply').addEventListener('click', applyJson);
  $('#btn-json-format').addEventListener('click', () => { try { jsonText.value = JSON.stringify(parseJson(), null, 2); setStatus(jsonStatus, ''); } catch (e) { setStatus(jsonStatus, 'Invalid JSON: ' + e.message, 'error'); } });
  $('#btn-refresh-bundles').addEventListener('click', loadBundles);
  $('#btn-att-lookup').addEventListener('click', lookupAttachments);
  $('#att-claim').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); lookupAttachments(); } });
  settingsForm.addEventListener('submit', saveSettings);
  $('#btn-settings-reload').addEventListener('click', loadSettings);
  $('#btn-settings-reset').addEventListener('click', resetSettings);
  checkHealth();

  // exposed for the browser tests
  window.cms1500Console = { collect, writeForm, prune, parsePath, readForm };
})();
