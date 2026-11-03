/* Bankzaken, automatisch importeren: de "robot" die in de website van de bank draait (BankLinkActivity, altijd in beeld).
 * Hij logt NOOIT zelf in en vult geen wachtwoorden of codes in: dat doe je zelf, met de app van je bank. Daarna zoekt hij
 * de pagina "transacties downloaden", kiest het formaat (liefst CAMT.053, anders CSV), de periode en tikt op Downloaden.
 * Het bestand gaat rechtstreeks (in het geheugen) naar Bankzaken; het komt nooit in je downloadmap.
 * Getest in een gewone browser (tests/ui/bank-robot.test.js) tegen een nagebootste bank. */
(function(){
  if (window.__br) return;
  const SLEUTEL = '__SLEUTEL__'; // per keer door de app ingevuld; alleen deze robot kent hem

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
  const KLIKBAAR = 'a, button, [role=button], [role=link], [role=menuitem], [role=tab], input[type=button], input[type=submit], label';
  function vind(patroon){
    const r = re(patroon); if (!r) return null;
    return alle(KLIKBAAR).find(e => zichtbaar(e) && tekst(e).length < 80 && r.test(tekst(e))) || null;
  }
  function klik(e){ try { e.scrollIntoView({ block: 'center' }); } catch (x) { } e.click(); }
  const geklikt = new Map(); // per patroon hoe vaak (geen eindeloze lussen)
  function eenKeer(naam, e, max){ const n = geklikt.get(naam) || 0; if (n >= (max || 2)) return false; geklikt.set(naam, n + 1); klik(e); return true; }

  // Formulier: liefst CAMT.053, anders CSV; alle rekeningen; periode vanaf de vorige import
  function kies(cfg){
    let gedaan = false;
    const fmt = re(cfg.formaat), res = re(cfg.formaatReserve), alleR = re(cfg.alleRekeningen);
    for (const s of alle('select')) {
      if (!zichtbaar(s)) continue;
      const opts = Array.from(s.options);
      let o = fmt && opts.find(x => fmt.test(x.text)); if (!o && res) o = opts.find(x => res.test(x.text));
      if (!o && alleR) o = opts.find(x => alleR.test(x.text));
      if (o && s.value !== o.value) { s.value = o.value; s.dispatchEvent(new Event('input', { bubbles: true })); s.dispatchEvent(new Event('change', { bubbles: true })); gedaan = true; }
    }
    const radios = alle('input[type=radio], input[type=checkbox]');
    const labelVan = i => { const l = i.id && document.querySelector('label[for="' + CSS.escape(i.id) + '"]'); return tekst(l || i.closest('label') || i.parentElement || i) + ' ' + (i.value || ''); };
    let r = fmt && radios.find(i => i.type === 'radio' && fmt.test(labelVan(i)));
    if (!r && res) r = radios.find(i => i.type === 'radio' && res.test(labelVan(i)));
    if (r && !r.checked) { klik(r); gedaan = true; }
    const ar = alleR && radios.find(i => alleR.test(labelVan(i)));
    if (ar && !ar.checked) { klik(ar); gedaan = true; }
    if (cfg.van) {
      const datums = alle('input').filter(i => zichtbaar(i) && (i.type === 'date' || /van|from|start|begin/i.test((i.name || '') + ' ' + (i.id || '') + ' ' + (i.getAttribute('aria-label') || '') + ' ' + (i.placeholder || ''))) && /date|text/.test(i.type));
      const v = datums[0];
      if (v && !v.dataset.brGezet) {
        const [y, m, d] = cfg.van.split('-');
        const waarde = v.type === 'date' ? cfg.van : d + '-' + m + '-' + y;
        const zet = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
        zet.call(v, waarde);
        v.dispatchEvent(new Event('input', { bubbles: true })); v.dispatchEvent(new Event('change', { bubbles: true }));
        v.dataset.brGezet = '1'; gedaan = true;
      }
    }
    return gedaan;
  }

  window.__br = {
    /** Eén stap. Geeft wat er gebeurde: inlog | download | formulier | naar | menu | wacht. */
    stap(cfg){
      const body = (document.body && document.body.innerText || '').slice(0, 20000);
      // Inloggen doe je zelf: wachten zolang er een wachtwoordveld of een vraag om de bank-app staat
      if (alle('input[type=password]').some(zichtbaar) || (re(cfg.inlog) && re(cfg.inlog).test(body) && !vind(cfg.naarDownload))) return 'inlog';
      const knop = vind(cfg.knop);
      const formulier = alle('select, input[type=radio], input[type=date]').some(zichtbaar);
      if (formulier) {
        if (kies(cfg)) return 'formulier';
        if (knop && eenKeer('knop', knop, 3)) return 'download';
        return 'wacht';
      }
      const naar = vind(cfg.naarDownload);
      if (naar && eenKeer('naar', naar, 2)) return 'naar';
      if (knop && eenKeer('knop', knop, 3)) return 'download';
      const menu = vind(cfg.menu);
      if (menu && eenKeer('menu', menu, 1)) return 'menu';
      return 'wacht';
    },
    /** Een blob:-adres dat de pagina als download aanbood, alsnog doorgeven. */
    blob(u, naam){ if (blobs.has(u)) geef(blobs.get(u), naam); else fetch(u).then(r => r.blob()).then(b => geef(b, naam)).catch(() => {}); return 'ok'; }
  };

  // Bestanden die de pagina zelf maakt (blob:/data:): doorgeven aan de app (alleen tekst/XML, max 25 MB)
  const blobs = new Map();
  const geef = (blob, naam) => {
    if (!window.BrBrug || !blob || blob.size > 25 * 1024 * 1024) return;
    const r = new FileReader();
    r.onload = () => { try { window.BrBrug.bestand(SLEUTEL, String(r.result), naam || ''); } catch (e) { } };
    r.readAsDataURL(blob);
  };
  const orig = URL.createObjectURL;
  URL.createObjectURL = function(o){
    const u = orig.apply(this, arguments);
    try { if (o instanceof Blob) blobs.set(u, o); } catch (e) { }
    return u;
  };
  const vang = (href, naam) => {
    if (!href) return;
    if (href.startsWith('blob:') && blobs.has(href)) geef(blobs.get(href), naam);
    else if (/^data:(text|application)\//i.test(href) && window.BrBrug) { try { window.BrBrug.bestand(SLEUTEL, href, naam || ''); } catch (e) { } }
  };
  document.addEventListener('click', ev => {
    const a = ev.target && ev.target.closest && ev.target.closest('a[href]');
    if (a) vang(a.href, a.getAttribute('download'));
  }, true);
  const aklik = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function(){ try { vang(this.href, this.getAttribute('download')); } catch (e) { } return aklik.apply(this, arguments); };
})();
