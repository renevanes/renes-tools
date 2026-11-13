// Thema (Weergave): Systeem, Licht, Donker, Automatisch op tijd en met de zon, Zwart (AMOLED), snel wisselen,
// bewaren, het register (assistent) en de Telefoon-skin. Kleuren worden gecontroleerd op de berekende --bg.
const { chromium } = require('playwright');
const path = require('path');
const LIGHT = '#F4F5F6', DARK = '#111619';
(async () => {
  const b = await chromium.launch();
  const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const page = async (scheme, extra) => {
    const ctx = await b.newContext(Object.assign({ viewport: { width: 390, height: 844 }, colorScheme: scheme, timezoneId: 'Europe/Amsterdam' }, extra || {}));
    const pg = await ctx.newPage();
    pg.on('pageerror', e => errs.push(e.message));
    pg.on('console', m => { if (m.type() === 'error' && !/ERR_TUNNEL|ERR_NAME|ERR_FILE/.test(m.text()) && !/\/icon\//.test((m.location() || {}).url || '')) errs.push(m.text() + ' @ ' + JSON.stringify(m.location())); });
    return { ctx, pg };
  };
  const url = 'file://' + path.join(__dirname, 'ui', 'index.html');
  const bg = pg => pg.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--bg').trim().toUpperCase());
  const lum = h => { const f = v => { v /= 255; return v <= .03928 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }; const n = parseInt(h.slice(1), 16); return .2126 * f(n >> 16 & 255) + .7152 * f(n >> 8 & 255) + .0722 * f(n & 255); };
  const roles = pg => pg.evaluate(() => { const cs = getComputedStyle(document.documentElement); return ['--bg', '--card', '--hi', '--ink', '--muted'].map(n => cs.getPropertyValue(n).trim()); });

  // 1. Licht schema in de browser: Systeem volgt, Donker wordt donker
  {
    const { ctx, pg } = await page('light');
    await pg.goto(url); await pg.waitForTimeout(300);
    ok(await bg(pg) === LIGHT && !(await pg.evaluate(() => uiDark())), 'Systeem in een lichte browser: licht');
    await pg.evaluate(() => show('setlook')); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => $('#st-theme').options.length === 5 && $('#st-theme').value === 'system'), 'keuzelijst met vijf thema\'s, standaard Systeem');
    ok(/Nu: licht, zoals je telefoon/.test(await pg.textContent('#st-th-now')), 'status: ' + await pg.textContent('#st-th-now'));
    await pg.evaluate(() => { window._tc = 0; addEventListener('themechange', () => window._tc++); });
    await pg.selectOption('#st-theme', 'dark'); await pg.waitForTimeout(150);
    ok(await bg(pg) === DARK, 'Donker: donkere achtergrond (' + await bg(pg) + ')');
    const s = await pg.evaluate(() => ({ dark: uiDark(), ev: window._tc, bars: Android._bars, cfg: JSON.parse(Android._themeCfg || '{}'), now: JSON.parse(localStorage.getItem('rt.themeNow')), attr: document.documentElement.dataset.thema,
      tile: (() => { show('home'); const i = document.querySelector('.tile .ic'); const c = i ? getComputedStyle(i).color : ''; show('setlook'); return c; })() }));
    ok(s.dark && s.ev === 1 && s.attr === 'donker', 'uiDark() en gebeurtenis themechange');
    ok(s.bars && s.bars[0] === DARK && s.bars[2] === false, 'systeembalken donker, lichte knoppen (' + JSON.stringify(s.bars) + ')');
    ok(s.cfg.mode === 'dark' && s.now.dark === true, 'doorgegeven aan Java en onthouden voor de volgende start');
    ok(!/rgb\(24, 93, 82\)/.test(s.tile), 'ook de andere donkere regels (pictogram op een tegel: ' + s.tile + ')');
    ok(await pg.evaluate(() => getComputedStyle(document.querySelector('#st-th-dark')).colorScheme) === 'dark', 'tijdkiezers in donker');
    // Zwart (AMOLED)
    await pg.click('#st-amoled'); await pg.waitForTimeout(100);
    ok(await bg(pg) === '#000000' && await pg.evaluate(() => thCfg().amoled), 'Zwart (AMOLED): zwarte achtergrond');
    { const [b0, c0, h0, ink, mu] = await roles(pg), ratio = (a, b) => (Math.max(lum(a), lum(b)) + .05) / (Math.min(lum(a), lum(b)) + .05);
      ok(lum(h0) > lum(c0) && lum(c0) > lum(b0), 'Zwart (AMOLED): --hi (' + h0 + ') lichter dan --card (' + c0 + '), lichter dan --bg');
      ok(ratio(h0, c0) >= 1.17 && ratio(ink, h0) >= 7 && ratio(mu, h0) >= 4.5 && ratio(ink, c0) >= 7, 'Zwart (AMOLED): velden zichtbaar op een kaart, tekst goed leesbaar (' + ratio(h0, c0).toFixed(2) + ', ' + ratio(mu, h0).toFixed(1) + ')'); }
    // Met kleuren uit je achtergrond: ook die volgen het thema
    await pg.evaluate(() => { const t = [0, 10, 50, 100, 200, 400, 500, 600, 700, 800, 900, 1000], pal = () => Object.fromEntries(t.map(x => { const v = Math.round(255 - x * .255).toString(16).padStart(2, '0'); return [x, '#' + v + v + v]; }));
      Android._theme = { accent1: pal(), accent2: pal(), neutral1: pal(), neutral2: pal() }; store.set('dynColor', true); applyTheme(); });
    await pg.click('#st-amoled'); await pg.waitForTimeout(100);
    const dyn = await bg(pg);
    ok(dyn !== DARK && dyn !== LIGHT && parseInt(dyn.slice(1, 3), 16) < 0x60, 'kleuren uit je achtergrond, donker (' + dyn + ')');
    await pg.selectOption('#st-theme', 'light'); await pg.waitForTimeout(100);
    ok(parseInt((await bg(pg)).slice(1, 3), 16) > 0xA0, 'kleuren uit je achtergrond, licht (' + await bg(pg) + ')');
    await pg.evaluate(() => { store.set('dynColor', false); applyTheme(); });
    // Snel wisselen in de kop van Instellingen, met Ongedaan maken
    await pg.evaluate(() => show('settings')); await pg.waitForTimeout(100);
    ok(await pg.getAttribute('#st-th-quick', 'aria-label') === 'Donker thema', 'snelle knop: "Donker thema"');
    await pg.click('#st-th-quick'); await pg.waitForTimeout(100);
    ok(await bg(pg) === DARK && await pg.evaluate(() => thCfg().mode) === 'dark' && await pg.getAttribute('#st-th-quick', 'aria-label') === 'Licht thema', 'snelle knop: donker');
    ok(/Thema: (donker|licht)/.test(await pg.textContent('#st-sum-look')), 'samenvatting bij Weergave: ' + await pg.textContent('#st-sum-look'));
    await pg.click('#undo-b'); await pg.waitForTimeout(100);
    ok(await bg(pg) === LIGHT && await pg.evaluate(() => thCfg().mode) === 'light', 'Ongedaan maken: weer licht');
    // Bewaren: na opnieuw laden nog steeds zo (en meteen, via de laatst berekende stand)
    await pg.evaluate(() => thSetMode('dark'));
    await pg.reload(); await pg.waitForTimeout(300);
    ok(await bg(pg) === DARK && await pg.evaluate(() => thCfg().mode) === 'dark', 'na opnieuw openen nog donker');
    await ctx.close();
  }

  // 2. Donker schema in de browser: Licht wordt licht, Systeem volgt (ook als de telefoon wisselt)
  {
    const { ctx, pg } = await page('dark');
    await pg.goto(url); await pg.waitForTimeout(300);
    ok(await bg(pg) === DARK, 'Systeem in een donkere browser: donker');
    await pg.evaluate(() => thSetMode('light')); await pg.waitForTimeout(100);
    ok(await bg(pg) === LIGHT && !(await pg.evaluate(() => uiDark())) && (await pg.evaluate(() => Android._bars))[2] === true, 'Licht in een donkere browser: licht, donkere knoppen in de balk');
    await pg.evaluate(() => thSetMode('system')); await pg.waitForTimeout(100);
    ok(await bg(pg) === DARK, 'terug naar Systeem: weer donker');
    await pg.emulateMedia({ colorScheme: 'light' }); await pg.waitForTimeout(150);
    // De wissel van de telefoon komt als 'change' van matchMedia: onder belasting soms later dan 150 ms
    await pg.waitForFunction(L => getComputedStyle(document.documentElement).getPropertyValue('--bg').trim().toUpperCase() === L && (Android._bars || [])[0] === L, LIGHT, { timeout: 5000 }).catch(() => {});
    ok(await bg(pg) === LIGHT && !(await pg.evaluate(() => uiDark())) && (await pg.evaluate(() => Android._bars))[0] === LIGHT, 'telefoon wordt licht: de app (en de balken) ook');
    await ctx.close();
  }

  // 3. Automatisch op tijd, met een nagebootste klok
  {
    const { ctx, pg } = await page('light');
    await pg.clock.install({ time: new Date('2026-11-03T19:57:30+01:00') });
    await pg.goto(url); await pg.waitForTimeout(300);
    await pg.evaluate(() => { show('setlook'); window._anim = []; addEventListener('themechange', () => window._anim.push(document.documentElement.classList.contains('th-anim'))); });
    await pg.selectOption('#st-theme', 'time'); await pg.waitForTimeout(100);
    ok(await pg.isVisible('#st-th-dark') && await pg.isVisible('#st-th-light') && !(await pg.isVisible('#st-th-off')), 'op tijd: donker vanaf en licht vanaf zichtbaar');
    ok(await pg.inputValue('#st-th-dark') === '20:00' && await pg.inputValue('#st-th-light') === '07:00', 'standaard 20:00 en 07:00');
    ok(await bg(pg) === LIGHT && /Nu: licht · wordt donker om 20:00/.test(await pg.textContent('#st-th-now')), 'om 19:57: ' + await pg.textContent('#st-th-now'));
    ok(JSON.parse(await pg.evaluate(() => Android._themeCfg)).win.join() === '1200,420', 'Java krijgt het venster 20:00–07:00');
    const tn = () => pg.evaluate(() => JSON.parse(localStorage.getItem('rt.themeNow')));
    ok((await tn()).dark === false && (await tn()).until === new Date('2026-11-03T20:00:00+01:00').getTime(), 'onthouden stand: licht tot 20:00');
    await pg.evaluate(() => { window._ts = 0; const o = Android.themeSet.bind(Android); Android.themeSet = j => { window._ts++; o(j); }; });
    await pg.clock.runFor(3 * 60000); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => window._ts) === 1, 'bij de wissel krijgt Java het opnieuw (achtergrond en afdeklaag van het app-slot)');
    ok((await tn()).dark === true && (await tn()).until === new Date('2026-11-04T07:00:00+01:00').getTime(), 'onthouden stand bijgewerkt: donker tot morgen 07:00');
    ok(await pg.evaluate(() => setGet(setEntry('weergave.donker'))) === 'aan', 'register: "Donker thema" staat aan zolang de app automatisch donker is');
    ok(await bg(pg) === DARK && /Nu: donker · wordt licht morgen om 07:00/.test(await pg.textContent('#st-th-now')), 'om 20:00 vanzelf donker: ' + await pg.textContent('#st-th-now'));
    ok((await pg.evaluate(() => window._anim)).slice(-1)[0] === true, 'met een korte overgang');
    await pg.fill('#st-th-dark', '21:30'); await pg.dispatchEvent('#st-th-dark', 'change'); await pg.waitForTimeout(100);
    ok(await bg(pg) === LIGHT && await pg.evaluate(() => thCfg().dark) === '21:30', 'donker vanaf 21:30: om 20:00 weer licht');
    ok((await tn()).dark === false && (await tn()).until === new Date('2026-11-03T21:30:00+01:00').getTime(), 'na een andere tijd: onthouden stand licht tot 21:30 (niet verouderd)');
    // De korte overgang hierboven doorloopt iedere timer. Grote sprongen controleren de eindstand,
    // zonder ook iedere kloktik van de andere app-onderdelen urenlang af te spelen.
    await pg.clock.fastForward(100 * 60000); await pg.waitForTimeout(100);
    ok(await bg(pg) === DARK, 'om 21:40 donker');
    await pg.clock.fastForward(10 * 3600000); await pg.waitForTimeout(100);
    ok(await bg(pg) === LIGHT, 'de volgende ochtend om 07:40 weer licht (over middernacht)');
    // Minder beweging: geen overgang
    await pg.emulateMedia({ reducedMotion: 'reduce' });
    const rm = await pg.evaluate(() => { thAnim(); return getComputedStyle(document.querySelector('.card')).transitionDuration; });
    await pg.emulateMedia({ reducedMotion: 'no-preference' });
    const nm = await pg.evaluate(() => { thAnim(); return getComputedStyle(document.querySelector('.card')).transitionDuration; });
    ok(!/0\.45s/.test(rm) && /0\.45s/.test(nm), 'overgang alleen zonder Minder beweging (' + rm + ' / ' + nm + ')');
    await ctx.close();
  }

  // 4. Met de zon: plaats, verschuiving, je locatie
  {
    const { ctx, pg } = await page('light');
    await pg.clock.install({ time: new Date('2026-11-03T16:50:00+01:00') });
    await pg.goto(url); await pg.waitForTimeout(300);
    await pg.evaluate(() => show('setlook'));
    await pg.selectOption('#st-theme', 'sun'); await pg.waitForTimeout(100);
    ok(await pg.isVisible('#st-th-off') && await pg.isVisible('#st-th-loc') && !(await pg.isVisible('#st-th-dark')), 'zon: verschuiving en plaats zichtbaar');
    ok(/in Rotterdam \(je locatie is niet bekend\)/.test(await pg.textContent('#st-th-sunnote')) && /zonder internet/.test(await pg.textContent('#st-th-sunnote')), 'zonder locatie: Rotterdam (' + await pg.textContent('#st-th-sunnote') + ')');
    ok(await bg(pg) === LIGHT && /wordt donker om 17:1\d/.test(await pg.textContent('#st-th-now')), '16:50: ' + await pg.textContent('#st-th-now'));
    const sch = JSON.parse(await pg.evaluate(() => Android._themeCfg));
    const nu = await pg.evaluate(() => Date.now()); // dezelfde gesimuleerde klok als het rooster
    ok(sch.mode === 'sun' && sch.sched.length >= 60 && sch.end > nu + 30 * 864e5 && sch.win.length === 2, 'Java krijgt een rooster voor ruim vier weken');
    await pg.selectOption('#st-th-off', '-30'); await pg.waitForTimeout(100);
    ok(await bg(pg) === DARK, 'half uur eerder donker: om 16:50 al donker');
    await pg.selectOption('#st-th-loc', 'groningen'); await pg.waitForTimeout(100);
    ok(/in Groningen/.test(await pg.textContent('#st-th-sunnote')), 'vaste plaats: Groningen');
    await pg.evaluate(() => { Android._themeLoc = '69.65,18.96'; thLocC = null; }); // Tromsø, november: korte dagen
    await pg.selectOption('#st-th-loc', 'auto'); await pg.waitForTimeout(100);
    ok(/op je laatst bekende locatie/.test(await pg.textContent('#st-th-sunnote')), 'met locatie: ' + await pg.textContent('#st-th-sunnote'));
    await ctx.close();
  }

  // 5. Register: de assistent kan het thema aanpassen
  {
    const { ctx, pg } = await page('light');
    await pg.goto(url); await pg.waitForTimeout(300);
    const r = await pg.evaluate(() => {
      const out = {};
      setEntry('weergave.donker').set('aan'); out.donker = [setGet(setEntry('weergave.donker')), setGet(setEntry('weergave.thema')), uiDark()];
      setEntry('weergave.thema').set('system'); out.sys = uiDark();
      setEntry('weergave.donkervanaf').set('21:00'); out.tijd = [thCfg().mode, thCfg().dark, setGet(setEntry('weergave.donkervanaf'))];
      setEntry('weergave.amoled').set('aan'); out.amoled = thCfg().amoled; setEntry('weergave.amoled').set('uit');
      setEntry('weergave.thema').set('system'); thSave({ dark: '20:00' });
      const h = setHandle('maak het automatisch donker vanaf 21 uur'), it = h && h.set && h.set.items && h.set.items[0];
      out.zin = it ? it.id + '=' + it.to : JSON.stringify(h);
      const h2 = setHandle('zet donker thema aan'), it2 = h2 && h2.set && h2.set.items && h2.set.items[0];
      out.zin2 = it2 ? it2.id + '=' + it2.to : JSON.stringify(h2);
      const h3 = setHandle('zet het thema op licht'), it3 = h3 && h3.set && h3.set.items && h3.set.items[0];
      out.zin3 = it3 ? it3.id + '=' + it3.to : JSON.stringify(h3);
      const h4 = setHandle('zet nachtmodus aan'), it4 = h4 && h4.set && h4.set.items && h4.set.items[0];
      out.zin4 = it4 ? it4.id + '=' + it4.to : JSON.stringify(h4);
      out.syn = ['dark mode', 'avond', 'zonsondergang'].map(q => (setFind(q)[0] || {}).e).map(e => e && e.id);
      const one = q => { const h = setHandle(q), it = h && h.set && h.set.items && h.set.items[0]; return it ? it.id + '=' + it.to : h && h.set && h.set.choose ? 'kies ' + h.set.choose : ''; };
      out.lamp = ['zet het licht om 7 uur uit', 'zet het licht om 22 uur aan', 'maak het licht om 8 uur', 'zet het licht uit', 'maak het licht vanaf 7 uur'].map(q => q + ' → ' + one(q));
      out.kleur = [one('maak het donker vanaf 21:00'), one('maak de app automatisch licht vanaf 6:45'), one('zet het thema op zon'), one('zet het thema op tijd')];
      return out;
    });
    const r2 = await pg.evaluate(() => {
      const hh = k => { const d = new Date(Date.now() + k * 36e5); return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0'); };
      thSave({ mode: 'time', light: hh(-1), dark: hh(1) }); // nu licht, wat de klok ook is
      const e = setEntry('weergave.donker'); return [setGet(e), e.note('uit')];
    });
    ok(r.donker.join() === 'aan,dark,true', 'register: donker thema aan (' + r.donker + ')');
    ok(r.sys === false, 'register: terug naar Systeem');
    ok(r.tijd.join() === 'time,21:00,21:00', 'register: donker vanaf 21:00 zet ook Automatisch op tijd (' + r.tijd + ')');
    ok(r.amoled === true, 'register: Zwart (AMOLED)');
    ok(r.zin === 'weergave.donkervanaf=21:00', '"maak het automatisch donker vanaf 21 uur" → ' + r.zin);
    ok(r.zin2 === 'weergave.donker=aan', '"zet donker thema aan" → ' + r.zin2);
    ok(r.zin3 === 'weergave.thema=light', '"zet het thema op licht" → ' + r.zin3);
    ok(r.zin4 === 'weergave.donker=aan', '"zet nachtmodus aan" → ' + r.zin4);
    ok(r.syn.every(id => /^weergave\./.test(id || '')), 'zoekwoorden dark mode, avond, zonsondergang → ' + r.syn.join(', '));
    ok(r.lamp.every(x => !/weergave\./.test(x)), 'een lamp is geen thema: ' + r.lamp.join(' | '));
    ok(r.kleur.join() === 'weergave.donkervanaf=21:00,weergave.lichtvanaf=06:45,weergave.thema=sun,weergave.thema=time', 'thema-zinnen: ' + r.kleur.join(', '));
    ok(r2[0] === 'uit' && /stond op Automatisch op tijd; daarna is het altijd licht/.test(r2[1]), 'Donker thema in Automatisch op tijd (overdag): ' + r2.join(' / '));
    await ctx.close();
  }

  // 5b. Vóór de eerste weergave (body-begin.html): onthouden stand, verlopen stand, en dan vragen aan de app
  {
    const prePage = async (now, java) => {
      const { ctx, pg } = await page('light');
      await pg.addInitScript(([n, j]) => {
        try { localStorage.setItem('rt.themeNow', JSON.stringify(n)); localStorage.setItem('rt.theme', JSON.stringify({ mode: 'time', dark: '00:00', light: '00:01' })); } catch (e) { }
        if (j == null) return;
        // De echte app heeft Android al vóór de pagina; hier alleen voor body-begin.html (daarna neemt de testbrug het over)
        const stub = { themeDark: () => j };
        Object.defineProperty(window, 'Android', { configurable: true, get: () => document.scripts.length <= 2 ? stub : undefined,
          set: v => Object.defineProperty(window, 'Android', { value: v, writable: true, configurable: true }) });
      }, [now, java]);
      await pg.goto(url); await pg.waitForTimeout(300);
      const pre = await pg.evaluate(() => window.thPre);
      await ctx.close();
      return pre;
    };
    ok(await prePage({ dark: true, until: Date.now() + 36e5 }, null) === true, 'onthouden stand nog geldig: meteen donker');
    ok(await prePage({ dark: true, until: 0 }, null) === true, 'vaste keuze (Donker): meteen donker');
    ok(await prePage({ dark: false, until: Date.now() - 6e4 }, null) === null, 'verlopen stand, geen app: niets vastzetten (geen verouderde kleur)');
    ok(await prePage({ dark: false, until: Date.now() - 6e4 }, true) === true, 'verlopen stand: de app zegt donker, dan meteen donker');
    ok(await prePage({ dark: null, until: 0 }, true) === null, 'Systeem: niets vastzetten');
  }

  // 6. Telefoon-skin: "Zoals Tools" volgt het thema van de app (Android.isNight), ook als dat vanzelf wisselt
  {
    const { ctx, pg } = await page('light');
    await pg.goto('file://' + path.join(__dirname, '..', '..', 'web', 'start.html')); await pg.waitForTimeout(400);
    ok(!(await pg.evaluate(() => document.documentElement.classList.contains('dark'))), 'skin: licht zoals Tools');
    await pg.evaluate(() => { Android.isNight = () => true; tickClock(); });
    ok(await pg.evaluate(() => document.documentElement.classList.contains('dark')), 'skin: Tools wordt donker, de skin bij de volgende kloktik ook');
    await pg.evaluate(() => { Android.isNight = () => false; window.onResumeHome(); });
    ok(!(await pg.evaluate(() => document.documentElement.classList.contains('dark'))), 'skin: weer licht bij terugkomen');
    await ctx.close();
  }
  await b.close();
  console.log('errors', errs);
  if (errs.length) process.exit(1);
})().catch(e => { console.log(e.message); process.exit(1); });
