// Engels als taal (Instellingen → Regio en taal → Taal; web/src/script/02-taal.js, web/src/taal/en.json):
// omschakelen via het scherm, alle schermen in het Engels zonder Nederlandse woorden (behalve gegevens van de testbrug en
// namen, zie TOEGESTAAN), elke t() met een vertaling, geen tekst die buiten een 360 px breed scherm valt, licht en donker,
// meldingen en vensters in het Engels, en weer terug naar Nederlands: precies dezelfde tekst als ervoor.
const { chromium } = require('playwright');
const path = require('path');

// Nederlandse woorden die in Engelse tekst niet voorkomen (hele woorden)
const NL = /(^|[^\p{L}])(de|het|een|niet|voor|met|naar|wordt|worden|kies|tik|nog|geen|deze|dit|van|je|jouw|bij|uit|aan|als|ook|alleen|wissen|opslaan|bewaren|annuleren|instellingen|zoeken|toevoegen|verwijderen|terug|gekoppeld|nieuwe|vandaag|gisteren|dagen|minuten|uur|keer|zet|staat|kan|moet|mag|wat|hoe|waar|welke|zijn|heeft|hebt|gaat|komt|blijft|alles|niets|eerst|daarna|altijd|nooit|meer|minder)(?![\p{L}])/iu;
// Wat in het Engels Nederlands mag blijven, met de reden
const TOEGESTAAN = [
  ['Contacten backup/', 'echte mapnamen in de backup-map'],
  ['Bediening van de radio', 'uitleg uit de testbrug; de app krijgt die uit Java (NotifCenter, Taal.T), daar wel vertaald'],
  ['Qwen3 4B · beter Nederlands, trager', 'beschrijving uit de testbrug (in Java staat een langere, vertaalde tekst)'],
  ['Tools: hulp bij gespreksopname', 'de naam van de toegankelijkheidsdienst in Android (Android-namen blijven Nederlands)'],
  ['Nieuws van Vandaag', 'titel van een podcast uit de testbrug'],
  ['Tech in 20 minuten', 'titel van een podcast uit de testbrug'],
  ['Spraakservices van Google', 'naam van de voorleesdienst van de telefoon'],
  ['Hoe voel je je?', 'vraag van Check-in uit de testbrug (gegevens van Rene)'],
  ['Hoe was je werk?', 'vraag van Check-in uit de testbrug (gegevens van Rene)'],
  ['Rene van Es', 'naam'],
];
const toegestaan = s => TOEGESTAAN.some(([x]) => s.includes(x));

(async () => {
  const b = await chromium.launch();
  const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const url = 'file://' + path.join(__dirname, 'ui', 'index.html');
  const page = async (scheme) => {
    const ctx = await b.newContext({ viewport: { width: 360, height: 780 }, colorScheme: scheme, timezoneId: 'Europe/Amsterdam' });
    const pg = await ctx.newPage();
    pg.on('pageerror', e => errs.push(scheme + ': ' + e.message));
    pg.on('console', m => { if (m.type() === 'error' && !/ERR_TUNNEL|ERR_NAME|ERR_FILE|ERR_INTERNET|tile\.openstreetmap|\/icon\//.test(m.text() + JSON.stringify(m.location()))) errs.push(m.text()); });
    await pg.goto(url); await pg.waitForTimeout(300);
    return { ctx, pg };
  };
  // De zichtbare tekst van het scherm (en wat erboven open staat), zonder gegevens met translate="no"
  const zichtbaar = (pg, id) => pg.evaluate(id => {
    const s = document.getElementById('s-' + id), out = [];
    if (!s) return out;
    const tw = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    for (let n = tw.nextNode(); n; n = tw.nextNode()) {
      const p = n.parentElement; if (!p || p.closest('[translate="no"],script,style,textarea,#taal-en')) continue;
      if (!p.closest('#s-' + id) && !p.closest('.sheet.open, dialog[open], #tabs')) continue;
      if (!p.getClientRects().length) continue;
      const v = n.data.replace(/\s+/g, ' ').trim(); if (v) out.push(v);
    }
    for (const el of s.querySelectorAll('[placeholder],[aria-label],[title]')) {
      if (el.closest('[translate="no"]')) continue;
      for (const a of ['placeholder', 'aria-label', 'title']) { const v = el.getAttribute(a); if (v) out.push('@' + a + ': ' + v); }
    }
    return out;
  }, id);
  const tekstVan = (pg, id) => pg.evaluate(id => { const s = document.getElementById('s-' + id); return s ? s.innerText.replace(/\d/g, '#').replace(/\s+/g, ' ').trim() : ''; }, id);
  const naar = async (pg, id) => { await pg.evaluate(id => { try { show(id); } catch (e) { } }, id); await pg.waitForTimeout(110); };

  // ---------- 1. Licht: alles ----------
  {
    const { ctx, pg } = await page('light');
    const ids = await pg.evaluate(() => [...document.querySelectorAll('section.screen')].map(s => s.id.slice(2)).filter(id => id !== 'info'));
    ok(ids.length > 60, ids.length + ' schermen');
    ok(await pg.evaluate(() => document.documentElement.lang === 'nl' && !TAAL.on && !TAAL.d && !TAAL.obs), 'standaard Nederlands: geen woordenboek gelezen, geen observer (kost niets)');
    // Nederlands vooraf vastleggen (een steekproef, om straks te vergelijken)
    const PROEF = ['home', 'settings', 'setregio', 'notes', 'backup', 'wa', 'sms', 'calls', 'aiset', 'checkin', 'apparaten', 'gebruik', 'sleep', 'facturen', 'auto', 'vpn', 'podcasts'].filter(x => ids.includes(x));
    const voor = {};
    for (const id of PROEF) { await naar(pg, id); voor[id] = await tekstVan(pg, id); }

    // Omschakelen zoals Rene het doet: Instellingen → Regio en taal → Taal
    await naar(pg, 'settings');
    await pg.click('button.strow:has-text("Regio en taal")'); await pg.waitForTimeout(150);
    const t0 = Date.now();
    await pg.selectOption('#rg-lang', 'en'); await pg.waitForTimeout(150);
    const st = await pg.evaluate(() => ({ lang: document.documentElement.lang, on: TAAL.on, java: JSON.parse(Android._regioCfg || '{}').lang, h1: $('#s-setregio h1').textContent, opt: $('#rg-lang').selectedOptions[0].text, loc: regioLocale() }));
    ok(st.lang === 'en' && st.on && st.java === 'en', 'Engels aan: <html lang="en">, en Java weet het (' + JSON.stringify(st) + ')');
    ok(st.h1 === 'Region and language' && st.opt === 'English', 'het scherm zelf is meteen Engels: ' + st.h1);
    ok(/^en-GB/.test(st.loc), 'regioLocale is Engels (en-GB), met je eigen scheidingstekens: ' + st.loc);
    ok(Date.now() - t0 < 2000, 'omschakelen gaat snel (' + (Date.now() - t0) + ' ms incl. wachten)');

    // Elke t() moet een vertaling hebben
    await pg.evaluate(() => { window._mis = new Set(); const o = window.t; window.t = function(s, v){ if (TAAL.on && s != null && /\p{L}{2}/u.test(String(s)) && taalLookup(s, v) === undefined) window._mis.add(String(s).slice(0, 100)); return o(s, v); }; });

    const nl = {}, breed = {};
    for (const id of ids) {
      await naar(pg, id);
      const bad = (await zichtbaar(pg, id)).filter(x => NL.test(x.replace(/^@[\w-]+: /, '')) && !toegestaan(x));
      if (bad.length) nl[id] = [...new Set(bad)].slice(0, 6);
      const w = await pg.evaluate(id => {
        const out = [], W = innerWidth, s = document.getElementById('s-' + id);
        if (document.documentElement.scrollWidth > W + 1) out.push('pagina ' + document.documentElement.scrollWidth + 'px');
        if (s) for (const el of s.querySelectorAll('button, h1, h2, .hdr, label, .chips')) {
          const r = el.getBoundingClientRect(); if (!r.width) continue;
          let sc = false; for (let p = el.parentElement; p && p !== s; p = p.parentElement) if (/auto|scroll/.test(getComputedStyle(p).overflowX)) { sc = true; break; } // rij om opzij te vegen
          if (sc) continue;
          const cs = getComputedStyle(el); if (cs.position === 'fixed' || cs.visibility === 'hidden') continue;
          if (r.right > W + 1 && r.left < W) out.push(el.tagName.toLowerCase() + '.' + (el.className || '') + ' "' + el.textContent.trim().slice(0, 30) + '" tot ' + Math.round(r.right) + 'px');
        }
        return out.slice(0, 3);
      }, id);
      if (w.length) breed[id] = w;
    }
    ok(!Object.keys(nl).length, 'geen Nederlandse woorden op ' + ids.length + ' schermen' + (Object.keys(nl).length ? ': ' + JSON.stringify(nl) : ''));
    const mis = await pg.evaluate(() => [...window._mis]);
    const misBad = mis.filter(x => !toegestaan(x));
    ok(!misBad.length, 'elke t() op die schermen heeft een vertaling' + (misBad.length ? ': ' + JSON.stringify(misBad.slice(0, 20)) : ''));
    ok(!Object.keys(breed).length, 'niets valt buiten een 360 px breed scherm' + (Object.keys(breed).length ? ': ' + JSON.stringify(breed) : ''));

    // Kop, tabbalk, melding en venster
    await naar(pg, 'settings');
    const ui = await pg.evaluate(() => ({ h1: $('#s-settings h1').textContent.trim(), tabs: [...document.querySelectorAll('#tabs button')].map(x => x.textContent.trim()).join('|'), nav: $('#tabs').getAttribute('aria-label') }));
    ok(ui.h1 === 'Settings' && /Settings/.test(ui.tabs) && /Search/.test(ui.tabs) && !/Instellingen|Zoeken/.test(ui.tabs) && ui.nav === 'Main menu', 'kop en tabbalk Engels: ' + ui.h1 + ' · ' + ui.tabs + ' · ' + ui.nav);
    await pg.evaluate(() => toast(t('Opgeslagen'))); await pg.waitForTimeout(80);
    ok(await pg.textContent('#toast') === 'Saved', 'melding: ' + await pg.textContent('#toast'));
    await pg.evaluate(() => askConfirm(t('Alles vergeten?'), tn(3, 'De assistent vergeet alles wat hier staat ({n} ding). Je gesprekken blijven.', 'De assistent vergeet alles wat hier staat ({n} dingen). Je gesprekken blijven.'), t('Alles vergeten'), () => {})); await pg.waitForTimeout(120);
    const dlg = await pg.evaluate(() => [...document.querySelectorAll('dialog[open], .modal.on, #modal')].map(x => x.innerText).join(' ').replace(/\s+/g, ' '));
    ok(/Forget everything\?/.test(dlg) && /\(3 things\)/.test(dlg) && !NL_TEST(dlg), 'venster Engels, met meervoud: ' + dlg.slice(0, 120));
    function NL_TEST(s){ return NL.test(s); }
    await pg.keyboard.press('Escape'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => tn(1, '{n} bericht', '{n} berichten') === '1 message' && tn(2, '{n} bericht', '{n} berichten') === '2 messages' && t('{n} keer iets gehoord', { n: 1 }) === 'Heard something once' && t('{n} keer iets gehoord', { n: 4 }) === 'Heard something 4 times' && t('Iets dat niet bestaat {x}', { x: 1 }) === 'Iets dat niet bestaat 1'), 'meervoud en plaatshouders (ook ["één", "meer"] in het woordenboek), zonder vertaling de brontekst');
    ok(await pg.evaluate(() => { const d = document.createElement('p'); d.innerHTML = 'Tik op <b>Opslaan</b>.'; document.body.append(d); return new Promise(r => setTimeout(() => { const x = d.textContent; d.remove(); r(x); }, 50)); }) !== 'Tik op Opslaan.', 'tekst die later met innerHTML verschijnt, wordt ook vertaald');
    ok(await pg.evaluate(() => { const d = document.createElement('p'); d.setAttribute('translate', 'no'); d.textContent = 'Opgeslagen'; document.body.append(d); return new Promise(r => setTimeout(() => { const x = d.textContent; d.remove(); r(x); }, 50)); }) === 'Opgeslagen', 'gegevens met translate="no" blijven zoals ze zijn');
    // Wat is er nieuw: in het Nederlands, met de melding erbij
    await pg.evaluate(() => show('info')); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => /\(in Dutch\)/.test($('#log').textContent) && !!$('#log ul[translate="no"]')), 'Versie en updates: wijzigingen blijven Nederlands, met "(in Dutch)"');
    await pg.screenshot({ path: path.join(__dirname, 'shots', 'engels-licht.png') });

    // ---------- Terug naar Nederlands: precies dezelfde tekst ----------
    await naar(pg, 'setregio');
    await pg.selectOption('#rg-lang', 'nl'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => document.documentElement.lang === 'nl' && !TAAL.on && !TAAL.obs && JSON.parse(Android._regioCfg || '{}').lang === 'nl'), 'terug naar Nederlands: lang="nl", observer weg, Java weet het');
    const diff = [];
    for (const id of PROEF) { await naar(pg, id); const na = await tekstVan(pg, id); if (na !== voor[id]) { let i = 0; while (i < na.length && na[i] === voor[id][i]) i++; diff.push(id + ': "' + voor[id].slice(Math.max(0, i - 20), i + 40) + '" ≠ "' + na.slice(Math.max(0, i - 20), i + 40) + '"'); } }
    ok(!diff.length, 'Nederlands na het terugschakelen precies gelijk op ' + PROEF.length + ' schermen' + (diff.length ? ': ' + diff.join(' | ') : ''));
    await ctx.close();
  }

  // ---------- 2. Donker: starten in het Engels ----------
  {
    const { ctx, pg } = await page('dark');
    await pg.evaluate(() => rgSave({ lang: 'en' }));
    await pg.reload(); await pg.waitForTimeout(400);
    const r = await pg.evaluate(() => ({ lang: document.documentElement.lang, on: TAAL.on, dark: document.documentElement.classList.contains('dark') || matchMedia('(prefers-color-scheme: dark)').matches }));
    ok(r.lang === 'en' && r.on && r.dark, 'opnieuw starten in het Engels, donker: ' + JSON.stringify(r));
    const nl = {};
    for (const id of ['home', 'settings', 'radio', 'notes', 'backup', 'aiset', 'models', 'checkin', 'apparaten', 'facturen', 'bank']) {
      await naar(pg, id);
      const bad = (await zichtbaar(pg, id)).filter(x => NL.test(x.replace(/^@[\w-]+: /, '')) && !toegestaan(x));
      if (bad.length) nl[id] = bad.slice(0, 5);
    }
    ok(!Object.keys(nl).length, 'donker: geen Nederlandse woorden' + (Object.keys(nl).length ? ': ' + JSON.stringify(nl) : ''));
    await naar(pg, 'home');
    await pg.screenshot({ path: path.join(__dirname, 'shots', 'engels-donker.png') });
    await ctx.close();
  }
  await b.close();
  console.log('errors', errs);
})();
