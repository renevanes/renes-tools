// Gebruik: tegel, startkaart (opt-in), schermtijd met grafiek (vandaag/week/maand/jaar) en top-apps, tijdlijn per
// ontgrendeling (pincode/anders/onbekend/mislukt + foto, vijandige app-naam als tekst), telefoonfuncties, meldingen en
// opladen, de instellingen (bewaartermijn, apparaatbeheer met bevestiging, 3-keuze foto bij ontgrendelen, proefopname,
// wissen), de uitleg als toegang ontbreekt. Licht en donker, 320 en 390 px breed.
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

      ok(await pg.isVisible('#tile-gebruik'), tag + ': tegel Gebruik op het startscherm');
      await pg.click('#tile-gebruik'); await pg.waitForTimeout(150);
      ok(await pg.evaluate(() => current === 'gebruik'), tag + ': scherm Gebruik opent');

      // Startkaart (opt-in): uit → kaart zichtbaar; aanzetten → weg
      await pg.evaluate(() => { Android._gb.set.collect = false; enterGebruik(); });
      ok(await pg.isVisible('#gb-start'), tag + ': startkaart als bijhouden uit staat');
      await pg.evaluate(() => { Android._gb.set.collect = true; enterGebruik(); });
      ok(!await pg.isVisible('#gb-start'), tag + ': startkaart weg als bijhouden aan staat');

      // Schermtijd
      ok(/\d/.test(await pg.textContent('#gb-total')), tag + ': totale schermtijd');
      ok((await pg.$$('#gb-chart svg')).length === 1 && (await pg.$$('#gb-chart .gb-bar')).length > 0, tag + ': grafiek met staven');
      ok((await pg.textContent('#gb-apps')).includes('WhatsApp'), tag + ': top-apps met naam');
      await pg.click('#gb-period button[data-p=week]'); await pg.waitForTimeout(100);
      ok((await pg.$$('#gb-chart .gb-bar')).length === 7, tag + ': week toont 7 staven');
      await pg.click('#gb-period button[data-p=maand]'); await pg.waitForTimeout(100);
      ok((await pg.$$('#gb-chart .gb-bar')).length === 30, tag + ': maand toont 30 staven');
      await pg.click('#gb-period button[data-p=jaar]'); await pg.waitForTimeout(100);
      ok((await pg.$$('#gb-chart .gb-bar')).length === 52, tag + ': jaar toont 52 staven');
      await pg.click('#gb-period button[data-p=today]'); await pg.waitForTimeout(80);

      // Ontgrendelingen
      await pg.click('#gb-tabs .gb-tab[data-tab=ontgrendel]'); await pg.waitForTimeout(120);
      ok((await pg.$$('#gb-blocks .gb-block')).length === 4, tag + ': vier ontgrendelingsblokken');
      const blk = await pg.textContent('#gb-blocks');
      ok(blk.includes('met pincode') && blk.includes('anders') && blk.includes('onbekend') && blk.includes('mislukt'), tag + ': pincode, anders, onbekend en mislukt');
      ok(blk.includes('na 2× mis'), tag + ': mislukte pogingen vóór een ontgrendeling getoond');
      ok((await pg.$$('#gb-blocks .gb-thumb')).length >= 1, tag + ': fotothumbnail bij een blok');
      // Vijandige app-naam moet als tekst renderen, niet als element
      ok(await pg.evaluate(() => document.querySelectorAll('#gb-blocks img').length === 0) && blk.includes('<img'), tag + ': vijandige app-naam als tekst (geen <img>)');

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
      ok(await pg.isVisible('#gb-collect') && await pg.isVisible('#gb-admin') && await pg.isVisible('#gb-pfail') && await pg.isVisible('#gb-punlock'), tag + ': schakelaars en keuzes aanwezig');
      ok((await pg.textContent('#gb-photo-status')).length > 0 && (await pg.textContent('#gb-a11y-note')).length > 0, tag + ': eerlijke status van foto en toegankelijkheid');
      // Foto bij ontgrendelen: 3-keuze
      await pg.click('#gb-punlock button[data-v=pincode]'); await pg.waitForTimeout(80);
      ok(await pg.evaluate(() => Android._gb.set.punlock === 'pincode'), tag + ': foto bij ontgrendelen op "alleen met pincode"');
      // Bewaartermijn
      await pg.click('#gb-keep button[data-v="730"]'); await pg.waitForTimeout(80);
      ok(await pg.evaluate(() => Android._gb.set.keep === 730), tag + ': samenvatting bewaren aangepast');
      // Apparaatbeheer aan, dan uit met bevestiging
      await pg.click('#gb-admin'); await pg.waitForTimeout(120);
      ok(await pg.evaluate(() => Android._gb.admin === true) && await pg.isVisible('#gb-admin-off'), tag + ': apparaatbeheer aan + uitzet-knop');
      await pg.click('#gb-admin-off'); await pg.waitForTimeout(120);
      ok(await pg.isVisible('#modal.on'), tag + ': apparaatbeheer uitzetten vraagt bevestiging');
      await pg.click('#modal-ok'); await pg.waitForTimeout(120);
      ok(await pg.evaluate(() => Android._gb.admin === false), tag + ': na bevestigen is apparaatbeheer uit');
      // Foto bij mislukte poging aan
      await pg.click('#gb-pfail'); await pg.waitForTimeout(100);
      ok(await pg.evaluate(() => Android._gb.set.photoFail === true), tag + ': foto bij mislukte poging aan');
      // Proefopname
      const before = await pg.evaluate(() => Android._gb.photos.length);
      await pg.click('#gb-test'); await pg.waitForTimeout(100);
      ok(await pg.evaluate(n => Android._gb.photos.length > n, before), tag + ': proefopname zet een foto klaar');
      await pg.evaluate(() => gbRenderPhotos());
      ok((await pg.$$('#gb-photos .gb-thumb')).length >= 1, tag + ': foto in de lijst');
      // Foto bekijken + sluiten met de knop
      await pg.click('#gb-photos .gb-thumb'); await pg.waitForTimeout(80);
      ok(await pg.isVisible('#gb-light img') && await pg.isVisible('#gb-light .gb-light-x'), tag + ': foto bekijken met sluitknop');
      await pg.click('#gb-light .gb-light-x'); await pg.waitForTimeout(60);
      ok(!await pg.$('#gb-light'), tag + ': sluitknop sluit de fotoweergave');

      await pg.close();
    }
  }
  await b.close();
  console.log('errors', errs);
})();
