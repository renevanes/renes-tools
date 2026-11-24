// Foutlogboek vanzelf naar de eigen privé repository (Instellingen → Foutrapport en statistieken, Logboek.java):
// standaard aan, zonder repository en sleutel gaat er niets weg; uit te zetten; vooraf te bekijken; zichtbaar wat er wegging.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => { if (!String(e.message).includes('proef-belofte')) errs.push(e.message); });
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => show('setcrash')); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#st-log') && (await pg.getAttribute('#st-log-aan', 'aria-checked')) === 'true', 'schakelaar staat er, standaard aan');
    ok((await pg.textContent('#st-log-sub')).includes('er gaat niets weg') && await pg.isDisabled('#st-log-nu'), 'zonder repository en sleutel: zichtbaar dat er niets weggaat, versturen kan niet');
    const uitleg = await pg.textContent('#st-log-uitleg');
    ok(uitleg.includes('privé repository') && uitleg.includes('opgeschoond') && uitleg.includes('Claude'), 'zichtbaar waarheen het gaat, waarom en dat het opgeschoond is');
    await pg.click('#st-log-wat summary'); await pg.waitForTimeout(60);
    ok((await pg.textContent('#st-log-voorbeeld')).includes('[JS]'), 'vooraf te bekijken wat er de volgende keer gaat');
    // Met een bestemming: nu versturen
    await pg.evaluate(() => { Android._stat.repo = 'renevanes/renes-tools-statistiek'; Android._stat.token = true; stLogRender(); });
    ok(!(await pg.isDisabled('#st-log-nu')), 'met repository en sleutel: nu versturen kan');
    await pg.click('#st-log-nu'); await pg.waitForTimeout(200);
    ok((await pg.evaluate(() => Android._log.verstuurd)) === 1 && (await pg.textContent('#st-log-sub')).includes('Laatst bijgewerkt'), 'verstuurd; de stand staat erbij');
    ok((await pg.textContent('#st-log-voorbeeld')).includes('Niets nieuws'), 'daarna: niets nieuws meer');
    ok((await pg.textContent('#st-diag-verstuurd')).includes('Foutlogboek'), 'staat bij wat de telefoon verliet, als Foutlogboek');
    await pg.click('#st-log-nu'); await pg.waitForTimeout(200);
    ok((await pg.evaluate(() => Android._log.verstuurd)) === 1, 'niets nieuws: er gaat niets');
    // Uitzetten
    await pg.click('#st-log-aan'); await pg.waitForTimeout(80);
    ok(!(await pg.evaluate(() => Android._log.aan)) && (await pg.getAttribute('#st-log-aan', 'aria-checked')) === 'false' && (await pg.textContent('#st-log-sub')).includes('blijft op de telefoon') && await pg.isDisabled('#st-log-nu'), 'uit: blijft op de telefoon');
    await pg.click('#st-log-aan'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => Android._log.aan), 'weer aan');
    // Een belofte die niemand opvangt komt ook in het logboek
    await pg.evaluate(() => { window.__js = []; const oud = Android.logJs; Android.logJs = m => { window.__js.push(String(m)); try { oud && oud.call(Android, m); } catch (e) {} }; Promise.reject(new Error('proef-belofte')); });
    await pg.waitForTimeout(150);
    ok((await pg.evaluate(() => window.__js.join('\n'))).includes('belofte: ') && (await pg.evaluate(() => window.__js.join('\n'))).includes('proef-belofte'), 'onopgevangen belofte gaat naar het logboek');
    await pg.close();
  }
  console.log('errors', JSON.stringify(errs)); await b.close(); process.exit(errs.length ? 1 : 0);
})();
