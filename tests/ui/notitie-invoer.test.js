// Notities: het invoerveld is één regel tot je iets schrijft, zodat je notities en lijsten bovenaan staan.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => show('notes')); await pg.waitForTimeout(250);
    const leeg = await pg.evaluate(() => ({ veld: $('#note-quick').getBoundingClientRect().height, knop: $('#note-quick-save').getClientRects().length, lijst: $('#notes-list').getBoundingClientRect().top }));
    ok(leeg.veld >= 44 && leeg.veld <= 56 && leeg.knop === 0, scheme + ': leeg veld is één regel (minstens 44 px), zonder knop (' + JSON.stringify(leeg) + ')');
    ok(leeg.lijst < 640, scheme + ': de lijst begint ruim boven de vouw (' + Math.round(leeg.lijst) + ' px)');
    ok(await pg.evaluate(() => { const h = $('#note-compose .hint'); return h.getClientRects().length > 0 && /Nieuwe lijst/.test(h.textContent); }), scheme + ': de hint over Nieuwe lijst blijft zichtbaar');
    await pg.click('#note-quick'); await pg.waitForTimeout(80);
    ok(Math.abs((await pg.evaluate(() => $('#notes-list').getBoundingClientRect().top)) - leeg.lijst) < 1, scheme + ': in het veld tikken verschuift de lijst niet');
    await pg.fill('#note-quick', 'Melk halen'); await pg.waitForTimeout(80);
    const vol = await pg.evaluate(() => ({ veld: $('#note-quick').getBoundingClientRect().height, knop: $('#note-quick-save').getClientRects().length }));
    ok(vol.veld >= 88 && vol.knop === 1, scheme + ': met tekst: groter veld en de knop Notitie bewaren (' + JSON.stringify(vol) + ')');
    await pg.click('#note-quick-save'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => $('#note-quick').value === '' && $('#note-quick-save').getClientRects().length === 0 && /Melk halen/.test($('#notes-list').textContent)), scheme + ': bewaard, veld weer één regel');
    await pg.screenshot({ path: __dirname + '/shots/notitie-invoer-' + scheme + '.png' });
    await pg.close();
  }
  console.log('errors', errs);
  await b.close();
  if (errs.length) process.exit(1);
})();
