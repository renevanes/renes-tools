/* ---------- Startscherm: groepen, volgorde, verbergen ---------- */
const HOME_DEFAULT = { grouped: true, compact: false, hidden: [], groups: [
  { name: 'Telefoon', tools: ['skin'] },
  { name: 'Bellen & contacten', tools: ['redial', 'calls', 'contacts', 'recorder', 'transcripts'] },
  { name: 'Berichten & backup', tools: ['backup', 'wa', 'sms', 'history'] },
  { name: 'Muziek & radio', tools: ['radio', 'podcasts', 'spotify', 'music'] },
  { name: 'Handig', tools: ['ai', 'notes', 'pdf', 'tracks', 'car', 'kluis', 'vpn', 'loc', 'auto', 'sleep'] } ] };
const TOOL_TILE = {}; Object.keys(TILE_TOOL).forEach(id => TOOL_TILE[TILE_TOOL[id]] = id);
function homeCfg(){
  let c = store.get('home', null);
  if (!c || !Array.isArray(c.groups)) c = JSON.parse(JSON.stringify(HOME_DEFAULT));
  c.hidden = Array.isArray(c.hidden) ? c.hidden : [];
  c.fav = Array.isArray(c.fav) ? c.fav.filter((t, i, a) => TOOL_TILE[t] && a.indexOf(t) === i) : []; // favorieten: bovenaan
  // Onbekende tools weghalen, nieuwe tools (uit een update) toevoegen aan de laatste groep
  const known = Object.keys(TOOL_TILE), seen = new Set();
  c.groups.forEach(g => { g.tools = (g.tools || []).filter(t => known.includes(t) && !seen.has(t) && seen.add(t)); });
  if (!c.groups.length) c.groups.push({ name: 'Tools', tools: [] });
  // Telefoon-skin (nieuw): bovenaan, in een eigen groep
  if (!seen.has('skin')) { c.groups.unshift({ name: 'Telefoon', tools: ['skin'] }); seen.add('skin'); }
  known.forEach(t => { if (!seen.has(t)) { const d = HOME_DEFAULT.groups.find(g => g.tools.includes(t)); const g = d && c.groups.find(x => x.name === d.name) || c.groups[c.groups.length - 1]; g.tools.push(t); } });
  return c;
}
function homeSave(c){ store.set('home', c); renderHome(); renderDash(); }
function renderHome(){
  const c = homeCfg(), box = $('#home-groups'), pool = $('#tile-pool');
  // Tegels eerst terug in de verborgen pool, dan opnieuw verdelen (de tegels zelf blijven dezelfde knoppen)
  Object.values(TOOL_TILE).forEach(id => { const t = document.getElementById(id); if (t) pool.appendChild(t); });
  box.innerHTML = '';
  box.classList.toggle('compact', !!c.compact);
  const mk = (name, tools) => {
    const vis = tools.filter(t => !c.hidden.includes(t)); if (!vis.length) return;
    const g = document.createElement('div'); g.className = 'hgroup';
    if (name) { const h = document.createElement('h3'); h.textContent = name; g.appendChild(h); }
    const grid = document.createElement('div'); grid.className = 'tiles'+(vis.length===1?' single':'');
    vis.forEach(t => { const el = document.getElementById(TOOL_TILE[t]); if (el) grid.appendChild(el); });
    g.appendChild(grid); box.appendChild(g);
  };
  // Favorieten eerst, in de volgorde die je koos; ze staan dan niet nog eens in hun eigen groep
  const fav = c.fav.filter(t => !c.hidden.includes(t)), notFav = t => !fav.includes(t);
  if (c.grouped) { if (fav.length) mk('★ Favorieten', fav); c.groups.forEach(g => mk(g.name, g.tools.filter(notFav))); }
  else mk('', fav.concat([].concat(...c.groups.map(g => g.tools)).filter(notFav)));
  pool.style.display = 'none';
}
/* ---------- Startscherm aanpassen ----------
 * Groepen in een eigen volgorde (pijlen bij de groep), tools slepen (ook naar een andere groep), of via ⋯:
 * naar een groep, omhoog/omlaag, verbergen. "Tool toevoegen" onder een groep haalt een tool uit een andere groep. */
function heName(t){ return (TOOLS[t] || [t])[0]; }
function heGroupOf(c, t){ return c.groups.findIndex(g => g.tools.includes(t)); }
function heRow(c, t, fav){
  const nm = heName(t), off = c.hidden.includes(t), on = c.fav.includes(t);
  return '<div class="herow' + (off ? ' off' : '') + '" role="listitem" data-t="' + t + '">' +
    '<span class="hedrag" aria-hidden="true" title="Slepen">⠿</span>' +
    '<span class=nm>' + esc(nm) + (off ? '<small>Verborgen</small>' : '') + '</span>' +
    '<button class="mv star' + (on ? ' on' : '') + '" data-t="' + t + '" onclick="toggleFav(this.dataset.t)" aria-pressed="' + on + '" aria-label="' + esc(nm) + (fav ? ' uit favorieten' : ' als favoriet') + '">' + (on ? '★' : '☆') + '</button>' +
    '<button class="mv more" data-t="' + t + '" onclick="heToolMenu(this.dataset.t, ' + (fav ? 'true' : 'false') + ')" aria-label="' + esc(nm) + (fav ? ': omhoog, omlaag, uit favorieten' : ': verplaatsen, verbergen …') + '">⋯</button></div>';
}
function enterHomeEdit(){
  const c = homeCfg();
  ['grouped', 'compact', 'dash'].forEach(k => { const on = k === 'dash' ? c.dash !== false : !!c[k], b = $('#he-' + k); b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false'); });
  $('#he-fav').innerHTML = '<h2>★ Favorieten</h2>' + (c.fav.length ? '<div class="helist" data-fav="1" role="list" aria-label="Favorieten">' + c.fav.map(t => heRow(c, t, true)).join('') + '</div>'
    : '<p class=note style="margin:0">Tik op ☆ bij een tool (hieronder, of houd de tegel ingedrukt) om hem bovenaan te zetten.</p>');
  const n = c.groups.length;
  $('#he-groups').innerHTML = (c.grouped ? '' : '<p class="note">Zonder groepen staan de tools in deze volgorde onder elkaar (zet In groepen aan voor de kopjes).</p>') +
    c.groups.map((g, gi) => '<div class="card hegroup" data-g="' + gi + '">' +
      '<div class=heghead><h2 class="hegname-h"><button class="hegname" data-g="' + gi + '" onclick="heRename(+this.dataset.g)" aria-label="' + esc(g.name + ', ' + g.tools.length + (g.tools.length === 1 ? ' tool' : ' tools') + ', hernoemen') + '">' + esc(g.name) + '<small>' + g.tools.length + (g.tools.length === 1 ? ' tool' : ' tools') + ' · ✎</small></button></h2>' +
      '<button class="mv" data-g="' + gi + '" onclick="heGroupMove(+this.dataset.g,-1)"' + (gi === 0 ? ' disabled' : '') + ' aria-label="Groep ' + esc(g.name) + ' omhoog">↑</button>' +
      '<button class="mv" data-g="' + gi + '" onclick="heGroupMove(+this.dataset.g,1)"' + (gi === n - 1 ? ' disabled' : '') + ' aria-label="Groep ' + esc(g.name) + ' omlaag">↓</button>' +
      '<button class="mv more" data-g="' + gi + '" onclick="heGroupMenu(+this.dataset.g)" aria-label="Groep ' + esc(g.name) + ': meer">⋯</button></div>' +
      '<div class="helist" data-g="' + gi + '" role="list" aria-label="' + esc(g.name) + '">' + g.tools.map(t => heRow(c, t, false)).join('') +
      (g.tools.length ? '' : '<div class="hedrop" role="listitem">Leeg: sleep hier een tool naartoe, of tik op Tool toevoegen.</div>') + '</div>' +
      '<button class="linkish headd" data-g="' + gi + '" onclick="heAddTo(+this.dataset.g)">+ Tool toevoegen</button></div>').join('');
}
/** Bewaren en opnieuw tonen; focus = waar de focus daarna heen moet (de knop van de tool of groep), voor een schermlezer. */
function heSaveEdit(c, msg, focus){
  homeSave(c); setDynamicShortcuts();
  if (current === 'homeedit') { enterHomeEdit(); if (focus) { const el = document.querySelector('#s-homeedit ' + focus); if (el) el.focus(); } }
  if (msg) toast(msg);
}
/* Favorieten: bovenaan het startscherm (en de eerste keuzes bij lang indrukken van het app-icoon) */
function isFav(t){ return homeCfg().fav.includes(t); }
function toggleFav(t){
  const c = homeCfg(), on = !c.fav.includes(t);
  c.fav = on ? c.fav.concat([t]) : c.fav.filter(x => x !== t);
  if (on) c.hidden = c.hidden.filter(x => x !== t); // een favoriet verbergen heeft geen zin
  heSaveEdit(c, on ? '★ ' + heName(t) + ' staat nu bovenaan' : heName(t) + ' uit favorieten', '#he-groups [data-t="' + t + '"] button.star');
}
function heFavMove(t, dir){
  const c = homeCfg(), i = c.fav.indexOf(t), j = i + dir;
  if (i < 0 || j < 0 || j >= c.fav.length) return;
  c.fav.splice(i, 1); c.fav.splice(j, 0, t);
  heSaveEdit(c, '', '#he-fav [data-t="' + t + '"] button.more');
}
function heSet(k, v){ const c = homeCfg(); c[k] = v; homeSave(c); if (current === 'homeedit') enterHomeEdit(); }
function heToggle(t, on){ const c = homeCfg(); c.hidden = c.hidden.filter(x => x !== t); if (!on) { c.hidden.push(t); c.fav = c.fav.filter(x => x !== t); } heSaveEdit(c, '', '#he-groups [data-t="' + t + '"] button.more'); } // verborgen = ook geen favoriet meer
/** Eén plek omhoog/omlaag; aan de rand van een groep schuift de tool naar de groep ernaast. Zuiver op c. */
function heMoveIn(c, t, dir){
  const gi = heGroupOf(c, t); if (gi < 0) return false;
  const g = c.groups[gi], i = g.tools.indexOf(t), j = i + dir;
  if (j >= 0 && j < g.tools.length) { g.tools.splice(i, 1); g.tools.splice(j, 0, t); return true; }
  const ng = c.groups[gi + dir]; if (!ng) return false;
  g.tools.splice(i, 1);
  if (dir < 0) ng.tools.push(t); else ng.tools.unshift(t);
  return true;
}
function heMove(t, dir){ const c = homeCfg(); if (heMoveIn(c, t, dir)) heSaveEdit(c, '', '#he-groups [data-t="' + t + '"] button.more'); }
/** Tool naar groep gi (achteraan, of op plek at). Zuiver op c. */
function heMoveToIn(c, t, gi, at){
  const from = heGroupOf(c, t), g = c.groups[gi]; if (!g) return false;
  if (from >= 0) c.groups[from].tools.splice(c.groups[from].tools.indexOf(t), 1);
  g.tools.splice(at == null ? g.tools.length : Math.max(0, Math.min(at, g.tools.length)), 0, t);
  return true;
}
function heMoveTo(t, gi){ const c = homeCfg(); if (heMoveToIn(c, t, gi)) heSaveEdit(c, heName(t) + ' staat nu in ' + c.groups[gi].name, '#he-groups [data-t="' + t + '"] button.more'); }
/** Menu bij een tool (ook vanaf een tegel op het startscherm). */
function heToolMenu(t, fav){
  const c = homeCfg(), gi = heGroupOf(c, t), off = c.hidden.includes(t);
  const acts = [];
  if (fav) {
    const i = c.fav.indexOf(t);
    if (i > 0) acts.push(['Omhoog', () => heFavMove(t, -1), 'up']);
    if (i < c.fav.length - 1) acts.push(['Omlaag', () => heFavMove(t, 1), 'down']);
    acts.push(['Uit favorieten', () => toggleFav(t), 'star']);
  } else {
    acts.push(['Naar een andere groep…', () => heMoveSheet(t), 'folder']);
    const flat = [].concat(...c.groups.map(g => g.tools)), i = flat.indexOf(t);
    if (i > 0) acts.push(['Omhoog', () => heMove(t, -1), 'up']);
    if (i < flat.length - 1) acts.push(['Omlaag', () => heMove(t, 1), 'down']);
    acts.push([off ? 'Tonen op het startscherm' : 'Verbergen van het startscherm', () => heToggle(t, off), 'eye']);
  }
  openSheet(heName(t), gi >= 0 ? 'In ' + c.groups[gi].name + (off ? ' · verborgen' : '') : '', acts);
}
/** Kies de groep waar de tool heen moet (met "Nieuwe groep…"). */
function heMoveSheet(t){
  const c = homeCfg(), gi = heGroupOf(c, t);
  openSheet(heName(t) + ' verplaatsen', 'Naar welke groep?', c.groups.map((g, i) => [g.name + (i === gi ? ' (nu)' : ''), () => { if (i !== gi) heMoveTo(t, i); }, i === gi ? 'check' : 'folder'])
    .concat([['Nieuwe groep…', () => askInput('Nieuwe groep', 'Naam van de groep', '', 'Maken', v => { v = v.trim(); if (!v) return; const d = homeCfg(); d.groups.push({ name: v.slice(0, 40), tools: [] }); heMoveToIn(d, t, d.groups.length - 1); heSaveEdit(d, heName(t) + ' staat nu in ' + v.slice(0, 40)); }), 'plus']]));
}
/** "Tool toevoegen" onder een groep: een tool uit een andere groep hierheen halen (blijft open voor nog een). */
function heAddTo(gi){
  const c = homeCfg(), g = c.groups[gi]; if (!g) return;
  const others = [].concat(...c.groups.map((x, i) => i === gi ? [] : x.tools.map(t => [t, x.name])));
  if (!others.length) { toast('Alle tools staan al in deze groep'); return; }
  openSheet('Toevoegen aan ' + g.name, 'Tik op een tool om hem hierheen te halen', [['Klaar', () => {}, 'check']].concat(others.map(([t, from]) => [heName(t) + ' (uit ' + from + ')', () => { const d = homeCfg(); if (heMoveToIn(d, t, gi)) { heSaveEdit(d, heName(t) + ' toegevoegd aan ' + g.name); setTimeout(() => { if (current === 'homeedit' && !$('#sheet').classList.contains('on')) heAddTo(gi); }, 250); } }, 'plus'])));
}
function heGroupMove(gi, dir){
  const c = homeCfg(), j = gi + dir; if (j < 0 || j >= c.groups.length) return;
  const [g] = c.groups.splice(gi, 1); c.groups.splice(j, 0, g);
  heSaveEdit(c);
  // Focus op dezelfde pijl (of, aan de rand, op de andere pijl of de naam van de groep)
  setTimeout(() => { const q = s => document.querySelector('#he-groups .hegroup[data-g="' + j + '"] ' + s);
    const b = [q('.mv[aria-label$="' + (dir < 0 ? 'omhoog' : 'omlaag') + '"]'), q('.mv[aria-label$="' + (dir < 0 ? 'omlaag' : 'omhoog') + '"]'), q('.hegname')].find(x => x && !x.disabled); if (b) b.focus(); }, 0);
}
function heGroupMenu(gi){
  const c = homeCfg(), g = c.groups[gi]; if (!g) return;
  const acts = [['Hernoemen', () => heRename(gi), 'edit'], ['Tool toevoegen', () => heAddTo(gi), 'plus']];
  if (gi > 0) acts.push(['Bovenaan zetten', () => { const d = homeCfg(); const [x] = d.groups.splice(gi, 1); d.groups.unshift(x); heSaveEdit(d); }, 'up']);
  if (gi < c.groups.length - 1) acts.push(['Onderaan zetten', () => { const d = homeCfg(); const [x] = d.groups.splice(gi, 1); d.groups.push(x); heSaveEdit(d); }, 'down']);
  if (c.groups.length > 1) acts.push(['Groep weghalen', () => heDelGroup(gi), 'trash']);
  openSheet(g.name, g.tools.length + (g.tools.length === 1 ? ' tool' : ' tools'), acts);
}
function heRename(gi){ const c = homeCfg(); askInput('Groep hernoemen', '', c.groups[gi].name, 'Opslaan', v => { v = v.trim(); if (!v) return; c.groups[gi].name = v.slice(0, 40); heSaveEdit(c); }); }
function heAddGroup(){ askInput('Nieuwe groep', 'Naam van de groep', '', 'Toevoegen', v => { v = v.trim(); if (!v) return; const c = homeCfg(); c.groups.push({ name: v.slice(0, 40), tools: [] }); heSaveEdit(c); setTimeout(() => { if (current === 'homeedit' && !$('#sheet').classList.contains('on')) heAddTo(c.groups.length - 1); }, 250); }); }
/** Groep weg; de tools erin gaan naar de groep erboven (of eronder als het de eerste is). */
function heDelGroup(gi){
  const c = homeCfg(), g = c.groups[gi]; if (!g || c.groups.length < 2) return;
  const to = gi > 0 ? gi - 1 : 1, go = () => { const d = homeCfg(); const [x] = d.groups.splice(gi, 1); const t = d.groups[gi > 0 ? gi - 1 : 0]; t.tools.push(...x.tools); heSaveEdit(d, 'Groep ' + x.name + ' weggehaald'); };
  if (!g.tools.length) { go(); return; }
  askConfirm('Groep ' + g.name + ' weghalen?', 'De ' + g.tools.length + (g.tools.length === 1 ? ' tool gaat' : ' tools gaan') + ' naar ' + c.groups[to].name + '.', 'Weghalen', go);
}
function heReset(){ askConfirm('Standaard herstellen?', 'Groepen, volgorde en verborgen tools gaan terug naar de standaardindeling. Je favorieten blijven.', 'Herstellen', () => { const fav = homeCfg().fav; store.set('home', Object.assign(JSON.parse(JSON.stringify(HOME_DEFAULT)), { fav: fav })); renderHome(); renderDash(); setDynamicShortcuts(); enterHomeEdit(); }); }

/* ---------- slepen (aan ⠿): binnen de favorieten, of tussen alle groepen ---------- */
(function(){
  let drag = null;
  const rowsIn = scope => [...document.querySelectorAll(scope)];
  function start(e){
    if (drag || !e.isPrimary || (e.pointerType === 'mouse' && e.button !== 0)) return; // één vinger, linkerknop
    const h = e.target && e.target.closest && e.target.closest('#s-homeedit .hedrag'); if (!h) return;
    const row = h.closest('.herow'), list = row.closest('.helist'); if (!row || !list) return;
    e.preventDefault();
    const r = row.getBoundingClientRect(), ghost = row.cloneNode(true);
    ghost.className += ' heghost'; ghost.setAttribute('aria-hidden', 'true'); ghost.removeAttribute('role'); ghost.style.width = r.width + 'px'; ghost.style.left = r.left + 'px'; ghost.style.top = r.top + 'px';
    document.body.appendChild(ghost);
    row.classList.add('hedragging');
    drag = { row, ghost, fav: !!list.dataset.fav, from: list.dataset.g, dy: e.clientY - r.top, y: e.clientY, y0: e.clientY, moved: false, id: e.pointerId, raf: 0 };
    try { h.setPointerCapture(e.pointerId); } catch (x) { }
    if (navigator.vibrate) navigator.vibrate(10);
    scroll();
  }
  function scroll(){
    if (!drag) return;
    if (!drag.moved) { drag.raf = requestAnimationFrame(scroll); return; } // pas meeschuiven als de vinger beweegt
    const edge = 90, y = drag.y, sp = y < edge ? -Math.ceil((edge - y) / 8) : y > innerHeight - edge ? Math.ceil((y - innerHeight + edge) / 8) : 0;
    if (sp) { const sc = document.querySelector('#s-homeedit main') || document.scrollingElement; const before = sc.scrollTop; sc.scrollTop += sp; if (sc.scrollTop === before) window.scrollBy(0, sp); place(); }
    drag.raf = requestAnimationFrame(scroll);
  }
  /** De rij zelf meeschuiven naar de plek onder de vinger (de schaduw volgt de vinger). */
  function place(){
    const d = drag; d.ghost.style.top = (d.y - d.dy) + 'px';
    const scope = d.fav ? '#he-fav .helist' : '#he-groups .helist';
    let best = null;
    for (const list of rowsIn(scope)) {
      const lr = list.getBoundingClientRect();
      if (d.y < lr.top - 24 || d.y > lr.bottom + 24) continue;
      const rows = [...list.querySelectorAll('.herow')].filter(x => x !== d.row);
      let before = null;
      for (const x of rows) { const rr = x.getBoundingClientRect(); if (d.y < rr.top + rr.height / 2) { before = x; break; } }
      best = { list, before }; break;
    }
    if (!best) return;
    const drop = best.list.querySelector('.hedrop');
    if (best.before) { if (d.row.nextSibling !== best.before) best.list.insertBefore(d.row, best.before); }
    else if (drop) best.list.insertBefore(d.row, drop);
    else if (best.list.lastElementChild !== d.row) best.list.appendChild(d.row);
  }
  function move(e){
    if (!drag || e.pointerId !== drag.id) return;
    e.preventDefault(); drag.y = e.clientY;
    if (!drag.moved && Math.abs(drag.y - drag.y0) > 8) drag.moved = true;
    if (drag.moved) place();
  }
  function end(e){
    if (!drag || (e && e.pointerId !== drag.id)) return;
    const d = drag; drag = null;
    cancelAnimationFrame(d.raf); d.ghost.remove(); d.row.classList.remove('hedragging');
    document.querySelectorAll('.heghost').forEach(g => g.remove());
    if (!e || e.type === 'pointercancel') { if (current === 'homeedit') enterHomeEdit(); return; }
    if (!d.moved) return; // alleen aangetikt: niets veranderd
    const c = homeCfg(), t = d.row.dataset.t;
    if (d.fav) c.fav = [...document.querySelectorAll('#he-fav .helist .herow')].map(x => x.dataset.t);
    else c.groups.forEach((g, gi) => { const l = document.querySelector('#he-groups .helist[data-g="' + gi + '"]'); if (l) g.tools = [...l.querySelectorAll('.herow')].map(x => x.dataset.t); });
    const gi = heGroupOf(c, t);
    heSaveEdit(c, !d.fav && gi >= 0 && String(gi) !== d.from ? heName(t) + ' staat nu in ' + c.groups[gi].name : '', '[data-t="' + t + '"] button.more');
  }
  /** Weg van het scherm (terug, app naar de achtergrond) tijdens het slepen: niets bewaren, schaduw weg. */
  window.heDragCancel = () => { if (drag) end(null); };
  document.addEventListener('visibilitychange', () => { if (document.hidden) window.heDragCancel(); });
  document.addEventListener('pointerdown', start);
  document.addEventListener('pointermove', move, { passive: false });
  document.addEventListener('pointerup', end);
  document.addEventListener('pointercancel', end);
})();
renderHome();
setTimeout(renderDash, 50);

// Snelle keuzes bij lang indrukken van het app-icoon: eerst je favorieten, aangevuld met de standaard
function setDynamicShortcuts(){
  const list = homeCfg().fav.concat(['player', 'radio', 'podcasts', 'notes', 'redial']).filter((k, i, a) => TOOLS[k] && k !== 'skin' && a.indexOf(k) === i).slice(0, 4);
  Android.shortcutsDynamic(JSON.stringify(list.map(k => [k, TOOLS[k][0], TOOLS[k][1], TOOLS[k][2]])));
}
setDynamicShortcuts();
