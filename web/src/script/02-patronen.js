/* ---------- Interface-patronen (AGENTS.md, "Interface-patronen"; tests/ui/ui-patronen.test.js) ----------
 * Kleine hulpjes voor de gedeelde onderdelen. De vormgeving staat in ontwerp.css.
 *  - Hoe werkt dit?   <details class="help"><summary data-icon="info">Hoe werkt dit?</summary><div class="help-body">…</div></details>
 *                     (in een kaart: class="help in"); vanuit JavaScript: uiHelp(t('…'), html).
 *  - Instellingen     <button class="icon-btn hset" data-to="carset" aria-label="Instellingen van …"></button> in de kop.
 *  - Lege lijst       uiEmpty('mic', t('Nog geen opnames.'), t('Start opname'), 'recorderStart()').
 *  - Eenmalig instellen  uiSetup('#rc-setup', klaar): een <div class="card setup"> gaat weg, een <details class="card setup"> klapt in. */

/** "Hoe werkt dit?" als HTML (voor schermen die hun inhoud zelf opbouwen). body is al veilige HTML.
 *  key (data-k): naam waarmee uiHtml() een uitgeklapt blok na opnieuw tekenen weer openzet. */
function uiHelp(summary, body, inCard, key){
  return '<details class="help' + (inCard ? ' in' : '') + '"' + (key ? ' data-k="' + esc(key) + '"' : '') + '><summary data-icon="info" aria-expanded="false">' + esc(summary || t('Hoe werkt dit?')) + '</summary><div class="help-body">' + body + '</div></details>';
}
/** Lege lijst: pictogram, één regel en hooguit één actie (act = JavaScript voor onclick, met jsq() voor waarden). */
function uiEmpty(icon, text, label, act){
  return '<div class="empty">' + uiIcon(icon) + '<p>' + esc(text) + '</p>' + (label && act ? '<button class="big tonal" onclick="' + esc(act) + '">' + esc(label) + '</button>' : '') + '</div>';
}
/** Kaart voor eenmalig instellen bijwerken. Een <details> klapt alleen vanzelf in of uit als de stand verandert
 *  (wie hem zelf openklapt, houdt hem open tijdens het pollen). */
function uiSetup(el, done){
  if (typeof el === 'string') el = document.querySelector(el);
  if (!el) return;
  done = !!done;
  el.classList.toggle('done', done);
  if (el.tagName === 'DETAILS') { if (el._uiDone !== done) { el._uiDone = done; el.open = !done; uiHelpSync(el); } }
  else el.hidden = done;
}
/** innerHTML alleen als er iets veranderd is, en uitgeklapte blokken met een data-k (uiHelp(…, key)) blijven open,
 *  ook als er intussen een blok bij kwam of weg ging (bijv. tijdens het pollen). */
function uiHtml(el, html){
  if (!el || el._h === html) return;
  const open = new Set([...el.querySelectorAll('details[data-k]')].filter(d => d.open).map(d => d.dataset.k));
  el.innerHTML = html; el._h = html;
  el.querySelectorAll('details').forEach(d => { if (d.dataset.k && open.has(d.dataset.k)) d.open = true; uiHelpSync(d); });
}
/* Uitklappers: de schermlezer hoort "ingeklapt"/"uitgeklapt" (aria-expanded op de summary). */
function uiHelpSync(d){ const s = d && d.querySelector(':scope > summary'); if (s) s.setAttribute('aria-expanded', d.open ? 'true' : 'false'); }
document.addEventListener('toggle', e => { const d = e.target; if (d && d.tagName === 'DETAILS' && d.matches('.help, .setup')) uiHelpSync(d); }, true);
/** Ingeklapte blokken (<details>, bijv. Geavanceerd) rond el openklappen, zodat zoeken en de assistent het kunnen aanwijzen. */
function uiOpenTo(el){ for (let d = el && el.closest('details'); d; d = d.parentElement && d.parentElement.closest('details')) if (!d.open) { d.open = true; uiHelpSync(d); } }
/** Knoppen in de kop: schuifjes (instellingen van de tool) krijgen hun pictogram en openen hun scherm. */
function uiPatterns(root){
  (root || document).querySelectorAll('button.hset[data-to]').forEach(b => {
    if (!b.firstElementChild) b.innerHTML = '<span class="ui-icon" data-icon="settings" aria-hidden="true"></span>';
    if (!b.onclick) b.onclick = () => show(b.dataset.to);
  });
  (root || document).querySelectorAll('details.help, details.setup').forEach(uiHelpSync);
}
uiPatterns();

/* Voortgang van een bestand toevoegen (boek, document, factuur, afschriften, foto's): de balk onderin.
   Java (Voortgang.java) stuurt onVoortgang({soort, stap: lezen|verwerken|opslaan|herkennen, gedaan, totaal, bestand, bestanden})
   en aan het eind {soort, klaar}. Pas na 400 ms zichtbaar, zodat een klein bestand niet flitst. Alleen stappen en
   aantallen, nooit namen. */
function uiVgWat(soort){
  if (soort === 'bezig') return uiVg.bezig;
  return ({ ereader: tc('voortgang', 'Boek toevoegen'), kluis: tc('voortgang', 'Document toevoegen'), factuur: tc('voortgang', 'Factuur toevoegen'), bank: t('Afschriften inlezen'), pdf: t('Bestanden toevoegen'), idkopie: t("Pagina's toevoegen"), handtekening: t('Handtekening inlezen'), kaartfoto: t('Kaartfoto verwerken'), terugzetten: t('Backup bekijken') })[soort] || t('Bestand toevoegen');
}
const uiVg = { soort: '', toon: 0, wacht: 0, kop: '', bezig: '' };
function uiVgStand(r){
  if (r.stap === 'bezig') return r.totaal > 0 ? fmtNum(Math.min(100, Math.floor(100 * r.gedaan / r.totaal))) + '%' : '';
  if (r.stap === 'lezen' && r.totaal > 0) return t('Lezen… {p}%', { p: fmtNum(Math.min(100, Math.floor(100 * r.gedaan / r.totaal))) });
  if (r.stap === 'lezen' && r.gedaan > 0) return t('Lezen… {grootte}', { grootte: fmtSize(r.gedaan) });
  if (r.stap === 'lezen') return t('Lezen…');
  if (r.stap === 'opslaan' && r.totaal > 0) return t('Opslaan… {n} van {m}', { n: fmtNum(Math.min(r.gedaan, r.totaal)), m: fmtNum(r.totaal) });
  if (r.stap === 'herkennen') { const s = Math.ceil((r.totaal - r.gedaan) / 1000); return r.totaal > 0 && s > 0 ? t('Gegevens herkennen… nog hooguit {n} s', { n: fmtNum(s) }) : t('Gegevens herkennen…'); }
  return r.stap === 'opslaan' ? t('Opslaan…') : t('Verwerken…');
}
function uiVgVerberg(){ clearTimeout(uiVg.toon); clearTimeout(uiVg.wacht); uiVg.toon = uiVg.wacht = 0; uiVg.soort = ''; $('#vg').hidden = true; }
// Een scherm of venster dat de voortgang van een soort zelf toont: uiVgEigen[soort](r) geeft true, dan geen balk onderin.
const uiVgEigen = {};
function uiVoortgang(r){
  if (!r || !r.soort) return;
  if (uiVgEigen[r.soort] && uiVgEigen[r.soort](r) === true) { if (r.soort === uiVg.soort) uiVgVerberg(); return; }
  if (r.klaar) { if (r.soort === uiVg.soort) uiVgVerberg(); return; }
  // Een andere soort (twee toevoegingen tegelijk) neemt de balk over; de wachttijd begint niet opnieuw.
  if (r.soort !== uiVg.soort) {
    uiVg.soort = r.soort;
    if ($('#vg').hidden && !uiVg.toon) uiVg.toon = setTimeout(() => {
      uiVg.toon = 0; if (!uiVg.soort) return;
      $('#vg').hidden = false;
      // De kop opnieuw zetten nu hij zichtbaar is, zodat een schermlezer hem één keer voorleest.
      const w = $('#vg-wat'); w.textContent = ''; requestAnimationFrame(() => { w.textContent = uiVg.kop || ''; });
    }, 400);
  }
  // Komt het eind nooit (de app herstart de interface), dan na 5 minuten stilte weg (uiBezig: 15 minuten, dat meldt niets tussendoor).
  clearTimeout(uiVg.wacht); uiVg.wacht = setTimeout(uiVgVerberg, r.soort === 'bezig' ? 900000 : 300000);
  const wat = uiVgWat(r.soort);
  const kop = r.bestanden > 1 && r.bestand > 0 ? t('{wat}: bestand {n} van {m}', { wat, n: fmtNum(r.bestand), m: fmtNum(r.bestanden) }) : wat;
  uiVg.kop = kop; if ($('#vg-wat').textContent !== kop) $('#vg-wat').textContent = kop;
  $('#vg-stand').textContent = uiVgStand(r);
  const p = $('#vg-prog'), num = r.totaal > 0 && r.stap !== 'verwerken';
  p.classList.toggle('busy', !num);
  if (num) { const pct = Math.min(100, Math.floor(100 * r.gedaan / r.totaal)); p.querySelector('i').style.width = pct + '%'; p.setAttribute('aria-valuenow', pct); }
  else { p.querySelector('i').style.width = ''; p.removeAttribute('aria-valuenow'); }
}
window.onVoortgang = uiVoortgang;

/* Wachten zonder eigen plek op het scherm (wat vroeger alleen een melding gaf, zoals terugzetten of exporteren): de balk
   onderin zegt waarom, tot het klaar is. tot: de naam (of een lijst namen) van de terugroepfunctie die het einde meldt (bijv. 'onRestoreDone'),
   dan gaat de balk vanzelf weg; anders (of ook) de teruggegeven functie aanroepen. Een volgende uiBezig neemt het over. */
const uiBezigTot = {};
function uiBezig(tekst, tot){
  const nr = ++uiVg.bezigNr;
  const klaar = () => { if (nr === uiVg.bezigNr && uiVg.soort === 'bezig') uiVgVerberg(); };
  uiVg.bezig = tekst; uiVoortgang({ soort: 'bezig', stap: 'bezig', gedaan: 0, totaal: 0, bestand: 1, bestanden: 1 });
  for (const naam of [].concat(tot || [])) {
    const f = window[naam];
    if (typeof f === 'function' && !f.uiBezig) {
      const w = function(...a){ const k = uiBezigTot[naam] || []; uiBezigTot[naam] = []; k.forEach(x => x()); return f.apply(this, a); };
      w.uiBezig = true; window[naam] = w;
    }
    (uiBezigTot[naam] = uiBezigTot[naam] || []).push(klaar);
  }
  return klaar;
}
uiVg.bezigNr = 0;
/* Voor een brugfunctie die de interface laat wachten tot hij klaar is (exporteren): de balk meteen tonen, eerst laten
   tekenen en dan pas beginnen. Geeft wat fn teruggaf (als belofte). */
function uiBezigNu(tekst, fn){
  const klaar = uiBezig(tekst), w = $('#vg-wat');
  clearTimeout(uiVg.toon); uiVg.toon = 0; $('#vg').hidden = false; w.textContent = '';
  // Zoals in uiVoortgang: de kop pas na het tonen zetten, zodat een schermlezer hem voorleest voordat de app even vastzit.
  return new Promise((ok, nee) => requestAnimationFrame(() => { w.textContent = uiVg.kop || ''; setTimeout(() => { try { ok(fn()); } catch (e) { nee(e); } finally { klaar(); } }, 30); }));
}
/* Een statusregel die al zegt waarop je wacht ("Zoeken…", "PDF maken…"): met een balk eronder die heen en weer loopt. Wordt
   de tekst daarna iets anders (het antwoord, een fout), dan gaat de balk vanzelf weg; uiWachtTekst(el, '') ook. Bijwerken
   met een nieuwe stand: opnieuw uiWachtTekst. Een plaatshouder die bij het antwoord helemaal vervangen wordt
   ('<p class="note wacht">Zoeken…</p>' in een lijst) kan ook gewoon de klasse wacht krijgen. */
let uiWachtObs = null;
function uiWachtTekst(el, tekst){
  if (!el) return;
  el.textContent = tekst || '';
  if (!tekst) { uiWachtUit(el); return; }
  el.dataset.wacht = el.textContent; el.classList.add('wacht');
  if (!uiWachtObs) {
    uiWachtObs = new MutationObserver(() => {
      for (const e of document.querySelectorAll('.wacht[data-wacht]')) if (e.textContent !== e.dataset.wacht && e.textContent !== t(e.dataset.wacht)) uiWachtUit(e);
      if (!document.querySelector('.wacht[data-wacht]')) { uiWachtObs.disconnect(); uiWachtObs = null; }
    });
    uiWachtObs.observe(document.body, { subtree: true, childList: true, characterData: true });
  }
}
function uiWachtUit(el){ el.classList.remove('wacht'); delete el.dataset.wacht; }
/* Wachten op iets wat je zelf startte (inlezen, herkennen, zoeken, ophalen) op de plek waar je het startte: één regel waarom,
   met een balk die heen en weer loopt, of met deel (0–1) hoe ver het is. el: een lege <div class="ui-wacht" hidden>;
   uiWacht(el, false) haalt hem weg. Duurt het ergens anders (Java, een bestand), gebruik dan ook Voortgang. */
function uiWacht(el, tekst, deel){
  if (!el) return;
  if (!tekst) { el.hidden = true; el.replaceChildren(); return; }
  let regel = el.querySelector('.ui-wacht-t'), balk = el.querySelector('.upd-prog');
  if (!regel) {
    regel = document.createElement('span'); regel.className = 'ui-wacht-t';
    balk = document.createElement('div'); balk.className = 'upd-prog'; balk.setAttribute('role', 'progressbar');
    balk.setAttribute('aria-valuemin', '0'); balk.setAttribute('aria-valuemax', '100'); balk.append(document.createElement('i'));
    el.setAttribute('role', 'status'); el.replaceChildren(regel, balk);
  }
  if (regel.textContent !== tekst) { regel.textContent = tekst; balk.setAttribute('aria-label', tekst); }
  const num = typeof deel === 'number' && isFinite(deel);
  balk.classList.toggle('busy', !num);
  if (num) { const pct = Math.max(0, Math.min(100, Math.floor(100 * deel))); balk.firstChild.style.width = pct + '%'; balk.setAttribute('aria-valuenow', pct); }
  else { balk.firstChild.style.width = ''; balk.removeAttribute('aria-valuenow'); }
  el.hidden = false;
}
