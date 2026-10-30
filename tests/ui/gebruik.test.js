// Gebruik: tegel, schermtijd met grafiek en top-apps, tijdlijn per ontgrendeling (pincode/anders/mislukt + foto),
// telefoonfuncties, meldingen en opladen, de instellingen (bewaartermijn, apparaatbeheer, foto bij ontgrendelen,
// proefopname, wissen), de uitleg als toegang ontbreekt. Licht en donker, 320 en 390 px breed.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    for (const width of [320, 390]) {
      const pg = await b.newPage({ viewport: { width, height: 844 }, colorScheme: scheme });
      pg.on('pageerror', e => errs.push(e.message));
      await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(250);
      const tag = scheme + ' ' + width + 'px';

      // Tegel op het startscherm
      ok(await pg.isVisible('#tile-gebruik'), tag + ': tegel Gebruik op het startscherm');
      await pg.click('#tile-gebruik'); await pg.waitForTimeout(150);
      ok(await pg.evaluate(() => current === 'gebruik'), tag + ': scherm Gebruik opent');

      // Schermtijd
      ok((await pg.textContent('#gb-total')).length > 1 && /\d/.test(await pg.textContent('#gb-total')), tag + ': totale schermtijd');
      ok((await pg.$$('#gb-chart svg')).length === 1, tag + ': grafiek (svg)');
      ok((await pg.$$('#gb-chart .gb-bar')).length > 0, tag + ': staven in de grafiek');
      ok((await pg.textContent('#gb-apps')).includes('WhatsApp'), tag + ': top-apps met naam');
      // Week
      await pg.click('#gb-period button[data-p=week]'); await pg.waitForTimeout(100);
      ok((await pg.$$('#gb-chart .gb-bar')).length === 7, tag + ': week toont 7 staven');
      await pg.click('#gb-period button[data-p=today]'); await pg.waitForTimeout(80);

      // Ontgrendelingen
      await pg.click('#gb-tabs .gb-tab[data-tab=ontgrendel]'); await pg.waitForTimeout(120);
      ok((await pg.$$('#gb-blocks .gb-block')).length === 3, tag + ': drie ontgrendelingsblokken');
      const blk = await pg.textContent('#gb-blocks');
      ok(blk.includes('met pincode') && blk.includes('anders') && blk.includes('mislukt'), tag + ': pincode, anders en mislukt');
      ok((await pg.$$('#gb-blocks .gb-thumb')).length >= 1, tag + ': fotothumbnail bij een blok');
      ok(blk.includes('WhatsApp') && blk.includes('Chrome'), tag + ': apps in volgorde met duur');

      // Functies
      await pg.click('#gb-tabs .gb-tab[data-tab=functies]'); await pg.waitForTimeout(120);
      const fn = await pg.textContent('#gb-funcs');
      ok(fn.includes('camera') && fn.includes('microfoon') && fn.includes('waarschijnlijk via'), tag + ': camera/microfoon met "waarschijnlijk via"');

      // Meldingen en opladen
      await pg.click('#gb-tabs .gb-tab[data-tab=meldingen]'); await pg.waitForTimeout(120);
      ok((await pg.textContent('#gb-notifs')).includes('WhatsApp'), tag + ': meldingen per app');
      ok((await pg.textContent('#gb-charge')).match(/Lader|opgestart/), tag + ': opladen of herstarts');

      // Geen toegang: uitleg
      await pg.evaluate(() => { Android._gb.access = false; enterGebruik(); });
      ok(await pg.isVisible('#gb-perm') && (await pg.textContent('#gb-perm')).includes('Toegang'), tag + ': uitleg als toegang ontbreekt');
      await pg.evaluate(() => { Android._gb.access = true; enterGebruik(); });

      // Instellingen
      await pg.click('#gb-set-btn'); await pg.waitForTimeout(150);
      ok(await pg.evaluate(() => current === 'gebruikset'), tag + ': instellingenscherm');
      ok(await pg.isVisible('#gb-collect') && await pg.isVisible('#gb-admin') && await pg.isVisible('#gb-pfail') && await pg.isVisible('#gb-punlock'), tag + ': schakelaars aanwezig');
      ok((await pg.textContent('#gb-photo-status')).length > 0, tag + ': eerlijke status van de foto-functie');
      // Bewaartermijn aanpassen (heen en terug via dezelfde functies)
      await pg.click('#gb-keep button[data-v="730"]'); await pg.waitForTimeout(80);
      ok(await pg.evaluate(() => Android._gb.set.keep === 730), tag + ': samenvatting bewaren aangepast');
      // Apparaatbeheer aanzetten (nagebootst)
      await pg.click('#gb-admin'); await pg.waitForTimeout(120);
      ok(await pg.evaluate(() => Android._gb.admin === true) && await pg.isVisible('#gb-admin-off'), tag + ': apparaatbeheer aan + uitzet-knop');
      // Foto bij mislukte poging aan
      await pg.click('#gb-pfail'); await pg.waitForTimeout(100);
      ok(await pg.evaluate(() => Android._gb.set.photoFail === true), tag + ': foto bij mislukte poging aan');
      // Proefopname maakt een foto aan
      const before = await pg.evaluate(() => Android._gb.photos.length);
      await pg.click('#gb-test'); await pg.waitForTimeout(100);
      ok(await pg.evaluate(n => Android._gb.photos.length > n, before), tag + ': proefopname zet een foto klaar');
      await pg.evaluate(() => gbRenderPhotos());
      ok((await pg.$$('#gb-photos .gb-thumb')).length >= 1, tag + ': foto in de lijst');
      // Foto bekijken (lichtbak)
      await pg.click('#gb-photos .gb-thumb'); await pg.waitForTimeout(80);
      ok(await pg.isVisible('#gb-light img'), tag + ': foto bekijken');
      await pg.click('#gb-light'); await pg.waitForTimeout(50);

      await pg.close();
    }
  }
  await b.close();
  console.log('errors', errs);
})();
