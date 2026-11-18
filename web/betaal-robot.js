/* Betalen, klaarzetten bij de bank: de "robot" die in de website van de bank draait (BetaalLinkActivity, altijd in beeld).
 * Hij logt NOOIT zelf in, vult geen wachtwoorden of codes in en verstuurt of ondertekent NOOIT iets: hij gaat alleen naar
 * het formulier voor een overboeking en vult daar de naam, het IBAN, het bedrag en het kenmerk of de omschrijving in.
 * Controleren, versturen en ondertekenen doe je zelf. Zodra het formulier in beeld is geweest klikt hij nergens meer op.
 * Getest in een gewone browser (tests/ui/betaal-robot.test.js) tegen een nagebootste bank. */
(function(){
  if (window.__bt) return;

  function zichtbaar(el){
    if (!el || el.disabled) return false;
    const r = el.getBoundingClientRect(), s = getComputedStyle(el);
    return r.width > 1 && r.height > 1 && s.visibility !== 'hidden' && s.display !== 'none' && Number(s.opacity) > 0.05;
  }
  function alle(sel, root, out){
    out = out || []; root = root || document;
    try {
      root.querySelectorAll(sel).forEach(e => out.push(e));
      root.querySelectorAll('*').forEach(e => { if (e.shadowRoot) alle(sel, e.shadowRoot, out); });
      root.querySelectorAll('iframe').forEach(f => { try { if (f.contentDocument) alle(sel, f.contentDocument, out); } catch (e) { } });
    } catch (e) { }
    return out;
  }
  const tekst = el => ((el.innerText || el.textContent || el.value || el.getAttribute('aria-label') || el.title || '') + '').replace(/\s+/g, ' ').trim();
  const re = s => { try { return s ? new RegExp(s, 'i') : null; } catch (e) { return null; } };

  // Alleen navigatie: links, menu's en tabbladen, en knoppen die geen formulier versturen. Nooit een knop in een formulier.
  const NAVIGATIE = 'a, [role=link], [role=menuitem], [role=tab], button, [role=button]';
  function navigatie(patroon){
    const r = re(patroon); if (!r) return null;
    return alle(NAVIGATIE).find(e => zichtbaar(e) && !e.closest('form') && (e.getAttribute('type') || '').toLowerCase() !== 'submit'
      && tekst(e).length < 60 && r.test(tekst(e))) || null;
  }
  const geklikt = new Map(); // per soort hoe vaak (geen eindeloze lussen)
  function eenKeer(naam, e, max){
    const n = geklikt.get(naam) || 0; if (n >= max) return false;
    geklikt.set(naam, n + 1);
    try { e.scrollIntoView({ block: 'center' }); } catch (x) { }
    e.click(); return true;
  }

  const VELDEN = 'input:not([type=hidden]):not([type=password]):not([type=checkbox]):not([type=radio]):not([type=submit]):not([type=button]):not([type=file]):not([type=date]), textarea';
  function label(i){
    const root = i.getRootNode && i.getRootNode() || document, delen = [];
    const voeg = s => { if (s) delen.push(String(s)); };
    voeg(i.getAttribute('aria-label'));
    (i.getAttribute('aria-labelledby') || '').split(/\s+/).filter(Boolean).forEach(id => { try { const l = root.getElementById ? root.getElementById(id) : document.getElementById(id); if (l) voeg(tekst(l)); } catch (e) { } });
    if (i.id) { try { const l = root.querySelector('label[for="' + CSS.escape(i.id) + '"]'); if (l) voeg(tekst(l)); } catch (e) { } }
    const om = i.closest('label'); if (om) voeg(tekst(om));
    voeg(i.placeholder); voeg(i.name); voeg(i.id);
    return delen.join(' | ').slice(0, 300);
  }
  function veld(patroon, niet){
    const r = re(patroon); if (!r) return null;
    return alle(VELDEN).find(i => zichtbaar(i) && !i.readOnly && !niet.includes(i) && r.test(label(i))) || null;
  }
  function zet(el, waarde){
    const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    try { el.focus(); } catch (e) { }
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, waarde);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    try { el.blur(); } catch (e) { }
    el.dataset.btGezet = '1';
  }
  const kaal = s => String(s || '').replace(/\s+/g, '').toUpperCase();
  const getal = s => { const v = String(s || '').replace(/[€\s]/g, ''); return Number(/,\d{1,2}$/.test(v) ? v.replace(/\./g, '').replace(',', '.') : v); };

  window.__bt = {
    /**
     * Eén stap. cfg = zoekpatronen (en geenNavigatie zodra het formulier in beeld is geweest), b = {naam, iban, bedrag,
     * kenmerk, omschrijving}. Geeft wat er gebeurde: inlog | menu | naar | invullen | ingevuld | controleer | wacht.
     */
    stap(cfg, b){
      const body = (document.body && document.body.innerText || '').slice(0, 20000);
      const fi = veld(cfg.iban, []), fb = veld(cfg.bedrag, fi ? [fi] : []);
      if (fi && fb) {
        let gedaan = false;
        const fn = veld(cfg.naam, [fi, fb]);
        const fo = veld(cfg.omschrijving, [fi, fb, fn]);
        const fk = veld(cfg.kenmerk, [fi, fb, fn, fo]);
        const eens = (el, waarde) => { if (el && waarde && !el.dataset.btGezet) { zet(el, waarde); gedaan = true; } };
        eens(fn, b.naam);
        eens(fi, b.iban);
        eens(fb, fb.type === 'number' ? String(b.bedrag).replace(',', '.') : b.bedrag);
        if (b.kenmerk) eens(fk || fo, b.kenmerk); else eens(fo, b.omschrijving);
        if (gedaan) return 'invullen';
        const goed = kaal(fi.value) === kaal(b.iban) && Math.abs(getal(fb.value) - getal(b.bedrag)) < 0.005 && (!fn || kaal(fn.value) === kaal(b.naam));
        return goed ? 'ingevuld' : 'controleer';
      }
      // Inloggen doe je zelf: wachten zolang er een wachtwoordveld of een vraag om de bank-app staat
      if (alle('input[type=password]').some(zichtbaar) || (re(cfg.inlog) && re(cfg.inlog).test(body) && !navigatie(cfg.naar))) return 'inlog';
      if (cfg.geenNavigatie) return 'wacht';
      const naar = navigatie(cfg.naar);
      if (naar && eenKeer('naar', naar, 2)) return 'naar';
      const menu = navigatie(cfg.menu);
      if (menu && eenKeer('menu', menu, 1)) return 'menu';
      return 'wacht';
    }
  };
})();
