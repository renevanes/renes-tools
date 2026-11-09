/* ---------- Extra ontgrendelen per tool ----------
 * Net als bij Stamgegevens: een tool waarvoor dit aan staat, vraagt bij het openen eerst de schermvergrendeling van de
 * telefoon (vingerafdruk, gezicht of pincode). Het scherm van de tool opent pas daarna. Hij gaat weer op slot zodra je
 * de tool verlaat of de app uit beeld gaat (ook als het scherm uitgaat); een eigen scherm van de app (kiezer, PDF-viewer)
 * telt hooguit twee minuten niet als weggaan (MainActivity.onStop/onStart, ToolSlot.KIES_MS).
 * Aan of uit per tool: Instellingen → Extra ontgrendelen, of lang indrukken van een tegel. Uitzetten vraagt de
 * schermvergrendeling (in Java, ToolSlot). Kluis, Bankzaken en Stamgegevens hebben hun eigen ontgrendeling (hun
 * sleutels werken pas daarna): die staan altijd aan. Nieuwe tools doen vanzelf mee: via TOOLS en de BACK-volgorde
 * naar het eerste scherm van de tool. Zolang een tool op slot is, tonen Overal zoeken en het startscherm er niets uit. */
// var: show() kan al tijdens het laden lopen; dan bestaat dit nog niet (en is er nog niets op slot)
var ts = { aan: [], secure: false, open: '', tool: '', doel: '', van: '', sprong: false, scherm: null, eigen: ['masterdata', 'kluis', 'bank'] };
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
/** Vraagt deze tool extra ontgrendelen? Zonder schermvergrendeling op de telefoon kan dat niet (dan zou je er niet meer in komen). */
function tsAan(tool){ return !!tool && !tsEigen(tool) && ts.secure && ts.aan.includes(tool); }
/** Staat de tool nu op slot? Buiten de tool altijd (weggaan zet hem op slot). */
function tsVerborgen(tool){ return !!ts && tsAan(tool) && ts.open !== tool; }
function tsZichtbaar(){ return $('#toolslot').classList.contains('on'); }

/** Vóór het wisselen van scherm: naar een tool op slot = eerst het slotscherm; het scherm zelf opent pas na ontgrendelen. */
function tsVoor(name){
  if (!ts) return false;
  const tool = tsTool(name);
  if (!tsVerborgen(tool) || tsTool(current) === tool) return false;
  ts.tool = tool; ts.doel = name; ts.van = current; ts.sprong = !!jumpTo.busy;
  tsToon();
  return true;
}
/** Na het wisselen van scherm: de tool verlaten = weer op slot */
function tsNa(){
  if (!ts) return;
  const tool = tsTool(current);
  if (ts.open && ts.open !== tool) ts.open = '';
  if (ts.doel && ts.tool !== tool && current !== ts.van) ts.doel = ''; // ergens anders heen gegaan: het slotscherm van die tool is niet meer nodig
  tsToon();
  if (current === 'home') tsTegels();
}
/** Het slotscherm tonen of weghalen, en Android laten weten of er een tool met extra ontgrendelen in beeld is (recente apps). */
function tsToon(){
  const hier = tsTool(current);
  const tool = ts.doel ? ts.tool : tsVerborgen(hier) ? hier : '';
  ts.tool = tool;
  const el = $('#toolslot'), on = !!tool;
  if (on) {
    const naam = t(TOOLS[tool][0]);
    $('#ts-naam').textContent = naam;
    $('#ts-titel').textContent = t('{naam} is vergrendeld', { naam });
    $('#ts-ic').innerHTML = uiToolIcon(tool) + '<span class="ts-slot">' + uiIcon('lock') + '</span>';
  }
  if (on !== el.classList.contains('on')) {
    if (on) { $('#ts-msg').textContent = ''; $('#ts-open').disabled = false; if (typeof gbCloseLight === 'function') gbCloseLight(); }
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
  if (ts.doel) { ts.doel = ''; tsToon(); return true; }
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
    ts.open = r.tool;
    const doel = ts.doel, van = ts.van, sprong = ts.sprong;
    ts.doel = '';
    if (doel) { show(doel); if (sprong && current !== van) jumpStack.push({ from: van, to: current }); }
    else tsToon();
    return;
  }
  tsLaad();
  if (r.ok) toast(r.soort === 'aan' ? t('{naam}: extra ontgrendelen aan', { naam }) : t('{naam}: extra ontgrendelen uit', { naam }));
  else if (r.msg) toast(t(r.msg));
  if (current === 'setslot') tsRender();
  if (current === 'settings') stSum('slot', tsStand());
  tsToon(); tsTegels();
};
/** De app ging uit beeld (Android): alles weer op slot */
window.onToolSlotWeg = function(){ if (!ts) return; ts.open = ''; tsToon(); };
(function(){
  const prev = window.onResumeApp;
  window.onResumeApp = function(){ if (prev) prev.apply(this, arguments); tsLaad(); tsToon(); tsTegels(); }; // schermvergrendeling kan intussen veranderd zijn
})();

/* ---------- Instellen ---------- */
function tsZet(tool, on){
  if (!TOOLS[tool] || tsEigen(tool)) return;
  Android.toolSlotSet(tool, !!on, t(TOOLS[tool][0]));
}
/** Korte stand voor het overzicht van Instellingen */
function tsStand(){
  tsLaad();
  const n = ts.aan.filter(k => !tsEigen(k)).length;
  if (n && !ts.secure) return t('Werkt pas met een schermvergrendeling');
  return n ? tn(n, 'Aan voor {n} tool', 'Aan voor {n} tools') : t('Uit');
}
function tsRender(){
  const box = $('#st-slot-list'); if (!box) return;
  tsLaad();
  $('#st-slot-geen').hidden = ts.secure;
  box.innerHTML = tsLijst().map(k => {
    const naam = t(TOOLS[k][0]);
    if (tsEigen(k)) return '<div class="sw"><span>' + esc(naam) + '<small>' + esc(t('Altijd: eigen ontgrendeling')) + '</small></span>' +
      '<button class="tg on" role="switch" aria-checked="true" disabled aria-label="' + esc(t('Extra ontgrendelen voor {naam}', { naam })) + '"></button></div>';
    const is = ts.aan.includes(k);
    return '<div class="sw"><span>' + esc(naam) + '</span><button class="tg' + (is ? ' on' : '') + '" role="switch" aria-checked="' + is + '" data-tool="' + k + '"' +
      (!ts.secure && !is ? ' disabled' : '') + ' aria-label="' + esc(t('Extra ontgrendelen voor {naam}', { naam })) + '" onclick="tsZet(this.dataset.tool, !this.classList.contains(\'on\'))"></button></div>';
  }).join('');
}
/** Keuze in het menu van een tegel (lang indrukken) */
function tsMenu(tool){
  if (!TOOLS[tool] || tsEigen(tool) || !document.getElementById('s-' + tool)) return [];
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
