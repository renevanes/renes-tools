// Instellingen: knop rechtsboven, en de lijst met meldingen van de app.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    const box = await pg.$eval('#s-home .hdr .hbtn', e => { const r = e.getBoundingClientRect(); return [Math.round(r.right), Math.round(r.top)]; });
    console.log('gear top right', box[0] > 340 && box[1] < 40);
    await pg.click('#s-home .hdr .hbtn'); await pg.waitForTimeout(150);
    console.log('settings open', await pg.evaluate(() => current));
    console.log('kinds', await pg.$$eval('#st-notif-list .nkind', x => x.map(e => e.textContent.replace(/\s+/g, ' ').trim()).join(' | ')));
    await pg.screenshot({ path: __dirname + '/shots/notif-' + scheme + '.png' });
    await pg.click('#st-notif-list .nkind[data-id=radio]'); console.log('opened kind', await pg.evaluate(() => Android._notifOpen));
    await pg.evaluate(() => { Android._notifOff = true; enterSettings(); });
    console.log('off state', await pg.textContent('#st-notif-state'), await pg.isVisible('#st-notif-ask'));
    await pg.click('#st-notif-ask'); console.log('asked', await pg.evaluate(() => Android._notifAsk));
    console.log('errors', errs); await pg.close();
  }
  await b.close();
})();
