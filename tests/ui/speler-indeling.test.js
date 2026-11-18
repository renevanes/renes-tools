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
      const row = $('#pl-opts').getBoundingClientRect(), sc = $('#pl-scroll');
      return { vh: innerHeight, vw: innerWidth, art: r('#pl-art'), cover: r('#pl-cover'), ctl: r('.pl-ctl'), title: r('#pl-title'), seek: r('#pl-seek'), opts,
        rowL: row.left, rowR: innerWidth - row.right, schuift: sc.scrollWidth > sc.clientWidth, scrollH: sc.scrollHeight, clientH: sc.clientHeight };
    });
    const last = Math.max(...m.opts.map(o => o.b));
    if (m.vh >= 600) assert.ok(last <= m.vh, w + 'x' + h + ': opties passen op het scherm (' + Math.round(last) + ' ≤ ' + m.vh + ')');
    else assert.ok(m.scrollH > m.clientH, w + 'x' + h + ': heel laag scherm: dan scrollen');
    assert.strictEqual(m.schuift, false, w + 'x' + h + ': niets steekt opzij buiten beeld');
    assert.ok(Math.abs(m.rowL - m.rowR) < 1.5 && m.rowL >= 0, w + 'x' + h + ': rij opties in het midden (' + Math.round(m.rowL) + '/' + Math.round(m.rowR) + ')');
    const breedtes = m.opts.map(o => Math.round(o.w));
    assert.ok(Math.max(...breedtes) - Math.min(...breedtes) <= 1, w + 'x' + h + ': opties even breed ' + breedtes);
    assert.deepStrictEqual(m.opts.map(o => o.id), ['pl-out', 'pl-speed', 'pl-stop', 'pl-sleep'], 'podcast: waar, snelheid, stop, slaaptimer (rechts)');
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

  // Radio met terugspoelen: Naar live staat in dezelfde rij (geen snelheid)
  await pg.evaluate(() => { plStop(); show('radio'); }); await pg.waitForTimeout(400);
  await pg.click('#rd-list .srow:nth-child(2) .sbtn'); await pg.waitForTimeout(1300);
  await pg.click('#plbar-open'); await pg.waitForTimeout(500);
  await pg.click('#pl-back'); await pg.waitForTimeout(500);
  const rad = await pg.evaluate(() => [...document.querySelectorAll('#pl-opts .pl-opt')].filter(e => getComputedStyle(e).display !== 'none').map(e => [e.id, Math.round(e.getBoundingClientRect().top)]));
  assert.deepStrictEqual(rad.map(x => x[0]), ['pl-out', 'pl-golive', 'pl-stop', 'pl-sleep'], 'radio: waar, naar live, stop, slaaptimer (rechts)');
  assert.strictEqual(new Set(rad.map(x => x[1])).size, 1, 'radio: één rij');
  await pg.evaluate(() => { plStop(); }); await pg.waitForTimeout(400);

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
  assert.strictEqual(await pg.evaluate(() => document.activeElement.id), 'sl-t', 'focus op de kop, niet op een tijd');
  await pg.evaluate(() => { sleepClose(); });
  // Engels: de rij en het venster (speler weer open met een podcast)
  await pg.evaluate(() => rgSave({ lang: 'en' })); await pg.waitForTimeout(300);
  await pg.evaluate(() => show('podcasts')); await pg.waitForTimeout(300);
  await pg.click('#pc-list .srow:nth-child(1) .sbtn'); await pg.waitForTimeout(500);
  await pg.click('#pd-eps .pcep:nth-child(2) .pcepb'); await pg.waitForTimeout(600);
  await pg.click('#plbar-open'); await pg.waitForTimeout(600);
  await pg.click('#pl-sleep'); await pg.waitForTimeout(300);
  const en = await pg.evaluate(() => ({ lang: document.documentElement.lang,
    rij: [...document.querySelectorAll('#pl-opts .pl-optl')].filter(e => e.offsetParent).map(e => e.textContent),
    vraag: $('#sl-vraag').textContent, eigen: $('#sl-eigen summary').textContent, sluit: $('#sl-sluit').textContent, label: $('#pl-speed').getAttribute('aria-label') }));
  assert.strictEqual(en.lang, 'en');
  assert.deepStrictEqual(en.rij, ['This phone', 'Speed', 'Stop', 'Sleep timer'], 'Engels: de rij');
  assert.deepStrictEqual([en.vraag, en.eigen, en.sluit], ['Stop the podcast after', 'Other time', 'Close'], 'Engels: het venster');
  assert.match(en.label, /^Playback speed /);
  await pg.click('#sl-quick button[data-m="90"]'); await pg.waitForTimeout(1300);
  assert.match(await pg.textContent('#pl-sleep-l'), /^(\d+ h \d\d|\d+ min) left$|^\S+ (1 h 2\d|1 h 30)/, 'Engels: kort onder het maantje: ' + await pg.textContent('#pl-sleep-l'));
  assert.match(await pg.getAttribute('#pl-sleep', 'aria-label'), /^Sleep timer, 1 h (2\d|30) min left, stops at /, 'Engels: naam van de knop');
  console.log('errors', errs);
  await b.close();
})().catch(e => { console.error(e); process.exit(1); });
