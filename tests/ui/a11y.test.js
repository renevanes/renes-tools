const { chromium } = require('playwright');
const assert = require('node:assert/strict');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    // geen bedieningselement zonder naam
    const unnamed = await pg.evaluate(() => [...document.querySelectorAll('button, input, select, textarea')].filter(e => {
      const name = (e.getAttribute('aria-label') || e.getAttribute('aria-labelledby') || e.textContent || e.placeholder || '').trim();
      const lab = (e.id && document.querySelector('label[for="' + e.id + '"]')) || e.closest('label');
      return e.tagName === 'BUTTON' ? !name : !(name || lab);
    }).map(e => e.id || e.className));
    console.log('unnamed controls', unnamed);
    assert.deepEqual(unnamed, [], 'Elk bedieningselement heeft een naam');
    // schakelaars: role=switch met stand
    await pg.evaluate(() => show('redial')); await pg.waitForTimeout(150);
    console.log('focus on heading', await pg.evaluate(() => document.activeElement.tagName + ':' + document.activeElement.textContent));
    console.log('switch', await pg.getAttribute('#t-spk', 'role'), await pg.getAttribute('#t-spk', 'aria-checked'), await pg.getAttribute('#t-spk', 'aria-label'));
    assert.equal(await pg.getAttribute('#t-spk', 'role'), 'switch');
    const before = await pg.getAttribute('#t-spk', 'aria-checked');
    assert.match(before, /^(true|false)$/);
    assert.ok(await pg.getAttribute('#t-spk', 'aria-label'));
    await pg.click('#t-spk'); await pg.waitForTimeout(50);
    console.log('switch after tap', await pg.getAttribute('#t-spk', 'aria-checked'));
    assert.equal(await pg.getAttribute('#t-spk', 'aria-checked'), before === 'true' ? 'false' : 'true');
    console.log('chip pressed', await pg.$$eval('#c-att button', x => x.map(e => e.getAttribute('aria-pressed')).join(',')));
    console.log('toast role', await pg.getAttribute('#toast', 'role'));
    assert.equal(await pg.getAttribute('#toast', 'role'), 'status');
    assert.equal(await pg.locator('#c-att button[aria-pressed="true"]').count(), 1);
    // tekstgrootte
    await pg.evaluate(() => show('setlook')); await pg.waitForTimeout(150);
    await pg.selectOption('#st-zoom', '150'); await pg.waitForTimeout(100);
    console.log('zoom set', await pg.evaluate(() => Android._zoom), await pg.textContent('#toast'));
    assert.equal(await pg.evaluate(() => Android._zoom), 150, 'De gewenste tekstgrootte gaat naar de brug');
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(200);
    const overflow = await pg.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
    assert.equal(overflow, false, 'Geen horizontale overloop in het browservenster');
    await pg.screenshot({ path: __dirname + '/shots/zoom150-' + scheme + '.png' });
    await pg.evaluate(() => { Android.textZoomSet(0); });
    // radio terugspoelen
    await pg.evaluate(() => show('radio')); await pg.waitForTimeout(300);
    await pg.evaluate(() => rdPlay('s', 0)); await pg.waitForTimeout(900); await pg.evaluate(() => rdPollOnce());
    await pg.click('#plbar-open'); await pg.waitForTimeout(400);
    console.log('shift visible', await pg.isVisible('#pl-seekwrap'), 'pos', await pg.textContent('#pl-left'), 'fwd disabled', await pg.isDisabled('#pl-fwd'));
    assert.equal(await pg.isVisible('#pl-seekwrap'), true);
    assert.equal(await pg.isDisabled('#pl-fwd'), true);
    await pg.click('#pl-back'); await pg.waitForTimeout(400);
    console.log('after rew', await pg.textContent('#pl-left'), 'fwd enabled', !(await pg.isDisabled('#pl-fwd')));
    assert.equal(await pg.isDisabled('#pl-fwd'), false);
    await pg.screenshot({ path: __dirname + '/shots/rdshift-' + scheme + '.png' });
    await pg.click('#pl-golive'); await pg.waitForTimeout(400);
    console.log('after live', await pg.textContent('#pl-left'));
    assert.equal(await pg.textContent('#pl-left'), 'Live');
    await pg.uncheck('#rd-ts'); console.log('ts off', await pg.evaluate(() => Android._ts));
    assert.equal(await pg.evaluate(() => Android._ts), false);
    assert.deepEqual(errs, []);
    console.log('errors', errs);
  }
  await b.close();
})();
