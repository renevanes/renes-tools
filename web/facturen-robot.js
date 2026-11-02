/* Facturen ophalen: de "robot" die in de website van de leverancier draait (onzichtbare of meekijkende WebView).
 * FacturenRun (Java) roept deze functies aan en beslist wat er gebeurt; hier alleen kijken, invullen en klikken.
 * Algemeen: werkt op de meeste inlogpagina's zonder instellingen; per leverancier kun je velden aanwijzen (CSS-selector).
 * Ook getest in een gewone browser (tests/ui/facturen-robot.test.js) tegen een nagebootste leverancier. */
(function(){
  if (window.__fr) return;
  const SLEUTEL = '__SLEUTEL__'; // per run door de app ingevuld; alleen deze robot kent hem
  const AUTO = {
    wachtwoord: 'input[type="password"]',
    code: 'input[autocomplete="one-time-code"], input[name*="otp" i], input[id*="otp" i], input[name*="code" i], input[id*="code" i], input[name*="token" i], input[id*="token" i], input[name*="verif" i], input[id*="verif" i], input[inputmode="numeric"]',
    gebruiker: 'input[type="email"], input[autocomplete="username"], input[autocomplete="email"], input[name*="user" i], input[id*="user" i], input[name*="gebruik" i], input[id*="gebruik" i], input[name*="login" i], input[id*="login" i], input[name*="email" i], input[id*="email" i], input[name*="account" i]'
  };
  // Ook "Log extra veilig in" (KPN, tweede stap) en "Ga verder met inloggen"
  const KNOP = /^(inloggen|log ?in|log\b.{0,30}\bin|aanmelden|volgende|ga verder|doorgaan|verder|bevestig(en)?|verifi[eë]{1,2}r(en)?|controleer|verstuur|versturen|indienen|submit|next|continue|sign ?in|verify|confirm|ok)\b/i;
  // Cookiemelding: liefst weigeren, anders accepteren (anders ligt hij over de knoppen heen)
  const COOKIE_NEE = /^(nee,? dank je( wel)?|weigeren|alles weigeren|alleen (functionele|noodzakelijke|essentiële)( cookies)?|cookies weigeren|reject( all)?|decline)$/i;
  const COOKIE_JA = /^(alle cookies accepteren|accepteren|alles accepteren|akkoord|ja,? (prima|akkoord)|accept( all)?)$/i;

  function zichtbaar(el){
    if (!el || el.disabled || el.type === 'hidden') return false;
    const r = el.getBoundingClientRect(), s = getComputedStyle(el);
    return r.width > 1 && r.height > 1 && s.visibility !== 'hidden' && s.display !== 'none' && Number(s.opacity) > 0.05;
  }
  // Ook in shadow-DOM en iframes van dezelfde website (veel inlogpagina's gebruiken die)
  function alle(sel, root, out){
    out = out || []; root = root || document;
    try {
      root.querySelectorAll(sel).forEach(e => out.push(e));
      root.querySelectorAll('*').forEach(e => { if (e.shadowRoot) alle(sel, e.shadowRoot, out); });
      root.querySelectorAll('iframe').forEach(f => { try { if (f.contentDocument) alle(sel, f.contentDocument, out); } catch (e) { } });
    } catch (e) { }
    return out;
  }
  /** Ligt het element bovenop (niet onder een venster of melding)? Buiten beeld of in een iframe/shadow-DOM: ja. */
  function bovenop(el){
    try {
      if (el.getRootNode() !== document || el.ownerDocument !== document) return true;
      const r = el.getBoundingClientRect(), y = r.top + r.height / 2;
      // De eigen omhulling van het veld (met zwevend label of icoon), niet de hele pagina
      let dichtbij = null;
      for (let n = el.parentElement, i = 0; n && n !== document.body && i < 2; n = n.parentElement, i++) {
        const q = n.getBoundingClientRect();
        if (q.height <= r.height * 3 + 40 && q.width <= r.width * 1.6 + 60) dichtbij = n; else break;
      }
      // Midden en driekwart: een zwevend label of icoon in het veld telt niet als "erover"
      return [r.left + r.width / 2, r.left + r.width * 0.75].some(x => {
        if (x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) return true;
        const t = document.elementFromPoint(x, y);
        return !t || t === el || el.contains(t) || t.contains(el) || (dichtbij && dichtbij.contains(t)) || (el.labels && Array.from(el.labels).some(l => l.contains(t)));
      });
    } catch (e) { return true; }
  }
  const TEKSTVELD = e => e.tagName === 'INPUT' && !/^(checkbox|radio|submit|button|search|hidden|file|image|reset|color|date|datetime-local|month|week|time|range)$/i.test(e.type);
  function vind(soort, cfg, behalve){
    const eigen = cfg && cfg.velden && cfg.velden[soort];
    const l = alle(eigen || AUTO[soort]).filter(e => zichtbaar(e) && !(behalve || []).includes(e) && (eigen || TEKSTVELD(e)));
    return l.filter(bovenop)[0] || null;
  }
  /** Een code in losse vakjes (4–8 smalle velden naast elkaar, één teken per vak). */
  function vakjes(){
    const l = alle('input').filter(e => /^(text|tel|number|)$/i.test(e.type) && zichtbaar(e) && bovenop(e)
      && (e.maxLength === 1 || (e.maxLength < 0 && e.getBoundingClientRect().width < 70)));
    if (l.length < 4 || l.length > 8) return null;
    // Eén rij even brede vakjes, in hetzelfde blok
    const r0 = l[0].getBoundingClientRect();
    if (!l.every(e => { const r = e.getBoundingClientRect(); return Math.abs(r.top - r0.top) < 6 && Math.abs(r.width - r0.width) < 3; })) return null;
    const p = l[0].parentElement && l[0].parentElement.parentElement;
    return p && l.every(e => p.contains(e)) ? l : null;
  }
  function velden(cfg){
    // Een wachtwoordveld dat bovenop ligt gaat voor; ligt er een codevenster overheen, dan telt het niet (bovenop)
    const pw = vind('wachtwoord', cfg);
    const user = vind('gebruiker', cfg, [pw]);
    if (pw) return { pw, user, code: null };
    const vk = vakjes();
    const code = vk ? vk[0] : vind('code', cfg, [user]);
    if (code) return { pw: null, user: null, code, vakjes: vk };
    return { pw: null, user, code: null };
  }
  function cookiesWeg(){
    // Alleen knoppen in een echte cookiemelding: een venster of vaste balk (of met cookie/consent in de naam),
    // kort en over cookies. Nooit links, nooit zomaar ergens "Akkoord".
    const melding = n => {
      const s = getComputedStyle(n), naam = (n.id || '') + ' ' + (typeof n.className === 'string' ? n.className : '');
      return (n.getAttribute('role') === 'dialog' || n.getAttribute('aria-modal') === 'true' || s.position === 'fixed' || s.position === 'sticky' || /cookie|consent|cmp/i.test(naam))
        && /cookie/i.test(n.innerText || '') && (n.innerText || '').length < 1500;
    };
    const inMelding = e => { for (let n = e.parentElement, i = 0; n && n !== document.body && i < 10; n = n.parentElement, i++) if (melding(n)) return true; return false; };
    const knoppen = alle('button, [role="button"]').filter(e => zichtbaar(e) && inMelding(e));
    const k = knoppen.find(e => COOKIE_NEE.test(tekstVan(e))) || knoppen.find(e => COOKIE_JA.test(tekstVan(e)));
    if (k) { try { k.click(); } catch (e) { } return true; }
    return false;
  }
  /** "Onthoud dit apparaat" aanvinken: dan vraagt de website de volgende keer minder vaak om een code. */
  function onthoud(){
    alle('input[type="checkbox"]').forEach(c => {
      const p = c.parentElement, eenInOuder = p && p.querySelectorAll('input[type="checkbox"]').length === 1;
      const lab = (c.labels && c.labels[0] ? c.labels[0].innerText : '') + ' ' + (c.closest('label') ? c.closest('label').innerText : '') + ' ' + (c.getAttribute('aria-label') || '') + ' ' + (eenInOuder ? p.innerText : '');
      if (!c.checked && /onthoud|vertrouw|remember|trust/i.test(lab) && zichtbaar(c.closest('label') || c)) (c.closest('label') || c).click();
    });
  }
  function inlogEl(){
    const re = /^(inloggen|log ?in|aanmelden|sign ?in|direct inloggen|mijn ?[a-z]+( zakelijk)? inloggen)$/i;
    return alle('a, button, [role="button"], [role="link"]').filter(e => zichtbaar(e) && re.test(tekstVan(e)))[0] || null;
  }
  function uitlogt(){ return alle('a, button, [role="button"], [role="menuitem"]').some(e => /^(uitloggen|log ?uit|afmelden|log ?out|sign ?out)$/i.test(tekstVan(e))); }
  function tekstVan(el){ return [el.innerText || el.value || '', el.getAttribute('aria-label') || '', el.getAttribute('title') || ''].join(' ').replace(/\s+/g, ' ').trim(); }

  function zet(el, v){
    el.focus();
    const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const set = Object.getOwnPropertyDescriptor(proto, 'value').set;
    set.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    el.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true, key: String(v).slice(-1) || 'a' }));
  }

  window.__fr = {
    versie: 1,
    /** Wat staat er op de pagina? {pw, user, userLeeg, code, fout, url, titel} */
    stand(cfg){
      cookiesWeg();
      const v = velden(cfg);
      const fout = alle('[role="alert"], .error, .alert, .alert-danger, .notification--error, .form-error, .field-error, [aria-live="assertive"]')
        .filter(zichtbaar).map(e => (e.innerText || '').trim()).filter(Boolean)[0] || '';
      return JSON.stringify({ pw: !!v.pw, user: !!v.user, userLeeg: !!v.user && !v.user.value, code: !!v.code, fout: fout.slice(0, 200),
        uit: uitlogt(), knop: !!inlogEl(),
        url: location.href, titel: document.title.slice(0, 120), klaar: document.readyState });
    },
    /** Veld invullen. Een code in losse vakjes (één cijfer per vak) wordt verdeeld. */
    vul(soort, waarde, cfg){
      const v = velden(cfg); const el = v[soort === 'wachtwoord' ? 'pw' : soort === 'gebruiker' ? 'user' : 'code'];
      if (!el) return 'geen veld';
      if (soort === 'code') onthoud();
      if (soort === 'code' && v.vakjes) {
        String(waarde).split('').forEach((ch, i) => { if (v.vakjes[i]) zet(v.vakjes[i], ch); });
        return 'vakjes ' + v.vakjes.length;
      }
      zet(el, waarde);
      return 'ok';
    },
    /** Het formulier van dit veld versturen: eigen knop, verstuurknop, knop met een bekende tekst, of Enter. */
    verstuur(soort, cfg){
      if (cfg && cfg.velden && cfg.velden.knop) { const k = alle(cfg.velden.knop).filter(zichtbaar)[0]; if (k) { k.click(); return 'eigen knop'; } }
      const v = velden(cfg); const el = v[soort === 'wachtwoord' ? 'pw' : soort === 'gebruiker' ? 'user' : 'code'] || v.pw || v.user || v.code;
      const form = el && el.closest('form');
      const NIET = /\buit\b|uitloggen|digid|eherkenning|google|microsoft|apple|facebook|wachtwoord vergeten/i;
      const goed = e => zichtbaar(e) && bovenop(e) && !COOKIE_NEE.test(tekstVan(e)) && !COOKIE_JA.test(tekstVan(e)) && !NIET.test(tekstVan(e));
      const binnen = form ? alle('button, input[type="submit"], [role="button"]', form) : [];
      let sub = binnen.filter(e => goed(e) && (e.type === 'submit' || KNOP.test(tekstVan(e))))[0];
      // Zonder formulier (vensters, zoals de codevraag van KPN): de knop in hetzelfde blok als het veld
      for (let n = el && el.parentElement, i = 0; !sub && n && i < 8; n = n.parentElement, i++)
        sub = alle('button, input[type="submit"], [role="button"]', n).filter(e => goed(e) && KNOP.test(tekstVan(e)))[0];
      if (sub) { sub.click(); return 'knop'; }
      const los = alle('button, input[type="submit"], [role="button"], a').filter(e => goed(e) && KNOP.test(tekstVan(e)))[0];
      if (los) { los.click(); return 'losse knop'; }
      if (form && form.requestSubmit) { form.requestSubmit(); return 'formulier'; }
      if (el) { ['keydown', 'keypress', 'keyup'].forEach(t => el.dispatchEvent(new KeyboardEvent(t, { bubbles: true, key: 'Enter', keyCode: 13, which: 13 }))); return 'enter'; }
      return 'niets';
    },
    /** Naar de facturen: klik op een link of knop waarvan de tekst past. Geeft de tekst terug, of ''. */
    menu(patroon){
      const re = new RegExp(patroon, 'i');
      const l = alle('a, button, [role="link"], [role="button"], [role="menuitem"], [role="tab"]').filter(e => re.test(tekstVan(e)));
      const zicht = l.filter(zichtbaar);
      const el = zicht.sort((a, b) => tekstVan(a).length - tekstVan(b).length)[0];
      if (el) { el.click(); return tekstVan(el).slice(0, 80) || 'link'; }
      const verstopt = l.find(e => e.tagName === 'A' && /^https?:/i.test(e.href));
      if (verstopt) { location.href = verstopt.href; return tekstVan(verstopt).slice(0, 80) || 'link'; }
      return '';
    },
    /** Downloadknoppen op de pagina (in volgorde), hooguit max: [{i, href, tekst}]. */
    knoppen(patroon, max){
      const re = new RegExp(patroon, 'i'), uit = [], gehad = new Set();
      alle('[data-fr]').forEach(e => e.removeAttribute('data-fr'));
      alle('a, button, [role="button"], [role="link"], [download]').forEach(e => {
        if (!zichtbaar(e) || uit.length >= max) return;
        const href = e.tagName === 'A' ? (e.getAttribute('href') || '') : '';
        const svg = Array.from(e.querySelectorAll('title, [aria-label]')).map(x => x.textContent || x.getAttribute('aria-label') || '').join(' ');
        const t = [tekstVan(e), href, e.getAttribute('download') !== null ? 'download ' + (e.getAttribute('download') || '') : '', e.className && e.className.baseVal === undefined ? e.className : '', svg].join(' ');
        if (!re.test(t)) return;
        const sleutel = (e.href && /^https?:/i.test(e.href)) ? e.href : null;
        if (sleutel && gehad.has(sleutel)) return;
        if (sleutel) gehad.add(sleutel);
        if (e.closest('[data-fr]') && e.closest('[data-fr]') !== e) return; // knop in een al gevonden knop
        const i = uit.length; e.setAttribute('data-fr', String(i));
        uit.push({ i, href: e.tagName === 'A' && /^https?:/i.test(e.href) && !e.getAttribute('href').startsWith('#') ? e.href : '', tekst: tekstVan(e).slice(0, 80) });
      });
      return JSON.stringify(uit);
    },
    /** Een inlogknop of -link op een startpagina zonder velden (dan eerst daarop klikken). */
    inlogKnop(){
      const el = inlogEl();
      if (!el) return '';
      el.click(); return tekstVan(el).slice(0, 80);
    },
    /** Staat er "uitloggen" op de pagina? Dan ben je ingelogd. */
    uitlog(){ return uitlogt(); },
    /** Een blob:-adres dat de pagina als download aanbood, alsnog doorgeven. */
    blob(u, naam){ if (blobs.has(u)) geef(blobs.get(u), naam); else fetch(u).then(r => r.blob()).then(b => geef(b, naam)).catch(() => {}); return 'ok'; },
    klik(i){
      const e = alle('[data-fr="' + i + '"]')[0];
      if (!e) return 'weg';
      try { e.scrollIntoView({ block: 'center' }); } catch (x) { }
      e.click();
      return 'ok';
    }
  };

  // PDF's die de pagina zelf maakt (blob:/data:), zoals "Download" in moderne portalen: doorgeven aan de app.
  const blobs = new Map();
  const geef = (blob, naam) => {
    if (!window.FrBrug || !blob || blob.size > 15 * 1024 * 1024) return;
    const r = new FileReader();
    r.onload = () => { try { window.FrBrug.blob(SLEUTEL, String(r.result), naam || ''); } catch (e) { } };
    r.readAsDataURL(blob);
  };
  const orig = URL.createObjectURL;
  URL.createObjectURL = function(o){
    const u = orig.apply(this, arguments);
    try { if (o instanceof Blob) { blobs.set(u, o); if (/pdf/i.test(o.type)) geef(o, ''); } } catch (e) { }
    return u;
  };
  const vang = (href, naam) => {
    if (!href) return;
    if (href.startsWith('blob:') && blobs.has(href)) geef(blobs.get(href), naam);
    else if (/^data:application\/(pdf|octet-stream)/i.test(href) && window.FrBrug) { try { window.FrBrug.blob(SLEUTEL, href, naam || ''); } catch (e) { } }
  };
  document.addEventListener('click', ev => {
    const a = ev.target && ev.target.closest && ev.target.closest('a[href]');
    if (a) vang(a.href, a.getAttribute('download'));
  }, true);
  const aklik = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function(){ try { vang(this.href, this.getAttribute('download')); } catch (e) { } return aklik.apply(this, arguments); };
  const wopen = window.open;
  window.open = function(u){ try { vang(String(u || ''), ''); } catch (e) { } return wopen.apply(this, arguments); };
})();
