const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    const scr = () => pg.evaluate(() => document.querySelector('.screen.on').id);
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    // radio alarm
    await pg.click('#tile-radio'); await pg.waitForTimeout(400);
    await pg.click('#rd-list .srow:nth-child(2) .sbtn'); await pg.waitForTimeout(300);
    await pg.click('#rd-alarm-btn'); await pg.waitForTimeout(200);
    console.log('alarm stations', await pg.$$eval('#al-station option', x => x.map(e => e.textContent)));
    await pg.fill('#al-time', '06:45'); await pg.click('#al-days button[data-d="5"]'); await pg.click('#al-days button[data-d="6"]');
    await pg.check('#al-on'); await pg.click('#s-ralarm button:has-text("Opslaan")'); await pg.waitForTimeout(150);
    console.log('saved', await pg.evaluate(() => JSON.stringify(Android._al)).then(s => s.slice(0, 90)), '|', await pg.textContent('#al-next'));
    await pg.screenshot({ path: __dirname + '/shots/s4-alarm-' + scheme + '.png', fullPage: true });
    await pg.evaluate(() => goBack()); console.log('back to', await scr());
    await pg.evaluate(() => goBack());
    // notes reminder + share
    await pg.click('#tile-notes'); await pg.waitForTimeout(200);
    await pg.click('#notes-list .nrow >> nth=0'); await pg.waitForTimeout(200);
    await pg.click('button:has-text("Herinner mij")'); await pg.waitForTimeout(100);
    console.log('rem', await pg.textContent('#note-rem-info'));
    await pg.click('button:has-text("Delen (WhatsApp")'); console.log('shared', JSON.stringify(await pg.evaluate(() => Android._shared)));
    await pg.screenshot({ path: __dirname + '/shots/s4-note-' + scheme + '.png', fullPage: true });
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    console.log('bell in list', await pg.$$eval('#notes-list .nrem', x => x.length));
    // incoming share
    await pg.evaluate(() => { Android._ps = JSON.stringify({ title: '', text: 'Melk\nEieren\n- Brood\n[x] Kaas' }); openTool('share'); }); await pg.waitForTimeout(200);
    console.log('share note', await scr(), await pg.$$eval('#note-open .it', x => x.map(e => e.value)), 'done', await pg.$$eval('#note-done .it', x => x.map(e => e.value)));
    await pg.evaluate(() => { Android._ps = JSON.stringify({ title: 'Artikel', text: 'Een lange gedeelde tekst die geen lijstje is maar gewoon een alinea met veel woorden die samen meer dan honderdtwintig tekens bevatten om het verschil te testen.' }); openTool('share'); }); await pg.waitForTimeout(200);
    console.log('text note', await pg.inputValue('#note-title'), '|', (await pg.inputValue('#note-text')).slice(0, 20));
    // widget entry points
    await pg.evaluate(() => { Android._mu.token = 'x'; Android._mu.mic = true; openTool('music-now'); }); await pg.waitForTimeout(600);
    console.log('music-now', await scr(), await pg.textContent('#mu-state'));
    const nid = await pg.evaluate(() => notesGet()[0].id);
    await pg.evaluate(id => openTool('note:' + id), nid); await pg.waitForTimeout(200);
    console.log('note deep link', await scr());
    console.log('errors', errs);
  }
  await b.close();
})();
