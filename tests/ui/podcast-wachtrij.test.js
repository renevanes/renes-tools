// Podcasts → Instellingen: wat er na een aflevering komt (Hierna, de volgende van de podcast), doorgaan met de
// slaaptimer, "Daarna vanzelf" in de speler, bufferen tonen en de downloadinstellingen op hetzelfde scherm.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    const ok = (c, m) => { if (!c) errs.push('mislukt: ' + m); };
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const txt = async sel => (await pg.textContent(sel)).replace(/\s+/g, ' ').trim();

    // Instellingen via de schuifjes in de kop van Podcasts
    await pg.evaluate(() => show('podcasts')); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#pc-set-btn'), 'schuifjes in de kop');
    await pg.click('#pc-set-btn'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'podset'), 'Instellingen van Podcasts geopend');
    ok(await pg.inputValue('#ps-na') === 'podcast', 'standaard: Hierna, daarna de volgende van dezelfde podcast');
    ok((await txt('#ps-na-uitleg')).includes('dezelfde podcast'), 'uitleg bij de keuze');
    ok(await pg.getAttribute('#ps-naslaap', 'aria-checked') === 'true', 'doorgaan met slaaptimer staat standaard aan');

    // Een aflevering laten spelen; Hierna is leeg: daarna vanzelf de nieuwere aflevering van dezelfde podcast
    await pg.evaluate(() => { const f = Android._pcFeed('https://feeds.example.org/vandaag'); Android.podPlay(JSON.stringify({ ep: f.items[5], pod: f.pod })); });
    await pg.evaluate(() => enterPodSet()); await pg.waitForTimeout(100);
    ok((await txt('#ps-next')).includes('Aflevering 60'), 'na deze: de nieuwere van dezelfde podcast (' + await txt('#ps-next') + ')');

    // In de speler: Daarna vanzelf onder een lege Hierna
    await pg.evaluate(() => pcPoll(true)); await pg.waitForFunction(() => pcSt.ep && pcSt.status === 'playing', null, { timeout: 5000 });
    await pg.evaluate(() => { plOpen(); plQueueSig = ''; plRender(); }); await pg.waitForTimeout(300);
    ok((await txt('#pl-queue')).includes('Daarna vanzelf: Aflevering 60'), 'speler toont wat er vanzelf komt (' + await txt('#pl-queue') + ')');

    // Iets in Hierna gaat voor
    await pg.evaluate(() => { const f = Android._pcFeed('https://feeds.example.org/tech'); Android.podQueueAdd(JSON.stringify({ ep: f.items[2], pod: f.pod }), true); plQueueSig = ''; plRender(); }); await pg.waitForTimeout(150);
    ok(!(await txt('#pl-queue')).includes('Daarna vanzelf'), 'met iets in Hierna geen "Daarna vanzelf"');
    await pg.evaluate(() => { plClose(); show('podset'); }); await pg.waitForTimeout(100);
    ok((await txt('#ps-next')).includes('Tech in 20 minuten'), 'na deze: de eerste uit Hierna');
    await pg.evaluate(() => Android.podQueueClear());

    // Stoppen: niets meer, behalve met een slaaptimer op tijd (dan toch de volgende van de podcast)
    await pg.selectOption('#ps-na', 'stop'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => Android._pc.na === 'stop') && (await txt('#ps-next')).includes('stopt de speler'), 'stoppen bewaard en uitgelegd');
    await pg.evaluate(() => { Android.podSleep(30); enterPodSet(); }); await pg.waitForTimeout(80);
    ok((await txt('#ps-next')).includes('Aflevering 60'), 'met slaaptimer gaat hij toch door');
    await pg.click('#ps-naslaap'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => Android._pc.naSlaap === false) && (await txt('#ps-next')).includes('stopt de speler'), 'zonder doorgaan-met-slaaptimer: stoppen');
    await pg.evaluate(() => { Android.podSleep(-1); Android.podNaSet('nieuw', true); enterPodSet(); }); await pg.waitForTimeout(80);
    ok((await txt('#ps-next')).includes('stopt de speler'), '"stoppen na deze aflevering" stopt altijd');
    await pg.evaluate(() => { Android.podSleep(0); Android.podNaSet('podcast', true); });

    // Bufferen: de speler zegt waarom het stil is
    await pg.evaluate(() => { Android._pc.st.buffering = true; Android._pc.st.status = 'playing'; plOpen(); }); await pg.waitForTimeout(1300);
    ok((await txt('#pl-state')).includes('Bufferen'), 'bufferen zichtbaar (' + await txt('#pl-state') + ')');
    await pg.evaluate(() => { Android._pc.st.buffering = false; plClose(); });

    // Downloaden staat op hetzelfde scherm (ook via het menu)
    await pg.evaluate(() => { show('podcasts'); pcDlSettings(); }); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => current === 'podset'), 'Instellingen voor downloaden → dit scherm');
    const wifi0 = await pg.evaluate(() => JSON.parse(Android.podDlPrefs()).wifiOnly);
    await pg.click('#ps-wifi'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(w => JSON.parse(Android.podDlPrefs()).wifiOnly === !w, wifi0) && await pg.getAttribute('#ps-wifi', 'aria-checked') === String(!wifi0), 'alleen via wifi');
    await pg.evaluate(() => goBack()); ok(await pg.evaluate(() => current === 'podcasts'), 'terug naar Podcasts');

    console.log(scheme, 'errors', errs);
    if (errs.length) process.exitCode = 1;
    await pg.close();
  }
  await b.close();
})().catch(e => { console.error(e); process.exit(1); });
