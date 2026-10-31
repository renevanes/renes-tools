// Minispeler sluiten: ✕ op de balk of omlaag vegen, op elk scherm. Podcast: plek bewaard en Ongedaan maken speelt verder;
// radio: stopt, en Ongedaan maken zet dezelfde zender weer aan.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const t = scheme + ': ';
    // Radio starten en naar een ander scherm
    await pg.click('#tile-radio'); await pg.waitForTimeout(400);
    await pg.click('#rd-list .srow:nth-child(2) .sbtn'); await pg.waitForTimeout(1300);
    const name = await pg.evaluate(() => (Android._rd.st.station || {}).name);
    await pg.evaluate(() => show('notes')); await pg.waitForTimeout(300);
    ok(await pg.isVisible('#plbar') && await pg.isVisible('#plbar-x'), t + 'minispeler met ✕ op een ander scherm');
    ok((await pg.getAttribute('#plbar-x', 'aria-label')) === 'Speler sluiten', t + '✕ heeft een naam');
    const box = await pg.$eval('#plbar-x', e => { const r = e.getBoundingClientRect(); return [r.width, r.height]; });
    ok(box[0] >= 44 && box[1] >= 44, t + '✕ is groot genoeg (' + box + ')');
    await pg.click('#plbar-x'); await pg.waitForTimeout(250);
    ok(!(await pg.isVisible('#plbar')) && await pg.evaluate(() => Android._rd.st.status === 'stopped' && current === 'notes'), t + 'radio gestopt, balk weg, je blijft op het scherm');
    ok(await pg.isVisible('#undo') && (await pg.textContent('#undo-t')).includes('Speler gesloten'), t + 'met Ongedaan maken');
    await pg.click('#undo-b'); await pg.waitForTimeout(900);
    ok(await pg.evaluate(n => Android._rd.st.status !== 'stopped' && (Android._rd.st.station || {}).name === n, name) && await pg.isVisible('#plbar'), t + 'ongedaan: dezelfde zender speelt weer');
    // Omlaag vegen op de balk sluit ook
    const bb = await pg.$eval('#plbar-t', e => { const r = e.getBoundingClientRect(); return [r.x + r.width / 2, r.y + r.height / 2]; });
    await pg.mouse.move(bb[0], bb[1]); await pg.mouse.down(); await pg.mouse.move(bb[0], bb[1] + 30, { steps: 3 }); await pg.mouse.move(bb[0], bb[1] + 70, { steps: 3 }); await pg.mouse.up(); await pg.waitForTimeout(250);
    ok(!(await pg.isVisible('#plbar')) && await pg.evaluate(() => Android._rd.st.status === 'stopped'), t + 'omlaag vegen sluit de speler');
    ok(await pg.evaluate(() => current === 'notes' && !document.querySelector('#player.on, #player[aria-hidden="false"]')), t + 'vegen opent de grote speler niet');
    // Podcast: plek bewaard, ongedaan speelt verder
    await pg.evaluate(() => { const A = Android._pc; const p = (A.top || [])[0]; A.subs.push(Object.assign({ fresh: 0 }, p)); });
    await pg.evaluate(() => { const p = Android._pc.top[0]; pdOpen(p); }); await pg.waitForTimeout(600);
    await pg.click('#pd-eps .pcep:nth-child(1) .pcepb'); await pg.waitForTimeout(500);
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(300);
    const key = await pg.evaluate(() => (Android._pc.st.ep || {}).key);
    ok(!!key && await pg.isVisible('#plbar'), t + 'podcast speelt, balk op Start');
    await pg.click('#plbar-x'); await pg.waitForTimeout(250);
    ok(!(await pg.isVisible('#plbar')) && await pg.evaluate(() => Android._pc.st.status === 'stopped'), t + 'podcast gestopt, balk weg');
    ok((await pg.textContent('#undo-t')).includes('plek is bewaard'), t + 'zegt dat je plek bewaard is');
    await pg.click('#undo-b'); await pg.waitForTimeout(600);
    ok(await pg.evaluate(k => (Android._pc.st.ep || {}).key === k && Android._pc.st.status !== 'stopped', key) && await pg.isVisible('#plbar'), t + 'ongedaan: dezelfde aflevering speelt verder');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
