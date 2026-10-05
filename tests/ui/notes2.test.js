// Notities: volgorde (slepen, sorteren), terugkerend lijstje en delen (hele lijst of alleen wat nog moet).
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme, hasTouch: true });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    await pg.evaluate(() => { const n = notesGet(); n.push({ id: 'wk', title: 'Weekboodschappen', text: '', items: [{ id: 'a', text: 'Melk', done: false }, { id: 'b', text: 'Brood', done: false }, { id: 'c', text: 'Appels', done: false }, { id: 'd', text: 'Kaas', done: true }], updated: Date.now() }); notesSaveNow(); openNote('wk'); });
    await pg.waitForTimeout(150);
    // slepen: Appels (3e) naar boven
    const h = await pg.$('#note-open li[data-id=c] .drag'), t = await pg.$('#note-open li[data-id=a]');
    const hb = await h.boundingBox(), tb = await t.boundingBox();
    await pg.mouse.move(hb.x + hb.width / 2, hb.y + hb.height / 2); await pg.mouse.down();
    await pg.mouse.move(hb.x + hb.width / 2, tb.y + 4, { steps: 8 }); await pg.mouse.up(); await pg.waitForTimeout(100);
    console.log('drag order', await pg.evaluate(() => noteById('wk').items.map(i => i.text).join(',')));
    await pg.evaluate(() => noteSort()); await pg.click('#sheet-acts button:has-text("A tot Z")');
    console.log('sorted', await pg.evaluate(() => noteById('wk').items.map(i => i.text).join(',')));
    await pg.selectOption('#note-repeat', 'week:6'); await pg.waitForTimeout(50);
    console.log('repeat', await pg.evaluate(() => JSON.stringify({every: noteById('wk').repeat.every, n: noteById('wk').repeat.n})), '|', await pg.textContent('#note-repeat-info'));
    await pg.evaluate(() => { window._shared = null; Android.noteShare = (t, x) => { window._shared = x; }; noteShareNow(); });
    console.log('share choice', await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent).join(' | ')));
    await pg.click('#sheet-acts button:has-text("Alleen wat nog moet")');
    console.log('shared open only', await pg.evaluate(() => JSON.stringify(window._shared)));
    await pg.screenshot({ path: __dirname + '/shots/notes2-' + scheme + '.png', fullPage: true });
    console.log('errors', errs); await pg.close();
  }
  await b.close();
})();
