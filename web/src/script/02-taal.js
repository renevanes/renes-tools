/* ---------- Taal: de app in het Nederlands (standaard) of in het Engels ----------
 * Nederlands is de brontekst in de code (zoals gettext): schrijf teksten gewoon in het Nederlands en zet ze in t().
 *   t('Opgeslagen')                          → "Saved" als de app in het Engels staat
 *   t('{n} berichten gewist', { n: 5 })      → plaatshouders {naam}; in het Nederlands alleen ingevuld
 *   tn(n, 'Eén bericht', '{n} berichten')    → enkelvoud of meervoud (n = 1), in beide talen
 * De vertalingen staan in web/src/taal/en.json (Nederlands → Engels; één woordenboek, ook voor Java: Taal.java).
 * Een waarde kan ook ["enkelvoud", "meervoud"] zijn (gekozen met {n}), voor als alleen het Engels het verschil maakt.
 * Vaste tekst in de schermen (HTML) hoeft niet in t(): als de app Engels is, vertaalt een MutationObserver elke tekst
 * en elk attribuut (placeholder, aria-label, title, alt) dat precies een sleutel uit het woordenboek is, ook wat later
 * met innerHTML of textContent in beeld komt. Een element met alleen tekst en opmaak (<b>, <i>, <a> …) wordt als
 * geheel vertaald (de sleutel is dan de HTML, bijv. "Tik op <b>Opslaan</b>."). Gebruikersgegevens (notities, berichten,
 * titels) staan in een element met translate="no", dan blijft het zoals het is.
 * In het Nederlands doet dit niets: het woordenboek wordt niet eens gelezen.
 * tests/ui/taal-dekking.test.js controleert dat elke tekst een Engelse vertaling heeft (zie AGENTS.md). */
var TAAL = { on: false, d: null, obs: null, busy: false };
/* Hoe een sleutel eruitziet: witruimte samengevoegd, zonder zachte afbreekstreepjes, zonder spaties aan de randen */
function taalNorm(s){ return String(s).replace(/­/g, '').replace(/[ \t\r\n]+/g, ' ').trim(); }
function taalDict(){
  if (!TAAL.d) {
    let d = {};
    try { const el = document.getElementById('taal-en'); if (el) d = JSON.parse(el.textContent) || {}; } catch (e) { d = {}; }
    TAAL.d = new Map(Object.entries(d));
  }
  return TAAL.d;
}
function taalId(){ try { return rgCfg().lang === 'en' ? 'en' : 'nl'; } catch (e) { return 'nl'; } }
/* Vul {naam} in (alleen namen die in v staan) */
function taalFill(s, v){ return v ? String(s).replace(/\{(\w+)\}/g, (m, k) => Object.prototype.hasOwnProperty.call(v, k) && v[k] != null ? String(v[k]) : m) : s; }
/* De vertaling van een sleutel, of undefined; houdt de witruimte aan de randen van de oorspronkelijke tekst vast */
function taalLookup(s, v){
  const raw = String(s), k = taalNorm(raw); if (!k) return undefined;
  let e = taalDict().get(k);
  if (e == null) return undefined;
  if (Array.isArray(e)) e = e[v && +v.n === 1 ? 0 : 1];
  const lead = /^\s*/.exec(raw)[0], trail = /\s*$/.exec(raw)[0];
  return lead + e + (trail.length < raw.length - lead.length ? trail : '');
}
function t(s, v){
  if (s == null) return '';
  if (TAAL.on) { const r = taalLookup(s, v); if (r !== undefined) return taalFill(r, v); }
  return taalFill(s, v);
}
/* Enkelvoud of meervoud: tn(3, 'Eén bestand', '{n} bestanden') → "3 bestanden" / "3 files" */
function tn(n, one, many, v){ return t(+n === 1 ? one : many, Object.assign({ n: n }, v)); }
/* Tekst in de taal van de app, voor de assistent en Java: 'nl' of 'en' */
function appLang(){ return TAAL.on ? 'en' : 'nl'; }

/* ---------- de schermen vertalen ---------- */
const TAAL_ATTRS = ['placeholder', 'aria-label', 'title', 'alt'];
const TAAL_INLINE = new Set(['BUTTON', 'B', 'I', 'EM', 'STRONG', 'SMALL', 'U', 'BR', 'CODE', 'KBD', 'SUP', 'SUB', 'A', 'SPAN', 'MARK', 'S', 'Q', 'ABBR', 'WBR']);
const TAAL_SKIP = new Set(['SCRIPT', 'STYLE', 'TEXTAREA', 'NOSCRIPT', 'SVG', 'svg', 'CODE', 'PRE']);
/* Element dat als geheel vertaald wordt: eigen tekst én opmaak met tekst, alleen opmaak-elementen, niets met een id */
function taalUnit(el){
  if (!el.firstElementChild || !el.firstChild) return false;
  let text = false, inl = false;
  for (let c = el.firstChild; c; c = c.nextSibling) {
    if (c.nodeType === 3) { if (/\S/.test(c.data)) text = true; }
    else if (c.nodeType === 1) { if (!taalInl(c)) return false; if (/\S/.test(c.textContent)) inl = true; }
  }
  if (!text || !inl) return false;
  for (const d of el.querySelectorAll('*')) if (!taalInl(d) || d.hasAttribute('translate')) return false;
  return true;
}
/* Opmaak zonder id; eenheden die Regio en taal zelf invult (rg-u-…) wisselen, die horen er niet bij */
function taalInl(e){ return TAAL_INLINE.has(e.tagName) && !e.id && !/\brg-u-/.test(e.className || ''); }
function taalSkip(el){ return el.nodeType === 1 && (TAAL_SKIP.has(el.tagName) || el.getAttribute('translate') === 'no' || el.isContentEditable); }
/* Alle sleutels van een stuk pagina (ook gebruikt door de dekkingstest): [soort, sleutel] */
function taalKeys(root){
  const out = [];
  const walk = n => {
    if (n.nodeType === 3) { const k = taalNorm(n.data); if (/\p{L}/u.test(k)) out.push(['tekst', k]); return; }
    if (n.nodeType !== 1 || taalSkip(n)) return;
    for (const a of TAAL_ATTRS) { const v = n.getAttribute(a); if (v && /\p{L}/u.test(v)) out.push(['attr', taalNorm(v)]); }
    if (n.tagName === 'INPUT' && /^(button|submit)$/i.test(n.type) && n.value) out.push(['attr', taalNorm(n.value)]);
    if (taalUnit(n)) { out.push(['html', taalNorm(n.innerHTML)]); return; }
    for (let c = n.firstChild; c; c = c.nextSibling) walk(c);
  };
  walk(root);
  return out;
}
/* Eén tekstknoop: de Nederlandse tekst onthouden (voor terug naar Nederlands) en de vertaling zetten */
function taalText(n){
  const d = n.data; if (d === n.__tEn || !/\p{L}/u.test(d)) return;
  const p = n.parentNode; if (!p || (p.nodeType === 1 && (taalSkip(p) || p.closest('[translate="no"]')))) return;
  const r = taalLookup(d); if (r === undefined) return;
  n.__tNl = d; n.__tEn = r; n.data = r;
}
function taalAttr(el, a){
  const v = el.getAttribute(a); if (!v || (el.__tEnA && el.__tEnA[a] === v)) return;
  const r = taalLookup(v); if (r === undefined) return;
  (el.__tNlA = el.__tNlA || {})[a] = v; (el.__tEnA = el.__tEnA || {})[a] = r;
  el.setAttribute(a, r);
}
function taalWalk(root){
  if (root.nodeType === 3) { taalText(root); return; }
  if (root.nodeType !== 1 && root.nodeType !== 11) return;
  if (root.nodeType === 1 && (taalSkip(root) || root.closest('[translate="no"]'))) return;
  const walk = n => {
    if (n.nodeType === 3) { taalText(n); return; }
    if (n.nodeType !== 1 && n.nodeType !== 11) return;
    if (n.nodeType === 1) {
      if (taalSkip(n)) return;
      for (const a of TAAL_ATTRS) if (n.hasAttribute(a)) taalAttr(n, a);
      if (n.tagName === 'INPUT' && /^(button|submit)$/i.test(n.type) && n.value) { const r = taalLookup(n.value); if (r !== undefined) { n.__tNlV = n.value; n.value = r; } }
      if (n.__tEnH !== undefined && n.innerHTML === n.__tEnH) return;
      if (taalUnit(n)) {
        const r = taalLookup(n.innerHTML);
        if (r !== undefined) { n.__tNlH = n.innerHTML; n.innerHTML = r; n.__tEnH = n.innerHTML; return; }
      }
    }
    for (let c = n.firstChild; c; c = c.nextSibling) walk(c);
  };
  walk(root);
}
function taalObserve(recs){
  if (TAAL.busy) return;
  TAAL.busy = true;
  try {
    const seen = new Set();
    for (const r of recs) {
      if (r.type === 'characterData') { taalText(r.target); continue; }
      if (r.type === 'attributes') { if (r.target.nodeType === 1) taalAttr(r.target, r.attributeName); continue; }
      // Nieuwe knopen: het ouder-element opnieuw bekijken (een geheel met opmaak kan net compleet zijn geworden)
      const p = r.target;
      if (!p || p.nodeType !== 1 || seen.has(p) || !p.isConnected) continue;
      if (taalUnit(p)) { seen.add(p); taalWalk(p); }
      else r.addedNodes.forEach(n => { if (n.isConnected) taalWalk(n); });
    }
  } catch (e) { }
  finally { TAAL.busy = false; taalObs().takeRecords(); }
}
function taalObs(){ return TAAL.obs || (TAAL.obs = new MutationObserver(taalObserve)); }
/* Alles terug naar de Nederlandse brontekst (wat nog vertaald in beeld staat) */
function taalRestore(root){
  const tw = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
  const els = [];
  for (let n = tw.currentNode; n; n = tw.nextNode()) els.push(n);
  for (const n of els) {
    if (n.nodeType === 3) { if (n.__tNl !== undefined && n.data === n.__tEn) n.data = n.__tNl; delete n.__tNl; delete n.__tEn; continue; }
    if (n.__tNlH !== undefined && n.innerHTML === n.__tEnH) n.innerHTML = n.__tNlH;
    delete n.__tNlH; delete n.__tEnH;
    if (n.__tNlA) { for (const a in n.__tNlA) if (n.getAttribute(a) === n.__tEnA[a]) n.setAttribute(a, n.__tNlA[a]); delete n.__tNlA; delete n.__tEnA; }
    if (n.__tNlV !== undefined) { n.value = n.__tNlV; delete n.__tNlV; }
  }
}
/* De gekozen taal toepassen (bij het starten en na een wijziging bij Regio en taal) */
function taalApply(){
  const on = taalId() === 'en';
  if (on === TAAL.on && (on ? TAAL.obs : true)) return false;
  TAAL.on = on;
  document.documentElement.lang = on ? 'en' : 'nl';
  if (on) {
    taalDict();
    TAAL.busy = true; try { taalWalk(document.body || document.documentElement); } finally { TAAL.busy = false; }
    taalObs().observe(document.documentElement, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: TAAL_ATTRS });
  } else {
    if (TAAL.obs) { TAAL.obs.disconnect(); TAAL.obs = null; }
    taalRestore(document.body || document.documentElement);
  }
  return true;
}
/* Alleen bij Engels iets doen: in het Nederlands kost dit niets */
if (taalId() === 'en') { if (document.body) taalApply(); else addEventListener('DOMContentLoaded', taalApply); }
addEventListener('regiochange', () => {
  if (!taalApply()) return;
  // Wat nu in beeld is opnieuw tekenen in de nieuwe taal (andere schermen tekenen zich bij het openen)
  try { if (typeof current !== 'undefined' && typeof show === 'function' && current !== 'setregio') show(current); } catch (e) { }
  try { window.dispatchEvent(new CustomEvent('taalchange', { detail: { lang: appLang() } })); } catch (e) { }
});
