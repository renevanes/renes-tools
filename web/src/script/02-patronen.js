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
