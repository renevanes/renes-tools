/* ---------- Startscherm: groepen, volgorde, verbergen ---------- */
const HOME_DEFAULT = { grouped: true, compact: false, hidden: [], groups: [
  { name: 'Telefoon', tools: ['skin'] },
  { name: 'Bellen & contacten', tools: ['redial', 'calls', 'contacts', 'recorder', 'transcripts'] },
  { name: 'Berichten & backup', tools: ['backup', 'wa', 'sms', 'history'] },
  { name: 'Muziek & radio', tools: ['radio', 'podcasts', 'spotify', 'music'] },
  { name: 'Handig', tools: ['ai', 'notes', 'pdf', 'tracks', 'car', 'kluis', 'vpn', 'loc', 'auto'] } ] };
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
function enterHomeEdit(){
  const c = homeCfg();
  $('#he-fav').innerHTML = c.fav.length ? '<h2>★ Favorieten</h2>' + c.fav.map((t, i) => '<div class="herow"><span class=nm>' + esc((TOOLS[t] || [t])[0]) + '</span>' +
      '<button class=mv data-t="' + t + '" onclick="heFavMove(this.dataset.t,-1)"' + (i === 0 ? ' disabled' : '') + ' aria-label="Omhoog">↑</button>' +
      '<button class=mv data-t="' + t + '" onclick="heFavMove(this.dataset.t,1)"' + (i === c.fav.length - 1 ? ' disabled' : '') + ' aria-label="Omlaag">↓</button>' +
      '<button class="mv star on" data-t="' + t + '" onclick="toggleFav(this.dataset.t)" aria-label="' + esc((TOOLS[t] || [t])[0]) + ' uit favorieten">★</button></div>').join('')
    : '<h2>★ Favorieten</h2><p class=note style="margin:0">Tik op ☆ bij een tool (hieronder, of houd de tegel ingedrukt) om hem bovenaan te zetten.</p>';
  $('#he-grouped').checked = !!c.grouped; $('#he-compact').checked = !!c.compact; $('#he-dash').checked = c.dash !== false;
  const flat = [].concat(...c.groups.map(g => g.tools));
  $('#he-groups').innerHTML = c.groups.map((g, gi) => '<div class=card><div class=heghead><h2 data-g="' + gi + '" onclick="heRename(+this.dataset.g)" style="cursor:pointer">' + esc(g.name) + ' ✎</h2>' +
    (g.tools.length ? '' : '<button class=mini style="float:none" data-g="' + gi + '" onclick="heDelGroup(+this.dataset.g)">Weghalen</button>') + '</div>' +
    (g.tools.map(t => { const i = flat.indexOf(t), off = c.hidden.includes(t);
      return '<div class="herow' + (off ? ' off' : '') + '"><input type=checkbox style="width:22px;height:22px;accent-color:var(--chip-on)" data-t="' + t + '"' + (off ? '' : ' checked') +
        ' onchange="heToggle(this.dataset.t, this.checked)" aria-label="Tonen"><span class=nm>' + esc((TOOLS[t] || [t])[0]) + '</span>' +
        '<button class=mv data-t="' + t + '" onclick="heMove(this.dataset.t,-1)"' + (i === 0 ? ' disabled' : '') + ' aria-label="Omhoog">↑</button>' +
        '<button class=mv data-t="' + t + '" onclick="heMove(this.dataset.t,1)"' + (i === flat.length - 1 ? ' disabled' : '') + ' aria-label="Omlaag">↓</button>' +
        '<button class="mv star' + (c.fav.includes(t) ? ' on' : '') + '" data-t="' + t + '" onclick="toggleFav(this.dataset.t)" aria-pressed="' + c.fav.includes(t) + '" aria-label="' + esc((TOOLS[t] || [t])[0]) + ' als favoriet">' + (c.fav.includes(t) ? '★' : '☆') + '</button></div>'; }).join('') ||
      '<p class=note style="margin:8px 0 0">Leeg. Verplaats er een tool naartoe met de pijlen.</p>') + '</div>').join('');
}
/* Favorieten: bovenaan het startscherm (en de eerste keuzes bij lang indrukken van het app-icoon) */
function isFav(t){ return homeCfg().fav.includes(t); }
function toggleFav(t){
  const c = homeCfg(), on = !c.fav.includes(t);
  c.fav = on ? c.fav.concat([t]) : c.fav.filter(x => x !== t);
  if (on) c.hidden = c.hidden.filter(x => x !== t); // een favoriet verbergen heeft geen zin
  homeSave(c); setDynamicShortcuts();
  if (current === 'homeedit') enterHomeEdit();
  toast(on ? '★ ' + (TOOLS[t] || [t])[0] + ' staat nu bovenaan' : (TOOLS[t] || [t])[0] + ' uit favorieten');
}
function heFavMove(t, dir){
  const c = homeCfg(), i = c.fav.indexOf(t), j = i + dir;
  if (i < 0 || j < 0 || j >= c.fav.length) return;
  c.fav.splice(i, 1); c.fav.splice(j, 0, t);
  homeSave(c); setDynamicShortcuts(); enterHomeEdit();
}
function heSet(k, v){ const c = homeCfg(); c[k] = v; homeSave(c); }
function heToggle(t, on){ const c = homeCfg(), wasFav = c.fav.includes(t); c.hidden = c.hidden.filter(x => x !== t); if (!on) { c.hidden.push(t); c.fav = c.fav.filter(x => x !== t); } homeSave(c); if (wasFav && !on) setDynamicShortcuts(); enterHomeEdit(); } // verborgen = ook geen favoriet meer
/** Eén plek omhoog/omlaag; aan de rand van een groep schuift de tool naar de groep ernaast. */
function heMove(t, dir){
  const c = homeCfg();
  const gi = c.groups.findIndex(g => g.tools.includes(t)); if (gi < 0) return;
  const g = c.groups[gi], i = g.tools.indexOf(t), j = i + dir;
  if (j >= 0 && j < g.tools.length) { g.tools.splice(i, 1); g.tools.splice(j, 0, t); }
  else {
    const ng = c.groups[gi + dir]; if (!ng) return;
    g.tools.splice(i, 1);
    if (dir < 0) ng.tools.push(t); else ng.tools.unshift(t);
  }
  homeSave(c); enterHomeEdit();
}
function heRename(gi){ const c = homeCfg(); askInput('Groep hernoemen', '', c.groups[gi].name, 'Opslaan', v => { v = v.trim(); if (!v) return; c.groups[gi].name = v.slice(0, 40); homeSave(c); enterHomeEdit(); }); }
function heAddGroup(){ askInput('Nieuwe groep', 'Naam van de groep', '', 'Toevoegen', v => { v = v.trim(); if (!v) return; const c = homeCfg(); c.groups.push({ name: v.slice(0, 40), tools: [] }); homeSave(c); enterHomeEdit(); }); }
function heDelGroup(gi){ const c = homeCfg(); if (c.groups[gi] && !c.groups[gi].tools.length && c.groups.length > 1) { c.groups.splice(gi, 1); homeSave(c); enterHomeEdit(); } }
function heReset(){ askConfirm('Standaard herstellen?', 'Groepen, volgorde en verborgen tools gaan terug naar de standaardindeling. Je favorieten blijven.', 'Herstellen', () => { const fav = homeCfg().fav; store.set('home', Object.assign(JSON.parse(JSON.stringify(HOME_DEFAULT)), { fav: fav })); renderHome(); renderDash(); setDynamicShortcuts(); enterHomeEdit(); }); }
renderHome();
setTimeout(renderDash, 50);

// Snelle keuzes bij lang indrukken van het app-icoon: eerst je favorieten, aangevuld met de standaard
function setDynamicShortcuts(){
  const list = homeCfg().fav.concat(['player', 'radio', 'podcasts', 'notes', 'redial']).filter((k, i, a) => TOOLS[k] && k !== 'skin' && a.indexOf(k) === i).slice(0, 4);
  Android.shortcutsDynamic(JSON.stringify(list.map(k => [k, TOOLS[k][0], TOOLS[k][1], TOOLS[k][2]])));
}
setDynamicShortcuts();
