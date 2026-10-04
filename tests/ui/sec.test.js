const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    await pg.evaluate(() => { Android._wa.info.dest = true; }); await pg.click('#tile-backup'); await pg.waitForTimeout(200);
    console.log('enc state', await pg.textContent('#bk-enc-state'), '| rotate', await pg.isChecked('#bk-rotate'));
    // wachtwoord: te kort, ongelijk, goed
    await pg.click('#bk-enc-btn'); await pg.waitForTimeout(100);
    console.log('input type', await pg.getAttribute('#modal-input', 'type'));
    await pg.fill('#modal-input', 'kort'); await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    console.log('toast', await pg.textContent('#toast'));
    await pg.click('#bk-enc-btn'); await pg.waitForTimeout(100);
    await pg.fill('#modal-input', 'goedwachtwoord'); await pg.click('#modal-ok'); await pg.waitForTimeout(200);
    console.log('second', await pg.textContent('#modal-title'), await pg.getAttribute('#modal-input', 'type'));
    await pg.fill('#modal-input', 'anders123'); await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    console.log('toast', await pg.textContent('#toast'));
    await pg.click('#bk-enc-btn'); await pg.waitForTimeout(100);
    await pg.fill('#modal-input', 'goedwachtwoord'); await pg.click('#modal-ok'); await pg.waitForTimeout(200);
    await pg.fill('#modal-input', 'goedwachtwoord'); await pg.click('#modal-ok'); await pg.waitForTimeout(400);
    console.log('toast', await pg.textContent('#toast'), '| state', await pg.textContent('#bk-enc-state'), '| btn', await pg.textContent('#bk-enc-btn'), '| off visible', await pg.isVisible('#bk-enc-off'));
    await pg.click('#bk-rotate'); console.log('rotate on', await pg.evaluate(() => Android._sec.rotate));
    // backup met archief en opruimen
    await pg.click('#bk-run'); await pg.waitForTimeout(1600);
    console.log('last', await pg.$$eval('#bk-parts-res .bkres', x => x.slice(-2).map(e => e.innerText.replace(/\n/g, ' '))));
    // opruimen
    await pg.click('button:has-text("Nu opruimen")'); await pg.waitForTimeout(300);
    console.log('rotate dialog', await pg.textContent('#modal-title'), '|', await pg.textContent('#modal-text'));
    await pg.click('#modal-ok'); await pg.waitForTimeout(250);
    console.log('toast', await pg.textContent('#toast'), 'deleted', await pg.evaluate(() => Android._sec.rotated));
    // ruimte
    await pg.click('#bk-space-btn'); await pg.waitForTimeout(400);
    console.log('space', await pg.$$eval('#bk-space .spc', x => x.map(e => e.innerText.replace(/\n/g, ' '))));
    // archief openen: vraagt wachtwoord, fout, goed
    await pg.click('button:has-text("Versleutelde backup openen")'); await pg.waitForTimeout(400);
    console.log('pw ask', await pg.textContent('#modal-title'), await pg.getAttribute('#modal-input', 'type'));
    await pg.fill('#modal-input', 'fout'); await pg.click('#modal-ok'); await pg.waitForTimeout(300);
    console.log('toast', await pg.textContent('#toast')); await pg.waitForTimeout(500);
    console.log('asks again', await pg.textContent('#modal-title'));
    await pg.fill('#modal-input', 'goedwachtwoord'); await pg.click('#modal-ok'); await pg.waitForTimeout(400);
    console.log('sheet', await pg.textContent('#sheet-title'), '|', await pg.textContent('#sheet-sub'), await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent)));
    await pg.screenshot({ path: __dirname + '/shots/sec-sheet-' + scheme + '.png' });
    await pg.click('#sheet-acts button:has-text("Contacten terugzetten")'); await pg.waitForTimeout(300);
    console.log('restore', await pg.evaluate(() => Android._arcRestore), '|', await pg.textContent('#modal-title'));
    await pg.click('#modal-cancel'); await pg.waitForTimeout(100);
    await pg.screenshot({ path: __dirname + '/shots/sec-' + scheme + '.png', fullPage: true });
    // uitzetten
    await pg.click('#bk-enc-off'); await pg.waitForTimeout(100); await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    console.log('off', await pg.textContent('#bk-enc-state'));
    // gewone invoer daarna weer tekst
    await pg.evaluate(() => askInput('x', '', 'a', 'OK', () => {})); console.log('plain input type', await pg.getAttribute('#modal-input', 'type'));
    await pg.click('#modal-cancel');
    console.log('errors', errs);
  }
  // ontsleutelen.html met een echt archief uit de JVM-test (indien aanwezig)
  const fs = require('fs'), path = require('path'), os = require('os');
  const rtb = path.join(os.tmpdir(), 'rt-test.rtb');
  if (fs.existsSync(rtb)) {
    const pg = await b.newPage({ acceptDownloads: true }); const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + path.join(__dirname, '..', '..', 'web', 'ontsleutelen.html'));
    await pg.setInputFiles('#f', rtb); await pg.fill('#pw', 'fout'); await pg.click('#go'); await pg.waitForTimeout(800);
    console.log('decrypt wrong', await pg.textContent('#msg'));
    await pg.fill('#pw', 'geheim-wachtwoord');
    const [dl] = await Promise.all([pg.waitForEvent('download'), pg.click('#go')]);
    const out = await dl.path(); console.log('decrypt ok', dl.suggestedFilename(), JSON.stringify(fs.readFileSync(out, 'utf8')), await pg.textContent('#msg'));
    console.log('errors', errs);
  } else console.log('decrypt skipped (run LogicTest first)', 'errors', []);
  await b.close();
})();
