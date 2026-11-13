// Auto redial: indeling (schermcheck 2026-11-13). Start staat meteen onder het nummer en is ook op een klein scherm
// (360×640) zonder scrollen te zien; eronder een samenvatting van de instellingen die meeverandert; de uitleg ingeklapt;
// namen voor TalkBack (nummer, ∞, dagknoppen, groepen). Ook in het Engels.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 360, height: 640 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => show('redial')); await pg.waitForTimeout(200);
    const start = await pg.evaluate(() => { const r = $('#rd-start').getBoundingClientRect(); return { top: r.top, bottom: r.bottom, h: innerHeight, sy: scrollY }; });
    ok(start.sy === 0 && start.bottom <= start.h, scheme + ': Start zonder scrollen in beeld op 360×640 (onderkant ' + Math.round(start.bottom) + ')');
    ok(await pg.evaluate(() => { const s = $('#rd-start'), c = $('#c-att'); return !!(s.compareDocumentPosition(c) & Node.DOCUMENT_POSITION_FOLLOWING); }), scheme + ': Start vóór de instellingen');
    const sum = () => pg.textContent('#rd-sum');
    ok(await sum() === '10 pogingen · 10 s ertussen · stopt bij opnemen', scheme + ': samenvatting: ' + await sum());
    await pg.click('#c-att button:has-text("25")'); await pg.click('#c-int button:has-text("1 min")'); await pg.waitForTimeout(50);
    ok(await sum() === '25 pogingen · 1 min ertussen · stopt bij opnemen', scheme + ': samenvatting volgt de keuzes: ' + await sum());
    await pg.click('#t-stop'); await pg.click('#c-att button[aria-label="Onbeperkt"]'); await pg.click('#c-int button:has-text("Willekeurig")'); await pg.waitForTimeout(50);
    ok(await sum() === 'Onbeperkt aantal pogingen · willekeurig 10 s tot 1 min ertussen · belt alle pogingen af', scheme + ': onbeperkt, willekeurig, niet stoppen: ' + await sum());
    await pg.selectOption('#r-max', '120'); await pg.waitForTimeout(50);
    ok((await sum()).includes('tot 2 min'), scheme + ': ander maximum in de samenvatting');
    ok(await pg.evaluate(() => store.get('attempts') === 0 && store.get('interval') === -1 && store.get('t-stop') === false), scheme + ': keuzes bewaard zoals voorheen');
    // Uitleg ingeklapt, de tekst staat er wel in
    ok(await pg.evaluate(() => { const d = $('#how-note').closest('details.help'); return !!d && !d.open && /oproepgeschiedenis/.test($('#how-note').textContent); }), scheme + ': uitleg onder Hoe werkt dit? (ingeklapt)');
    // Toegankelijkheid
    ok(await pg.getAttribute('#num', 'aria-label') === 'Telefoonnummer', scheme + ': invoerveld heeft een naam');
    ok(await pg.evaluate(() => ['c-att', 'c-int', 'rp-days'].every(id => $('#' + id).getAttribute('role') === 'group')), scheme + ': keuzes als groep');
    ok(await pg.evaluate(() => [...document.querySelectorAll('#rp-days button')].map(x => x.getAttribute('aria-label')).join() === 'maandag,dinsdag,woensdag,donderdag,vrijdag,zaterdag,zondag'), scheme + ': dagknoppen voluit');
    ok(await pg.evaluate(() => [...document.querySelectorAll('#c-att button')].some(x => x.getAttribute('aria-pressed') === 'true' && x.getAttribute('aria-label') === 'Onbeperkt')), scheme + ': ∞ heet Onbeperkt en staat aan');
    ok(await pg.evaluate(() => $('#t-stop').getAttribute('aria-checked') === 'false'), scheme + ': schakelaar meldt zijn stand');
    // Leeg naamvak en lege lijst met recente nummers nemen geen ruimte in
    ok(await pg.evaluate(() => getComputedStyle($('#pname')).display === 'none' && getComputedStyle($('#recent')).display === 'none'), scheme + ': lege naam en lege lijst verborgen');
    await pg.evaluate(() => remember('0612345678', 'Huisarts')); await pg.waitForTimeout(50);
    ok(await pg.isVisible('#recent button'), scheme + ': recent nummer wel zichtbaar');
    await pg.screenshot({ path: __dirname + '/shots/redial-indeling-' + scheme + '.png' });
    // Engels
    await pg.evaluate(() => { store.set('attempts', 10); store.set('interval', 10); store.set('t-stop', true); rgSave({ lang: 'en' }); }); await pg.waitForTimeout(150);
    await pg.evaluate(() => { show('home'); show('redial'); }); await pg.waitForTimeout(200);
    ok(await sum() === '10 attempts · 10 s apart · stops when answered', scheme + ': Engels: ' + await sum());
    await pg.close();
  }
  console.log('errors', errs);
  await b.close();
})().catch(e => { console.log(e.message); process.exit(1); });
