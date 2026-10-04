const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const pg = await ctx.newPage();
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    // existing user who last saw version code-2
    await pg.addInitScript(() => { if (!localStorage.getItem('rt.seenVersion')) { localStorage.setItem('rt.x', '1'); localStorage.setItem('rt.seenVersion', String(14)); } });
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(900);
    console.log('whatsnew', await pg.evaluate(() => document.querySelector('#modal').classList.contains('on')), await pg.textContent('#modal-title'), (await pg.textContent('#modal-text')).slice(0, 80));
    await pg.screenshot({ path: __dirname + '/shots/st-new-' + scheme + '.png' });
    await pg.click('#modal-ok');
    await pg.click('.foot button:has-text("Instellingen")'); await pg.waitForTimeout(200);
    await pg.screenshot({ path: __dirname + '/shots/st-settings-' + scheme + '.png', fullPage: true });
    console.log('perms', await pg.$$eval('#st-perms .setrow', x => x.map(e => e.innerText.replace(/\n/g, ' '))));
    await pg.click('#st-perms button[data-p="mic"]'); await pg.waitForTimeout(250);
    console.log('mic after', await pg.$$eval('#st-perms .permok', x => x.length));
    await pg.click('#st-lock-btn'); await pg.waitForTimeout(250);
    console.log('lock', await pg.textContent('#st-lock-state'), 'timeout row', await pg.isVisible('#st-lock-tw'));
    // simulate app returning after timeout
    await pg.evaluate(() => { Android._st.locked = true; onLock(); }); await pg.waitForTimeout(100);
    console.log('lockscreen on', await pg.evaluate(() => document.querySelector('#lockscr').classList.contains('on')));
    await pg.screenshot({ path: __dirname + '/shots/st-lock-' + scheme + '.png' });
    await pg.waitForTimeout(600);
    console.log('unlocked', !(await pg.evaluate(() => document.querySelector('#lockscr').classList.contains('on'))));
    // a confirm dialog after what's-new must show its cancel button again
    await pg.click('#st-crash ~ .row button:has-text("Wissen")'); await pg.waitForTimeout(100);
    console.log('cancel visible', await pg.isVisible('#modal-cancel')); await pg.click('#modal-cancel');
    await pg.evaluate(() => setTimeout(() => { nonexistentFn(); }, 0)); await pg.waitForTimeout(100);
    console.log('js error logged', await pg.evaluate(() => Android._jsErr));
    console.log('errors', errs.filter(e => !e.includes('nonexistentFn')));
    await ctx.close();
  }
  await b.close();
})();
