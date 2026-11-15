// Aan de slag: na een nieuwe installatie (terugzetten overgeslagen) drie stappen op het startscherm.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const url = 'file://' + require('path').join(__dirname, 'ui', 'index.html');
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(url); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => $('#home-start').hidden), scheme + ': een bestaande installatie ziet de kaart niet');
    // nieuwe installatie: terugzetten overslaan
    await pg.evaluate(() => { Android._hs.state = 'nieuw'; Android._wa.info.dest = ''; Android._st.lock = false; const c = homeCfg(); c.fav = []; homeSave(c); show('herstel'); });
    await pg.waitForTimeout(200);
    await pg.click('#hs-skip'); await pg.waitForTimeout(300);
    const rijen = () => pg.$$eval('#home-start-list .strow', x => x.map(e => ({ naar: e.dataset.naar, klaar: e.dataset.klaar, tekst: e.textContent, h: e.getBoundingClientRect().height })));
    let r = await rijen();
    ok(await pg.evaluate(() => current === 'home' && !$('#home-start').hidden) && r.length === 3 && r.every(x => x.klaar === '0' && x.h >= 44), scheme + ': na overslaan drie open stappen op het startscherm (' + r.map(x => x.naar).join(', ') + ')');
    ok(/zonder backup/.test(r[1].tekst) && /Uit: iedereen/.test(r[2].tekst), scheme + ': elke rij zegt de stand');
    await pg.click('#home-start-list .strow[data-naar="setlock"]'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current === 'setlock'), scheme + ': een rij gaat naar het scherm waar je het regelt');
    await pg.evaluate(() => { Android._st.lock = true; goBack(); }); await pg.waitForTimeout(250);
    r = await rijen();
    ok(await pg.evaluate(() => current === 'home') && r[2].klaar === '1' && /staat aan/.test(r[2].tekst) && r[0].klaar === '0', scheme + ': terug: die stap is afgevinkt, de rest nog niet');
    await pg.evaluate(() => { Android._wa.info.dest = 'content://x'; toggleFav('notes'); show('settings'); show('home'); }); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => $('#home-start').hidden && !store.get('homeStart', 0)), scheme + ': alles gedaan: de kaart gaat weg en komt niet terug');
    await pg.evaluate(() => { store.set('homeStart', 1); Android._st.lock = false; show('settings'); show('home'); }); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => !$('#home-start').hidden), scheme + ': (opnieuw open voor de volgende controle)');
    await pg.click('#home-start button:has-text("Niet meer tonen")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => $('#home-start').hidden && !store.get('homeStart', 0)), scheme + ': Niet meer tonen haalt hem weg');
    await pg.close();
  }
  console.log('errors', errs);
  await b.close();
  if (errs.length) process.exit(1);
})();
