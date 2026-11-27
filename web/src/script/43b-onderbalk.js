/* ---------- Onderbalk: de tabbalk onderin, zelf in te delen (wens van Rene, 27-11-2026) ----------
   Start staat altijd links; daarnaast hooguit OB_MAX plekken met een tool of een vast scherm (Zoeken, Instellingen).
   Indelen zoals op een Android-startscherm: houd een tegel op het startscherm ingedrukt en sleep hem naar de balk
   (staat hij vol, dan vervangt hij de plek waar je loslaat); in de balk zelf ingedrukt houden en schuiven, of omhoog
   slepen om hem weg te halen. Zonder slepen: lang drukken zonder te bewegen geeft het menu (ook met de schermlezer),
   en Startscherm aanpassen → Weergave → Onderbalk heeft pijlen. Bewaard in store 'onderbalk' (gaat mee in de backup). */
const OB_MAX = 4;
const OB_STD = ['search', 'ai', 'backup', 'settings'];
const OB_SCHERM = { search: 'Zoeken', settings: 'Instellingen' }; // vaste schermen die geen tool zijn
// Korte namen voor de balk (de volle naam staat in aria-label)
const OB_KORT = { ai: 'Assistent', backup: 'Backup', wc: 'Toiletten', recorder: 'Opnemen', transcripts: 'Uitschrijven', history: 'Meldingen',
  music: 'Herkennen', spotify: 'Spotify', sms: 'SMS', wa: 'WhatsApp', tracks: 'Routes', loc: 'Locatie', cpap: 'CPAP',
  foto: 'Foto', redial: 'Redial', car: 'Auto', masterdata: 'Stamgegevens', apparaten: 'Apparaten', ereader: 'E-reader', contacts: 'Contacten' };
// De vaste pictogrammen van de oude balk (zodat hij er hetzelfde uitziet)
const OB_SVG = {
  home: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3.5" y="3.5" width="7" height="7" rx="2"/><rect x="13.5" y="3.5" width="7" height="7" rx="2"/><rect x="3.5" y="13.5" width="7" height="7" rx="2"/><rect x="13.5" y="13.5" width="7" height="7" rx="2"/></svg>',
  search: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg>',
  ai: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3.5l1.9 4.6 4.6 1.9-4.6 1.9L12 16.5l-1.9-4.6L5.5 10l4.6-1.9z"/><path d="M18.5 15.5l.8 1.9 1.9.8-1.9.8-.8 1.9-.8-1.9-1.9-.8 1.9-.8z"/></svg>',
  backup: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M7 18a5 5 0 0 1-.6-10A6 6 0 0 1 18 9a4.5 4.5 0 0 1-.5 9"/><path d="M12 12v8M9 15l3-3 3 3"/></svg>',
  settings: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 7h10M18 7h2M4 17h2M10 17h10"/><circle cx="16" cy="7" r="2.2"/><circle cx="8" cy="17" r="2.2"/></svg>'
};

/* Mag dit in de balk? Een tool met een eigen scherm (niet de skin, niet in de Play-versie weggelaten), of Zoeken/Instellingen. */
function obMag(id){
  if (typeof id !== 'string' || !id || id === 'home' || id === 'skin') return false;
  if (!OB_SCHERM[id] && !Object.prototype.hasOwnProperty.call(TOOLS, id)) return false;
  if (typeof playWeg === 'function' && playWeg(id)) return false;
  return !!document.getElementById('s-' + id);
}
/* De plekken naast Start (opgeschoond: alleen wat mag, geen dubbele, hooguit OB_MAX) */
function obItems(){
  let o = null; try { o = store.get('onderbalk', null); } catch (e) { }
  const l = o && Array.isArray(o.items) ? o.items : OB_STD;
  return l.filter((id, i, a) => obMag(id) && a.indexOf(id) === i).slice(0, OB_MAX);
}
function obBewaar(items){ store.set('onderbalk', { v: 1, items: items.filter((id, i, a) => obMag(id) && a.indexOf(id) === i).slice(0, OB_MAX) }); obRender(); if (typeof current !== 'undefined' && current === 'homeedit') obEditRender(); }
function obNaam(id){ return id === 'home' ? t('Start') : OB_SCHERM[id] ? t(OB_SCHERM[id]) : TOOLS[id] ? t(TOOLS[id][0]) : id; }
function obKort(id){ return id === 'home' ? t('Start') : OB_SCHERM[id] ? t(OB_SCHERM[id]) : OB_KORT[id] ? t(OB_KORT[id]) : obNaam(id); }
function obIcoon(id){ return OB_SVG[id] || (typeof uiToolIcon === 'function' ? uiToolIcon(id) : ''); }

/* De balk tekenen; TABS (43-uiterlijk.js) volgt mee: die schermen tonen de balk en zijn een begin (geen terugknop) */
function obRender(){
  const nav = document.getElementById('tabs'); if (!nav) return;
  const ids = ['home'].concat(obItems());
  TABS = ids.slice();
  const inst = document.getElementById('home-inst'); if (inst) inst.hidden = ids.includes('settings'); // Instellingen blijft altijd bereikbaar
  // De zichtbare (korte) naam staat vooraan in de naam voor de schermlezer, zodat ook "tik op Backup" (Voice Access) werkt
  const label = id => obKort(id) === obNaam(id) ? '' : ' aria-label="' + esc(t('{kort}, {naam}', { kort: obKort(id), naam: obNaam(id) })) + '"';
  nav.innerHTML = ids.map(id => '<button type="button" data-t="' + esc(id) + '"' + (id === 'home' ? ' data-vast="1"' : '') + label(id) + '><span class="ti" aria-hidden="true">' + obIcoon(id) + '</span><span>' + esc(obKort(id)) + '</span></button>').join('');
  if (typeof current !== 'undefined' && typeof uiShown === 'function') uiShown(current);
}

/* ---------- Toevoegen, verplaatsen, weghalen ---------- */
function obIn(id){ return obItems().includes(id); }
/* Zet id op plek pos (0 = direct na Start). Vol: vervangt de plek pos (of de laatste). Geeft wat eruit ging, of ''. */
function obZet(id, pos){
  if (!obMag(id)) return '';
  const l = obItems(), was = l.indexOf(id);
  let weg = '';
  if (was >= 0) l.splice(was, 1);
  pos = Math.max(0, Math.min(pos == null ? l.length : pos, l.length));
  if (was < 0 && l.length >= OB_MAX) { const k = Math.min(pos, l.length - 1); weg = l[k]; l.splice(k, 1); pos = k; }
  l.splice(pos, 0, id);
  obBewaar(l);
  return weg;
}
function obWeg(id){ const l = obItems(), i = l.indexOf(id); if (i < 0) return; l.splice(i, 1); obBewaar(l); }
function obSchuif(id, dir){ const l = obItems(), i = l.indexOf(id), j = i + dir; if (i < 0 || j < 0 || j >= l.length) return; l.splice(i, 1); l.splice(j, 0, id); obBewaar(l); }
/* Met een melding en Ongedaan maken */
function obZetMeld(id, pos){
  const voor = obItems(), weg = obZet(id, pos);
  if (!obIn(id)) return;
  undoToast(weg ? t('{naam} vervangt {oud} in de onderbalk', { naam: obNaam(id), oud: obNaam(weg) }) : voor.includes(id) ? t('Onderbalk aangepast') : t('{naam} staat in de onderbalk', { naam: obNaam(id) }), () => obBewaar(voor));
}
function obWegMeld(id){ const voor = obItems(); obWeg(id); undoToast(t('{naam} uit de onderbalk gehaald', { naam: obNaam(id) }), () => obBewaar(voor)); }
function obStandaard(){ const voor = obItems(); store.set('onderbalk', null); obRender(); if (typeof current !== 'undefined' && current === 'homeedit') obEditRender(); undoToast(t('Onderbalk zoals standaard'), () => obBewaar(voor)); }

/* Menu van een plek in de balk (lang drukken zonder te slepen, of met de schermlezer) */
function obMenu(id){
  const l = obItems(), i = l.indexOf(id); if (i < 0) return;
  openSheet(obNaam(id), t('In de onderbalk'), [
    ['Openen', () => tabGo(id)],
    ...(i > 0 ? [['Naar links', () => obSchuif(id, -1)]] : []),
    ...(i < l.length - 1 ? [['Naar rechts', () => obSchuif(id, 1)]] : []),
    ['Uit de onderbalk halen', () => obWegMeld(id)],
    ['Onderbalk aanpassen…', () => obAanpassen()]
  ]);
}
/* Vanuit het menu van een tegel: in de balk zetten (vol: kiezen welke plek hij vervangt) */
function obMenuItems(tool){
  if (!obMag(tool) || (typeof SOLO !== 'undefined' && SOLO)) return [];
  if (obIn(tool)) return [['Uit de onderbalk halen', () => obWegMeld(tool)]];
  return [['In de onderbalk zetten', () => {
    const l = obItems();
    if (l.length < OB_MAX) { obZetMeld(tool); return; }
    openSheet(t('Welke plek vervangen?'), t('De onderbalk is vol'), l.map((id, k) => [t('In plaats van {naam}', { naam: obNaam(id) }), () => obZetMeld(tool, k)]));
  }]];
}
/* Startscherm aanpassen → Tool toevoegen: alles wat erin kan en er nog niet in staat, op naam */
function obKies(){
  const l = obItems();
  const ids = Object.keys(OB_SCHERM).concat(Object.keys(TOOLS)).filter((id, i, a) => a.indexOf(id) === i && obMag(id) && !l.includes(id))
    .sort((a, b) => obNaam(a).localeCompare(obNaam(b), regioLocale()));
  openSheet(t('Tool toevoegen'), l.length >= OB_MAX ? t('De onderbalk is vol: daarna kies je welke plek hij vervangt') : '', ids.map(id => [obNaam(id), () => {
    if (obItems().length < OB_MAX) { obZetMeld(id); obEditRender(); return; }
    openSheet(t('Welke plek vervangen?'), obNaam(id), obItems().map((o, k) => [t('In plaats van {naam}', { naam: obNaam(o) }), () => { obZetMeld(id, k); obEditRender(); }]));
  }]));
}
function obAanpassen(){ jumpTo('homeedit'); setTimeout(() => { if (typeof heTab === 'function') heTab('weer'); const k = document.getElementById('he-ob'); if (k) k.scrollIntoView({ block: 'start' }); }, 60); }

/* Startscherm aanpassen → Weergave → Onderbalk: dezelfde balk als lijst met pijlen (zonder slepen); boven = links */
function obMv(id, dir, uit){ return '<button class="mv" data-id="' + esc(id) + '" data-dir="' + dir + '"' + (uit ? ' disabled' : '') + ' aria-label="' + esc(dir < 0 ? t('{naam} naar links', { naam: obNaam(id) }) : t('{naam} naar rechts', { naam: obNaam(id) })) + '">' + (dir < 0 ? '↑' : '↓') + '</button>'; }
function obEditRender(){
  const box = document.getElementById('he-ob-list'); if (!box) return;
  const l = obItems(), n = l.length;
  box.innerHTML = '<div class="herow heov ob-vast"><span class="stic">' + uiIcon('grid') + '</span><span class="nm">' + esc(t('Start')) + '<small>' + esc(t('Staat altijd links')) + '</small></span></div>' +
    l.map((id, i) => '<div class="herow heov" data-id="' + esc(id) + '"><span class="stic">' + (OB_SVG[id] ? '<span class="ui-icon" aria-hidden="true">' + OB_SVG[id] + '</span>' : uiToolIcon(id)) + '</span><span class="nm">' + esc(obNaam(id)) + '</span>' +
      obMv(id, -1, i === 0) + obMv(id, 1, i === n - 1) +
      '<button class="mv" data-weg="1" data-id="' + esc(id) + '" aria-label="' + esc(t('{naam} uit de onderbalk halen', { naam: obNaam(id) })) + '">' + uiIcon('close') + '</button></div>').join('');
  $('#he-ob-stand').textContent = tn(OB_MAX - n, 'Nog {n} plek vrij', 'Nog {n} plekken vrij');
  $('#he-ob-stand').hidden = n >= OB_MAX;
}
(function(){
  const box = document.getElementById('he-ob-list'); if (!box) return;
  box.addEventListener('click', e => {
    const b = e.target.closest && e.target.closest('button'); if (!b || b.disabled) return;
    const id = b.dataset.id;
    if (b.dataset.weg) obWegMeld(id); else if (b.dataset.dir) obSchuif(id, +b.dataset.dir);
    obEditRender();
    const f = box.querySelector('[data-id="' + id + '"] .mv[data-dir="' + b.dataset.dir + '"]:not([disabled])') || box.querySelector('.mv:not([disabled])');
    if (f) f.focus();
  });
})();

/* ---------- Slepen (zoals Android): een tegel naar de balk, of een plek in de balk verschuiven of eruit ---------- */
let obSleep = null; // { id, van: 'tegel'|'balk', bron, x0, y0, timer, op, sleept, geest, doel }
const OB_HOUD = 500, OB_SLAP = 10;
let obNaKlik = 0, obLosTijd = 0; // de tik en het contextmenu die bij het loslaten horen, doen niets
function obSolo(){ return typeof SOLO !== 'undefined' && SOLO; }
/* Bij pointerdown. Na OB_HOUD ms stil houden is hij opgepakt; loslaten zonder schuiven = het menu, schuiven = slepen. */
function obPak(e, id, van, bron){
  if (obSolo() || e.button > 0 || !obMag(id)) return false;
  obLos();
  const s = obSleep = { id, van, bron, x0: e.clientX, y0: e.clientY, x: e.clientX, y: e.clientY, op: false, sleept: false, pid: e.pointerId };
  s.timer = setTimeout(() => obOp(s), OB_HOUD);
  return true;
}
function obOp(s){
  if (obSleep !== s || s.op) return;
  clearTimeout(s.timer);
  s.op = true; s.bron.classList.add('ob-op'); document.body.classList.add('ob-klaar');
  if (navigator.vibrate) navigator.vibrate(15);
}
function obBeweeg(e){
  const s = obSleep; if (!s || e.pointerId !== s.pid) return;
  s.x = e.clientX; s.y = e.clientY;
  const ver = Math.hypot(s.x - s.x0, s.y - s.y0);
  if (!s.op) { if (ver > OB_SLAP) obLos(); return; } // eerst bewogen: dat is scrollen, geen slepen
  if (!s.sleept && ver > OB_SLAP) obStart(s);
  if (!s.sleept) return;
  if (e.cancelable) e.preventDefault();
  s.geest.style.transform = 'translate(' + Math.round(s.x - 32) + 'px,' + Math.round(s.y - 88) + 'px)'; // boven de vinger: de plek eronder blijft te zien
  obDoel(s);
}
function obStart(s){
  s.sleept = true;
  const g = s.geest = document.createElement('div');
  g.className = 'ob-geest'; g.setAttribute('aria-hidden', 'true');
  g.innerHTML = '<span class="ti">' + obIcoon(s.id) + '</span><span>' + esc(obKort(s.id)) + '</span>';
  document.body.appendChild(g);
  document.body.classList.add('ob-sleept');
  if (s.van === 'balk') s.bron.classList.add('ob-leeg');
}
/* Waar laat je los? In (of vlak boven) de balk: die plek; is hij vol, dan de plek die hij vervangt.
   Vanuit de balk ver genoeg omhoog: weghalen. */
function obDoel(s){
  const nav = document.getElementById('tabs');
  if (!nav.getClientRects().length) { s.doel = null; return; } // balk niet in beeld: nergens heen
  const r = nav.getBoundingClientRect();
  const kn = [...nav.querySelectorAll('button[data-t]')].filter(b => !b.dataset.vast && b.dataset.t !== s.id);
  nav.querySelectorAll('button').forEach(b => b.classList.remove('ob-hier', 'ob-vervang'));
  nav.classList.remove('ob-boven', 'ob-eind');
  document.body.classList.remove('ob-weg');
  s.doel = null;
  if (s.y >= r.top - 24) {
    nav.classList.add('ob-boven');
    const vol = !obIn(s.id) && obItems().length >= OB_MAX;
    if (vol) { // de plek die het dichtst bij is
      let k = 0, best = Infinity;
      kn.forEach((b, i) => { const br = b.getBoundingClientRect(), d = Math.abs(s.x - (br.left + br.width / 2)); if (d < best) { best = d; k = i; } });
      kn[k].classList.add('ob-vervang'); s.doel = { soort: 'zet', pos: k };
    } else {
      let pos = kn.length;
      for (let k = 0; k < kn.length; k++) { const br = kn[k].getBoundingClientRect(); if (s.x < br.left + br.width / 2) { pos = k; break; } }
      if (kn[pos]) kn[pos].classList.add('ob-hier'); else nav.classList.add('ob-eind');
      s.doel = { soort: 'zet', pos };
    }
  } else if (s.van === 'balk' && s.y < r.top - 56) { s.doel = { soort: 'weg' }; document.body.classList.add('ob-weg'); }
}
function obLaat(e){
  const s = obSleep; if (!s || (e && e.pointerId !== s.pid)) return;
  const { sleept, op, doel, id, van } = s;
  obLos();
  if (!op) return; // gewone tik: de knop of tegel doet zijn eigen werk
  obNaKlik = obLosTijd = Date.now();
  if (!sleept) { if (van === 'balk') obMenu(id); else if (typeof toolMenu === 'function') toolMenu(id); return; } // stil vastgehouden: het menu
  if (doel && doel.soort === 'zet') obZetMeld(id, doel.pos);
  else if (doel && doel.soort === 'weg') obWegMeld(id);
}
function obLos(){
  const s = obSleep; obSleep = null;
  if (!s) return;
  clearTimeout(s.timer);
  if (s.bron) s.bron.classList.remove('ob-op', 'ob-leeg');
  if (s.geest) s.geest.remove();
  document.body.classList.remove('ob-klaar', 'ob-sleept', 'ob-weg');
  const nav = document.getElementById('tabs');
  if (nav) { nav.classList.remove('ob-boven', 'ob-eind'); nav.querySelectorAll('button').forEach(b => b.classList.remove('ob-hier', 'ob-vervang')); }
}
document.addEventListener('pointermove', obBeweeg, { passive: false });
document.addEventListener('pointerup', obLaat);
document.addEventListener('pointercancel', e => { if (obSleep && e.pointerId === obSleep.pid) obLos(); });
document.addEventListener('visibilitychange', () => { if (document.hidden) obLos(); });
// Opgepakt: de pagina scrolt niet mee (alleen op wat je oppakt, niet op het hele document: elders blijft scrollen vlot)
function obGeenScroll(el){ el.addEventListener('touchmove', e => { if (obSleep && obSleep.op && e.cancelable) e.preventDefault(); }, { passive: false }); }
// Het lang-indrukken-menu van de WebView doen we zelf; komt het (Android 12+ na 400 ms) vóór OB_HOUD, dan is hij nu opgepakt.
// Komt het pas na het loslaten, of volgt er nog een tik: niets (anders twee keer het menu)
document.addEventListener('contextmenu', e => {
  if (obSleep) { e.preventDefault(); obOp(obSleep); return; }
  if (Date.now() - obLosTijd < 600) { e.preventDefault(); e.stopImmediatePropagation(); }
}, true);
document.addEventListener('pointerdown', () => { obNaKlik = 0; }, true); // een nieuwe aanraking telt gewoon
document.addEventListener('click', e => { if (obNaKlik && Date.now() - obNaKlik < 450) { e.stopImmediatePropagation(); e.preventDefault(); obNaKlik = 0; } }, true);

/* De balk zelf: tikken opent, ingedrukt houden pakt op (behalve Start); het contextmenu (rechtsklik, toets) geeft het menu */
(function(){
  const nav = document.getElementById('tabs'); if (!nav) return;
  obGeenScroll(nav);
  nav.addEventListener('click', e => { const b = e.target.closest && e.target.closest('button[data-t]'); if (b) tabGo(b.dataset.t); });
  nav.addEventListener('pointerdown', e => { const b = e.target.closest && e.target.closest('button[data-t]'); if (b && !b.dataset.vast) obPak(e, b.dataset.t, 'balk', b); });
  nav.addEventListener('contextmenu', e => { const b = e.target.closest && e.target.closest('button[data-t]'); if (!b || b.dataset.vast) return; e.preventDefault(); if (!obSleep && !obSolo()) obMenu(b.dataset.t); });
})();
addEventListener('taalchange', () => { obRender(); if (typeof current !== 'undefined' && current === 'homeedit') obEditRender(); });

obRender();
