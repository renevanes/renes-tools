const { chromium } = require('playwright');
const assert = require('assert');
// Slaaptimer: hoe lang het geluid aan het eind langzaam zachter wordt, is in te stellen (standaard 20 seconden),
// als rustige regel onderin het venster (kort, gedempt, zonder kader).
(async () => {
  const b = await chromium.launch();
  const errs = [];
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 360, height: 640 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => show('podcasts')); await pg.waitForTimeout(300);
    await pg.click('#pc-list .srow:nth-child(1) .sbtn'); await pg.waitForTimeout(500);
    await pg.click('#pd-eps .pcep:nth-child(2) .pcepb'); await pg.waitForTimeout(600);
    await pg.click('#plbar-open'); await pg.waitForTimeout(500);
    await pg.click('#pl-sleep'); await pg.waitForTimeout(200);

    // Zichtbaar, met de vaste keuzes (kort geschreven); standaard 20 seconden
    assert.strictEqual(await pg.isVisible('#sl-fade'), true, 'keuze staat in het venster');
    const opts = await pg.$$eval('#sl-fade option', x => x.map(o => [o.value, o.textContent]));
    assert.deepStrictEqual(opts.map(o => o[0]), ['0', '15', '20', '30', '60', '120', '180']);
    assert.deepStrictEqual(opts.map(o => o[1]), ['Uit', '15 sec', '20 sec', '30 sec', '1 min', '2 min', '3 min']);
    assert.strictEqual(await pg.inputValue('#sl-fade'), '20', 'standaard 20 seconden');
    assert.strictEqual(await pg.textContent('label[for="sl-fade"]'), 'Aan het eind zachter');
    // Minder opvallend: onderaan (onder Andere tijd), één regel, kleine gedempte tekst, keuze zonder kader
    const stil = await pg.evaluate(() => {
      const lab = $('label[for="sl-fade"]'), sel = $('#sl-fade'), rij = $('.sl-fade-r'), cs = getComputedStyle(lab), ss = getComputedStyle(sel);
      const muted = getComputedStyle(document.documentElement).getPropertyValue('--muted').trim(), probe = document.createElement('i');
      probe.style.color = muted; document.body.append(probe); const mutedRgb = getComputedStyle(probe).color; probe.remove();
      return { onder: $('#sl-eigen').getBoundingClientRect().bottom <= rij.getBoundingClientRect().top + 1,
        regel: Math.abs(lab.getBoundingClientRect().top + lab.offsetHeight / 2 - (sel.getBoundingClientRect().top + sel.offsetHeight / 2)) < 4 && lab.getClientRects().length === 1 && lab.offsetHeight < 30,
        klein: parseFloat(cs.fontSize) <= 14 && +cs.fontWeight < 600, gedempt: cs.color === mutedRgb,
        kader: ss.borderTopWidth, hoogte: rij.offsetHeight };
    });
    assert.deepStrictEqual(stil, { onder: true, regel: true, klein: true, gedempt: true, kader: '0px', hoogte: stil.hoogte }, 'rustige regel: ' + JSON.stringify(stil));
    assert.ok(stil.hoogte >= 44 && stil.hoogte <= 56, 'één regel, wel 44 px om op te tikken: ' + stil.hoogte);
    // Einde van de aflevering: een keuze zoals de tijden, op één regel, geen tweede gekleurd blok
    const eind = await pg.evaluate(() => { const e = $('#sl-end'), q = $('#sl-quick button'); return { big: e.classList.contains('big'), bg: getComputedStyle(e).backgroundColor === getComputedStyle(q).backgroundColor, h: e.offsetHeight, tekst: e.textContent }; });
    assert.deepStrictEqual([eind.big, eind.bg, eind.tekst], [false, true, 'Einde van deze aflevering'], 'einde aflevering als keuze');
    assert.ok(eind.h >= 44 && eind.h < 56, 'op één regel: ' + eind.h);
    assert.strictEqual(await pg.evaluate(() => /30 seconden wordt/.test($('#sleepdlg').textContent)), false, 'oude vaste tekst weg');

    // Kiezen: bewaard in de app, met een bevestiging; ook na opnieuw openen
    await pg.selectOption('#sl-fade', '15'); await pg.waitForTimeout(150);
    assert.strictEqual(await pg.evaluate(() => Android._fade), 15);
    assert.strictEqual(await pg.textContent('#toast'), 'Aan het eind 15 seconden langzaam zachter');
    await pg.click('#sl-sluit'); await pg.waitForTimeout(150);
    await pg.click('#pl-sleep'); await pg.waitForTimeout(200);
    assert.strictEqual(await pg.inputValue('#sl-fade'), '15', 'keuze onthouden');
    await pg.selectOption('#sl-fade', '0'); await pg.waitForTimeout(150);
    assert.strictEqual(await pg.evaluate(() => Android._fade), 0);
    assert.strictEqual(await pg.textContent('#toast'), 'Aan het eind niet zachter: meteen stoppen');
    // Een waarde die de app weigert: terug naar wat er staat
    await pg.evaluate(() => { const s = $('#sl-fade'); const o = document.createElement('option'); o.value = '45'; s.append(o); s.value = '45'; s.dispatchEvent(new Event('change')); });
    await pg.waitForTimeout(150);
    assert.strictEqual(await pg.evaluate(() => Android._fade), 0, 'geweigerd: niets veranderd');
    assert.strictEqual(await pg.inputValue('#sl-fade'), '0', 'keuze weer zoals bewaard');

    // Past in het venster en is groot genoeg om op te tikken
    const r = await pg.evaluate(() => { const s = $('#sl-fade').getBoundingClientRect(), box = $('#sleepdlg .modal-box').getBoundingClientRect(); return { h: s.height, r: s.right, boxR: box.right, b: box.bottom, vh: innerHeight }; });
    assert.ok(r.h >= 44 && r.r <= r.boxR && r.b <= r.vh, 'keuze ≥ 44 px, binnen het venster: ' + JSON.stringify(r));
    if (scheme === 'light') await pg.screenshot({ path: __dirname + '/shots/slaaptimer-zachter.png' });

    assert.strictEqual(await pg.isVisible('#sl-fade-cast'), false, 'niet casten: geen opmerking over de Chromecast');
    // De assistent kent de instelling (instellingenregister)
    const reg = await pg.evaluate(() => { const r = SET_REG.find(x => x.id === 'slaaptimer.zachter'); if (!r) return null; const v0 = r.get(); const m = r.set('120'); return { v0, m, na: Android._fade, avail: r.avail() }; }).catch(e => 'fout: ' + e.message);
    assert.deepStrictEqual(reg, { v0: '0', m: '', na: 120, avail: '' }, 'assistent: lezen en zetten');

    // Radio: dezelfde keuze
    await pg.evaluate(() => { sleepClose(); sleepOpen('radio'); }); await pg.waitForTimeout(150);
    assert.strictEqual(await pg.isVisible('#sl-fade'), true, 'ook bij de radio');
    await pg.evaluate(() => sleepClose());

    // App 1.210 (keuze wel, lijst nog niet): alleen wat die app kent, met de standaard van toen (1 minuut)
    const oud = await pg.evaluate(() => {
      Android.sleepFadeKeuzes = undefined; Android.sleepFade = () => 60; sleepOpen('pod');
      const o = [...$('#sl-fade').options].map(x => [x.value, x.textContent]);
      const r = SET_REG.find(x => x.id === 'slaaptimer.zachter');
      return { o, v: $('#sl-fade').value, reg: r.vals().map(x => x[0]).join(), get: r.get() };
    });
    assert.deepStrictEqual(oud.o.map(x => x[0]), ['0', '15', '30', '60', '120', '180'], 'oudere app: geen 20 s');
    assert.strictEqual(oud.o[3][1], '1 min');
    assert.strictEqual(oud.v, '60');
    assert.deepStrictEqual([oud.reg, oud.get], ['0,15,30,60,120,180', '60'], 'assistent: zelfde keuzes als de app');
    await pg.evaluate(() => sleepClose());

    // Oudere app zonder deze keuze: de regel blijft weg
    await pg.evaluate(() => { window._fadeBewaard = Android.sleepFade; delete Android.sleepFade; Android.sleepFade = undefined; sleepOpen('pod'); });
    await pg.waitForTimeout(150);
    assert.strictEqual(await pg.isVisible('#sl-fade-rij'), false, 'oudere app: geen keuze');
    await pg.close();
  }

  // Engels
  const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  pg.on('pageerror', e => errs.push(e.message));
  await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
  await pg.evaluate(() => rgSave({ lang: 'en' })); await pg.waitForTimeout(300);
  await pg.evaluate(() => sleepOpen('pod')); await pg.waitForTimeout(300);
  assert.deepStrictEqual(await pg.$$eval('#sl-fade option', x => x.map(o => o.textContent)), ['Off', '15 sec', '20 sec', '30 sec', '1 min', '2 min', '3 min']);
  assert.strictEqual(await pg.textContent('label[for="sl-fade"]'), 'Fade out at the end');
  assert.strictEqual(await pg.textContent('#sl-end'), 'End of this episode');
  // De assistent noemt de keuzes wel voluit, met de standaard
  assert.deepStrictEqual(await pg.evaluate(() => setVals(SET_REG.find(x => x.id === 'slaaptimer.zachter')).map(x => x[1])), ['Off', '15 seconds', '20 seconds (default)', '30 seconds', '1 minute', '2 minutes', '3 minutes']);
  console.log('errors', errs);
  await b.close();
})().catch(e => { console.error(e); process.exit(1); });
