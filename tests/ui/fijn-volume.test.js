// Fijner volume met de volumeknoppen: dezelfde instelling bij Podcasts → Instellingen en bij Spraak, de uitleg, de
// melding bij een knop terwijl Tools speelt, Engels, en een oudere app zonder deze functie (kaart weg).
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
    const txt = async sel => (await pg.textContent(sel)).replace(/\s+/g, ' ').trim();

    // Podcasts → Instellingen: standaard aan, 3 keer zo fijn
    await pg.evaluate(() => show('podcasts')); await pg.waitForTimeout(150);
    await pg.click('#pc-set-btn'); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#ps-fvk'), 'kaart Volumeknoppen bij Podcasts');
    ok(await pg.getAttribute('#ps-fv', 'aria-checked') === 'true', 'standaard aan');
    ok(await pg.inputValue('#ps-fvstap') === '3', 'standaard 3 keer zo fijn');
    const opts = await pg.$$eval('#ps-fvstap option', o => o.map(x => x.textContent));
    ok(opts.length === 3 && opts[1] === '3 keer zo fijn (45 stappen)', 'keuzes met het aantal stappen (' + opts + ')');
    ok((await txt('#ps-fvu')).includes('45 stappen in plaats van 15'), 'uitleg (' + await txt('#ps-fvu') + ')');

    // Fijner kiezen
    await pg.selectOption('#ps-fvstap', '4'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => Android._fv.fijn === 4 && Android._fv.aan === true), '4 keer zo fijn bewaard');
    ok((await txt('#ps-fvu')).includes('60 stappen'), 'uitleg bij 4');

    // Uit: keuze niet te veranderen, uitleg zegt het
    await pg.click('#ps-fv'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => Android._fv.aan === false && Android._fv.fijn === 4), 'uit bewaard, keuze blijft');
    ok(await pg.isDisabled('#ps-fvstap') && (await txt('#ps-fvu')).startsWith('Uit'), 'uit: keuze grijs, uitleg');

    // Spraak toont dezelfde instelling
    await pg.evaluate(() => show('spraak')); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#sv-fvk') && await pg.getAttribute('#sv-fv', 'aria-checked') === 'false' && await pg.inputValue('#sv-fvstap') === '4', 'Spraak: dezelfde stand');
    await pg.click('#sv-fv'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => Android._fv.aan === true) && !(await pg.isDisabled('#sv-fvstap')), 'Spraak: weer aan');

    // Via het register (zoeken in Instellingen / de assistent)
    ok(await pg.evaluate(() => { const r = SET_REG.find(x => x.id === 'podcast.fijnstap'); return r.set('2') === '' && Android._fv.fijn === 2 && r.get() === '2'; }), 'register: hoe fijn');

    // Een volumeknop terwijl Tools speelt: korte melding met het volume
    await pg.evaluate(() => onFijnVolume(23, 45)); await pg.waitForTimeout(100);
    ok((await txt('#toast')) === 'Volume 51%', 'melding bij de knop (' + await txt('#toast') + ')');

    // Engels
    await pg.evaluate(() => { rgSave({ lang: 'en' }); show('podset'); }); await pg.waitForTimeout(250);
    ok((await pg.$$eval('#ps-fvstap option', o => o.map(x => x.textContent)))[0] === '2 times as fine (30 steps)', 'Engels: keuzes');
    ok((await txt('#ps-fvu')).startsWith('Each step'), 'Engels: uitleg');
    await pg.evaluate(() => rgSave({ lang: 'nl' }));
    await pg.close();

    // Oudere app zonder fijn volume: geen kaart
    const oud = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    oud.on('pageerror', e => errs.push(e.message));
    await oud.goto(URL); await oud.waitForTimeout(300);
    await oud.evaluate(() => { delete Android.fijnVolume; show('podset'); }); await oud.waitForTimeout(150);
    ok(!(await oud.isVisible('#ps-fvk')), 'oudere app: geen kaart');
    await oud.close();
  }
  console.log('errors', errs);
  await b.close();
  if (errs.length) process.exitCode = 1;
})().catch(e => { console.error(e); process.exit(1); });
