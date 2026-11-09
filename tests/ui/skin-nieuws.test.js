// Telefoon-skin: Nieuws staat bij de snelkoppelingen naar tools (met het krantenicoon), is te zoeken en opent de tool.
const { chromium } = require('playwright');
const W = +process.env.UI_WACHT || 1;
(async () => {
  const b = await chromium.launch(), errs = [];
  const ok = (c, m) => { if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme, hasTouch: true, isMobile: true });
    const pg = await ctx.newPage();
    pg.on('pageerror', e => errs.push(scheme + ': ' + e.message));
    await pg.goto('file://' + require('path').join(__dirname, '..', '..', 'web', 'start.html')); await pg.waitForTimeout(500 * W);
    ok(!(await pg.evaluate(() => cfg.tools.includes('nieuws'))), scheme + ': Nieuws staat niet vanzelf tussen de snelkoppelingen');
    await pg.evaluate(() => openLook()); await pg.waitForTimeout(100 * W);
    const kies = pg.locator('#l-tools button[data-t="nieuws"]');
    ok(await kies.count() === 1, scheme + ': Nieuws is te kiezen bij Snelkoppelingen naar tools');
    ok(await kies.locator('.ui-icon[data-icon="news"]').count() === 1, scheme + ': met het krantenicoon');
    await kies.click(); await pg.waitForTimeout(100 * W);
    ok(await pg.evaluate(() => cfg.tools.includes('nieuws') && JSON.parse(Android._cfg).tools.includes('nieuws')), scheme + ': keuze bewaard');
    const chip = pg.locator('#tools .tchip[data-t="nieuws"]');
    ok(await chip.count() === 1, scheme + ': Nieuws staat op het startscherm');
    const icoon = await chip.evaluate(e => { const i = e.querySelector('.ui-icon[data-icon="news"]'); return i ? getComputedStyle(i, '::before').maskImage || getComputedStyle(i).maskImage : ''; });
    ok(/svg/.test(icoon || ''), scheme + ': het icoon wordt echt getekend: ' + icoon);
    ok((await chip.textContent()).includes('Nieuws'), scheme + ': met de naam');
    await chip.evaluate(e => e.click());
    ok(await pg.evaluate(() => Android._tool) === 'nieuws', scheme + ': tikken opent Nieuws');
    await pg.screenshot({ path: __dirname + '/shots/skin-nieuws-' + scheme + '.png' });
    const gevonden = await pg.evaluate(() => searchMoreNow('nieuws', 'nieuws'));
    ok(/data-a="tool" data-v="nieuws"/.test(gevonden), scheme + ': zoeken vindt Nieuws');
    await ctx.close();
  }
  await b.close();
  console.log('errors', errs);
})();
