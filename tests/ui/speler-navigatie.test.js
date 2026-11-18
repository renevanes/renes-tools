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
    await pg.evaluate(() => { const f = Android._pcFeed('https://feeds.example.org/vandaag'); Android.podPlay(JSON.stringify({ ep: f.items[60], pod: f.pod })); pcPoll(true); });
    await pg.waitForFunction(() => pcSt.ep, null, { timeout: 5000 });
    await open();

    // Slaaptimer helemaal rechts
    const opts = await pg.$$eval('#pl-opts .pl-opt', x => x.filter(e => e.offsetParent).map(e => e.id));
    ok(opts[opts.length - 1] === 'pl-sleep', 'slaaptimer rechts (' + opts + ')');

    // Namen voor de schermlezer: de titel zelf, met waar hij heen gaat als uitleg (de speler heet naar de titel)
    const naam = await pg.evaluate(() => ({ titel: $('#pl-title').textContent, dialoog: document.getElementById($('#player').getAttribute('aria-labelledby')).textContent,
      th: $('#pl-title-h').textContent, sh: $('#pl-sub-h').textContent, kh: $('#pl-kind-h').textContent, al: $('#pl-titleb').getAttribute('aria-label') }));
    ok(naam.dialoog === naam.titel && naam.al === null && naam.th === 'Tonen tussen de afleveringen' && naam.sh === 'Naar de podcast' && naam.kh === 'Naar Podcasts', 'namen en uitleg (' + JSON.stringify(naam) + ')');
    // Tikvlakken minstens 44 px
    ok(await pg.evaluate(() => ['#pl-titleb', '#pl-subb'].every(id => $(id).getBoundingClientRect().height >= 43.5)), 'titel en naam: tikvlak 44 px');
    ok(await pg.evaluate(() => { const r = $('#pl-kind').getBoundingClientRect(), x = r.left + r.width / 2; return document.elementFromPoint(x, r.bottom + 4) === $('#pl-kind') && document.elementFromPoint(r.left - 8, r.top + r.height / 2) === $('#pl-kind'); }), 'Podcast bovenaan: groter tikvlak');

    // Naam van de podcast → de podcast
    await pg.click('#pl-subb'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => current === 'podcast' && !plIsOpen), 'naam: naar de podcast, speler verkleind');

    // Titel van de aflevering → de podcast, met deze aflevering in beeld (ook verder dan de eerste 50)
    await open();
    await pg.click('#pl-titleb');
    await pg.waitForFunction(() => document.querySelector('#pd-eps .pcep.cur'), null, { timeout: 5000 }); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => { const r = document.querySelector('#pd-eps .pcep.cur'), b = r.getBoundingClientRect();
      return current === 'podcast' && +r.dataset.i >= 50 && r.classList.contains('pcep-hl') && b.top >= 0 && b.bottom <= innerHeight && document.activeElement === r.querySelector('.pcepb'); }), 'titel: aflevering (verder dan de eerste 50) in beeld, oplichtend en met focus');
    // Oplichten blijft na het verversen van de rij
    await pg.evaluate(() => { pcSt = Object.assign({}, pcSt, { pos: (+pcSt.pos || 0) + 30000 }); pdRefreshRows(); });
    ok(await pg.evaluate(() => document.querySelector('#pd-eps .pcep.cur').classList.contains('pcep-hl')), 'oplichten blijft na verversen');

    // Beluisterd en verborgen: de lijst toont ze weer
    await pg.evaluate(() => { if (!pdHide) pdHideToggle(); Android.podMarkPlayed(pcSt.ep.key, true); });
    await open(); await pg.click('#pl-titleb');
    await pg.waitForFunction(() => document.querySelector('#pd-eps .pcep-hl'), null, { timeout: 5000 });
    ok(await pg.evaluate(() => pdList().some(e => e.key === pcSt.ep.key) && document.querySelector('#pd-eps .pcep-hl .pcepb') === document.activeElement), 'beluisterd en "verberg beluisterd": toch in beeld');
    // Zuiver: een verborgen aflevering zet het verbergen uit
    ok(await pg.evaluate(() => { if (!pdHide) pdHideToggle(); const e = pdFeed.items.find(x => x.key !== pcSt.ep.key); e.done = true; pdRender(); pdToon(e.key); return !pdHide && $('#pd-hide').getAttribute('aria-pressed') === 'false' && !!document.querySelector('#pd-eps .pcep-hl'); }), 'verborgen aflevering: beluisterde weer zichtbaar');

    // "Podcast" bovenaan → Podcasts
    await open();
    await pg.click('#pl-kind'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'podcasts'), 'bovenaan: naar Podcasts');

    // Radio: titel, naam en "Radio" gaan naar Radio
    await pg.evaluate(() => { Android.podStop(); const st = rdJson(Android.radioFavorites(), [])[0] || { name: 'Testzender', url: 'https://stream.example.org/a' }; Android.radioPlay(JSON.stringify(st)); plHintSrc('radio'); rdPollOnce(true); });
    await pg.waitForFunction(() => (plModel() || {}).src === 'radio', null, { timeout: 5000 });
    for (const id of ['#pl-titleb', '#pl-subb', '#pl-kind']) {
      await pg.evaluate(() => { show('nieuws'); plOpen(); }); await pg.waitForTimeout(250);
      ok(await pg.evaluate(() => $('#pl-title-h').textContent === 'Naar Radio'), 'radio: uitleg');
      await pg.click(id); await pg.waitForTimeout(200);
      ok(await pg.evaluate(() => current === 'radio'), 'radio: ' + id + ' naar Radio');
    }

    // Engels
    await pg.evaluate(() => { rgSave({ lang: 'en' }); }); await open();
    ok(await pg.evaluate(() => $('#pl-kind-h').textContent === 'To Radio'), 'Engels: ' + await pg.evaluate(() => $('#pl-kind-h').textContent));
    await pg.evaluate(() => rgSave({ lang: 'nl' }));
    await pg.close();

    // Losse app Radio met een podcast in de speler: niet naar Podcasts (die hoort niet bij deze app)
    const solo = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    solo.on('pageerror', e => errs.push(e.message));
    await solo.addInitScript(() => { window.__testSolo = 'radio'; });
    await solo.goto(URL); await solo.waitForTimeout(400);
    await solo.evaluate(() => { const f = Android._pcFeed('https://feeds.example.org/vandaag'); Android.podPlay(JSON.stringify({ ep: f.items[3], pod: f.pod })); plHintSrc('podcast'); pcPoll(true); });
    await solo.waitForFunction(() => (plModel() || {}).src === 'podcast', null, { timeout: 5000 });
    await solo.evaluate(() => { show('radio'); plOpen(); }); await solo.waitForTimeout(300);
    ok(await solo.evaluate(() => ['#pl-titleb', '#pl-subb', '#pl-kind'].every(id => $(id).getAttribute('aria-disabled') === 'true')), 'losse app: knoppen naar een andere tool uit');
    await solo.evaluate(() => $('#pl-titleb').click()); await solo.waitForTimeout(200);
    ok(await solo.evaluate(() => plIsOpen && current !== 'podcast' && !pdPod), 'losse app: blijft in de speler, haalt niets op');
    await solo.close();
  }
  console.log('errors', errs);
  await b.close();
  if (errs.length) process.exitCode = 1;
})().catch(e => { console.error(e); process.exit(1); });
