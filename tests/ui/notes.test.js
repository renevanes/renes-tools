const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light','dark']) {
  const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
  const errs = []; pg.on('pageerror', e => errs.push(e.message));
  await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
  console.log('tile', await pg.textContent('#tile-notes-sub'));
  await pg.click('#tile-notes'); await pg.waitForTimeout(200);
  await pg.click('#s-notes .fab'); await pg.waitForTimeout(150);
  await pg.keyboard.type('Klusjes');
  for (const t of ['Lamp vervangen','Gras maaien','Fiets plakken','Dakgoot']) { await pg.fill('#note-add', t); await pg.press('#note-add','Enter'); }
  await pg.click('#note-open li:nth-child(2) input[type=checkbox]'); await pg.waitForTimeout(100);
  await pg.fill('#note-text', 'Voor zaterdag');
  await pg.waitForTimeout(500);
  await pg.screenshot({ path: __dirname + '/shots/n-edit-'+scheme+'.png' });
  console.log('open', await pg.$$eval('#note-open li', x=>x.length), 'done', await pg.$$eval('#note-done li', x=>x.length));
  await pg.click('#s-note .back'); await pg.waitForTimeout(200);
  await pg.screenshot({ path: __dirname + '/shots/n-list-'+scheme+'.png' });
  await pg.fill('#notes-search', 'fiets'); await pg.waitForTimeout(100);
  console.log('search', await pg.$$eval('#notes-list .nrow', x=>x.map(e=>e.innerText.replace(/\n/g,' | '))));
  await pg.screenshot({ path: __dirname + '/shots/n-search-'+scheme+'.png' });
  // empty note gets discarded
  await pg.fill('#notes-search', ''); await pg.click('#s-notes .fab'); await pg.click('#s-note .back'); await pg.waitForTimeout(100);
  const saved = JSON.parse(await pg.evaluate(() => Android._notes));
  console.log('saved notes', saved.length, saved.map(n=>n.title+':'+n.items.length));
  // delete
  await pg.click('#notes-list .nrow >> nth=0'); await pg.click('#s-note button[aria-label=Verwijderen]'); await pg.click('#modal-ok'); await pg.waitForTimeout(100);
  console.log('after delete', JSON.parse(await pg.evaluate(() => Android._notes)).length, 'screen', await pg.evaluate(()=>document.querySelector('.screen.on').id));
  console.log('errors', errs);
  }
  await b.close();
})();
