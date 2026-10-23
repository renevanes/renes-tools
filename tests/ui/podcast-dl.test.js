// Podcasts downloaden voor offline: per aflevering, de lijst Gedownload, instellingen en vanzelf downloaden.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => show('podcasts')); await pg.waitForTimeout(300);
    ok(!(await pg.isVisible('#pc-dl-wrap')), 'nog geen downloads: geen lijst');
    // Podcast openen, aflevering downloaden via het menu
    await pg.evaluate(() => pdOpen(Android._pc.top[0])); await pg.waitForTimeout(400);
    ok(!(await pg.isVisible('#pd-dl-row')), 'niet gevolgd: geen "vanzelf downloaden"');
    await pg.click('#pd-eps .pcep[data-i="1"] .pcmore'); await pg.waitForTimeout(100);
    ok((await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent))).includes('↓ Downloaden voor offline'), 'menu: downloaden');
    await pg.click('#sheet-acts button:has-text("Downloaden voor offline")'); await pg.waitForTimeout(500);
    ok(/↓ \d+%|↓ Bezig/.test(await pg.textContent('#pd-eps .pcep[data-i="1"] small')), 'voortgang in de rij');
    await pg.waitForTimeout(2600);
    ok((await pg.textContent('#pd-eps .pcep[data-i="1"] small')).includes('✓ Offline'), 'klaar: offline');
    await pg.click('#pd-eps .pcep[data-i="1"] .pcmore'); await pg.waitForTimeout(100);
    ok((await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent))).includes('Download verwijderen'), 'menu: download verwijderen');
    await pg.evaluate(() => closeSheet());
    // Volgen en vanzelf downloaden
    await pg.click('#pd-sub'); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#pd-dl-row'), 'gevolgd: "nieuwe afleveringen downloaden" zichtbaar');
    await pg.click('#pd-dl'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._pc.subs[0].dl === true) && (await pg.getAttribute('#pd-dl', 'aria-checked')) === 'true', 'vanzelf downloaden aan');
    await pg.screenshot({ path: __dirname + '/shots/podcast-dl-' + scheme + '.png' });
    // Hoofdscherm: lijst Gedownload
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(300);
    ok(await pg.isVisible('#pc-dl-wrap') && (await pg.textContent('#pc-dl-sum')).includes('1 aflevering'), 'lijst Gedownload met totaal');
    await pg.click('#pc-dl .pcepb'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => Android._pc.st.ep && Android._pc.st.ep.key === Android._dl.list[0].key), 'afspelen vanuit Gedownload');
    await pg.screenshot({ path: __dirname + '/shots/podcast-dl-list-' + scheme + '.png' });
    // Instellingen
    await pg.click('#pc-dl-wrap button:has-text("Instellingen")'); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Alleen via wifi")');
    ok(await pg.evaluate(() => Android._dl.prefs.wifiOnly === false), 'alleen via wifi uit te zetten');
    await pg.click('#pc-dl-wrap button:has-text("Instellingen")'); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Alle downloads verwijderen")'); await pg.waitForTimeout(100);
    await pg.click('#modal-ok'); await pg.waitForTimeout(200);
    ok(!(await pg.isVisible('#pc-dl-wrap')) && await pg.evaluate(() => Android._dl.list.length === 0), 'alles verwijderd');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
