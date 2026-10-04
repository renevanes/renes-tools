const { chromium } = require('playwright');
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
    // schakelaars: role=switch met stand
    await pg.evaluate(() => show('redial')); await pg.waitForTimeout(150);
    console.log('focus on heading', await pg.evaluate(() => document.activeElement.tagName + ':' + document.activeElement.textContent));
    console.log('switch', await pg.getAttribute('#t-spk', 'role'), await pg.getAttribute('#t-spk', 'aria-checked'), await pg.getAttribute('#t-spk', 'aria-label'));
    await pg.click('#t-spk'); await pg.waitForTimeout(50);
    console.log('switch after tap', await pg.getAttribute('#t-spk', 'aria-checked'));
    console.log('chip pressed', await pg.$$eval('#c-att button', x => x.map(e => e.getAttribute('aria-pressed')).join(',')));
    console.log('toast role', await pg.getAttribute('#toast', 'role'));
    // tekstgrootte
    await pg.evaluate(() => show('settings')); await pg.waitForTimeout(150);
    await pg.selectOption('#st-zoom', '150'); await pg.waitForTimeout(100);
    console.log('zoom set', await pg.evaluate(() => Android._zoom), await pg.textContent('#toast'));
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(200);
    const overflow = await pg.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
    console.log('no horizontal overflow at 150%', !overflow);
    await pg.screenshot({ path: __dirname + '/shots/zoom150-' + scheme + '.png' });
    await pg.evaluate(() => { Android.textZoomSet(0); });
    // radio terugspoelen
    await pg.evaluate(() => show('radio')); await pg.waitForTimeout(300);
    await pg.click('#rd-list .st >> nth=0').catch(() => {}); await pg.evaluate(() => rdPlay('s', 0)); await pg.waitForTimeout(900); await pg.evaluate(() => rdPollOnce());
    console.log('shift visible', await pg.isVisible('#rd-shift'), 'pos', await pg.textContent('#rd-shpos'), 'live disabled', await pg.isDisabled('#rd-live'));
    await pg.click('#rd-shift button:has-text("30 s") >> nth=0'); await pg.waitForTimeout(400);
    console.log('after rew', await pg.textContent('#rd-shpos'), 'live enabled', !(await pg.isDisabled('#rd-live')));
    await pg.screenshot({ path: __dirname + '/shots/rdshift-' + scheme + '.png' });
    await pg.click('#rd-live'); await pg.waitForTimeout(400);
    console.log('after live', await pg.textContent('#rd-shpos'));
    await pg.uncheck('#rd-ts'); console.log('ts off', await pg.evaluate(() => Android._ts));
    console.log('errors', errs);
  }
  await b.close();
})();
