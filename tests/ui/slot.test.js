const { chromium } = require('playwright');
// App-code op het slotscherm, de neutrale versie bij een verkeerde code, en de instellingen daarvoor.
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    // Instellen via Instellingen
    await pg.evaluate(() => { Android._st.lock = true; show('settings'); }); await pg.waitForTimeout(300);
    console.log('code state', await pg.textContent('#st-code-state'), 'more hidden', !(await pg.isVisible('#st-code-more')));
    await pg.click('#st-code-btn'); await pg.waitForTimeout(150);
    console.log('input type', await pg.getAttribute('#modal-input', 'type'), 'inputmode', await pg.evaluate(() => $('#modal-input').inputMode));
    await pg.fill('#modal-input', '12'); await pg.click('#modal-ok'); await pg.waitForTimeout(250);
    console.log('too short toast', await pg.textContent('#toast'));
    await pg.click('#st-code-btn'); await pg.waitForTimeout(150);
    await pg.fill('#modal-input', '2468'); await pg.click('#modal-ok'); await pg.waitForTimeout(300);
    await pg.fill('#modal-input', '2468'); await pg.click('#modal-ok'); await pg.waitForTimeout(300);
    console.log('code set', await pg.evaluate(() => Android._st.code), 'state', await pg.textContent('#st-code-state'), 'more', await pg.isVisible('#st-code-more'));
    await pg.check('#st-decoy'); await pg.waitForTimeout(100);
    console.log('decoy', await pg.evaluate(() => Android._st.decoy), 'bio default off', !(await pg.isChecked('#st-codebio')));
    await pg.check('#st-codebio'); await pg.waitForTimeout(100);
    await pg.screenshot({ path: __dirname + '/shots/slot-settings-' + scheme + '.png', fullPage: true });

    // Slotscherm met cijfers
    await pg.evaluate(() => { Android._st.locked = true; onLock(); }); await pg.waitForTimeout(300);
    console.log('pad', await pg.isVisible('#lock-pad'), 'old button hidden', !(await pg.isVisible('#lock-btn')), 'bio', await pg.isVisible('#lock-bio'), 'keys', await pg.$$eval('#lock-keys button', x => x.length));
    await pg.screenshot({ path: __dirname + '/shots/slot-pad-' + scheme + '.png' });
    // Verkeerd met neutrale versie aan: geen melding, de app gaat naar de neutrale versie
    for (const k of ['1', '1', '1', '1']) await pg.click('#lock-keys [data-k="' + k + '"]');
    console.log('dots', await pg.textContent('#lock-dots'));
    await pg.click('#lock-keys [data-k="✓"]'); await pg.waitForTimeout(200);
    console.log('neutral', await pg.evaluate(() => Android._st.neutral), 'msg', JSON.stringify(await pg.textContent('#lock-msg')), 'still locked', await pg.isVisible('#lockscr'));
    // Zonder neutrale versie: melding
    await pg.evaluate(() => { Android._st.decoy = false; });
    await pg.keyboard.type('9999'); await pg.keyboard.press('Enter'); await pg.waitForTimeout(200);
    console.log('wrong msg', await pg.textContent('#lock-msg'));
    // ⌫ en de juiste code
    await pg.keyboard.type('24689'); await pg.click('#lock-keys [aria-label="Wissen"]'); await pg.click('#lock-keys [aria-label="Ontgrendelen"]'); await pg.waitForTimeout(250);
    console.log('unlocked', !(await pg.isVisible('#lockscr')), 'tries', await pg.evaluate(() => JSON.stringify(Android._st.tries)));
    // Vingerafdruk-knop vraagt de app
    await pg.evaluate(() => { onLock(); }); await pg.waitForTimeout(200);
    await pg.click('#lock-bio'); console.log('bio asked', await pg.evaluate(() => Android._st.bioAsked));
    await pg.evaluate(() => onUnlocked());
    console.log('errors', errs);
    await pg.close();

    // De neutrale versie zelf (eigen pagina, geen brug)
    const np = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const nerrs = []; np.on('pageerror', e => nerrs.push(e.message));
    await np.goto('file://' + require('path').join(__dirname, '..', '..', 'web', 'neutraal.html')); await np.waitForTimeout(200);
    console.log('neutral tiles', await np.$$eval('.tile b', x => x.map(e => e.textContent)), 'no Android', await np.evaluate(() => typeof Android));
    await np.screenshot({ path: __dirname + '/shots/neutraal-home-' + scheme + '.png' });
    await np.click('.tile'); await np.waitForTimeout(150);
    await np.fill('#val', '10'); await np.waitForTimeout(50);
    console.log('10 m → ft', await np.textContent('#res'));
    await np.click('[data-c="Temperatuur"]'); await np.fill('#val', '100'); await np.waitForTimeout(50);
    console.log('100 C → F', await np.textContent('#res'));
    await np.click('.swap'); console.log('swap', await np.textContent('#res'));
    await np.click('[data-c="Gewicht"]'); await np.fill('#val', '1,5'); console.log('1,5', await np.textContent('#formula'));
    await np.fill('#val', 'abc'); console.log('bad', await np.textContent('#res'));
    await np.screenshot({ path: __dirname + '/shots/neutraal-conv-' + scheme + '.png' });
    console.log('back', await np.evaluate(() => goBack()), 'again', await np.evaluate(() => goBack()));
    console.log('errors', nerrs);
    await np.close();
  }
  await b.close();
})();
