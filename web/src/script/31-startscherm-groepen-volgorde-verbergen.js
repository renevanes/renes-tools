/* ---------- Startscherm: groepen, volgorde, verbergen ----------
 * Indeling 2 (v: 2): "Handig" verdeeld over Documenten & geld, Dag & gezondheid, Onderweg en Slim; de groep
 * Telefoon (alleen de skin) is weg. Een nieuwe tool hoort in HOME_DEFAULT; homeCfg zet hem bij Rene in die groep. */
const HOME_LAYOUT = 2;
const HOME_OLD_HANDIG = ['ai', 'notes', 'pdf', 'tracks', 'car', 'kluis', 'vpn', 'loc', 'auto', 'sleep', 'checkin', 'apparaten', 'facturen', 'bank', 'masterdata', 'gebruik'];
const HOME_DEFAULT = { v: HOME_LAYOUT, grouped: true, compact: false, hidden: [], groups: [
  { name: 'Bellen & contacten', tools: ['redial', 'calls', 'contacts', 'recorder', 'transcripts'] },
  { name: 'Berichten & backup', tools: ['backup', 'wa', 'sms', 'history'] },
  { name: 'Muziek & radio', tools: ['radio', 'podcasts', 'spotify', 'music'] },
  { name: 'Documenten & geld', tools: ['pdf', 'ereader', 'kluis', 'facturen', 'bank', 'masterdata'] },
  { name: 'Dag & gezondheid', tools: ['checkin', 'sleep', 'gebruik', 'nieuws', 'agenda'] },
  { name: 'Onderweg', tools: ['tracks', 'car', 'wc', 'loc', 'vpn'] },
  { name: 'Slim', tools: ['ai', 'notes', 'auto', 'apparaten', 'skin'] } ] };
/* De oude standaardindeling (indeling 1, tot en met versie 1.149), met per groep de lijsten die de app ooit als standaard
 * had (uit de geschiedenis van dit bestand). Gebruik stond er nooit in en kwam vanzelf achteraan in Handig.
 * in: per tool die later bij die groep kwam het volgnummer van de versie waarin hij verscheen (1.17 = 0), zodat
 * homeIsOld weet in welke volgorde de oude app ze er vanzelf achteraan zette. */
const HOME_OLD = [
  { name: 'Bellen & contacten', tools: ['redial', 'calls', 'contacts', 'recorder', 'transcripts'], was: [['redial', 'calls', 'contacts', 'transcripts']], in: { recorder: 4 } },
  { name: 'Berichten & backup', tools: ['backup', 'wa', 'sms', 'history'], was: [['backup', 'wa', 'sms']], in: { history: 3 } },
  { name: 'Muziek & radio', tools: ['radio', 'podcasts', 'spotify', 'music'], was: [['radio', 'music'], ['radio', 'podcasts', 'music']], in: { podcasts: 8, spotify: 9 } },
  { name: 'Handig', tools: HOME_OLD_HANDIG,
    was: [['notes', 'tracks'], ['notes', 'tracks', 'auto'], ['notes', 'tracks', 'car', 'auto'], ['notes', 'tracks', 'car', 'kluis', 'auto'], ['notes', 'pdf', 'tracks', 'car', 'kluis', 'auto'],
      ['notes', 'pdf', 'tracks', 'car', 'kluis', 'vpn', 'auto'], ['notes', 'pdf', 'tracks', 'car', 'kluis', 'vpn', 'loc', 'auto']].concat([9, 10, 11, 12, 13, 14, 15].map(n => HOME_OLD_HANDIG.slice(0, n))),
    in: { auto: 2, car: 5, kluis: 6, pdf: 7, vpn: 10, loc: 11, ai: 12, sleep: 13, checkin: 15, apparaten: 16, gebruik: 17, facturen: 18, bank: 19, masterdata: 20 } } ];
const TOOL_TILE = {}; Object.keys(TILE_TOOL).forEach(id => TOOL_TILE[TILE_TOOL[id]] = id);
const homeCopy = o => JSON.parse(JSON.stringify(o));
/** Konden deze extra tools achteraan een groep zo vanzelf ontstaan? De oude app zette nieuwe tools bij elk bewaren
 * achteraan (per keer in de volgorde van de tegels); een latere keer bevat alleen tools die later verschenen dan alles
 * van de keren ervoor. Dus: is extra op te delen in zulke opeenvolgende stukken? */
function homeBatchesOk(intro, extra){
  const ti = tk => Object.values(TILE_TOOL).indexOf(tk), at = tk => intro[tk] == null ? -1 : intro[tk], ok = [true];
  for (let j = 1; j <= extra.length; j++) {
    ok[j] = false;
    for (let i = 0; i < j && !ok[j]; i++) {
      if (!ok[i]) continue;
      const run = extra.slice(i, j);
      if (!run.every((tk, k) => !k || ti(run[k - 1]) < ti(tk))) continue;
      const first = Math.min(...run.map(at));
      if (extra.slice(0, i).every(tk => at(tk) < first)) ok[j] = true;
    }
  }
  return ok[extra.length];
}
/** Is deze bewaarde indeling (nog) de oude standaard? Dan heeft Rene groepen, volgorde en verborgen tools nooit aangepast.
 * Wat de oude homeCfg zelf deed telt niet als aanpassing: de groep Telefoon die er vanzelf bovenaan bij kwam, en per groep
 * een standaardlijst van toen met daarachter de tools die er later vanzelf bij kwamen (homeBatchesOk). Bij twijfel: niet
 * de oude standaard (dan blijft alles zoals het is; Standaard herstellen kan altijd). Andersom kan een zelf gemaakte volgorde
 * er precies zo uitzien: daarom bewaart homeCfg de vorige indeling (rt.homeV1) en kan Rene die terugzetten. */
function homeIsOld(s, known){
  if (!s || !Array.isArray(s.groups) || (s.v || 1) >= HOME_LAYOUT) return false;
  const kn = tk => known.includes(tk);
  if ((Array.isArray(s.hidden) ? s.hidden : []).some(kn)) return false;
  let gs = s.groups.map(g => ({ name: g && g.name, tools: (g && Array.isArray(g.tools) ? g.tools : []).filter(kn) }));
  if (gs.length && gs[0].name === 'Telefoon' && gs[0].tools.every(tk => tk === 'skin')) gs = gs.slice(1);
  if (gs.length !== HOME_OLD.length || gs.some((g, i) => g.name !== HOME_OLD[i].name)) return false;
  return gs.every((g, i) => {
    const o = HOME_OLD[i];
    return [o.tools].concat(o.was).some(h => {
      h = h.filter(kn);
      if (g.tools.length < h.length || h.some((tk, j) => g.tools[j] !== tk)) return false;
      const extra = g.tools.slice(h.length);
      return extra.every(tk => o.tools.includes(tk)) && homeBatchesOk(o.in, extra);
    });
  });
}
/** Zuiver: bewaarde indeling (of null) → indeling om te tonen. known = alle tools (in de volgorde van de tegels).
 *  - niets bewaard of nog de oude standaard → de nieuwe standaard (favorieten, compact, groepen aan/uit en Overzicht/Recent blijven);
 *  - zelf aangepast → precies zo gelaten; onbekende tools gaan weg en een nieuwe tool komt in zijn standaardgroep:
 *    de groep met dezelfde naam, anders de groep waar de meeste tools uit die standaardgroep staan, anders een nieuwe groep
 *    met die naam (nooit zomaar in de laatste groep). */
function homeMigrate(s, known){
  let c;
  if (!s || !Array.isArray(s.groups)) c = homeCopy(HOME_DEFAULT);
  else if (homeIsOld(s, known)) c = Object.assign(homeCopy(s), { groups: homeCopy(HOME_DEFAULT.groups), hidden: [] });
  else c = homeCopy(s);
  c.v = HOME_LAYOUT;
  c.hidden = Array.isArray(c.hidden) ? c.hidden.filter(tk => known.includes(tk)) : [];
  c.fav = Array.isArray(c.fav) ? c.fav.filter((tk, i, a) => known.includes(tk) && a.indexOf(tk) === i) : []; // favorieten: bovenaan
  const seen = new Set();
  c.groups = c.groups.filter(g => g && typeof g === 'object').map(g => ({ name: String(g.name == null ? '' : g.name), tools: (Array.isArray(g.tools) ? g.tools : []).filter(tk => known.includes(tk) && !seen.has(tk) && seen.add(tk)) }));
  if (!c.groups.length) c.groups.push({ name: 'Tools', tools: [] });
  // Ontbrekende (nieuwe) tools in de volgorde van de standaardindeling, zodat ze ook onderling goed staan
  const flat = [].concat(...HOME_DEFAULT.groups.map(g => g.tools)), at = tk => { const i = flat.indexOf(tk); return i < 0 ? flat.length : i; };
  known.slice().sort((a, b) => at(a) - at(b)).forEach(tk => {
    if (seen.has(tk)) return;
    seen.add(tk);
    const d = HOME_DEFAULT.groups.find(g => g.tools.includes(tk));
    let g = d && c.groups.find(x => x.name === d.name);
    if (!g && d) {
      const score = x => x.tools.filter(o => d.tools.includes(o)).length, best = Math.max(...c.groups.map(score));
      g = best > 0 ? c.groups.find(x => score(x) === best) : null;
      if (!g) { g = { name: d.name, tools: [] }; c.groups.push(g); }
    }
    (g || c.groups[c.groups.length - 1]).tools.push(tk);
  });
  return c;
}
function homeCfg(){ return homeMigrate(store.get('home', null), Object.keys(TOOL_TILE)); }
/* ---------- De vorige indeling (indeling 1) bewaren en terugzetten ----------
 * Een zelf gemaakte volgorde kan er precies uitzien als een oude standaard en wordt dan ook omgezet. Daarom: bij de overstap
 * de bewaarde indeling apart zetten (rt.homeV1: { cfg, kaart }) en de nieuwe meteen bewaren; op het startscherm één keer
 * een kaart "Nieuwe indeling" met Vorige terugzetten, en in Startscherm aanpassen blijft Vorige indeling terugzetten staan. */
function homeV1Store(){
  const s = store.get('home', null);
  if (!s || !Array.isArray(s.groups) || (s.v || 1) >= HOME_LAYOUT || !homeIsOld(s, Object.keys(TOOL_TILE))) return;
  if (!store.get('homeV1', null)) store.set('homeV1', { cfg: s, kaart: true });
  store.set('home', homeCfg());
}
function homeV1(){ const o = store.get('homeV1', null); return o && o.cfg && Array.isArray(o.cfg.groups) ? o : null; }
/** De groepen, volgorde en verborgen tools van toen terug (als je eigen indeling, v: 2: niet opnieuw omzetten); je
 *  favorieten en weergave blijven zoals ze nu zijn. Ongedaan maken kan meteen. */
function homeV1Restore(){
  const o = homeV1(); if (!o) return;
  const nu = homeCfg(), terug = Object.assign(homeCopy(nu), { groups: homeCopy(o.cfg.groups), hidden: Array.isArray(o.cfg.hidden) ? homeCopy(o.cfg.hidden) : [], v: HOME_LAYOUT });
  store.set('homeV1', Object.assign(o, { kaart: false }));
  heSaveEdit(homeMigrate(terug, Object.keys(TOOL_TILE)));
  undoToast(t('Vorige indeling teruggezet'), () => heSaveEdit(nu));
}
function homeV1Dismiss(){ const o = homeV1(); if (o) store.set('homeV1', Object.assign(o, { kaart: false })); renderHomeNew(); }
function renderHomeNew(){ const el = $('#home-new'); if (el) el.hidden = !(homeV1() && homeV1().kaart); }
function homeSave(c){ store.set('home', c); renderHome(); renderDash(); }
function homeGroepenDicht(){ const d = store.get('homeGroepenDicht', []); return Array.isArray(d) ? d.filter(x => typeof x === 'string') : []; }
/** Groep in- of uitklappen; namen van groepen die niet meer bestaan vallen eraf. */
function homeKlap(name){
  if (!name) return;
  const namen = ['★ Favorieten'].concat(homeCfg().groups.map(g => g.name)), d = homeGroepenDicht().filter(x => namen.includes(x));
  store.set('homeGroepenDicht', d.includes(name) ? d.filter(x => x !== name) : d.concat(name));
  renderHome();
  const h = [...document.querySelectorAll('#home-groups .hgroup h3')].find(e => e.dataset.g === name); if (h) h.focus({ preventScroll: true });
}
(function(){ const box = document.getElementById('home-groups'); if (!box) return;
  box.addEventListener('click', e => { const h = e.target.closest && e.target.closest('.hgroup h3[data-g]'); if (h) homeKlap(h.dataset.g); });
  box.addEventListener('keydown', e => { const h = e.target.closest && e.target.closest('.hgroup h3[data-g]'); if (h && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); homeKlap(h.dataset.g); } });
})();
function renderHome(){
  const c = homeCfg(), box = $('#home-groups'), pool = $('#tile-pool');
  // Tegels eerst terug in de verborgen pool, dan opnieuw verdelen (de tegels zelf blijven dezelfde knoppen)
  Object.values(TOOL_TILE).forEach(id => { const tk = document.getElementById(id); if (tk) pool.appendChild(tk); });
  box.innerHTML = '';
  box.classList.toggle('compact', !!c.compact);
  const mk = (name, tools) => {
    const vis = tools.filter(tk => !c.hidden.includes(tk) && !playWeg(tk)); if (!vis.length) return;
    const g = document.createElement('div'); g.className = 'hgroup';
    // Een groep met een naam klap je in en uit met een tik op de kop (onthouden per groep, homeGroepenDicht)
    const dicht = !!name && homeGroepenDicht().includes(name);
    if (name) { const h = document.createElement('h3'); h.textContent = name; h.dataset.g = name; h.tabIndex = 0; h.setAttribute('role', 'button'); h.setAttribute('aria-expanded', dicht ? 'false' : 'true'); g.appendChild(h); }
    if (dicht) g.classList.add('dicht');
    const grid = document.createElement('div'); grid.className = 'tiles'+(vis.length===1?' single':vis.length%2?' oneven':''); // oneven: de laatste tegel over de volle breedte, geen gat
    vis.forEach(tk => { const el = document.getElementById(TOOL_TILE[tk]); if (el) grid.appendChild(el); });
    g.appendChild(grid); box.appendChild(g);
  };
  // Favorieten eerst, in de volgorde die je koos; ze staan dan niet nog eens in hun eigen groep
  const fav = c.fav.filter(tk => !c.hidden.includes(tk)), notFav = tk => !fav.includes(tk);
  if (c.grouped) { if (fav.length) mk('★ Favorieten', fav); c.groups.forEach(g => mk(g.name, g.tools.filter(notFav))); }
  else mk('', fav.concat([].concat(...c.groups.map(g => g.tools)).filter(notFav)));
  pool.style.display = 'none';
  renderHomeRecent();
  renderHomeNew();
}
/* ---------- Recent gebruikt: de laatste vier tools die Rene opende ----------
 * Alleen welke tool (bijv. "pdf"), geen tijden of wat erin gebeurde; alleen in deze WebView (localStorage rt.homeRecent).
 * Het is gebruik, geen instelling: niet in de interface-instellingen voor Alles back-uppen (WS_SKIP in 02-kern.js).
 * Uit in Startscherm aanpassen = niets meer bijhouden en de lijst wissen. Lang indrukken: hetzelfde menu als een tegel. */
const HOME_RECENT_MAX = 4;
function homeRecentList(){ const r = store.get('homeRecent', []); return Array.isArray(r) ? r.filter((tk, i, a) => TOOL_TILE[tk] && tk !== 'skin' && a.indexOf(tk) === i).slice(0, HOME_RECENT_MAX) : []; }
/** Zuiver: tk vooraan, zonder dubbele, hooguit max. */
function homeRecentPush(list, tk, max){ return [tk].concat(list.filter(x => x !== tk)).slice(0, max); }
function homeRecentAdd(tk){
  if (!TOOL_TILE[tk] || tk === 'skin' || homeCfg().recent === false) return;
  const r = homeRecentList(); if (r[0] === tk) return;
  store.set('homeRecent', homeRecentPush(r, tk, HOME_RECENT_MAX));
}
function renderHomeRecent(){
  const box = $('#home-recent'), list = $('#home-recent-list'); if (!box || !list) return;
  const c = homeCfg(), r = c.recent === false ? [] : homeRecentList().filter(tk => !c.hidden.includes(tk) && !playWeg(tk));
  box.hidden = !r.length;
  const key = r.join() + '|' + taalId();
  if (list.dataset.k === key) return;
  list.dataset.k = key;
  list.innerHTML = r.map(tk => '<button class="hrtool" data-t="' + tk + '" onclick="show(this.dataset.t)"><span class="ic">' + uiToolIcon(tk) + '</span><b>' + esc(homeShy(t(TOOLS[tk][0]))) + '</b></button>').join('');
  list.querySelectorAll('.hrtool').forEach(el => toolPress(el, el.dataset.t));
}
/* Lange namen in de smalle knoppen: alleen op een nette plek afbreken (zacht afbreekstreepje), nooit midden in een woord. */
// Alleen hier (niet in en.json): een zacht afbreekstreepje in de vertaling zelf zou ook in zoeken, voorlezen en vergelijken terechtkomen.
const HOME_SHY = { Meldingsgeschiedenis: 'Meldings\u00adgeschiedenis', Automatiseringen: 'Automati\u00adseringen', Stamgegevens: 'Stam\u00adgegevens', Automations: 'Auto\u00admations' };
function homeShy(s){ return String(s).split(' ').map(w => HOME_SHY[w] || w).join(' '); }
function heRecent(on){ const c = homeCfg(); c.recent = !!on; if (!on) store.set('homeRecent', []); homeSave(c); if (current === 'homeedit') enterHomeEdit(); }
// Elk scherm van een tool dat echt open gaat (ook via zoeken, een snelkoppeling of de assistent) telt als gebruikt
(function(){
  const inner = show;
  show = function(name){
    const r = inner.apply(this, arguments);
    try { if (current === name) { if (name === 'home') renderHomeRecent(); else homeRecentAdd(name); } } catch (e) { }
    return r;
  };
  try { if (current !== 'home') homeRecentAdd(current); } catch (e) { } // al geopend bij het starten (snelkoppeling, widget)
})();
addEventListener('taalchange', () => { try { renderHomeRecent(); } catch (e) { } });
/* ---------- Startscherm aanpassen ----------
 * Groepen in een eigen volgorde (pijlen bij de groep), tools slepen (ook naar een andere groep), of via ⋯:
 * naar een groep, omhoog/omlaag, verbergen. "Tool toevoegen" onder een groep haalt een tool uit een andere groep. */
function heName(tk){ return t((TOOLS[tk] || [tk])[0]); }
function heGroupOf(c, tk){ return c.groups.findIndex(g => g.tools.includes(tk)); }
function heRow(c, tk, fav){
  if (playWeg(tk)) return '';
  const nm = heName(tk), off = c.hidden.includes(tk), on = c.fav.includes(tk);
  return '<div class="herow' + (off ? ' off' : '') + '" role="listitem" data-t="' + tk + '">' +
    '<span class="hedrag" aria-hidden="true" title="Slepen">⠿</span>' +
    '<span class=nm>' + esc(nm) + (off ? '<small>Verborgen</small>' : '') + '</span>' +
    '<button class="mv star' + (on ? ' on' : '') + '" data-t="' + tk + '" onclick="toggleFav(this.dataset.t)" aria-pressed="' + on + '" aria-label="' + (fav ? t('{naam} uit favorieten', { naam: esc(nm) }) : t('{naam} als favoriet', { naam: esc(nm) })) + '">' + (on ? '★' : '☆') + '</button>' +
    '<button class="mv more" data-t="' + tk + '" onclick="heToolMenu(this.dataset.t, ' + (fav ? 'true' : 'false') + ')" aria-label="' + (fav ? t('{naam}: omhoog, omlaag, uit favorieten', { naam: esc(nm) }) : t('{naam}: verplaatsen, verbergen …', { naam: esc(nm) })) + '">⋯</button></div>';
}
function enterHomeEdit(){
  const c = homeCfg();
  $('#he-v1').style.display = homeV1() ? '' : 'none';
  ['grouped', 'compact', 'dash', 'recent'].forEach(k => { const on = k === 'dash' || k === 'recent' ? c[k] !== false : !!c[k], b = $('#he-' + k); b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false'); });
  $('#he-fav').innerHTML = '<h2>★ Favorieten</h2>' + (c.fav.length ? '<div class="helist" data-fav="1" role="list" aria-label="Favorieten">' + c.fav.map(tk => heRow(c, tk, true)).join('') + '</div>'
    : '<p class=note style="margin:0">Tik op ☆ bij een tool (hieronder, of houd de tegel ingedrukt) om hem bovenaan te zetten.</p>');
  const n = c.groups.length;
  $('#he-groups').innerHTML = (c.grouped ? '' : '<p class="note">Zonder groepen staan de tools in deze volgorde onder elkaar (zet In groepen aan voor de kopjes).</p>') +
    c.groups.map((g, gi) => '<div class="card hegroup" data-g="' + gi + '">' +
      '<div class=heghead><h2 class="hegname-h"><button class="hegname" data-g="' + gi + '" onclick="heRename(+this.dataset.g)" aria-label="' + esc(t(g.name) + ', ' + tn(g.tools.length, '{n} tool', '{n} tools') + ', ' + t('hernoemen')) + '">' + esc(g.name) + '<small>' + tn(g.tools.length, '{n} tool', '{n} tools') + ' · ✎</small></button></h2>' +
      '<button class="mv" data-g="' + gi + '" onclick="heGroupMove(+this.dataset.g,-1)"' + (gi === 0 ? ' disabled' : '') + ' aria-label="' + t('Groep {naam} omhoog', { naam: esc(t(g.name)) }) + '">↑</button>' +
      '<button class="mv" data-g="' + gi + '" onclick="heGroupMove(+this.dataset.g,1)"' + (gi === n - 1 ? ' disabled' : '') + ' aria-label="' + t('Groep {naam} omlaag', { naam: esc(t(g.name)) }) + '">↓</button>' +
      '<button class="mv more" data-g="' + gi + '" onclick="heGroupMenu(+this.dataset.g)" aria-label="' + t('Groep {naam}: meer', { naam: esc(t(g.name)) }) + '">⋯</button></div>' +
      '<div class="helist" data-g="' + gi + '" role="list" aria-label="' + esc(g.name) + '">' + g.tools.map(tk => heRow(c, tk, false)).join('') +
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
function isFav(tk){ return homeCfg().fav.includes(tk); }
function toggleFav(tk){
  const c = homeCfg(), on = !c.fav.includes(tk);
  c.fav = on ? c.fav.concat([tk]) : c.fav.filter(x => x !== tk);
  if (on) c.hidden = c.hidden.filter(x => x !== tk); // een favoriet verbergen heeft geen zin
  heSaveEdit(c, on ? t('★ {naam} staat nu bovenaan', { naam: heName(tk) }) : t('{naam} uit favorieten', { naam: heName(tk) }), '#he-groups [data-t="' + tk + '"] button.star');
}
function heFavMove(tk, dir){
  const c = homeCfg(), i = c.fav.indexOf(tk), j = i + dir;
  if (i < 0 || j < 0 || j >= c.fav.length) return;
  c.fav.splice(i, 1); c.fav.splice(j, 0, tk);
  heSaveEdit(c, '', '#he-fav [data-t="' + tk + '"] button.more');
}
function heSet(k, v){ const c = homeCfg(); c[k] = v; homeSave(c); if (current === 'homeedit') enterHomeEdit(); }
function heToggle(tk, on){ const c = homeCfg(); c.hidden = c.hidden.filter(x => x !== tk); if (!on) { c.hidden.push(tk); c.fav = c.fav.filter(x => x !== tk); } heSaveEdit(c, '', '#he-groups [data-t="' + tk + '"] button.more'); } // verborgen = ook geen favoriet meer
/** Eén plek omhoog/omlaag; aan de rand van een groep schuift de tool naar de groep ernaast. Zuiver op c. */
function heMoveIn(c, tk, dir){
  const gi = heGroupOf(c, tk); if (gi < 0) return false;
  const g = c.groups[gi], i = g.tools.indexOf(tk), j = i + dir;
  if (j >= 0 && j < g.tools.length) { g.tools.splice(i, 1); g.tools.splice(j, 0, tk); return true; }
  const ng = c.groups[gi + dir]; if (!ng) return false;
  g.tools.splice(i, 1);
  if (dir < 0) ng.tools.push(tk); else ng.tools.unshift(tk);
  return true;
}
function heMove(tk, dir){ const c = homeCfg(); if (heMoveIn(c, tk, dir)) heSaveEdit(c, '', '#he-groups [data-t="' + tk + '"] button.more'); }
/** Tool naar groep gi (achteraan, of op plek at). Zuiver op c. */
function heMoveToIn(c, tk, gi, at){
  const from = heGroupOf(c, tk), g = c.groups[gi]; if (!g) return false;
  if (from >= 0) c.groups[from].tools.splice(c.groups[from].tools.indexOf(tk), 1);
  g.tools.splice(at == null ? g.tools.length : Math.max(0, Math.min(at, g.tools.length)), 0, tk);
  return true;
}
function heMoveTo(tk, gi){ const c = homeCfg(); if (heMoveToIn(c, tk, gi)) heSaveEdit(c, t('{naam} staat nu in {groep}', { naam: heName(tk), groep: t(c.groups[gi].name) }), '#he-groups [data-t="' + tk + '"] button.more'); }
/** Menu bij een tool (ook vanaf een tegel op het startscherm). */
function heToolMenu(tk, fav){
  const c = homeCfg(), gi = heGroupOf(c, tk), off = c.hidden.includes(tk);
  const acts = [];
  if (fav) {
    const i = c.fav.indexOf(tk);
    if (i > 0) acts.push(['Omhoog', () => heFavMove(tk, -1), 'up']);
    if (i < c.fav.length - 1) acts.push(['Omlaag', () => heFavMove(tk, 1), 'down']);
    acts.push(['Uit favorieten', () => toggleFav(tk), 'star']);
  } else {
    acts.push(['Naar een andere groep…', () => heMoveSheet(tk), 'folder']);
    const flat = [].concat(...c.groups.map(g => g.tools)), i = flat.indexOf(tk);
    if (i > 0) acts.push(['Omhoog', () => heMove(tk, -1), 'up']);
    if (i < flat.length - 1) acts.push(['Omlaag', () => heMove(tk, 1), 'down']);
    acts.push([off ? 'Tonen op het startscherm' : 'Verbergen van het startscherm', () => heToggle(tk, off), 'eye']);
  }
  openSheet(heName(tk), gi >= 0 ? t('In {groep}', { groep: t(c.groups[gi].name) }) + (off ? ' · ' + t('verborgen') : '') : '', acts);
}
/** Kies de groep waar de tool heen moet (met "Nieuwe groep…"). */
function heMoveSheet(tk){
  const c = homeCfg(), gi = heGroupOf(c, tk);
  openSheet(t('{naam} verplaatsen', { naam: heName(tk) }), t('Naar welke groep?'), c.groups.map((g, i) => [t(g.name) + (i === gi ? ' ' + t('(nu)') : ''), () => { if (i !== gi) heMoveTo(tk, i); }, i === gi ? 'check' : 'folder'])
    .concat([['Nieuwe groep…', () => askInput('Nieuwe groep', 'Naam van de groep', '', 'Maken', v => { v = v.trim(); if (!v) return; const d = homeCfg(); d.groups.push({ name: v.slice(0, 40), tools: [] }); heMoveToIn(d, tk, d.groups.length - 1); heSaveEdit(d, t('{naam} staat nu in {groep}', { naam: heName(tk), groep: v.slice(0, 40) })); }), 'plus']]));
}
/** "Tool toevoegen" onder een groep: een tool uit een andere groep hierheen halen (blijft open voor nog een). */
function heAddTo(gi){
  const c = homeCfg(), g = c.groups[gi]; if (!g) return;
  const others = [].concat(...c.groups.map((x, i) => i === gi ? [] : x.tools.filter(tk => !playWeg(tk)).map(tk => [tk, x.name])));
  if (!others.length) { toast('Alle tools staan al in deze groep'); return; }
  openSheet(t('Toevoegen aan {groep}', { groep: t(g.name) }), 'Tik op een tool om hem hierheen te halen', [['Klaar', () => {}, 'check']].concat(others.map(([tk, from]) => [t('{naam} (uit {groep})', { naam: heName(tk), groep: t(from) }), () => { const d = homeCfg(); if (heMoveToIn(d, tk, gi)) { heSaveEdit(d, t('{naam} toegevoegd aan {groep}', { naam: heName(tk), groep: t(g.name) })); setTimeout(() => { if (current === 'homeedit' && !$('#sheet').classList.contains('on')) heAddTo(gi); }, 250); } }, 'plus'])));
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
  const to = gi > 0 ? gi - 1 : 1, go = () => { const d = homeCfg(); const [x] = d.groups.splice(gi, 1); const tk = d.groups[gi > 0 ? gi - 1 : 0]; tk.tools.push(...x.tools); heSaveEdit(d, t('Groep {naam} weggehaald', { naam: t(x.name) })); };
  if (!g.tools.length) { go(); return; }
  askConfirm(t('Groep {naam} weghalen?', { naam: t(g.name) }), tn(g.tools.length, 'De {n} tool gaat naar {groep}.', 'De {n} tools gaan naar {groep}.', { groep: t(c.groups[to].name) }), 'Weghalen', go);
}
function heReset(){ askConfirm('Standaard herstellen?', 'Groepen, volgorde en verborgen tools gaan terug naar de standaardindeling. Je favorieten blijven.', 'Herstellen', () => { const was = homeCfg(); store.set('home', Object.assign(homeCopy(HOME_DEFAULT), { fav: was.fav, recent: was.recent, compact: was.compact, grouped: was.grouped, dash: was.dash })); /* je weergave (compact, groepen, Overzicht, Recent) blijft ook */ renderHome(); renderDash(); setDynamicShortcuts(); enterHomeEdit(); }); }

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
    const c = homeCfg(), tk = d.row.dataset.t;
    if (d.fav) c.fav = [...document.querySelectorAll('#he-fav .helist .herow')].map(x => x.dataset.t);
    else c.groups.forEach((g, gi) => { const l = document.querySelector('#he-groups .helist[data-g="' + gi + '"]'); if (l) g.tools = [...l.querySelectorAll('.herow')].map(x => x.dataset.t); });
    const gi = heGroupOf(c, tk);
    heSaveEdit(c, !d.fav && gi >= 0 && String(gi) !== d.from ? t('{naam} staat nu in {groep}', { naam: heName(tk), groep: t(c.groups[gi].name) }) : '', (d.fav ? '#he-fav ' : '#he-groups ') + '[data-t="' + tk + '"] button.more');
  }
  /** Weg van het scherm (terug, app naar de achtergrond) tijdens het slepen: niets bewaren, schaduw weg. */
  window.heDragCancel = () => { if (drag) end(null); };
  document.addEventListener('visibilitychange', () => { if (document.hidden) window.heDragCancel(); });
  document.addEventListener('pointerdown', start);
  document.addEventListener('pointermove', move, { passive: false });
  document.addEventListener('pointerup', end);
  document.addEventListener('pointercancel', end);
})();
homeV1Store();
renderHome();
setTimeout(renderDash, 50);

// Snelle keuzes bij lang indrukken van het app-icoon: eerst je favorieten, aangevuld met de standaard
function setDynamicShortcuts(){
  const list = homeCfg().fav.concat(['player', 'radio', 'podcasts', 'notes', 'redial']).filter((k, i, a) => TOOLS[k] && k !== 'skin' && !playWeg(k) && a.indexOf(k) === i).slice(0, 4);
  Android.shortcutsDynamic(JSON.stringify(list.map(k => [k, t(TOOLS[k][0]), TOOLS[k][1], TOOLS[k][2]])));
}
addEventListener('taalchange', () => { try { setDynamicShortcuts(); } catch (e) { } });
setDynamicShortcuts();
