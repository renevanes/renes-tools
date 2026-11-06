// Interface-patronen (AGENTS.md, "Interface-patronen"; web/src/ontwerp.css en web/src/script/02-patronen.js):
// Hoe werkt dit? met toetsenbord en schermlezer, overal dezelfde terugknop (en kleine kop op detailschermen),
// hooguit één gevulde hoofdknop per scherm, geen kale keuzelijsten, en gemeten hoogtes: Versie en updates korter
// dan 3 schermen, de opnameknop, de meldingenlijst en "Hier staat hij" boven de vouw. Licht, donker en in het Engels.
const { chromium } = require('playwright');
const path = require('path');

const W = +process.env.UI_WACHT || 1;
const FOLD = 844;
const BACK_D = 'M15 18l-6-6 6-6'; // de SVG-chevron van de terugknop
// Detailschermen (geopend vanuit een ander scherm): kleine kop
const DETAIL = ['cversions', 'cversion', 'vpnedit', 'autoed', 'chats', 'ralarm', 'herstel', 'chat', 'smschat', 'track', 'txd', 'contact', 'note',
  'podcast', 'podlijst', 'podspotify', 'models', 'carset', 'gebruikset', 'gebruikontgr', 'apset', 'apdev', 'aiset', 'aisetvoice', 'aisetdata', 'aichats',
  'sleepset', 'sleepnight', 'checkined', 'checkinq', 'checkincmp', 'setnotif', 'setlook', 'setregio', 'setcrash', 'facstuk', 'facgoogle', 'idcopy', 'formulieren',
  'pdfedit', 'pdfimg', 'pdfsplit', 'pdftext', 'pdfmade', 'bkparts', 'bkset', 'bkrestore', 'wasel', 'waauto', 'warestore',
  'wcplek', 'wcbewerk', 'wcdeel', 'wcset', 'wcbronnen', 'wcbron', 'wceigen', 'wcgedeeld'];
// Meer dan één gevulde hoofdknop: bewust (met de reden)
const MEER_PRIMAIR = {
};

(async () => {
  const b = await chromium.launch();
  const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const open = async (scheme, en) => {
    const ctx = await b.newContext({ viewport: { width: 390, height: FOLD }, colorScheme: scheme, timezoneId: 'Europe/Amsterdam' });
    const pg = await ctx.newPage();
    pg.on('pageerror', e => errs.push(scheme + ': ' + e.message));
    await pg.goto('file://' + path.join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300 * W);
    if (en) { await pg.evaluate(() => show('setregio')); await pg.selectOption('#rg-lang', 'en'); await pg.waitForTimeout(200 * W); }
    return { ctx, pg };
  };
  const naar = async (pg, id) => { await pg.evaluate(id => { try { show(id); } catch (e) { window._showErr = e.message; } }, id); await pg.waitForTimeout(140 * W); };

  for (const [scheme, en] of [['light', false], ['dark', false], ['light', true]]) {
    const tag = scheme + (en ? ' (Engels)' : '');
    const { ctx, pg } = await open(scheme, en);
    const ids = await pg.evaluate(() => [...document.querySelectorAll('section.screen')].map(s => s.id.slice(2)));

    // ---------- 6. Terugknop: overal dezelfde, met een titel; detailschermen met kleine kop ----------
    const kop = await pg.evaluate(D => [...document.querySelectorAll('section.screen')].map(s => {
      const id = s.id.slice(2), h = s.querySelector(':scope > .hdr'), back = h && h.querySelector('.back'), p = back && back.querySelector('svg path');
      return { id, hdr: !!h, sm: !!(h && h.classList.contains('sm')), h1: !!(h && h.querySelector('h1')), d: p ? p.getAttribute('d') : back ? 'anders: ' + back.innerHTML.slice(0, 30) : '', label: back ? back.getAttribute('aria-label') : '', home: id === 'home' };
    }), BACK_D);
    const zonder = kop.filter(k => !k.hdr || !k.h1).map(k => k.id);
    ok(!zonder.length, tag + ': elk scherm heeft een kop met een titel' + (zonder.length ? ' (niet: ' + zonder + ')' : ''));
    const anders = kop.filter(k => !k.home && k.d !== BACK_D).map(k => k.id + ' ' + k.d);
    ok(!anders.length, tag + ': overal dezelfde terugknop (SVG-chevron)' + (anders.length ? ': ' + anders.join(', ') : ''));
    ok(kop.filter(k => !k.home).every(k => k.label && k.label.length > 2), tag + ': de terugknop heeft een naam voor de schermlezer');
    const groot = DETAIL.filter(id => ids.includes(id)).filter(id => !kop.find(k => k.id === id).sm);
    ok(!groot.length, tag + ': detailschermen hebben een kleine kop' + (groot.length ? ' (niet: ' + groot + ')' : ''));
    ok(DETAIL.filter(id => !ids.includes(id)).length === 0, tag + ': alle detailschermen bestaan (' + DETAIL.filter(id => !ids.includes(id)) + ')');

    // ---------- 3. Schuifjes in de kop: pictogram, naam, en het opent het eigen instellingenscherm ----------
    const hset = await pg.evaluate(() => [...document.querySelectorAll('button.hset')].map(b => ({ to: b.dataset.to, icon: !!b.querySelector('[data-icon="settings"]'), label: b.getAttribute('aria-label') || '', scr: b.closest('section').id })));
    ok(hset.length >= 3 && hset.every(h => h.icon && h.label.length > 3 && h.to && ids.includes(h.to)), tag + ': schuifjes in de kop (' + hset.map(h => h.scr + '→' + h.to).join(', ') + ')');
    await naar(pg, 'car'); await pg.click('#s-car .hset'); await pg.waitForTimeout(120 * W);
    ok(await pg.evaluate(() => current === 'carset'), tag + ': Mijn auto → schuifjes → instellingen');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(80 * W);
    ok(await pg.evaluate(() => current === 'car'), tag + ': terug naar Mijn auto');
    await naar(pg, 'sleep'); await pg.click('#s-sleep .hset'); await pg.waitForTimeout(120 * W);
    ok(await pg.evaluate(() => current === 'sleepset'), tag + ': Slaappraat → schuifjes → instellingen');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(80 * W);
    ok(await pg.evaluate(() => current === 'sleep'), tag + ': terug naar Slaappraat');

    // ---------- 1. Hoe werkt dit?: ingeklapt, met toetsenbord te openen, de schermlezer hoort de stand ----------
    await naar(pg, 'recorder');
    const sum = pg.locator('#s-recorder > main > details.help > summary');
    ok(await sum.count() === 1 && !(await pg.evaluate(() => document.querySelector('#s-recorder > main > details.help').open)), tag + ': Gesprekken opnemen: uitleg staat ingeklapt');
    ok((await sum.getAttribute('aria-expanded')) === 'false', tag + ': aria-expanded="false" als hij dicht is');
    await sum.focus();
    ok(await pg.evaluate(() => document.activeElement && document.activeElement.matches('details.help > summary')), tag + ': de kop van de uitleg krijgt de focus');
    await pg.keyboard.press('Enter'); await pg.waitForTimeout(60 * W);
    ok(await pg.evaluate(() => document.querySelector('#s-recorder > main > details.help').open) && (await sum.getAttribute('aria-expanded')) === 'true', tag + ': Enter klapt open (aria-expanded="true")');
    ok(await pg.evaluate(() => { const b = document.querySelector('#s-recorder > main > details.help .help-body'); return b.getBoundingClientRect().height > 40 && /2 (uur|hours)/.test(b.textContent); }), tag + ': de uitleg is te lezen');
    await pg.keyboard.press(' '); await pg.waitForTimeout(60 * W);
    ok(!(await pg.evaluate(() => document.querySelector('#s-recorder > main > details.help').open)) && (await sum.getAttribute('aria-expanded')) === 'false', tag + ': spatie klapt weer dicht');
    // Wat de schermlezer ervan maakt (Chromium-toegankelijkheidsboom): een uitklapper met de stand
    const cdp = await ctx.newCDPSession(pg);
    await cdp.send('Accessibility.enable');
    const ax = (await cdp.send('Accessibility.getFullAXTree')).nodes.filter(n => n.name && /^(Hoe werkt dit\?|How does this work\?)$/.test(n.name.value || '') && !n.ignored);
    const role = ax.length ? ax[0].role.value : '';
    const exp = ax.length ? (ax[0].properties || []).find(p => p.name === 'expanded') : null;
    ok(ax.length && /DisclosureTriangle|button/i.test(role) && exp && exp.value.value === false, tag + ': schermlezer: ' + role + ', ingeklapt (' + JSON.stringify(exp && exp.value) + ')');
    // Zichtbaar in beide thema's: tekst heeft een andere kleur dan zijn achtergrond
    ok(await pg.evaluate(() => { const s = document.querySelector('#s-recorder details.help > summary'), d = s.parentElement; const c = getComputedStyle(s).color, bg = getComputedStyle(d).backgroundColor; return c !== bg && bg !== 'rgba(0, 0, 0, 0)'; }), tag + ': uitleg leesbaar (eigen kleur op een eigen vlak)');

    // ---------- 4. Lege lijst en 5. eenmalig instellen ----------
    ok(await pg.evaluate(() => { const e = document.querySelector('#recorder-list .empty'); return !!(e && e.querySelector('.ui-icon') && e.querySelector('p')); }), tag + ': lege lijst: pictogram en één regel');
    ok(await pg.evaluate(() => document.querySelector('#recorder-setup').open && !document.querySelector('#recorder-setup').classList.contains('done')), tag + ': eenmalig instellen staat open zolang het niet klaar is');
    await pg.evaluate(() => { Object.assign(Android._recorder, { callAccess: true }); Android.recorderRequestPermissions(); }); await pg.waitForTimeout(700 * W);
    await pg.evaluate(() => loadRecorder()); await pg.waitForTimeout(60 * W);
    ok(await pg.evaluate(() => !document.querySelector('#recorder-setup').open && document.querySelector('#recorder-setup').classList.contains('done')), tag + ': klaar: eenmalig instellen klapt in');
    await pg.click('#recorder-setup > summary'); await pg.waitForTimeout(60 * W);
    await pg.evaluate(() => loadRecorder()); await pg.waitForTimeout(60 * W);
    ok(await pg.evaluate(() => document.querySelector('#recorder-setup').open) && await pg.isVisible('#recorder-auto'), tag + ': zelf opengeklapt blijft open (automatisch starten bereikbaar), ook na verversen');

    // ---------- Gemeten: korte schermen en de hoofdknop boven de vouw ----------
    // Vaste wijzigingslijst (niet changelog.json: publish.sh zet daar vóór de tests de nieuwe versie in, met al zijn regels)
    await pg.evaluate(() => { const log = []; for (let v = 40; v > 10; v--) log.push({ version: '1.' + v, date: '2026-11-05', changes: [1, 2, 3, 4, 5].map(i => 'Regel ' + i + ' van versie 1.' + v + ': een wijziging zoals Rene hem leest, ongeveer zo lang als een gewone regel.') });
      Android._vastLog = log; Android.lastManifest = () => JSON.stringify({ versionCode: 1e9, changelog: log }); });
    await naar(pg, 'info');
    const infoH = await pg.evaluate(() => document.documentElement.scrollHeight);
    ok(infoH < 3 * FOLD, tag + ': Versie en updates is ' + (infoH / FOLD).toFixed(1) + ' schermen (< 3; 30 versies van 5 regels)');
    const nAll = await pg.evaluate(() => Android._vastLog.length);
    ok(await pg.evaluate(() => document.querySelectorAll('#log h3').length) === Math.min(3, nAll), tag + ': de laatste 3 versies');
    await pg.click('#log-all'); await pg.waitForTimeout(80 * W);
    ok(await pg.evaluate(() => document.querySelectorAll('#log h3').length) === nAll && (await pg.getAttribute('#log-all', 'aria-expanded')) === 'true', tag + ': Alle versies: alle ' + nAll);
    await pg.click('#log-all'); await pg.waitForTimeout(80 * W);
    ok(await pg.evaluate(() => document.querySelectorAll('#log h3').length) === Math.min(3, nAll), tag + ': en weer inklappen');

    const boven = async (scr, sel, wat) => {
      await naar(pg, scr); await pg.evaluate(() => scrollTo(0, 0));
      const r = await pg.evaluate(sel => { const e = document.querySelector(sel); if (!e || !e.getClientRects().length) return null; const b = e.getBoundingClientRect(); return { top: Math.round(b.top), bottom: Math.round(b.bottom) }; }, sel);
      ok(r && r.bottom <= FOLD && r.top >= 0, tag + ': ' + wat + ' boven de vouw (' + JSON.stringify(r) + ')');
    };
    await pg.evaluate(() => { Object.assign(Android._recorder, { mic: false, notifications: false, phone: false, callAccess: false }); });
    await boven('recorder', '#recorder-start', 'Gesprekken opnemen: Start opname (ook als nog niets is ingesteld)');
    await pg.evaluate(() => { if (Android._historyRows.length < 3) for (let i = 0; i < 3; i++) Android._historyRows.push({ time: Date.now() - i * 6e4, app: 'Mail', title: 'Bericht ' + i, text: 'Tekst', package: 'mail.test' }); });
    await boven('history', '#history-list .history-entry', 'Meldingsgeschiedenis: de eerste melding');
    ok(await pg.isVisible('#history-search') && await pg.isVisible('#history-bar'), tag + ': Meldingsgeschiedenis: zoeken en de balk met de schakelaar');
    await boven('car', '#car-here', 'Mijn auto: Hier staat hij');

    // ---------- 7. Keuzelijsten: overal de gedeelde vormgeving (ook in donker) ----------
    const kaal = await pg.evaluate(() => [...document.querySelectorAll('select')].filter(s => { const cs = getComputedStyle(s); return parseFloat(cs.borderTopLeftRadius) < 6 || parseFloat(cs.paddingLeft) < 8 || cs.backgroundColor === 'rgba(0, 0, 0, 0)'; }).map(s => (s.closest('section') || {}).id + ' #' + s.id));
    ok(!kaal.length, tag + ': geen kale keuzelijsten' + (kaal.length ? ': ' + kaal.join(', ') : ''));
    await naar(pg, 'carset');
    ok(await pg.evaluate(() => { const s = document.querySelector('#car-dev'), cs = getComputedStyle(s); return s.classList.contains('sel') && cs.color !== cs.backgroundColor && s.getBoundingClientRect().height >= 44; }), tag + ': Mijn auto: keuzelijst met de gedeelde stijl, 44 px hoog');

    // ---------- 8. Hooguit één gevulde hoofdknop per scherm (eerst zoals bij de start, dan met alle toestemmingen) ----------
    if (!en) {
      const tel = () => pg.evaluate(() => {
        const s = document.querySelector('.screen.on'), probe = document.createElement('div');
        probe.style.background = 'var(--primary)'; document.body.append(probe); const prim = getComputedStyle(probe).backgroundColor; probe.remove();
        return [...s.querySelectorAll('button, [role=button]')].filter(e => e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden' &&
          getComputedStyle(e).backgroundColor === prim && !e.matches('[role=switch],[role=tab],[role=radio],[role=checkbox],[aria-pressed],[aria-selected],.tg') &&
          e.textContent.trim().length > 1).map(e => e.textContent.trim().slice(0, 30));
      });
      const teveel = [];
      for (const fase of ['start', 'met toestemmingen']) {
        if (fase !== 'start') await pg.evaluate(() => { const A = Android, tr = f => { try { f(); } catch (e) { } };
          tr(() => A.smsRequestPermission()); tr(() => A.callsRequestPermission()); tr(() => A.contactsRequestPermission()); tr(() => A.historyRequestPermission());
          tr(() => A.trackRequestPermission()); tr(() => A.recorderRequestPermissions()); tr(() => ['nearby', 'bgloc', 'notif'].forEach(k => A.apPerm(k)));
          tr(() => ['loc', 'bgloc', 'bt', 'notif'].forEach(k => A.autoPerm(k))); tr(() => ['mic', 'notif'].forEach(k => A.permRequest(k))); });
        for (const id of ids) {
          if (MEER_PRIMAIR[id]) continue;
          await pg.evaluate(id => { try { show(id); } catch (e) { } }, id); await pg.waitForTimeout(90 * W);
          const p = await tel();
          if (p.length > 1) teveel.push(fase + ': ' + id + ' (' + p.join(' | ') + ')');
        }
      }
      ok(!teveel.length, tag + ': hooguit één gevulde hoofdknop per scherm' + (teveel.length ? ': ' + teveel.join('; ') : ''));
      // Modellen: alleen het aanbevolen model gevuld
      await naar(pg, 'models');
      ok(await pg.evaluate(() => document.querySelectorAll('#md-cats .mini.fill').length === 1 && /Klein en snel|Small and fast/.test(document.querySelector('#md-cats .mini.fill').closest('.md-linebox').textContent)), tag + ': Modellen: alleen het aanbevolen model heeft een gevulde knop');
    }

    // Geen tekst buiten het scherm op de vernieuwde schermen
    const breed = [];
    for (const id of ['info', 'recorder', 'history', 'models', 'car', 'carset', 'gebruikset', 'gebruikontgr', 'apset', 'aisetvoice', 'aisetdata', 'facstuk', 'ralarm', 'redial', 'sleep', 'sleepset']) {
      await naar(pg, id);
      if (await pg.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)) breed.push(id);
    }
    ok(!breed.length, tag + ': niets breder dan het scherm' + (breed.length ? ' (' + breed + ')' : ''));
    // Kleine indelingsfouten: tikvlakken en de factuurdatum
    await naar(pg, 'ralarm');
    ok(await pg.evaluate(() => [...document.querySelectorAll('#al-days button')].every(b => { const r = b.getBoundingClientRect(); return r.width >= 44 && r.height >= 44; })), tag + ': Radiowekker: dagknoppen minstens 44×44');
    await naar(pg, 'redial');
    ok(await pg.evaluate(() => [...document.querySelectorAll('#rp-days button')].every(b => { const r = b.getBoundingClientRect(); return !r.width || (r.width >= 44 && r.height >= 44); })), tag + ': Auto redial: dagknoppen minstens 44×44');
    await naar(pg, 'facstuk');
    ok(await pg.evaluate(() => document.querySelector('#fs-datum').getBoundingClientRect().width >= 140), tag + ': Factuur: factuurdatum breed genoeg (' + await pg.evaluate(() => Math.round(document.querySelector('#fs-datum').getBoundingClientRect().width)) + ' px)');
    await naar(pg, 'loc'); await pg.waitForTimeout(250 * W);
    const zoom = await pg.evaluate(() => [...document.querySelectorAll('#lc-map .leaflet-control-zoom a')].map(a => { const r = a.getBoundingClientRect(); return [Math.round(r.width), Math.round(r.height)]; }));
    ok(zoom.length === 2 && zoom.every(([w, h]) => w >= 44 && h >= 44), tag + ': Locatie kiezen: zoomknoppen van de kaart minstens 44×44 (' + JSON.stringify(zoom) + ')');
    await naar(pg, 'car');
    ok(await pg.evaluate(() => { const b = document.querySelector('#car-export'), lh = parseFloat(getComputedStyle(b).lineHeight) || 21; return b.getBoundingClientRect().height < 2.5 * lh + 26; }), tag + ': Mijn auto: Maand exporteren past op hooguit twee regels');
    // Ritten vanzelf opnemen zegt zichtbaar dat de route met kilometers bewaard wordt
    await naar(pg, 'carset');
    ok(/kilometers|kilometres/.test(await pg.textContent('#car-trips-n')) && await pg.isVisible('#car-trips-n'), tag + ': Mijn auto: ritten opnemen zegt dat de route met kilometers bewaard wordt');
    // Gebruik → Ontgrendelen: de stand staat op de rij zelf (beveiliging), niet afgekapt
    await pg.evaluate(() => { Android._gb.admin = false; Android._gb.set = Object.assign({}, Android._gb.set, { punlock: 'uit', photoFail: false }); });
    await naar(pg, 'gebruikset');
    const uit = await pg.textContent('#gb-sum-ontgr');
    await pg.evaluate(() => { Android._gb.admin = true; Android._gb.set = Object.assign({}, Android._gb.set, { punlock: 'elke' }); enterGebruikSet(); });
    const aan = await pg.textContent('#gb-sum-ontgr');
    ok(/uit|off/i.test(uit) && /apparaatbeheer aan|device admin on/i.test(aan) && /elke ontgrendeling|every unlock/i.test(aan), tag + ': Ontgrendelen-rij toont de stand ("' + uit + '" / "' + aan + '")');
    ok(await pg.evaluate(() => { const e = document.querySelector('#gb-sum-ontgr'); return e.scrollWidth <= e.clientWidth + 1 && getComputedStyle(e).whiteSpace === 'normal'; }), tag + ': Ontgrendelen-stand wordt niet afgekapt');
    await pg.evaluate(() => { Android._gb.admin = false; Android._gb.set = Object.assign({}, Android._gb.set, { punlock: 'uit' }); });
    // Balk "Er ontbreekt een toestemming" op 360 px: de tekst krijgt de ruimte, de pijl blijft klein
    await pg.setViewportSize({ width: 360, height: FOLD });
    await pg.evaluate(() => { Android._car.trips = true; }); await naar(pg, 'car');
    const hint = await pg.evaluate(() => { const b = document.querySelector('#car-perm-hint'); if (!b || !b.getClientRects().length) return null; const c = b.querySelector('.stchev').getBoundingClientRect(), x = b.querySelector('span:not(.ui-icon)').getBoundingClientRect(); return { chev: Math.round(c.width), tekst: Math.round(x.width), breed: document.documentElement.scrollWidth > innerWidth + 1 }; });
    ok(hint && hint.chev <= 24 && hint.tekst >= 220 && !hint.breed, tag + ': Mijn auto: toestemmingsbalk op 360 px (' + JSON.stringify(hint) + ')');
    await pg.evaluate(() => { Android._car.trips = false; }); await pg.setViewportSize({ width: 390, height: FOLD });
    await pg.screenshot({ path: __dirname + '/shots/ui-patronen-' + scheme + (en ? '-en' : '') + '.png' });
    await ctx.close();
  }

  // Telefoon-skin: rij met tools vervaagt aan de rand, statusregels 44 px
  for (const scheme of ['light', 'dark']) {
    const pg = await (await b.newContext({ viewport: { width: 390, height: FOLD }, colorScheme: scheme })).newPage();
    pg.on('pageerror', e => errs.push('skin: ' + e.message));
    await pg.goto('file://' + path.join(__dirname, '..', '..', 'web', 'start.html')); await pg.waitForTimeout(600 * W);
    const st = await pg.evaluate(() => ({ fade: !!document.querySelector('#tools .tchips.fade'), rows: [...document.querySelectorAll('#tools .trow')].map(r => r.getBoundingClientRect().height), emoji: [...document.querySelectorAll('#tools .tchip > span')].filter(s => !s.querySelector('.ui-icon')).map(s => s.textContent) }));
    ok(st.fade, 'skin ' + scheme + ': rij met tools vervaagt rechts (er is meer)');
    ok(st.rows.length && st.rows.every(h => h >= 44), 'skin ' + scheme + ': statusregels minstens 44 px (' + st.rows.map(Math.round) + ')');
    ok(!st.emoji.length, 'skin ' + scheme + ': tools met lijniconen, geen emoji (' + st.emoji + ')');
    await pg.evaluate(() => { const c = document.querySelector('#tools .tchips'); c.scrollLeft = c.scrollWidth; c.dispatchEvent(new Event('scroll')); }); await pg.waitForTimeout(60 * W);
    ok(!(await pg.evaluate(() => !!document.querySelector('#tools .tchips.fade'))), 'skin ' + scheme + ': aan het eind geen vervaging meer');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})().catch(e => { console.error(e); process.exitCode = 1; });
