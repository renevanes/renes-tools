// Grote speler: de slaaptimer staat rechts; tikken op "Podcast", de naam van de podcast of de titel van de aflevering
// gaat naar het overzicht (de aflevering in beeld); bij de radio naar Radio.
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
    const open = async () => { await pg.evaluate(() => { pcPoll(true); plOpen(); }); await pg.waitForTimeout(300); };
    await pg.evaluate(() => { const f = Android._pcFeed('https://feeds.example.org/vandaag'); Android.podPlay(JSON.stringify({ ep: f.items[40], pod: f.pod })); pcPoll(true); });
    await pg.waitForFunction(() => pcSt.ep, null, { timeout: 5000 });
    await open();

    // Slaaptimer helemaal rechts
    const opts = await pg.$$eval('#pl-opts .pl-opt', x => x.filter(e => e.offsetParent).map(e => e.id));
    ok(opts[opts.length - 1] === 'pl-sleep', 'slaaptimer rechts (' + opts + ')');

    // Naam van de podcast → de podcast
    ok(await pg.getAttribute('#pl-sub', 'aria-label') === 'Vandaag, naar de podcast' || (await pg.getAttribute('#pl-sub', 'aria-label')).endsWith(', naar de podcast'), 'naam: knop met uitleg');
    await pg.click('#pl-sub'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => current === 'podcast' && !plIsOpen), 'naam: naar de podcast, speler verkleind');

    // Titel van de aflevering → de podcast, met deze aflevering in beeld (ook verder dan de eerste 50)
    await open();
    await pg.click('#pl-title');
    await pg.waitForFunction(() => document.querySelector('#pd-eps .pcep.cur'), null, { timeout: 5000 }); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => { const r = document.querySelector('#pd-eps .pcep.cur'), b = r.getBoundingClientRect();
      return current === 'podcast' && r.classList.contains('flits') && b.top >= 0 && b.bottom <= innerHeight && document.activeElement === r.querySelector('.pcepb'); }), 'titel: aflevering in beeld, oplichtend en met focus');

    // "Podcast" bovenaan → Podcasts
    await open();
    ok(await pg.getAttribute('#pl-kind', 'aria-label') === 'Naar Podcasts', 'bovenaan: naam');
    await pg.click('#pl-kind'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'podcasts'), 'bovenaan: naar Podcasts');

    // Radio: titel en naam gaan naar Radio
    await pg.evaluate(() => { Android.podStop && Android.podStop(); const st = rdJson(Android.radioFavorites(), [])[0] || { name: 'Testzender', url: 'https://stream.example.org/a' }; Android.radioPlay(JSON.stringify(st)); plHintSrc('radio'); rdPollOnce && rdPollOnce(true); });
    await pg.waitForTimeout(400);
    const src = await pg.evaluate(() => (plModel() || {}).src);
    if (src === 'radio') {
      await pg.evaluate(() => { show('nieuws'); plOpen(); }); await pg.waitForTimeout(300);
      await pg.click('#pl-title'); await pg.waitForTimeout(200);
      ok(await pg.evaluate(() => current === 'radio'), 'radio: titel naar Radio');
    }

    // Engels
    await pg.evaluate(() => { rgSave({ lang: 'en' }); }); await open();
    ok((await pg.getAttribute('#pl-kind', 'aria-label') || '').startsWith('To '), 'Engels: ' + await pg.getAttribute('#pl-kind', 'aria-label'));
    await pg.evaluate(() => rgSave({ lang: 'nl' }));
    await pg.close();
  }
  console.log('errors', errs);
  await b.close();
  if (errs.length) process.exitCode = 1;
})().catch(e => { console.error(e); process.exit(1); });
