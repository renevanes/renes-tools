// Notitie (bijv. boodschappenlijst) vastzetten op het startscherm van de app, de telefoon en de skin.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    await pg.evaluate(() => { const n = notesGet(); n.push({ id: 'bood', title: 'Boodschappen', text: '', items: [{ id: 'a', text: 'Melk', done: false }, { id: 'b', text: 'Brood', done: false }, { id: 'c', text: 'Kaas', done: true }], updated: Date.now() }); notesSaveNow(); });
    console.log('notes group hidden at start', !(await pg.isVisible('#home-notes')));
    await pg.evaluate(() => openNote('bood')); await pg.waitForTimeout(150);
    console.log('phone btn', await pg.textContent('#note-pin-phone'));
    await pg.check('#note-pin-app'); await pg.click('#note-pin-phone'); await pg.waitForTimeout(100);
    console.log('pins', await pg.evaluate(() => Android.notePins('app')), 'phone shortcut', await pg.evaluate(() => Android._pinned), 'toast', await pg.textContent('#toast'));
    await pg.evaluate(() => goBack()); await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    console.log('home tile', await pg.isVisible('#home-notes'), (await pg.textContent('#home-notes-tiles')).replace(/\s+/g, ' '));
    await pg.screenshot({ path: __dirname + '/shots/notepin-home-' + scheme + '.png' });
    await pg.click('#home-notes-tiles .tile'); await pg.waitForTimeout(150);
    console.log('opened', await pg.evaluate(() => current + ':' + noteCur));
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150); console.log('back to', await pg.evaluate(() => current));
    // skin is het startscherm: zelfde knop zet hem op de skin
    await pg.evaluate(() => { Android._home = true; openNote('bood'); }); await pg.waitForTimeout(100);
    console.log('skin btn', await pg.textContent('#note-pin-phone')); await pg.click('#note-pin-phone');
    console.log('skin pins', await pg.evaluate(() => Android.notePins('skin')), await pg.textContent('#note-pin-phone'));
    await pg.uncheck('#note-pin-app'); await pg.evaluate(() => { goBack(); goBack(); }); await pg.waitForTimeout(150);
    console.log('unpinned app', !(await pg.isVisible('#home-notes')));
    // shortcut van de telefoon opent de notitie
    await pg.evaluate(() => openTool('note:bood')); console.log('shortcut', await pg.evaluate(() => current + ':' + noteCur));
    console.log('errors', errs);
  }
  // skin toont vastgezette notities
  const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  const errs = []; pg.on('pageerror', e => errs.push(e.message));
  await pg.goto('file://' + require('path').join(__dirname, '..', '..', 'web', 'start.html')); await pg.waitForTimeout(400);
  console.log('skin card', (await pg.textContent('#snotes')).replace(/\s+/g, ' '));
  await pg.click('#snotes .nc'); console.log('skin opens', await pg.evaluate(() => Android._tool));
  console.log('order', await pg.evaluate(() => cfg.order.join(',')));
  await pg.evaluate(() => { document.body.style.background = 'linear-gradient(160deg,#1e3a8a,#db2777)'; });
  await pg.screenshot({ path: __dirname + '/shots/notepin-skin.png', fullPage: true });
  // oude indeling zonder 'notes' krijgt het vóór de apps
  await pg.evaluate(() => { Android._cfg = JSON.stringify({ order: ['clock', 'apps', 'weather', 'cal', 'tools'] }); }); await pg.reload(); await pg.waitForTimeout(300);
  console.log('errors', errs);
  await b.close();
})();
