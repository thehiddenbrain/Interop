/* Vendor Extract Builder: core shell. Plain browser JS, no build step. */
window.App = (function () {
  const state = { user: localStorage.getItem('veb.user') || 'analyst', me: null, users: [], catalog: null, theme: localStorage.getItem('veb.theme') || '', route: null, unread: 0 };
  const pages = {};

  /* ---------- DOM helpers ---------- */
  function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    if (attrs) for (const [k, v] of Object.entries(attrs)) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'html') el.innerHTML = v;
      else if (k === 'text') el.textContent = v;
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
      else if (k === 'dataset') Object.assign(el.dataset, v);
      else if (v === true) el.setAttribute(k, '');
      else el.setAttribute(k, v);
    }
    append(el, children);
    return el;
  }
  function append(el, children) {
    for (const c of children.flat(Infinity)) {
      if (c === null || c === undefined || c === false) continue;
      el.appendChild(typeof c === 'string' || typeof c === 'number' ? document.createTextNode(String(c)) : c);
    }
    return el;
  }
  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }
  const ICONS = {
    dashboard: '<path d="M4 13h6V4H4zM14 20h6v-9h-6zM4 20h6v-5H4zM14 8h6V4h-6z"/>',
    definitions: '<path d="M4 6h16M4 12h10M4 18h13"/>',
    approvals: '<path d="M20 6 9 17l-5-5"/>',
    runs: '<path d="M5 12h14M13 6l6 6-6 6"/>',
    partners: '<path d="M3 8l9-5 9 5v8l-9 5-9-5z"/><path d="M12 13V3"/>',
    catalog: '<path d="M4 5h16v14H4z"/><path d="M4 10h16M9 5v14"/>',
    compliance: '<path d="M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z"/><path d="m9 12 2 2 4-4"/>',
    audit: '<path d="M6 3h9l5 5v13H6z"/><path d="M14 3v5h5M9 13h6M9 17h6"/>',
    importer: '<path d="M12 3v12M7 10l5 5 5-5"/><path d="M4 19h16"/>',
    feedapi: '<path d="m8 8-4 4 4 4M16 8l4 4-4 4M14 4l-4 16"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
    plus: '<path d="M12 5v14M5 12h14"/>', check: '<path d="M20 6 9 17l-5-5"/>', x: '<path d="M18 6 6 18M6 6l12 12"/>', play: '<path d="M6 4l14 8-14 8z"/>',
    download: '<path d="M12 3v12M7 10l5 5 5-5M4 20h16"/>', send: '<path d="M22 2 11 13M22 2l-7 20-4-9-9-4z"/>', trash: '<path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14"/>',
    eye: '<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>', refresh: '<path d="M21 12a9 9 0 1 1-3-6.7L21 8"/><path d="M21 3v5h-5"/>',
    warn: '<path d="M12 3 2 20h20L12 3z"/><path d="M12 10v4M12 17h.01"/>', info: '<circle cx="12" cy="12" r="9"/><path d="M12 8h.01M11 12h1v4h1"/>',
    grip: '<circle cx="9" cy="6" r="1.5"/><circle cx="15" cy="6" r="1.5"/><circle cx="9" cy="12" r="1.5"/><circle cx="15" cy="12" r="1.5"/><circle cx="9" cy="18" r="1.5"/><circle cx="15" cy="18" r="1.5"/>',
    up: '<path d="M12 19V5M5 12l7-7 7 7"/>', down: '<path d="M12 5v14M5 12l7 7 7-7"/>', sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
    moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>', bolt: '<path d="M13 2 3 14h9l-1 8 10-12h-9z"/>', copy: '<rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/>',
    arrowRight: '<path d="M5 12h14M13 6l6 6-6 6"/>', arrowLeft: '<path d="M19 12H5M11 6l-6 6 6 6"/>', wand: '<path d="m15 4 5 5L8 21l-5-5zM15 4l-2-2M20 9l2 2M4 4l1-1M9 3l-1 1"/>',
    pause: '<path d="M8 5v14M16 5v14"/>', history: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5M12 7v5l3 2"/>', file: '<path d="M6 3h9l5 5v13H6z"/><path d="M14 3v5h5"/>',
    shield: '<path d="M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z"/>', lock: '<rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
    filter: '<path d="M3 5h18l-7 8v6l-4 2v-8z"/>', sort: '<path d="M4 6h16M7 12h10M10 18h4"/>', gear: '<circle cx="12" cy="12" r="3"/><path d="M4 12h2M18 12h2M12 4v2M12 18v2"/>',
  };
  function icon(name, size) {
    const s = size || 16;
    const span = document.createElement('span');
    span.className = 'ico';
    span.innerHTML = `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${ICONS[name] || ''}</svg>`;
    return span;
  }

  /* ---------- formatting ---------- */
  const fmt = {
    date(s) { if (!s) return '—'; const d = new Date(s); return isNaN(d) ? s : d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' }); },
    dt(s) { if (!s) return '—'; const d = new Date(s); return isNaN(d) ? s : d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }); },
    time(s) { if (!s) return '—'; const d = new Date(s); return isNaN(d) ? s : d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }); },
    ago(s) {
      if (!s) return '—';
      const d = new Date(s); if (isNaN(d)) return s;
      const sec = Math.round((Date.now() - d.getTime()) / 1000);
      if (Math.abs(sec) < 60) return 'just now';
      const m = Math.round(sec / 60); if (Math.abs(m) < 60) return m > 0 ? m + ' min ago' : 'in ' + (-m) + ' min';
      const hr = Math.round(m / 60); if (Math.abs(hr) < 36) return hr > 0 ? hr + ' h ago' : 'in ' + (-hr) + ' h';
      const day = Math.round(hr / 24); return day > 0 ? day + ' days ago' : 'in ' + (-day) + ' days';
    },
    num(n) { return n === null || n === undefined ? '—' : Number(n).toLocaleString(); },
    bytes(n) { if (n === null || n === undefined) return '—'; if (n < 1024) return n + ' B'; if (n < 1048576) return (n / 1024).toFixed(1) + ' KB'; return (n / 1048576).toFixed(1) + ' MB'; },
    status(s) { return (s || '').toLowerCase().replace(/_/g, ' '); },
    title(s) { s = (s || '').toLowerCase().replace(/_/g, ' '); return s.charAt(0).toUpperCase() + s.slice(1); },
    plural(n, one, many) { return n === 1 ? '1 ' + one : n + ' ' + (many || one + 's'); },
  };
  function pill(status, text) {
    const s = (status || '').toLowerCase();
    return h('span', { class: 'pill ' + s }, h('span', { class: 'dot' }), text || fmt.title(status));
  }

  /* ---------- API ---------- */
  async function api(method, path, body, opts) {
    const headers = { 'X-Demo-User': state.user };
    let payload = body;
    if (body !== undefined && !(body instanceof FormData)) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
    const res = await fetch(path, { method, headers, body: payload });
    if (res.status === 204) return null;
    const ct = res.headers.get('content-type') || '';
    const data = ct.includes('application/json') ? await res.json() : await res.text();
    if (!res.ok) {
      const err = new Error((data && data.error) || res.statusText || 'Request failed');
      err.details = (data && data.details) || [];
      err.status = res.status;
      if (!(opts && opts.silent)) toast(err.message, 'danger', err.details);
      throw err;
    }
    return data;
  }
  const get = (p, o) => api('GET', p, undefined, o);
  const post = (p, b, o) => api('POST', p, b === undefined ? {} : b, o);
  const put = (p, b, o) => api('PUT', p, b, o);
  const patch = (p, b, o) => api('PATCH', p, b, o);
  const del = (p, o) => api('DELETE', p, undefined, o);

  async function catalog(force) {
    if (!state.catalog || force) {
      state.catalog = await get('/api/v1/catalog');
      state.catalog.elementById = Object.fromEntries(state.catalog.elements.map(e => [e.id, e]));
      state.catalog.entityById = Object.fromEntries(state.catalog.entities.map(e => [e.id, e]));
      state.catalog.ruleByType = Object.fromEntries(state.catalog.rules.map(r => [r.type, r]));
      state.catalog.lookupById = Object.fromEntries(state.catalog.lookups.map(l => [l.id, l]));
      state.catalog.templateById = Object.fromEntries(state.catalog.filterTemplates.map(t => [t.id, t]));
    }
    return state.catalog;
  }
  function elementName(id) { const c = state.catalog; const e = c && c.elementById[id]; return e ? e.name : id; }
  function elementShort(id) { return (id || '').replace(/^[^.]+\./, ''); }

  /* ---------- toasts, modals, drawer ---------- */
  function toast(message, kind, details) {
    const root = document.getElementById('toasts');
    const el = h('div', { class: 'toast ' + (kind || '') }, icon(kind === 'danger' ? 'warn' : kind === 'ok' ? 'check' : 'info'),
      h('div', {}, h('div', {}, message), details && details.length ? h('ul', {}, details.slice(0, 6).map(d => h('li', {}, d))) : null));
    root.appendChild(el);
    setTimeout(() => el.remove(), kind === 'danger' ? 9000 : 4500);
  }
  function modal(opts) {
    const root = document.getElementById('modal');
    clear(root);
    root.hidden = false;
    const close = () => { root.hidden = true; clear(root); document.removeEventListener('keydown', onKey); };
    const onKey = e => { if (e.key === 'Escape') close(); };
    document.addEventListener('keydown', onKey);
    const foot = h('div', { class: 'modal-foot' });
    for (const a of opts.actions || []) {
      const b = h('button', { class: 'btn ' + (a.kind || ''), type: 'button', onclick: async () => {
        if (a.onClick) {
          b.disabled = true;
          try { const r = await a.onClick(close); if (r !== false) close(); } catch (e) { /* toast already shown */ } finally { b.disabled = false; }
        } else close();
      } }, a.icon ? icon(a.icon) : null, a.label);
      foot.appendChild(b);
    }
    const m = h('div', { class: 'modal ' + (opts.wide ? 'wide' : ''), role: 'dialog', 'aria-modal': 'true' },
      h('div', { class: 'modal-head' }, h('h2', {}, opts.title), h('div', { class: 'topbar-spacer' }), h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Close', onclick: close }, icon('x'))),
      h('div', { class: 'modal-body' }, opts.body), foot);
    root.appendChild(m);
    root.onclick = e => { if (e.target === root) close(); };
    const first = m.querySelector('input,select,textarea,button.primary');
    if (first) setTimeout(() => first.focus(), 30);
    return close;
  }
  function confirm(title, message, label, kind) {
    return new Promise(resolve => {
      modal({ title, body: h('p', {}, message), actions: [
        { label: 'Cancel', onClick: () => { resolve(false); } },
        { label: label || 'Confirm', kind: kind || 'primary', onClick: () => { resolve(true); } },
      ] });
    });
  }
  function promptText(title, label, placeholder, opts) {
    return new Promise(resolve => {
      const ta = opts && opts.multiline ? h('textarea', { class: 'input', rows: 4, placeholder }) : h('input', { class: 'input', placeholder });
      modal({ title, body: h('div', { class: 'field' }, h('label', {}, label), ta, opts && opts.help ? h('div', { class: 'help' }, opts.help) : null), actions: [
        { label: 'Cancel', onClick: () => resolve(null) },
        { label: (opts && opts.okLabel) || 'Save', kind: (opts && opts.okKind) || 'primary', onClick: () => { if ((opts && opts.required) && !ta.value.trim()) { toast('Please enter a value', 'danger'); return false; } resolve(ta.value); } },
      ] });
    });
  }
  function drawer(title, body) {
    const root = document.getElementById('drawer');
    clear(root);
    root.hidden = false;
    const close = () => { root.hidden = true; clear(root); };
    root.appendChild(h('div', { class: 'drawer-head' }, h('h2', {}, title), h('div', { class: 'topbar-spacer' }), h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Close', onclick: close }, icon('x'))));
    root.appendChild(h('div', { class: 'drawer-body' }, body));
    return close;
  }
  function dropdown(button, items) {
    const wrap = h('div', { class: 'dropdown' }, button);
    let menu = null;
    const closeMenu = () => { if (menu) { menu.remove(); menu = null; document.removeEventListener('click', outside); } };
    const outside = e => { if (!wrap.contains(e.target)) closeMenu(); };
    button.addEventListener('click', e => {
      e.stopPropagation();
      if (menu) return closeMenu();
      menu = h('div', { class: 'menu' }, items.map(it => it === 'hr' ? h('hr') : h('button', { type: 'button', class: it.kind || '', onclick: () => { closeMenu(); it.onClick(); } }, it.icon ? icon(it.icon) : null, it.label)));
      wrap.appendChild(menu);
      setTimeout(() => document.addEventListener('click', outside), 0);
    });
    return wrap;
  }

  /* ---------- shell ---------- */
  const NAV = [
    { section: 'Work' },
    { id: 'dashboard', label: 'Dashboard', href: '#/', icon: 'dashboard' },
    { id: 'definitions', label: 'Definitions', href: '#/definitions', icon: 'definitions' },
    { id: 'approvals', label: 'Approvals', href: '#/approvals', icon: 'approvals', count: 'approvals' },
    { id: 'runs', label: 'Runs', href: '#/runs', icon: 'runs' },
    { id: 'importer', label: 'Import a vendor spec', href: '#/import', icon: 'importer' },
    { section: 'Govern' },
    { id: 'compliance', label: 'Compliance', href: '#/compliance', icon: 'compliance' },
    { id: 'audit', label: 'Audit log', href: '#/audit', icon: 'audit' },
    { section: 'Configure' },
    { id: 'partners', label: 'MFT partners', href: '#/partners', icon: 'partners' },
    { id: 'catalog', label: 'Catalog', href: '#/catalog', icon: 'catalog' },
    { id: 'feedapi', label: 'Feed API', href: '#/feed-api', icon: 'feedapi' },
    { id: 'settings', label: 'Settings', href: '#/settings', icon: 'settings' },
  ];
  const ROUTES = [
    ['#/', 'dashboard'], ['#/definitions', 'definitions'], ['#/definitions/new', 'definitions'],
    ['#/definitions/:id', 'builder'], ['#/definitions/:id/v/:no', 'builder'], ['#/definitions/:id/v/:no/productionalize', 'productionalize'],
    ['#/definitions/:id/runs', 'runs'], ['#/runs', 'runs'], ['#/runs/:id', 'run'], ['#/approvals', 'approvals'], ['#/import', 'importer'],
    ['#/compliance', 'compliance'], ['#/compliance/:code', 'compliance'], ['#/audit', 'audit'], ['#/partners', 'partners'], ['#/partners/:code', 'partners'],
    ['#/catalog', 'catalog'], ['#/catalog/:element', 'catalog'], ['#/feed-api', 'feedapi'], ['#/settings', 'settings'],
  ];
  function match(hash) {
    const path = (hash || '#/').split('?')[0];
    const query = Object.fromEntries(new URLSearchParams((hash.split('?')[1] || '')));
    for (const [pattern, page] of ROUTES) {
      const pp = pattern.split('/'), hp = path.split('/');
      if (pp.length !== hp.length) continue;
      const params = {};
      let ok = true;
      for (let i = 0; i < pp.length; i++) {
        if (pp[i].startsWith(':')) params[pp[i].slice(1)] = decodeURIComponent(hp[i]);
        else if (pp[i] !== hp[i]) { ok = false; break; }
      }
      if (ok) return { page, params, query, path };
    }
    return { page: 'dashboard', params: {}, query: {}, path: '#/' };
  }
  function renderNav() {
    const nav = clear(document.getElementById('nav'));
    const cur = state.route ? state.route.page : 'dashboard';
    for (const item of NAV) {
      if (item.section) { nav.appendChild(h('div', { class: 'nav-section' }, item.section)); continue; }
      const a = h('a', { href: item.href, class: cur === item.id ? 'on' : '', onclick: () => { document.getElementById('sidebar').classList.remove('open'); } }, icon(item.icon, 18), item.label);
      if (item.count === 'approvals' && state.pendingApprovals) a.appendChild(h('span', { class: 'cnt' }, state.pendingApprovals));
      nav.appendChild(a);
    }
  }
  function crumbs(list) {
    const c = clear(document.getElementById('crumbs'));
    list.forEach((it, i) => {
      if (i > 0) c.appendChild(h('span', { class: 'sep' }, '/'));
      c.appendChild(i === list.length - 1 ? h('span', { class: 'cur' }, it.label) : h('a', { href: it.href || '#/' }, it.label));
    });
    document.title = (list.length ? list[list.length - 1].label + ' · ' : '') + 'Vendor Extract Builder';
  }
  function renderUser() {
    const root = clear(document.getElementById('userSwitch'));
    const me = state.me;
    if (!me) return;
    const initials = me.name.split(' ').map(s => s[0]).join('').slice(0, 2);
    const btn = h('button', { class: 'user-btn', type: 'button', 'aria-label': 'Switch demo user' }, h('span', { class: 'avatar' }, initials), h('span', { class: 'user-meta' }, h('b', {}, me.name), h('span', {}, me.role.charAt(0) + me.role.slice(1).toLowerCase() + (me.phiUnmasked ? ' · PHI' : ''))));
    root.appendChild(btn);
    let open = null;
    btn.addEventListener('click', e => {
      e.stopPropagation();
      if (open) { open.remove(); open = null; return; }
      open = h('div', { class: 'user-menu' }, h('div', { class: 'hint' }, 'Demo sign-in. Pick who you are; permissions and the PHI privilege follow the role.'),
        state.users.map(u => h('button', { type: 'button', class: u.id === state.user ? 'on' : '', onclick: () => { setUser(u.id); open.remove(); open = null; } },
          h('span', { class: 'avatar' }, u.name.split(' ').map(s => s[0]).join('').slice(0, 2)), h('span', { class: 'user-meta' }, h('b', {}, u.name), h('span', {}, u.title + (u.phiUnmasked ? ' · can see PHI' : ''))))));
      root.appendChild(open);
      const outside = ev => { if (!root.contains(ev.target) && open) { open.remove(); open = null; document.removeEventListener('click', outside); } };
      setTimeout(() => document.addEventListener('click', outside), 0);
    });
  }
  async function setUser(id) {
    state.user = id;
    localStorage.setItem('veb.user', id);
    state.me = await get('/api/v1/me');
    renderUser();
    toast('Now signed in as ' + state.me.name + ' (' + state.me.role.toLowerCase() + ')', 'ok');
    render();
  }
  function applyTheme() {
    const root = document.documentElement;
    if (state.theme) root.setAttribute('data-theme', state.theme); else root.removeAttribute('data-theme');
    const dark = state.theme === 'dark' || (!state.theme && window.matchMedia('(prefers-color-scheme: dark)').matches);
    const ti = document.getElementById('themeIcon');
    clear(ti).appendChild(icon(dark ? 'sun' : 'moon', 15));
    document.getElementById('themeToggle').lastChild.textContent = dark ? 'Light theme' : 'Dark theme';
  }
  async function refreshBell() {
    try {
      const n = await get('/api/v1/notifications?limit=30', { silent: true });
      state.unread = n.unread;
      document.getElementById('bellDot').hidden = !n.unread;
    } catch (e) { /* ignore */ }
  }
  async function openNotifications() {
    const n = await get('/api/v1/notifications?limit=40');
    const body = h('div', {}, n.items.length ? n.items.map(it => h('div', { class: 'note ' + (it.read ? '' : 'unread') }, h('span', { class: 'sev ' + it.severity }),
      h('div', {}, h('div', { class: 't' }, it.title), h('div', { class: 'm' }, it.message), h('div', { class: 'w' }, fmt.ago(it.at) + (it.recipients && it.recipients.length ? ' · to ' + it.recipients.join(', ') : '') + (it.runId ? ' · ' : '')), it.runId ? h('a', { href: '#/runs/' + it.runId, class: 'small' }, 'Open run') : it.definitionId && !it.definitionId.startsWith('partner:') ? h('a', { href: '#/definitions/' + it.definitionId, class: 'small' }, 'Open definition') : null))) : h('div', { class: 'empty' }, 'Nothing yet.'));
    const close = drawer('Notifications', body);
    if (n.unread) { await post('/api/v1/notifications/read-all'); state.unread = 0; document.getElementById('bellDot').hidden = true; }
    return close;
  }

  async function render() {
    const route = match(location.hash);
    state.route = route;
    renderNav();
    const content = document.getElementById('content');
    clear(content);
    document.getElementById('drawer').hidden = true;
    const page = pages[route.page];
    if (!page) { content.appendChild(h('div', { class: 'empty' }, 'Page not found')); return; }
    try {
      await page.render(content, route.params, route.query);
    } catch (e) {
      console.error(e);
      content.appendChild(h('div', { class: 'alert danger' }, icon('warn'), h('div', {}, h('b', {}, 'This page could not load.'), h('div', {}, e.message))));
    }
    content.scrollTop = 0;
  }

  async function start() {
    applyTheme();
    document.getElementById('themeToggle').addEventListener('click', () => {
      const dark = state.theme === 'dark' || (!state.theme && window.matchMedia('(prefers-color-scheme: dark)').matches);
      state.theme = dark ? 'light' : 'dark';
      localStorage.setItem('veb.theme', state.theme);
      applyTheme();
    });
    document.getElementById('menuBtn').addEventListener('click', () => document.getElementById('sidebar').classList.toggle('open'));
    document.getElementById('bellBtn').addEventListener('click', openNotifications);
    try {
      [state.me, state.users] = await Promise.all([get('/api/v1/me'), get('/api/v1/users')]);
    } catch (e) {
      document.getElementById('content').appendChild(h('div', { class: 'alert danger' }, icon('warn'), 'The service is not reachable. Start it with run.bat or ./gradlew bootRun and reload.'));
      return;
    }
    renderUser();
    window.addEventListener('hashchange', render);
    await render();
    refreshBell();
    setInterval(refreshBell, 20000);
  }

  function registerPage(name, page) { pages[name] = page; }
  function elementPicker(onPick, opts) {
    /* A searchable list of catalog elements, grouped by entity, restricted to entities reachable from a grain when given. */
    const c = state.catalog;
    const allowed = opts && opts.entities ? new Set(opts.entities) : null;
    const box = h('div', { class: 'stack' });
    const search = h('input', { class: 'input sm', placeholder: 'Search elements', type: 'search' });
    const list = h('div', { style: { maxHeight: '360px', overflowY: 'auto' } });
    const draw = () => {
      clear(list);
      const q = search.value.trim().toLowerCase();
      for (const en of c.entities) {
        if (allowed && !allowed.has(en.id)) continue;
        const els = c.elements.filter(e => e.entity === en.id && (!q || e.name.toLowerCase().includes(q) || e.id.includes(q) || (e.aliases || []).some(a => a.includes(q))));
        if (!els.length) continue;
        list.appendChild(h('div', { class: 'eyebrow', style: { padding: '8px 8px 4px' } }, en.name));
        for (const e of els) {
          list.appendChild(h('div', { class: 'el', role: 'button', tabindex: 0, onclick: () => onPick(e), onkeydown: ev => { if (ev.key === 'Enter') onPick(e); } },
            h('div', { class: 'grow' }, h('div', { class: 'n' }, e.name), h('div', { class: 'c' }, e.id + ' · ' + e.type.toLowerCase())),
            e.phi ? h('span', { class: 'pill ' + (e.restricted ? 'restricted' : 'phi') }, e.restricted ? 'RESTRICTED' : 'PHI') : null,
            e.watermark ? h('span', { class: 'tag' }, 'watermark') : null));
        }
      }
      if (!list.children.length) list.appendChild(h('div', { class: 'empty' }, 'No elements match.'));
    };
    search.addEventListener('input', draw);
    draw();
    box.append(search, list);
    setTimeout(() => search.focus(), 30);
    return box;
  }

  return { state, h, append, clear, icon, fmt, pill, api, get, post, put, patch, del, catalog, elementName, elementShort, toast, modal, confirm, promptText, drawer, dropdown, crumbs, render, start, registerPage, elementPicker, setUser };
})();
