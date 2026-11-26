// Nieuwe app vanzelf downloaden (Versie en updates): standaard aan en alleen via wifi; de voortgang staat in de balk en
// daarna staat hij klaar om te installeren. Niet in de Play-versie, niet met een app die het nog niet kent.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);

    await pg.evaluate(() => show('info')); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#i-vz-aan'), 'schakelaar op Versie en updates');
    ok(await pg.getAttribute('#i-vz-aan', 'aria-checked') === 'true' && await pg.getAttribute('#i-vz-wifi', 'aria-checked') === 'true', 'standaard aan, alleen via wifi');
    const h = await pg.$eval('#i-vz-aan', e => e.getBoundingClientRect().height);
    ok(h >= 44 || await pg.$eval('#i-vz-aan', e => e.closest('.sw').getBoundingClientRect().height >= 44), 'tikvlak groot genoeg');

    // Alleen via wifi uit, dan vanzelf downloaden uit: de wifi-regel gaat weg
    await pg.click('#i-vz-wifi');
    ok(await pg.evaluate(() => Android._updVz.aan === true && Android._updVz.wifi === false), 'alleen via wifi uit: bewaard');
    await pg.click('#i-vz-aan');
    ok(await pg.evaluate(() => Android._updVz.aan === false), 'vanzelf downloaden uit: bewaard');
    ok(!await pg.isVisible('#i-vz-wifi'), 'uit: Alleen via wifi doet er niet toe');
    await pg.click('#i-vz-aan'); await pg.click('#i-vz-wifi');
    ok(await pg.evaluate(() => Android._updVz.aan === true && Android._updVz.wifi === true), 'weer aan');

    // Vanzelf downloaden met de app open: voortgang in de balk, daarna klaar om te installeren
    await pg.evaluate(() => { onUpdateStatus({ state: 'apk', kind: 'web', versionName: '9.9' }); onUpdateStatus({ state: 'progress', kind: 'apk', phase: 'download', done: 1e6, total: 4e6 }); });
    ok(/Downloaden… 25%/.test(await pg.textContent('#upd-text2')) && await pg.isDisabled('#upd-btn2'), 'voortgang in de balk: ' + await pg.textContent('#upd-text2'));
    await pg.evaluate(() => onUpdateStatus({ state: 'apk', kind: 'apk', vanzelf: true, versionName: '9.9', klaar: true }));
    ok(/Versie 9\.9 is gedownload/.test(await pg.textContent('#upd-text2')) && !await pg.isDisabled('#upd-btn2'), 'klaar: tik op Installeren');
    ok(!await pg.evaluate(() => document.querySelector('#toast') && document.querySelector('#toast').classList.contains('on')), 'geen melding ertussendoor');
    // Mislukt (bijv. geen verbinding): gewoon weer beschikbaar, zonder melding
    await pg.evaluate(() => { updKlaar = ''; updAvail = ''; onUpdateStatus({ state: 'apk', kind: 'apk', vanzelf: true, versionName: '9.9', reden: 'download' }); });
    ok(/Nieuwe versie 9\.9 is beschikbaar/.test(await pg.textContent('#upd-text2')), 'mislukt: weer beschikbaar');

    // Engels
    await pg.evaluate(() => { rgSave({ lang: 'en' }); show('home'); show('info'); }); await pg.waitForTimeout(250);
    ok(/Download new app automatically/.test(await pg.textContent('#i-vz')), 'Engels: ' + (await pg.textContent('#i-vz')).trim().slice(0, 60));
    await pg.evaluate(() => rgSave({ lang: 'nl' }));

    // Een oudere app zonder deze keuze: de kaart blijft weg
    await pg.evaluate(() => { window.__vz = Android.updVanzelf; Android.updVanzelf = undefined; show('home'); show('info'); }); await pg.waitForTimeout(150);
    ok(!await pg.isVisible('#i-vz'), 'oudere app: geen kaart');
    await pg.evaluate(() => { Android.updVanzelf = window.__vz; });
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
  process.exit(errs.length ? 1 : 0);
})();
