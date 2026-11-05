// Het logboek kan tijdens en na ophalen bekeken, gekopieerd en gedeeld worden zonder factuurgegevens.
const { chromium } = require('playwright');
const assert = require('assert/strict');
const path = require('path');
(async () => {
  const b = await chromium.launch();
  try {
    for (const scheme of ['light', 'dark']) {
      const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
      const errors = []; pg.on('pageerror', e => errors.push(e.message));
      await pg.goto('file://' + path.join(__dirname, 'ui/index.html'));
      await pg.waitForFunction(() => typeof faLog === 'function');
      await pg.evaluate(() => {
        Android._fa.lev = [{ id: 'kpn', naam: 'Mijn leverancier', soort: 'website', startUrl: 'https://leverancier.test', schema: 'uit' }];
        Android._fa.bezig = 'kpn';
        const d = { t: Date.now(), versie: '1.148', native: 135, stappen: [
          { ms: 0, stap: 'START', pagina: 0, aantal: 0 },
          { ms: 2500, stap: 'MEER', pagina: 1, aantal: 5 },
          { ms: 8000, stap: 'HTTP', pagina: 2, aantal: 404 },
          { ms: 8100, stap: 'LAADFOUT', pagina: 2, aantal: 5 }
        ] };
        Android._fa.toestand.kpn = { diagnose: d, log: [{ t: d.t, ok: false, msg: 'wachtwoord=inloggeheim; code=123456; url=https://privaat.test', namen: ['Factuur-privaat.pdf'], diagnose: d }] };
        show('facturen'); faRender(); faOpen('kpn');
      });
      await pg.click('#sheet button:has-text("Logboek ophalen")');
      await pg.click('#sheet button:has-text("Bekijken")');
      const tekst = await pg.textContent('#modal');
      assert(tekst.includes('Toon meer aangeklikt') && tekst.includes('404') && tekst.includes('Factuurlijst niet op tijd geladen'));
      assert.equal((tekst.match(/Ophaalpoging/g) || []).length, 1);
      assert(!/inloggeheim|123456|privaat|Mijn leverancier/.test(tekst));
      await pg.click('#modal button:has-text("Sluiten")');
      await pg.evaluate(() => faLog('kpn')); await pg.click('#sheet button:has-text("Kopiëren")');
      const kopie = await pg.evaluate(() => Android._copied);
      assert(kopie.includes('app 1.148') && kopie.includes('APK 135') && kopie.includes('Toon meer aangeklikt'));
      assert(!/inloggeheim|123456|privaat|Mijn leverancier/.test(kopie));
      await pg.evaluate(() => faLog('kpn')); await pg.click('#sheet button:has-text("Delen")');
      assert.equal(await pg.evaluate(() => Android._shared), kopie);
      console.log('errors', errors); assert.equal(errors.length, 0);
      await pg.close();
    }
  } finally { await b.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
