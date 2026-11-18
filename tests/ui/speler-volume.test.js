// Volumeschuif in de grote speler: van de telefoon (met Fijner volume in de fijne stappen), van de Chromecast tijdens
// casten, de volumeknoppen zetten hem mee, en een oudere app zonder deze brugfunctie.
const { chromium } = require('playwright');
const path = require('path');
const URL = 'file://' + path.join(__dirname, 'ui', 'index.html');
(async () => {
  const b = await chromium.launch();
  const errs = [];
  const ok = (c, m) => { if (!c) errs.push('mislukt: ' + m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(URL); await pg.waitForTimeout(300);
    await pg.evaluate(() => { const f = Android._pcFeed('https://feeds.example.org/vandaag'); Android.podPlay(JSON.stringify({ ep: f.items[3], pod: f.pod })); pcPoll(true); });
    await pg.waitForFunction(() => pcSt.ep, null, { timeout: 5000 });
    await pg.evaluate(() => plOpen()); await pg.waitForTimeout(300);

    // Telefoon, Fijner volume aan (3 keer zo fijn): 45 stappen
    ok(await pg.isVisible('#pl-vol'), 'schuif zichtbaar zonder Chromecast');
    ok(await pg.getAttribute('#pl-vol', 'max') === '45' && await pg.inputValue('#pl-vol') === '30', 'fijne stappen: 30 van 45');
    ok(await pg.getAttribute('#pl-vol', 'aria-label') === 'Volume' && (await pg.getAttribute('#pl-vol', 'aria-valuetext')) === 'Volume 67%', 'naam en stand voor de schermlezer');
    // Schuiven zet het volume meteen
    await pg.fill('#pl-vol', '12'); await pg.dispatchEvent('#pl-vol', 'input'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => Android._tv === 12), 'schuiven zet het volume');
    // De volumeknoppen zetten de schuif mee, zonder melding eroverheen
    await pg.evaluate(() => { document.getElementById('toast').textContent = ''; onFijnVolume(20, 45); }); await pg.waitForTimeout(80);
    ok(await pg.inputValue('#pl-vol') === '20' && (await pg.textContent('#toast')) === '', 'knop: schuif mee, geen melding');

    // Fijner volume uit: de stappen van Android
    await pg.evaluate(() => { Android.fijnVolumeSet(false, 3); plRender(); }); await pg.waitForTimeout(1300);
    await pg.evaluate(() => plRender());
    ok(await pg.getAttribute('#pl-vol', 'max') === '15', 'uit: 15 stappen van Android');
    await pg.evaluate(() => Android.fijnVolumeSet(true, 3));

    // Chromecast: dezelfde schuif voor het volume van de Chromecast
    await pg.evaluate(() => document.activeElement.blur());
    await pg.evaluate(() => { Android._cast = { active: true, device: 'Woonkamer', src: 'podcast', status: 'playing', playing: true, title: 'Aflevering', volume: 0.5 }; plRender(); }); await pg.waitForTimeout(100);
    ok(await pg.getAttribute('#pl-vol', 'max') === '20' && await pg.getAttribute('#pl-vol', 'aria-label') === 'Volume van de Chromecast' && await pg.inputValue('#pl-vol') === '10', 'Chromecast: eigen schaal en naam');
    await pg.fill('#pl-vol', '5'); await pg.dispatchEvent('#pl-vol', 'change'); await pg.waitForTimeout(50);
    ok(await pg.evaluate(() => Android._cast.volume === 0.25 && Android._tv === 12), 'Chromecast: volume van de Chromecast, niet van de telefoon');
    await pg.evaluate(() => { Android._cast = { active: false }; plRender(); });
    await pg.close();

    // Oudere app zonder telefoonVolume: geen schuif zonder Chromecast
    const oud = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    oud.on('pageerror', e => errs.push(e.message));
    await oud.goto(URL); await oud.waitForTimeout(300);
    await oud.evaluate(() => { delete Android.telefoonVolume; const f = Android._pcFeed('https://feeds.example.org/vandaag'); Android.podPlay(JSON.stringify({ ep: f.items[3], pod: f.pod })); pcPoll(true); });
    await oud.waitForFunction(() => pcSt.ep, null, { timeout: 5000 });
    await oud.evaluate(() => plOpen()); await oud.waitForTimeout(300);
    ok(!(await oud.isVisible('#pl-vol')), 'oudere app: geen schuif');
    await oud.close();
  }
  console.log('errors', errs);
  await b.close();
  if (errs.length) process.exitCode = 1;
})().catch(e => { console.error(e); process.exit(1); });
