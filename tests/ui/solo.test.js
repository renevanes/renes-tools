// Losse apps: instellen in de gewone app, en een tool als losse app (geen slot, geen links naar andere tools).
const { chromium } = require('playwright');
const register = require('../../tools/tool-register');
const toolIds = register.soloTools(register.readTools());
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const url = 'file://' + require('path').join(__dirname, 'ui', 'index.html');
  for (const scheme of ['light', 'dark']) {
    // 1. Gewone app: instellen en vastzetten
    let pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(url); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => !SOLO && document.body.classList.contains('tabs-on')), 'gewone app: tabbalk, geen losse app');
    await pg.evaluate(() => show('setsolo')); await pg.waitForTimeout(150);
    ok((await pg.$$('#st-solo-list .sw')).length === toolIds.length, 'instellingen: alle tools in de lijst');
    await pg.click('#st-solo-list .tg[aria-label="Notities als losse app"]'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#modal-text')).includes('algemene app-slot') && (await pg.textContent('#modal-text')).includes('eigen ontgrendeling'), 'uitleg over het app-slot en de eigen ontgrendeling bij aanzetten');
    await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    ok((await pg.evaluate(() => Android._solo)).join() === 'notes', 'notities mag als losse app');
    await pg.click('#st-solo-list button:has-text("Op startscherm zetten")');
    ok((await pg.evaluate(() => Android._soloPinned || [])).join('|').startsWith('notes|Notities|'), 'losse app vastgezet');
    await pg.screenshot({ path: __dirname + '/shots/solo-settings-' + scheme + '.png' });
    // via het tegelmenu: een tool die nog uit staat
    await pg.evaluate(() => { show('home'); toolMenu('calls'); }); await pg.waitForTimeout(100);
    const acts = await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent));
    ok(acts.includes('Als losse app op startscherm…') && acts.includes('Openen'), 'tegelmenu: losse app en gewoon openen');
    await pg.click('#sheet-acts button:has-text("Als losse app")'); await pg.waitForTimeout(100);
    await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    ok((await pg.evaluate(() => Android._solo)).includes('calls') && (await pg.evaluate(() => Android._soloPinned[0])) === 'calls', 'tegelmenu zet aan en vast');
    await pg.evaluate(() => show('setsolo')); await pg.waitForTimeout(100);
    await pg.click('#st-solo-list .tg[aria-label="Notities als losse app"]'); await pg.waitForTimeout(100);
    ok(!(await pg.evaluate(() => Android._solo)).includes('notes'), 'uitzetten zonder vragen');
    await pg.close();

    // 2. Losse app "Oproepen": alleen die tool
    pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.addInitScript(() => { window.__testSolo = 'calls'; });
    await pg.goto(url); await pg.waitForTimeout(400);
    const st = await pg.evaluate(() => ({ cur: current, tabs: getComputedStyle(document.querySelector('#tabs')).display, lock: document.querySelector('#lockscr').classList.contains('on'),
      back: getComputedStyle(document.querySelector('#s-calls .back')).display }));
    ok(st.cur === 'calls' && st.tabs === 'none' && !st.lock && st.back === 'none', 'losse app: eigen tool, geen tabbalk, geen slot, geen terugknop: ' + JSON.stringify(st));
    await pg.evaluate(() => { show('home'); show('settings'); jumpTo('contacts'); openTool('notes'); }); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => current) === 'calls', 'andere tools niet te openen');
    await pg.click('#calls-perm-btn').catch(() => {}); await pg.waitForTimeout(400);
    const idx = await pg.evaluate(() => callsShown.findIndex(c => c.ck && c.number));
    await pg.click('#calls-list button >> nth=' + idx); await pg.waitForTimeout(150);
    const sacts = await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent));
    ok(!sacts.some(a => /Contact bekijken|Auto redial/.test(a)) && sacts.some(a => /Alle oproepen met/.test(a)), 'menu zonder links naar andere tools: ' + sacts.join(' | '));
    await pg.screenshot({ path: __dirname + '/shots/solo-calls-' + scheme + '.png' });
    ok(await pg.evaluate(() => goBack()) === true && await pg.evaluate(() => current) === 'calls', 'terug sluit eerst het menu');
    ok(await pg.evaluate(() => goBack()) === false, 'terug op het eerste scherm sluit de losse app');
    const vis = await pg.evaluate(() => [...document.querySelectorAll('[onclick]')].filter(e => e.offsetParent && !soloSrcOk(e.getAttribute('onclick'))).map(e => e.getAttribute('onclick')));
    ok(!vis.length, 'geen zichtbare knoppen naar andere tools: ' + vis.join(' ; '));
    await pg.close();

    // 3. Losse app "Notities": eigen subschermen werken wel
    pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.addInitScript(() => { window.__testSolo = 'notes'; });
    await pg.goto(url); await pg.waitForTimeout(400);
    await pg.evaluate(() => { const n = notesGet(); n.push({ id: 'n9', title: 'Boodschappen', text: '', items: [], updated: Date.now() }); openNote('n9'); }); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current) === 'note', 'notitie openen in de losse app');
    const backVis = await pg.evaluate(() => getComputedStyle(document.querySelector('#s-note .back')).display);
    ok(backVis !== 'none', 'terugknop op het tweede scherm');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => current) === 'notes', 'terug naar de lijst');
    await pg.screenshot({ path: __dirname + '/shots/solo-notes-' + scheme + '.png' });
    await pg.close();

    // 4. Losse app "Meldingsgeschiedenis": exporteren en wissen in het menu ⋯ blijven, backup-map kiezen (Instellingen) niet
    pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.addInitScript(() => { window.__testSolo = 'history'; });
    await pg.goto(url); await pg.waitForTimeout(400);
    ok(await pg.evaluate(() => current) === 'history', 'losse app Meldingsgeschiedenis opent de geschiedenis');
    await pg.click('#history-menu'); await pg.waitForTimeout(100);
    const hacts = await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent));
    ok(hacts.includes('Geschiedenis exporteren') && hacts.includes('Geschiedenis op deze telefoon wissen') && !hacts.includes('Backup-map kiezen'), 'menu ⋯ in de losse app: exporteren en wissen, geen backup-map kiezen (' + hacts.join(' | ') + ')');
    await pg.click('#sheet-acts button:has-text("Geschiedenis exporteren")'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => current) === 'history' && /backup-map/.test(await pg.textContent('#toast')), 'exporteren zonder backup-map: melding, en de losse app blijft bij de geschiedenis');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
