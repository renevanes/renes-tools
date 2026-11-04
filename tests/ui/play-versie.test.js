// Play-versie: tools zonder toestemming uit beeld, geen eigen updates. De gewone versie blijft ongewijzigd.
const { chromium } = require('playwright');
const assert = (ok, what) => { if (!ok) throw new Error('mislukt: ' + what); console.log('✓ ' + what); };
const WEG = ['tile-redial', 'tile-sms', 'tile-calls', 'tile-recorder'];
(async () => {
  const b = await chromium.launch();
  const errs = [];
  for (const play of [false, true]) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, hasTouch: true });
    pg.on('pageerror', e => errs.push(e.message));
    if (play) await pg.addInitScript(() => { window.__testPlay = true; });
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const zichtbaar = id => pg.$eval('#' + id, el => el.checkVisibility()).catch(() => false);
    const naam = play ? 'Play-versie' : 'gewone versie';
    for (const id of WEG) assert((await zichtbaar(id)) === !play, naam + ': ' + id + (play ? ' verborgen' : ' zichtbaar'));
    assert(await zichtbaar('tile-notes'), naam + ': notities zichtbaar');
    await pg.evaluate(() => show('info')); await pg.waitForTimeout(150);
    assert((await zichtbaar('chk-btn')) === !play, naam + ': knop "Controleren op updates" ' + (play ? 'weg' : 'er'));
    assert(!!(await pg.$('#play-upd')) === play, naam + ': uitleg over Google Play ' + (play ? 'er' : 'niet'));
    await pg.evaluate(() => show('sms')); await pg.waitForTimeout(150);
    const scherm = await pg.evaluate(() => current);
    assert(play ? scherm === 'info' : scherm === 'sms', naam + ': sms-backup openen ' + (play ? 'gaat niet' : 'gaat'));
    await pg.evaluate(() => show('gebruikset')); await pg.waitForTimeout(150);
    assert((await zichtbaar('gb-admin')) === !play, naam + ': apparaatbeheer ' + (play ? 'weg' : 'er'));
    assert((await zichtbaar('gb-test')) === !play, naam + ': foto bij ontgrendelen ' + (play ? 'weg' : 'er'));
    if (play) await pg.screenshot({ path: __dirname + '/shots/play-versie.png' });
    await pg.close();
  }
  console.log('errors', errs);
  await b.close();
})().catch(e => { console.error(e); process.exit(1); });
