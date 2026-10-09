// Beveiliging 1.57: CSP, ontsnapte waarden uit backups, onversleutelde exports, sleutel vergeten, music-now op slot.
const { chromium } = require('playwright');
const assert = require('node:assert');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);

    // CSP: geen verbindingen naar buiten
    const csp = await pg.evaluate(() => document.querySelector('meta[http-equiv="Content-Security-Policy"]').content);
    assert.match(csp, /connect-src 'none'/); assert.match(csp, /frame-src 'none'/);
    const blocked = await pg.evaluate(() => new Promise(res => {
      document.addEventListener('securitypolicyviolation', e => res(e.violatedDirective), { once: true });
      fetch('https://example.com/x').catch(() => {});
      setTimeout(() => res('niet geblokkeerd'), 1500);
    }));
    assert.match(blocked, /connect-src/, 'fetch naar buiten geblokkeerd');

    // Muziek: een vreemd tijdstip uit een backup wordt een getal
    await pg.evaluate(() => { Android._mu.hist = [{ t: '1" autofocus onfocus="window.__x=1', title: 'A', artist: 'B' }]; show('music'); });
    await pg.waitForTimeout(250);
    assert.strictEqual(await pg.evaluate(() => window.__x), undefined);
    assert.strictEqual(await pg.$$eval('#mu-hist [onfocus]', x => x.length), 0, 'geen onfocus uit backup');
    await pg.evaluate(() => goBack());

    // Onversleutelde export: met versleutelen aan eerst vragen
    await pg.evaluate(() => { Android._sec.on = true; Android._wa.info.dest = true; Android._sms.perm = true; window.__sms = 0; const o = Android.smsExport; Android.smsExport = function(){ window.__sms++; return o.call(this); }; });
    await pg.evaluate(() => smsExport()); await pg.waitForTimeout(150);
    assert.strictEqual(await pg.evaluate(() => window.__sms), 0, 'nog niet geëxporteerd');
    assert.match(await pg.textContent('#modal-title'), /Onversleuteld opslaan\?/);
    await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    assert.strictEqual(await pg.evaluate(() => window.__sms), 1, 'na bevestigen wel');
    await pg.evaluate(() => { Android._sec.on = false; smsExport(); }); await pg.waitForTimeout(100);
    assert.strictEqual(await pg.evaluate(() => window.__sms), 2, 'zonder versleutelen meteen');

    // Versleutelde backup: menu dicht zonder keuze → sleutel vergeten
    await pg.evaluate(() => { Android._arcClosed = false; onArchive({ name: 'x.rtb', files: [], bytes: 1, notes: 'n' }); }); await pg.waitForTimeout(100);
    await pg.evaluate(() => closeSheet()); await pg.waitForTimeout(50);
    assert.strictEqual(await pg.evaluate(() => Android._arcClosed), true, 'archiveClose bij wegtikken');
    await pg.evaluate(() => { Android._arcClosed = false; onArchive({ name: 'x.rtb', files: [], bytes: 1, notes: 'n' }); }); await pg.waitForTimeout(100);
    await pg.evaluate(() => { const o = Android.archiveRestore; Android.archiveRestore = function(){ window.__restore = Android._arcClosed; }; });
    await pg.click('#sheet-acts >> text=Notities terugzetten'); await pg.waitForTimeout(50);
    assert.strictEqual(await pg.evaluate(() => window.__restore), false, 'bij een keuze niet eerst vergeten');
    // uitpakken annuleren → vergeten
    await pg.evaluate(() => { Android._arcClosed = false; onArchive({ name: 'x.rtb', files: [], bytes: 1 }); }); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts >> text=Uitpakken naar de backup-map'); await pg.waitForTimeout(100);
    await pg.click('#modal-cancel'); await pg.waitForTimeout(50);
    assert.strictEqual(await pg.evaluate(() => Android._arcClosed), true, 'uitpakken geannuleerd → vergeten');

    // music-now terwijl de app op slot is: niet opnemen
    await pg.evaluate(() => { Android._mu.token = 'x'; Android._mu.mic = true; $('#lockscr').classList.add('on'); openTool('music-now'); });
    await pg.waitForTimeout(600);
    assert.strictEqual(await pg.evaluate(() => Android._mu.st.state), 'idle', 'op slot geen opname');
    await pg.evaluate(() => { $('#lockscr').classList.remove('on'); });

    assert.deepStrictEqual(errs, []);
    await pg.close();
  }
  console.log('errors []');
  await b.close();
})().catch(e => { console.error(e); console.log('errors [' + e.message + ']'); process.exit(1); });
