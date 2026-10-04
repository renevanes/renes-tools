const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message + ' ' + (e.stack||'').split('\n').slice(0,3).join(' | ')));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    // een notitie om te vinden
    await pg.evaluate(() => { const n = notesGet(); n.push({ id: 'n1', title: 'Boodschappen', text: '', items: [{ id: 'i1', text: 'Taart voor Piet', done: false }], updated: Date.now() }); });
    await pg.fill('#gs-q', 'p'); await pg.waitForTimeout(100);
    console.log('1 char: results hidden', !(await pg.isVisible('#gs-res')), 'tiles visible', await pg.isVisible('#home-groups'));
    await pg.fill('#gs-q', 'piet'); await pg.waitForTimeout(80);
    console.log('immediate', await pg.$$eval('#gs-res h3', x => x.map(e => e.innerText.replace(/\n/g, ' '))), 'tiles hidden', !(await pg.isVisible('#home-groups')), 'dash hidden', !(await pg.isVisible('#home-dash')));
    await pg.waitForTimeout(700);
    console.log('groups', await pg.$$eval('#gs-res h3', x => x.map(e => e.innerText.replace(/\n/g, ' '))));
    console.log('marks', await pg.$$eval('#gs-res mark', x => x.length));
    await pg.screenshot({ path: __dirname + '/shots/gs-' + scheme + '.png', fullPage: true });
    // contact openen en terug
    await pg.click('#gs-res .gi[data-k="contacts"]'); await pg.waitForTimeout(200);
    console.log('opened', await pg.evaluate(() => current));
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200);
    console.log('back to', await pg.evaluate(() => current), 'query kept', await pg.inputValue('#gs-q'), 'results', await pg.isVisible('#gs-res'));
    // notitie openen
    await pg.click('#gs-res .gi[data-k="notes"]'); await pg.waitForTimeout(200);
    console.log('note', await pg.evaluate(() => current), await pg.inputValue('#note-title'));
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    // "Alle 12" bij oproepen → oproepen met zoekterm
    await pg.click('#gs-res .more[data-k="calls"]'); await pg.waitForTimeout(400);
    console.log('more', await pg.evaluate(() => current), await pg.inputValue('#calls-search'));
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    // gesprek openen op de juiste plek
    await pg.evaluate(() => { Android._tx.done[Android._txRecs[0].id] = true; });
    await pg.click('#gs-res .gi[data-k="tx"]'); await pg.waitForTimeout(250);
    console.log('tx', await pg.evaluate(() => current));
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    // snel typen: oude resultaten tellen niet
    await pg.fill('#gs-q', 'xq'); await pg.waitForTimeout(50); await pg.fill('#gs-q', 'zzzz'); await pg.waitForTimeout(700);
    console.log('latest query groups', await pg.$$eval('#gs-res h3', x => x.map(e => e.innerText.split('\n')[0].trim())));
    await pg.fill('#gs-q', 'ölkjh'); await pg.evaluate(() => Android.searchAll = function(q){ setTimeout(() => onSearchAll({ id: 99, q, done: true }), 50); return 99; }); await pg.fill('#gs-q', 'qwqw'); await pg.waitForTimeout(600);
    console.log('nothing', await pg.textContent('#gs-res'));
    await pg.fill('#gs-q', ''); await pg.waitForTimeout(100);
    console.log('cleared: tiles back', await pg.isVisible('#home-groups'), 'results hidden', !(await pg.isVisible('#gs-res')));
    await pg.evaluate(() => show('calls')); await pg.waitForTimeout(150);
    console.log('tile open resets tool search', JSON.stringify(await pg.inputValue('#calls-search')));
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    await pg.fill('#gs-q', 'piet'); await pg.waitForTimeout(100);
    console.log('back on home clears search', await pg.evaluate(() => goBack()), JSON.stringify(await pg.inputValue('#gs-q')), await pg.isVisible('#home-groups'));
    console.log('errors', errs);
  }
  await b.close();
})();
