// De assistent in het Engels (web/src/script/59b-assistent-engels.js): Engelse opdrachten worden dezelfde kaarten als in
// het Nederlands (timer, wekker, radio, openen, instellingen, Regio en taal), altijd pas na een tik; zinnen die op een
// opdracht lijken maar het niet zijn, gaan gewoon naar het taalmodel (geen kaart). Nederlands blijft werken, en als de app
// Nederlands is worden Engelse zinnen niet omgezet. Ook: terug naar Nederlands met "switch to Dutch". Licht en donker.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const url = 'file://' + require('path').join(__dirname, 'ui', 'index.html');
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(url); await pg.waitForTimeout(300);
    await pg.evaluate(() => { rgSave({ lang: 'en' }); Android._ai.st.klein = { state: 'ready' }; Android._rd.favs = [Android._rd.list[1], Android._rd.list[2]]; show('ai'); });
    await pg.waitForTimeout(250);
    const last = '#ai-msgs .aimsg:last-child', card = last + ' .cmdcard', setc = last + ' .setcard';
    const ask = async q => {
      await pg.waitForFunction(() => !aiBusy, null, { timeout: 8000 }).catch(() => {});
      const a0 = await pg.evaluate(() => Android._ai.asked || 0);
      await pg.fill('#ai-in', q); await pg.click('#ai-send'); await pg.waitForTimeout(300);
      return pg.evaluate(([last, a0]) => ({ txt: ((($(last) || {}).innerText) || '').replace(/\s+/g, ' '), card: !!document.querySelector(last + ' .cmdcard'), set: !!document.querySelector(last + ' .setcard'), llm: (Android._ai.asked || 0) > a0 }), [last, a0]);
    };
    const done = () => pg.evaluate(() => (Android._aiDone || []).slice());
    ok(await pg.evaluate(() => appLang() === 'en' && TAAL.on), scheme + ': de app is Engels');

    // Timer
    let r = await ask('Set a timer for 10 minutes');
    ok(r.card && !r.llm && /Set a timer\?/.test(r.txt) && /Timer for 10 min/.test(r.txt) && !/Timer zetten|Annuleren/.test(r.txt), 'timer: Engelse kaart (' + r.txt.replace(/\s+/g, ' ').slice(0, 80) + '…)');
    ok((await done()).length === 0, 'nog niets gedaan');
    await pg.click(card + ' .cmdgo'); await pg.waitForTimeout(150);
    let d = await done();
    ok(d.length === 1 && d[0].type === 'timer' && d[0].secs === 600, 'na de tik: timer van 600 s');
    r = await ask('please set a 5 minute timer');
    ok(r.card && /Timer for 5 min/.test(r.txt), '"please set a 5 minute timer"');
    // Wekker
    r = await ask('wake me up at 7');
    ok(r.card && /Alarm at 07:00/.test(r.txt), 'wekker: "wake me up at 7" → 07:00');
    await pg.click(card + ' .cmdgo'); await pg.waitForTimeout(150);
    d = await done(); ok(d[d.length - 1].type === 'wekker' && d[d.length - 1].min === 420, 'wekker om 07:00 naar de Klok-app');
    // Radio en openen
    r = await ask('play radio 2');
    ok(r.card && /NPO Radio 2/.test(r.txt) && /Play the radio\?/.test(r.txt), 'radio: "play radio 2" → NPO Radio 2');
    r = await ask('open notes');
    ok(r.card && /Notes/.test(r.txt) && /Open\?/.test(r.txt), 'openen: "open notes"');
    // Instellingen
    await pg.evaluate(() => { store.set('theme', Object.assign({}, thCfg(), { mode: 'light' })); thApply(true); });
    r = await ask('turn on dark mode');
    ok(r.set && !r.llm && /Dark theme/.test(r.txt) && /From off to on/.test(r.txt) && !/ naar /.test(r.txt), 'instelling: "turn on dark mode" → Dark theme, van off naar on (Engels)' + (r.set ? '' : ': ' + r.txt.slice(0, 160)));
    await pg.click(setc + ' .mini.on'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => thCfg().mode === 'dark'), 'na Apply: thema donker');
    r = await ask('use miles');
    ok(r.set && /Distance unit/.test(r.txt) && /Miles/.test(r.txt), 'regio: "use miles"');
    await pg.click(setc + ' .mini.on'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => rgCfg().dist === 'mi'), 'na Apply: mijlen');
    r = await ask('make the text larger');
    ok(r.set && /Text size/.test(r.txt) && /Larger/.test(r.txt), '"make the text larger" → Text size');
    r = await ask('use the 12-hour clock');
    ok(r.set && /Time format/.test(r.txt), '"use the 12-hour clock" → Time format');
    r = await ask('where can I find the text size');
    ok(!r.set && !r.card && !r.llm && /Text size/.test(r.txt) && /Settings → Layout/.test(r.txt), 'opzoeken: "where can I find the text size" → waar het staat, zonder taalmodel');
    r = await ask('is the nightly backup on');
    ok(!r.llm && /every night is set to off/.test(r.txt), 'vraag naar een stand: "is the nightly backup on"');
    // Nederlands werkt nog steeds
    r = await ask('zet een timer van 5 minuten');
    ok(r.card && /Timer for 5 min/.test(r.txt), 'een Nederlandse opdracht werkt ook (antwoord in het Engels)');

    // Geen opdracht: gewoon het taalmodel, geen kaart
    for (const q of ['turn on the lights', 'turn off the stove', 'play a game with me', 'play some music', 'open the door', 'set the table for dinner', 'set my mind at ease', 'what is the capital of France', 'where is Amsterdam']) {
      r = await ask(q);
      ok(!r.card && !r.set && r.llm, 'geen opdracht: "' + q + '" → taalmodel, geen kaart');
    }
    const n0 = (await done()).length;
    ok(n0 === 2 && await pg.evaluate(() => rgCfg().lang === 'en'), 'daarbij is niets gedaan');

    // Terug naar Nederlands
    r = await ask('switch to Dutch');
    ok(r.set && /App language/.test(r.txt) && /From English to Dutch/.test(r.txt), '"switch to Dutch" → Taal van de app, van English naar Nederlands');
    await pg.click(setc + ' .mini.on'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => rgCfg().lang === 'nl' && !TAAL.on && document.documentElement.lang === 'nl' && JSON.parse(Android._regioCfg || '{}').lang === 'nl'), 'na Apply: de app is weer Nederlands');
    // In het Nederlands worden Engelse zinnen niet omgezet
    ok(await pg.evaluate(() => aiCmdQ('use miles') === 'use miles' && aiCmdQ('switch to English') === 'switch to English'), 'Nederlandse app: Engelse zinnen worden niet omgezet');
    r = await ask('play a game with me');
    ok(!r.card && !r.set && r.llm, 'Nederlandse app: "play a game with me" → taalmodel');
    r = await ask('zet de taal op Engels');
    ok(r.set && /Taal van de app/.test(r.txt) && /English/.test(r.txt), '"zet de taal op Engels" → Taal van de app');
    await pg.click(setc + ' .mini.on'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => rgCfg().lang === 'en' && TAAL.on), 'na Toepassen: Engels');
    await pg.screenshot({ path: require('path').join(__dirname, 'shots', 'ai-engels-' + scheme + '.png') });
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
