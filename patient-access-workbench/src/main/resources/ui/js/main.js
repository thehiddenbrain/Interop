/* Boot: wire tabs, environment picker, health, modules; restore state from the URL hash. */
(() => {
  'use strict';
  const { $, $$, api, state, modules, showTab, readHash, refreshEnvironments, setEnvironment, on } = window.PAW;

  async function health() {
    const pill = $('#health');
    try {
      const res = await fetch('/actuator/health');
      const body = await res.json();
      pill.textContent = body.status === 'UP' ? 'service up' : 'service ' + body.status;
      pill.className = 'pill ' + (body.status === 'UP' ? 'up' : 'down');
    } catch (e) {
      pill.textContent = 'service unreachable';
      pill.className = 'pill down';
    }
  }

  document.addEventListener('DOMContentLoaded', async () => {
    $$('.tab-btn').forEach(b => b.addEventListener('click', () => showTab(b.dataset.tab)));
    $('#env-select').addEventListener('change', (e) => setEnvironment(e.target.value || null));
    for (const m of Object.values(modules)) {
      if (m.init) { try { await m.init(); } catch (e) { console.error('module init failed', e); } }
    }
    health();
    setInterval(health, 30000);
    const hash = readHash();
    await refreshEnvironments(hash.env || null);
    if (hash.patient) window.PAW.setPatient(hash.patient, null);
    showTab(hash.tab || (state.environments.length ? 'search' : 'environments'));
    on('env-changed', () => {}); // keeps the hash in sync through setEnvironment
  });
})();
