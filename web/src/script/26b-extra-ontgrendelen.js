/* ---------- Extra ontgrendelen per tool ----------
 * Net als bij Stamgegevens: een tool waarvoor dit aan staat, vraagt bij het openen eerst de schermvergrendeling van de
 * telefoon (vingerafdruk, gezicht of pincode). Het scherm van de tool opent pas daarna. Hij gaat weer op slot zodra je
 * de tool verlaat of de app uit beeld gaat (ook als het scherm uitgaat); een scherm dat de app zelf opent en waarvan hij
 * een antwoord verwacht (kiezer, camera, PDF-viewer, instellingen van Android) telt hooguit twee minuten niet als
 * weggaan, net als bij het app-slot (MainActivity.onStop/onStart, ToolSlot.KIES_MS).
 * Wat bij het openen hoort (een vraag uit een melding, de speler): via tsDaarna, pas na ontgrendelen.
 * Aan of uit per tool: Instellingen → Extra ontgrendelen, of lang indrukken van een tegel. Uitzetten vraagt de
 * schermvergrendeling (in Java, ToolSlot). Nieuwe tools doen vanzelf mee: via TOOLS en de BACK-volgorde naar het eerste
 * scherm van de tool. Zolang een tool op slot is, tonen Overal zoeken en het startscherm er niets uit; alleen wat er
 * speelt (spelerbalk, net als de melding van Android) blijft zichtbaar.
 * Dit is het enige slotscherm: geen tool vraagt zelf om te ontgrendelen of heeft een eigen slotscherm. Kluis, Bankzaken
 * en Stamgegevens (ts.eigen) staan standaard aan (tsEigenUit = uitgezet). Hun gegevens zijn versleuteld; Java opent ze
 * alleen meteen na dit slotscherm (een ontgrendeling geldt één keer, voor die tool) of als het uit staat. De sleutels van
 * Bankzaken en Stamgegevens werken alleen kort na het ontgrendelen van de telefoon (Ontgrendeld.java): is dat te lang
 * geleden, of gaat de tool zelf op slot terwijl je erin bent (na een tijd niets doen), dan vraagt de tool via tsVraag
 * dit slotscherm over wat je deed (ook midden in iets anders, zoals een keuzelijst in Formulieren). */
// var: show() kan al tijdens het laden lopen; dan bestaat dit nog niet (en is er nog niets op slot)
var ts = { aan: [], secure: false, open: '', tool: '', doel: '', van: '', sprong: false, daarna: null, lagen: [], getoond: '', scherm: null, vraag: null, eigen: ['masterdata', 'kluis', 'bank'] };
function tsLaad(){
  let s = {}; try { s = JSON.parse(Android.toolSlotState()) || {}; } catch (e) { }
  ts.aan = (Array.isArray(s.aan) ? s.aan : []).filter(k => Object.prototype.hasOwnProperty.call(TOOLS, k));
  ts.secure = !!s.secure;
}
/** De tool waar dit scherm bij hoort (via de terug-volgorde naar zijn eerste scherm), of '' */
function tsTool(name){
  for (let n = name, i = 0; n && i < 12; n = BACK[n], i++) if (Object.prototype.hasOwnProperty.call(TOOLS, n) && document.getElementById('s-' + n)) return n;
  return '';
}
/** Tools met een eigen scherm in de app, op naam */
function tsLijst(){ return Object.keys(TOOLS).filter(k => document.getElementById('s-' + k)).sort((a, b) => t(TOOLS[a][0]).localeCompare(t(TOOLS[b][0]))); }
function tsEigen(tool){ return ts.eigen.includes(tool); }
/** Kluis, Bankzaken of Stamgegevens met extra ontgrendelen uit: opent zonder slotscherm */
function tsEigenUit(tool){ return !!ts && tsEigen(tool) && !ts.aan.includes(tool); }
/** Mag zo'n tool nu openen? Niet onder het app-slot of een slotscherm (Java kijkt zelf ook). */
function tsVanzelfMag(){ const app = document.getElementById('lockscr'); return !tsZichtbaar() && !(app && app.classList.contains('on')); }
/** Vraagt deze tool extra ontgrendelen? Zonder schermvergrendeling op de telefoon kan dat niet (dan zou je er niet meer in komen). */
function tsAan(tool){ return !!tool && ts.secure && ts.aan.includes(tool); }
/** Staat de tool nu op slot? Buiten de tool altijd (weggaan zet hem op slot). */
function tsVerborgen(tool){ return !!ts && tsAan(tool) && ts.open !== tool; }
function tsZichtbaar(){ return $('#toolslot').classList.contains('on'); }

/** Vóór het wisselen van scherm: naar een tool op slot = eerst het slotscherm; het scherm zelf opent pas na ontgrendelen. */
function tsVoor(name){
  if (!ts) return false;
  const tool = tsTool(name);
  if (!tsVerborgen(tool) || tsTool(current) === tool) return false;
  if (!ts.doel || ts.tool !== tool) ts.lagen = tsLagen();
  ts.tool = tool; ts.doel = name; ts.van = current; ts.sprong = !!jumpTo.busy;
  tsToon();
  return true;
}
/** Staat het slotscherm van deze tool nu in beeld (voor het openen, of over de tool heen)? */
function tsWacht(tool){ return !!ts && !!tool && tsZichtbaar() && ts.tool === tool; }
/** Wat bij het openen van een tool hoort (een vraag, een zoekactie): staat die tool op slot, dan pas na ontgrendelen. */
function tsDaarna(tool, fn){
  if (tsWacht(tool)) ts.daarna = fn;
  else fn();
}
/**
 * Kluis, Bankzaken of Stamgegevens kan nu niet open zonder (nieuwe) ontgrendeling: het slotscherm over wat je deed.
 * Na ontgrendelen daarna() (die opent de tool, met die ontgrendeling); terug = terug() (bijv. een keuzelijst sluiten),
 * of zonder terug: de tool verlaten. msg = waarom (zichtbaar op het slotscherm). Zonder schermvergrendeling kan het
 * niet: dan false (de tool zegt dat zelf).
 */
function tsVraag(tool, daarna, terug, msg){
  if (!ts || !TOOLS[tool]) return false;
  tsLaad();
  if (!ts.secure) return false;
  ts.vraag = { tool, daarna: daarna || null, terug: terug || null, bij: tsTool(current) };
  tsToon();
  if (msg && !ts.doel) $('#ts-msg').textContent = msg;
  return true;
}
/* Vensters die terug sluit. Wat onder het slotscherm openging (of openstond in de tool die je verlaat), mag na terug
 * niet zichtbaar worden zonder ontgrendelen. */
const TS_LAGEN = [
  ['picker', () => typeof cm !== 'undefined' && cm.picker, () => cmClosePicker()],
  ['fmq', () => typeof fmq !== 'undefined' && fmq.open, () => fmqClose()],
  ['modal', () => $('#modal').classList.contains('on'), () => closeModal(null)],
  ['sigdlg', () => $('#sigdlg').classList.contains('on'), () => sigClose()],
  ['voice', () => !!$('#voice') && $('#voice').classList.contains('on'), () => voiceClose()],
  ['ci-ans', () => !!$('#ci-ans') && $('#ci-ans').classList.contains('on'), () => ciAnsClose()],
  ['ci-rem', () => !!$('#ci-rem') && $('#ci-rem').classList.contains('on'), () => ciRemPark()], // wat je schreef blijft als concept
  ['sleepdlg', () => $('#sleepdlg').classList.contains('on'), () => sleepClose()],
  ['vp-scan', () => !!$('#vp-scan') && $('#vp-scan').classList.contains('on'), () => vpScanClose()],
  ['sheet', () => $('#sheet').classList.contains('on'), () => closeSheet()],
  ['er-image', () => $('#er-image').classList.contains('on'), () => erImageClose()],
  ['player', () => typeof plIsOpen !== 'undefined' && plIsOpen, () => plClose()]
];
function tsLagen(){ return TS_LAGEN.filter(l => { try { return l[1](); } catch (e) { return false; } }).map(l => l[0]); }
function tsLagenDicht(behalve){
  for (let i = 0; i < 3; i++) { // een venster kan bij sluiten een volgend openen (annuleren met een vraag)
    let dicht = false;
    for (const l of TS_LAGEN) {
      if (behalve && behalve.includes(l[0])) continue;
      try { if (l[1]()) { l[2](); dicht = true; } } catch (e) { }
    }
    if (!dicht) return;
  }
}
/** Na het wisselen van scherm: de tool verlaten = weer op slot */
function tsNa(){
  if (!ts) return;
  const tool = tsTool(current);
  if (ts.open && ts.open !== tool) ts.open = '';
  if (ts.doel && ts.tool !== tool && current !== ts.van) ts.doel = ''; // ergens anders heen gegaan: het slotscherm van die tool is niet meer nodig
  if (ts.vraag && ts.vraag.bij !== tool) ts.vraag = null; // de vraag hoorde bij wat je in een andere tool deed
  tsToon();
  if (current === 'home') tsTegels();
}
/** Het slotscherm tonen of weghalen, en Android laten weten of er een tool met extra ontgrendelen in beeld is (recente apps). */
function tsToon(){
  const hier = tsTool(current);
  const tool = ts.doel ? ts.tool : ts.vraag ? ts.vraag.tool : tsVerborgen(hier) ? hier : '';
  ts.tool = tool;
  const el = $('#toolslot'), on = !!tool;
  if (on && (tool !== ts.getoond || !el.classList.contains('on'))) {
    const naam = t(TOOLS[tool][0]);
    $('#ts-naam').textContent = naam;
    $('#ts-titel').textContent = t('{naam} is vergrendeld', { naam });
    $('#ts-ic').innerHTML = uiToolIcon(tool) + '<span class="ts-slot">' + uiIcon('lock') + '</span>';
    // Standaard waarom, tot een tool een eigen reden zet (tsVraag, ná deze functie). Bij Stamgegevens stond dit
    // vroeger op het eigen slotscherm: versleuteld, en zonder dit toestel niet terug te halen.
    $('#ts-msg').textContent = tool === 'masterdata' ? t('Versleuteld op dit toestel en niet in back-ups: zonder dit toestel zijn je stamgegevens niet te herstellen.') : '';
    $('#ts-open').disabled = false; // een vraag voor een andere tool telt hier niet
  }
  if (tool !== ts.getoond) ts.daarna = null; // wat wachtte, hoort bij het slotscherm van een andere tool
  ts.getoond = tool;
  if (on !== el.classList.contains('on')) {
    if (on && typeof gbCloseLight === 'function') gbCloseLight();
    el.classList.toggle('on', on);
    if (on) dialogFocus.sync($('#ts-open'));
  }
  const scherm = on || tsAan(hier);
  if (scherm !== ts.scherm) { ts.scherm = scherm; try { Android.toolSlotScherm(scherm); } catch (e) { } }
}
function tsOntgrendel(){
  if (!ts.tool) return;
  $('#ts-msg').textContent = '';
  $('#ts-open').disabled = true;
  Android.toolSlotUnlock(ts.tool, t(TOOLS[ts.tool][0]));
}
/** Terug op het slotscherm: niet openen (bleef je nog waar je was), of de tool verlaten. */
function tsTerug(){
  if (!ts || !tsZichtbaar()) return false;
  ts.daarna = null;
  if (ts.doel) { // je blijft waar je was: een tool die daar intussen op slot ging (Kluis, Bankzaken, Stamgegevens), opent weer of vraagt het
    ts.doel = ''; tsLagenDicht(ts.lagen); tsToon();
    if (typeof window.onToolSlotVrij === 'function') setTimeout(window.onToolSlotVrij, 0);
    return true;
  }
  const v = ts.vraag; ts.vraag = null;
  if (v && v.terug) { tsToon(); v.terug(); return true; }
  tsLagenDicht();
  const tool = tsTool(current), j = jumpStack[jumpStack.length - 1];
  if (j && j.to === current && tsTool(j.from) !== tool) { jumpStack.pop(); show(j.from); return true; }
  let n = current;
  for (let i = 0; i < 12 && tsTool(n) === tool; i++) n = BACK[n] || 'home';
  show(tsTool(n) === tool ? 'home' : n);
  return true;
}
/** Antwoord van Android: ontgrendeld (soort open) of aan/uit gezet */
window.onToolSlot = function(r){
  r = r || {};
  const tt = TOOLS[r.tool]; if (!tt) return;
  const naam = t(tt[0]);
  if (r.soort === 'open') {
    if (r.tool !== ts.tool || !tsZichtbaar()) return; // intussen ergens anders heen
    $('#ts-open').disabled = false;
    if (!r.ok) { $('#ts-msg').textContent = r.msg ? t(r.msg) : t('Niet ontgrendeld'); return; }
    const v = !ts.doel && ts.vraag && ts.vraag.tool === r.tool ? ts.vraag : null;
    if (v) { // midden in iets: verder waar je was (een tool die je net verliet, blijft dicht)
      ts.vraag = null;
      if (tsTool(current) === r.tool) ts.open = r.tool;
      tsToon();
      if (v.daarna) v.daarna();
      return;
    }
    ts.open = r.tool;
    const doel = ts.doel, van = ts.van, sprong = ts.sprong, daarna = ts.daarna;
    ts.doel = ''; ts.daarna = null;
    if (doel) {
      jumpTo.busy = sprong; // een sprong (zoeken, startscherm) houdt zijn filter in de tool
      try { show(doel); } finally { jumpTo.busy = false; }
      if (sprong && current !== van) jumpStack.push({ from: van, to: current });
    }
    else tsToon();
    if (daarna && tsTool(current) === r.tool && !tsZichtbaar()) daarna();
    return;
  }
  tsLaad();
  if (r.ok) toast(r.soort === 'aan' ? t('{naam}: extra ontgrendelen aan', { naam }) : t('{naam}: extra ontgrendelen uit', { naam }));
  else if (r.msg) toast(t(r.msg));
  if (current === 'setslot') tsRender();
  if (current === 'settings') stSum('slot', tsStand());
  tsToon(); tsTegels();
};
/** De app ging uit beeld (Android): alles weer op slot. Tijdens een vraag om te ontgrendelen (soort 'vraag') alleen als
 * die vraag voor een andere tool is: een vraag binnen de open tool (bijv. iets tonen) laat hem open. */
window.onToolSlotWeg = function(soort){
  if (!ts || (soort === 'vraag' && !ts.doel)) return;
  ts.open = '';
  const v = ts.vraag; // een vraag midden in iets anders (een keuzelijst): dat gaat niet meer verder
  if (v && v.terug) { ts.vraag = null; tsToon(); v.terug(); return; }
  tsToon();
};
(function(){
  const prev = window.onResumeApp;
  window.onResumeApp = function(){ if (prev) prev.apply(this, arguments); tsLaad(); tsToon(); tsTegels(); }; // schermvergrendeling kan intussen veranderd zijn
})();

/* ---------- Instellen ---------- */
function tsZet(tool, on){
  if (!TOOLS[tool]) return;
  Android.toolSlotSet(tool, !!on, t(TOOLS[tool][0]));
}
/** Korte stand voor het overzicht van Instellingen */
function tsStand(){
  tsLaad();
  const n = ts.aan.length;
  if (n && !ts.secure) return t('Werkt pas met een schermvergrendeling');
  return n ? tn(n, 'Aan voor {n} tool', 'Aan voor {n} tools') : t('Uit');
}
/** Wat uitzetten betekent bij Kluis, Bankzaken en Stamgegevens: kort en zichtbaar (het gaat om een veilige keuze) */
function tsEigenUitleg(tool){
  if (!tsEigen(tool)) return '';
  if (tsEigenUit(tool)) return tool === 'kluis' ? t('Opent zonder vraag, blijft versleuteld') : t('Geen vraag als je telefoon net ontgrendeld is');
  return tool === 'kluis' ? t('Uit: opent zonder vraag, blijft versleuteld') : t('Uit: geen vraag als je telefoon net ontgrendeld is');
}
function tsRender(){
  const box = $('#st-slot-list'); if (!box) return;
  tsLaad();
  $('#st-slot-geen').hidden = ts.secure;
  box.innerHTML = tsLijst().map(k => {
    const naam = t(TOOLS[k][0]), is = ts.aan.includes(k), uitleg = tsEigen(k) ? '<small>' + esc(tsEigenUitleg(k)) + '</small>' : '';
    return '<div class="sw"><span>' + esc(naam) + uitleg + '</span><button class="tg' + (is ? ' on' : '') + '" role="switch" aria-checked="' + is + '" data-tool="' + k + '"' +
      (!ts.secure && !is ? ' disabled' : '') + ' aria-label="' + esc(t('Extra ontgrendelen voor {naam}', { naam })) + '" onclick="tsZet(this.dataset.tool, !this.classList.contains(\'on\'))"></button></div>';
  }).join('');
}
/** Keuze in het menu van een tegel (lang indrukken) */
function tsMenu(tool){
  if (!TOOLS[tool] || !document.getElementById('s-' + tool)) return [];
  tsLaad();
  return [[ts.aan.includes(tool) ? 'Extra ontgrendelen uitzetten…' : 'Extra ontgrendelen aanzetten', () => tsZet(tool, !ts.aan.includes(tool))]];
}
/** Tegels op het startscherm: van een tool op slot geen stand eronder, wel een slotje */
function tsTegels(){
  let tegels; try { tegels = TILE_TOOL; } catch (e) { return; } // tijdens het laden bestaat het tegeloverzicht nog niet
  document.querySelectorAll('.tile[id^="tile-"]').forEach(el => {
    const tool = tegels[el.id], dicht = !!tool && tsAan(tool), ic = el.querySelector('.ic');
    el.classList.toggle('ts-dicht', dicht);
    const slot = ic && ic.querySelector('.ts-tegel');
    if (dicht && ic && !slot) ic.insertAdjacentHTML('beforeend', '<span class="ts-tegel">' + uiIcon('lock') + '</span>');
    else if (!dicht && slot) slot.remove();
  });
}
tsLaad();
setTimeout(tsTegels, 0);
