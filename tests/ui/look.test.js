// Vormgeving: alle hoofdschermen licht en donker, met vaste kleuren en met kleuren uit de achtergrond.
// Maakt screenshots in tests/ui/shots/look-*.png en controleert kop, tabbalk en contrast.
const { chromium } = require('playwright');
const assert=require('node:assert/strict');
const SCREENS = ['home', 'search', 'backup', 'settings', 'redial', 'wa', 'calls', 'notes', 'contacts', 'radio', 'podcasts', 'podspotify', 'spotify', 'vpn', 'loc', 'sms', 'tracks', 'car', 'kluis', 'pdf', 'recorder', 'sleep', 'checkin', 'checkincmp', 'apparaten', 'apset', 'transcripts', 'music', 'history', 'auto', 'info', 'homeedit', 'selftest'];
// Een paars Material You-palet zoals Android het geeft
const DYN = (() => { const t = [0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000];
  const pal = (h, s) => Object.fromEntries(t.map(x => { const l = 100 - x / 10; return [x, hsl(h, s, x === 0 ? 100 : x === 1000 ? 0 : l)]; }));
  function hsl(h, s, l){ s /= 100; l /= 100; const k = n => (n + h / 30) % 12, a = s * Math.min(l, 1 - l), f = n => l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1)));
    return '#' + [f(0), f(8), f(4)].map(x => Math.round(x * 255).toString(16).padStart(2, '0')).join('').toUpperCase(); }
  return { accent1: pal(285, 55), accent2: pal(285, 18), accent3: pal(340, 30), neutral1: pal(285, 5), neutral2: pal(285, 9) }; })();
function lum(c){ const m = c.match(/\d+(\.\d+)?/g).map(Number); const f = v => { v /= 255; return v <= .03928 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }; return .2126 * f(m[0]) + .7152 * f(m[1]) + .0722 * f(m[2]); }
function ratio(a, b){ const x = lum(a), y = lum(b); return (Math.max(x, y) + .05) / (Math.min(x, y) + .05); }
(async () => {
  const b = await chromium.launch();
  const errs = [], fails = [];
  for (const scheme of ['light', 'dark']) for (const dyn of [false, true]) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message)); pg.on('console', m => { if (m.type() === 'error' && !m.text().includes('ERR_TUNNEL') && !m.text().includes('ERR_NAME')) errs.push(m.text()); });
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    if (dyn) await pg.evaluate(d => { Android._theme = d; store.set('dynColor',true); applyTheme(); }, DYN);
    const tag = scheme + (dyn ? '-dyn' : '');
    for (const s of SCREENS) {
      await pg.evaluate(n => show(n), s); await pg.waitForTimeout(120);
      if (['home', 'settings', 'calls', 'spotify', 'radio', 'notes', 'search'].includes(s) || !dyn) await pg.screenshot({ path: __dirname + '/shots/look-' + s + '-' + tag + '.png' });
      // titel en tekst goed leesbaar op de achtergrond
      const c = await pg.evaluate(() => { const h = document.querySelector('.screen.on .hdr .hrow'), t = document.querySelector('.screen.on .hdr h1'); const cs = getComputedStyle(h), ct = getComputedStyle(t);
        const card = document.querySelector('.screen.on .card'); const cc = card ? getComputedStyle(card) : null; const h2 = card && card.querySelector('h2');
        return { bg: cs.backgroundColor, fg: ct.color, card: cc && cc.backgroundColor, h2: h2 ? getComputedStyle(h2).color : null, tabs: document.body.classList.contains('tabs-on') }; });
      if (ratio(c.bg, c.fg) < 7) fails.push(tag + ' ' + s + ': titel ' + ratio(c.bg, c.fg).toFixed(1));
      if (c.h2 && ratio(c.card, c.h2) < 4.5) fails.push(tag + ' ' + s + ': kaartkop ' + ratio(c.card, c.h2).toFixed(1));
      if (c.tabs !== ['home', 'search', 'backup', 'settings'].includes(s)) fails.push(tag + ' ' + s + ': tabbalk ' + c.tabs);
    }
    // knoppen: tekst op de knopkleur
    await pg.evaluate(() => show('setskin'));
    const bt = await pg.evaluate(() => [...document.querySelectorAll('.screen.on .big')].slice(0, 6).map(e => { const cs = getComputedStyle(e); return [cs.backgroundColor, cs.color, e.textContent.trim().slice(0, 20)]; }));
    for (const [bg, fg, t] of bt) if (ratio(bg, fg) < 4.5) fails.push(tag + ' knop "' + t + '": ' + ratio(bg, fg).toFixed(1));
    // kop schuift: grote titel weg, kleine titel zichtbaar
    await pg.evaluate(() => show('settings')); await pg.evaluate(() => scrollTo(0, 400)); await pg.waitForTimeout(250);
    const k = await pg.evaluate(() => { const h = document.querySelector('.screen.on .hdr'), r = h.querySelector('.hrow').getBoundingClientRect(); return { compact: h.classList.contains('compact'), hs: h.querySelector('.hs').textContent, top: r.top, h1: h.querySelector('h1').getBoundingClientRect().bottom, bars: Android._bars }; });
    if (!k.compact || k.hs !== 'Instellingen' || k.top !== 0 || k.h1 > 0) fails.push(tag + ' kop schuift niet: ' + JSON.stringify(k));
    await pg.screenshot({ path: __dirname + '/shots/look-scrolled-' + tag + '.png' });
    // tab wisselen
    await pg.evaluate(() => scrollTo(0, 0)); await pg.click('#tabs button[data-t=search]'); await pg.waitForTimeout(150);
    const ss = await pg.evaluate(() => ({ cur: current, focus: document.activeElement && document.activeElement.id }));
    if (ss.cur !== 'search' || ss.focus !== 'gs-q') fails.push(tag + ' zoektab: ' + JSON.stringify(ss));
    await pg.fill('#gs-q', 'melding'); await pg.waitForTimeout(500);
    await pg.screenshot({ path: __dirname + '/shots/look-searchres-' + tag + '.png' });
    // detailscherm met kleine kop
    await pg.evaluate(() => { show('notes'); }); await pg.waitForTimeout(100);
    await pg.close();
  }
  await b.close();
  console.log(fails.length ? fails.join('\n') : '✓ vormgeving');
  console.log('errors', errs);
  assert.deepEqual(fails,[]);assert.deepEqual(errs,[]);
})().catch(e => { console.log(e.message); process.exit(1); });
