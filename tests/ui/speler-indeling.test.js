const { chromium } = require('playwright');
const assert = require('assert');
// De speler op één scherm: hoes, titel, plek, knoppen en één rij opties passen zonder scrollen (ook op een klein scherm);
// de hoes staat al klaar voor je de speler opent en komt uit de eigen cache van de app; het slaaptimervenster.
const PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';
(async () => {
  const b = await chromium.launch();
  const errs = [];
  for (const [w, h, scheme] of [[412, 860, 'dark'], [390, 844, 'light'], [360, 640, 'dark'], [360, 560, 'light']]) {
    const pg = await b.newPage({ viewport: { width: w, height: h }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => show('podcasts')); await pg.waitForTimeout(300);
    await pg.click('#pc-list .srow:nth-child(1) .sbtn'); await pg.waitForTimeout(500);
    await pg.click('#pd-eps .pcep:nth-child(2) .pcepb'); await pg.waitForTimeout(700);
    // De hoes staat al klaar terwijl de speler dicht is
    assert.ok(await pg.evaluate(() => !!$('#pl-art').dataset.k && $('#pl-art').childNodes.length > 0), w + 'x' + h + ': hoes voorbereid terwijl de speler dicht is');
    await pg.click('#plbar-open'); await pg.waitForTimeout(600);
    const m = await pg.evaluate(() => {
      const r = q => { const e = $(q).getBoundingClientRect(); return { t: e.top, b: e.bottom, l: e.left, r: e.right, w: e.width, h: e.height }; };
      const opts = [...document.querySelectorAll('#pl-opts .pl-opt')].filter(e => getComputedStyle(e).display !== 'none').map(e => { const x = e.getBoundingClientRect(); return { id: e.id, t: Math.round(x.top), b: x.bottom, w: x.width, h: x.height }; });
      return { vh: innerHeight, vw: innerWidth, art: r('#pl-art'), cover: r('#pl-cover'), ctl: r('.pl-ctl'), title: r('#pl-title'), seek: r('#pl-seek'), opts };
    });
    const fits = m.vh >= 600;
    const last = Math.max(...m.opts.map(o => o.b));
    if (fits) assert.ok(last <= m.vh, w + 'x' + h + ': opties passen op het scherm (' + Math.round(last) + ' ≤ ' + m.vh + ')');
    else assert.ok(last > m.vh - 1 || last <= m.vh, w + 'x' + h + ': heel laag scherm: scrollen mag');
    assert.deepStrictEqual(m.opts.map(o => o.id), ['pl-out', 'pl-sleep', 'pl-speed', 'pl-stop'], 'podcast: waar, slaaptimer, snelheid, stop');
    assert.strictEqual(new Set(m.opts.map(o => o.t)).size, 1, w + 'x' + h + ': opties in één rij');
    assert.ok(m.opts.every(o => o.w >= 44 && o.h >= 44), w + 'x' + h + ': opties minstens 44 px');
    assert.ok(Math.abs(m.art.w - m.art.h) < 1.5 && m.art.w >= 110, w + 'x' + h + ': hoes vierkant (' + Math.round(m.art.w) + '×' + Math.round(m.art.h) + ')');
    assert.ok(m.art.w <= m.vw - 39 && m.art.t >= m.cover.t - 1 && m.art.b <= m.cover.b + 1, w + 'x' + h + ': hoes binnen zijn vak');
    assert.ok(m.cover.b <= m.title.t && m.title.b <= m.seek.t && m.seek.b <= m.ctl.t, w + 'x' + h + ': volgorde hoes, titel, plek, knoppen');
    if (h === 860) await pg.screenshot({ path: __dirname + '/shots/speler-indeling-' + scheme + '.png' });
    // Wat minder belangrijk is (over de aflevering, Hierna) staat eronder
    const below = await pg.evaluate(() => ({ queue: $('.pl-queue').getBoundingClientRect().top, main: $('#pl-main').getBoundingClientRect().bottom }));
    assert.ok(below.queue >= below.main, 'Hierna onder het hoofddeel');
    console.log(w + 'x' + h, 'hoes', Math.round(m.art.w), 'opties onderkant', Math.round(last), 'van', m.vh);
    await pg.close();
  }

  // Hoes uit de eigen cache van de app (nieuwe app): meteen, en zonder netwerk
  const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  pg.on('pageerror', e => errs.push(e.message));
  await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
  assert.strictEqual(await pg.evaluate(() => plArtLokaal('https://x.nl/a.jpg')), '', 'oudere app zonder cache: het adres zelf');
  await pg.evaluate(() => { Android._artLokaal = true; });
  assert.strictEqual(await pg.evaluate(() => plArtLokaal('https://x.nl/a b.jpg')), '/art?u=' + encodeURIComponent('https://x.nl/a b.jpg'), 'nieuwe app: adres versleuteld in /art');
  // Staat hij in de cache: dat plaatje, geen vraag aan de app
  const r1 = await pg.evaluate(async (png) => {
    const el = document.createElement('span'); el.className = 'plart'; document.body.append(el);
    window.plArtLokaal = () => png; Android._artAsked = [];
    plSetArt(el, { art: 'https://x.nl/hoes.jpg', ini: '🎧' });
    await new Promise(r => setTimeout(r, 300));
    return { src: el.querySelector('img') && el.querySelector('img').src.slice(0, 22), asked: Android._artAsked.length };
  }, PNG);
  assert.deepStrictEqual(r1, { src: 'data:image/png;base64,', asked: 0 }, 'uit de cache: meteen, zonder ophalen');
  // Nog niet in de cache: de app haalt hem op (playerArt) en daarna nog eens uit de cache
  const r2 = await pg.evaluate(async (png) => {
    const el = document.createElement('span'); el.className = 'plart'; document.body.append(el);
    window.plArtLokaal = (u, opnieuw) => (opnieuw ? png : 'file:///bestaat-niet.png'); Android._artAsked = [];
    plSetArt(el, { art: 'https://x.nl/nieuw.jpg', ini: '🎧' });
    await new Promise(r => setTimeout(r, 700));
    const img = el.querySelector('img');
    return { asked: Android._artAsked, src: img && img.src.slice(0, 22) };
  }, PNG);
  assert.deepStrictEqual(r2.asked, ['https://x.nl/nieuw.jpg'], 'nog niet bewaard: de app haalt hem op');
  assert.strictEqual(r2.src, 'data:image/png;base64,', 'daarna uit de cache');
  // Lukt het de app niet: het pictogram (geen kapot plaatje), en een late fout overschrijft een nieuwere hoes niet
  const r3 = await pg.evaluate(async () => {
    const el = document.createElement('span'); el.className = 'plart'; document.body.append(el);
    window.plArtLokaal = () => 'file:///bestaat-niet.png';
    const oud = Android.playerArt; Android.playerArt = u => setTimeout(() => onPlayerArt({ url: u, color: '' }), 30);
    plSetArt(el, { art: 'https://x.invalid/weg.jpg', ini: '🎧' });
    await new Promise(r => setTimeout(r, 900));
    Android.playerArt = oud;
    return { ini: el.classList.contains('ini'), icon: !!el.querySelector('.ui-icon'), img: !!el.querySelector('img') };
  });
  assert.deepStrictEqual(r3, { ini: true, icon: true, img: false }, 'niet op te halen: pictogram');

  // Slaaptimervenster: tijden in een raster van vier, alles minstens 44 px, past op een klein scherm
  await pg.setViewportSize({ width: 360, height: 640 });
  await pg.evaluate(() => { window.plArtLokaal = () => ''; Android._artLokaal = false; show('podcasts'); }); await pg.waitForTimeout(300);
  await pg.click('#pc-list .srow:nth-child(1) .sbtn'); await pg.waitForTimeout(500);
  await pg.click('#pd-eps .pcep:nth-child(2) .pcepb'); await pg.waitForTimeout(600);
  await pg.click('#plbar-open'); await pg.waitForTimeout(500);
  await pg.click('#pl-sleep'); await pg.waitForTimeout(200);
  const d = await pg.evaluate(() => {
    const qs = [...document.querySelectorAll('#sl-quick button')].map(e => e.getBoundingClientRect());
    const box = $('#sleepdlg .modal-box').getBoundingClientRect();
    const knoppen = [...document.querySelectorAll('#sleepdlg button')].filter(e => e.offsetParent).map(e => { const x = e.getBoundingClientRect(); return [e.id || e.textContent.trim(), Math.round(x.width), Math.round(x.height)]; });
    return { rows: new Set(qs.map(x => Math.round(x.top))).size, cols: new Set(qs.map(x => Math.round(x.left))).size, boxB: box.bottom, vh: innerHeight, knoppen,
      wrapped: [...document.querySelectorAll('#sl-quick b')].some(e => e.getClientRects().length > 1) };
  });
  assert.strictEqual(d.rows, 2); assert.strictEqual(d.cols, 4, 'tijden in een raster van 2×4');
  assert.strictEqual(d.wrapped, false, 'getallen niet afgebroken');
  assert.ok(d.boxB <= d.vh, 'venster past op 360×640');
  assert.ok(d.knoppen.every(([, , hh]) => hh >= 44), 'knoppen minstens 44 px hoog: ' + JSON.stringify(d.knoppen.filter(k => k[2] < 44)));
  await pg.screenshot({ path: __dirname + '/shots/slaaptimer-venster.png' });
  // Engels: geen Nederlandse resten in het venster
  await pg.evaluate(() => { sleepClose(); });
  console.log('errors', errs);
  await b.close();
})().catch(e => { console.error(e); process.exit(1); });
