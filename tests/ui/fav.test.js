// Favorieten bovenaan het startscherm + snelkoppelingen voor podcast en "Verder luisteren"
const { chromium } = require('playwright');
const assert = (ok, what) => { if (!ok) throw new Error('mislukt: ' + what); console.log('✓ ' + what); };
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const groups = () => pg.$$eval('#home-groups .hgroup', gs => gs.map(g => ((g.querySelector('h3') || {}).textContent || '') + ':' + [...g.querySelectorAll('.tiles > *')].map(t => t.id).join(',')));
    // Lang indrukken (rechtsklik) → Favoriet
    await pg.click('#tile-notes', { button: 'right' }); await pg.waitForTimeout(150);
    await pg.click('#sheet-acts button:has-text("Favoriet (bovenaan)")'); await pg.waitForTimeout(150);
    await pg.click('#tile-podcasts', { button: 'right' }); await pg.waitForTimeout(150);
    await pg.click('#sheet-acts button:has-text("Favoriet (bovenaan)")'); await pg.waitForTimeout(150);
    let g = await groups();
    assert(g[0] === '★ Favorieten:tile-notes,tile-podcasts', 'favorieten bovenaan in hun eigen groep: ' + g[0]);
    assert(!g.slice(1).some(x => x.includes('tile-notes') || x.includes('tile-podcasts')), 'niet nog eens in hun gewone groep');
    const dyn = JSON.parse(await pg.evaluate(() => Android._dyn)).map(x => x[0]);
    assert(dyn.join() === 'notes,podcasts,player,radio', 'app-icoon-keuzes beginnen met de favorieten: ' + dyn);
    // Startscherm aanpassen: volgorde van favorieten en ster
    await pg.evaluate(() => show('homeedit')); await pg.waitForTimeout(200);
    assert((await pg.$$eval('#he-fav .herow .nm', x => x.map(e => e.textContent))).join() === 'Notities,Podcasts', 'favorietenkaart');
    await pg.click('#he-fav .herow:nth-of-type(2) button.more'); await pg.click('#sheet-acts button:has-text("Omhoog")'); await pg.waitForTimeout(150);
    g = await groups();
    assert(g[0] === '★ Favorieten:tile-podcasts,tile-notes', 'volgorde omgedraaid: ' + g[0]);
    await pg.screenshot({ path: __dirname + '/shots/fav-edit-' + scheme + '.png' });
    // Zonder groepen: favorieten eerst in de ene lijst
    await pg.evaluate(() => heSet('grouped', false)); await pg.waitForTimeout(100);
    g = await groups();
    assert(g.length === 1 && g[0].startsWith(':tile-podcasts,tile-notes,'), 'zonder groepen: favorieten eerst');
    await pg.evaluate(() => heSet('grouped', true));
    // Ster uit in de lijst
    await pg.click('#he-groups button.star.on[data-t="notes"]'); await pg.waitForTimeout(150);
    g = await groups();
    assert(g[0] === '★ Favorieten:tile-podcasts', 'notities uit favorieten: ' + g[0]);
    // Verborgen tool favoriet maken = weer zichtbaar
    await pg.evaluate(() => { heToggle('pdf', false); toggleFav('pdf'); });
    g = await groups();
    assert(g[0] === '★ Favorieten:tile-podcasts,tile-pdf', 'verborgen tool als favoriet is weer zichtbaar: ' + g[0]);
    // Verbergen = geen favoriet meer; standaard herstellen houdt de favorieten
    await pg.evaluate(() => heToggle('pdf', false));
    g = await groups();
    assert(g[0] === '★ Favorieten:tile-podcasts' && !(await pg.evaluate(() => isFav('pdf'))), 'verborgen tool is geen favoriet meer');
    await pg.evaluate(() => heReset()); await pg.waitForTimeout(150);
    await pg.click('#modal-ok'); await pg.waitForTimeout(200);
    g = await groups();
    assert(g[0] === '★ Favorieten:tile-podcasts' && !(await pg.evaluate(() => homeCfg().hidden.length)), 'standaard herstellen houdt de favorieten: ' + g[0]);
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    await pg.screenshot({ path: __dirname + '/shots/fav-home-' + scheme + '.png' });
    // Podcast op startscherm
    await pg.evaluate(() => pdOpen({ feed: 'https://example.com/feed.xml', title: 'Test Pod', id: '1' })); await pg.waitForTimeout(300);
    await pg.click('#pd-menu'); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Op startscherm")'); await pg.waitForTimeout(100);
    assert((await pg.evaluate(() => Android._pinned)) === 'pod|https://example.com/feed.xml', 'podcast-snelkoppeling gevraagd');
    const tt = await pg.textContent('#toast'); assert(tt.startsWith('Bevestig op je startscherm om ') && tt.includes(await pg.evaluate(() => pdPod.title)), 'toast noemt de podcast: ' + tt);
    // Verder luisteren vanuit het menu van de speler (radio)
    await pg.evaluate(() => goBack()); await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    await pg.click('#tile-radio'); await pg.waitForTimeout(300);
    await pg.click('#rd-list .srow:nth-child(1) .sbtn'); await pg.waitForTimeout(900);
    await pg.click('#plbar-open'); await pg.waitForTimeout(400);
    await pg.click('#player [aria-label="Meer opties"]'); await pg.waitForTimeout(150);
    await pg.click('#sheet-acts button:has-text("Verder luisteren op startscherm")'); await pg.waitForTimeout(100);
    assert((await pg.evaluate(() => Android._pinned)) === 'player', 'Verder luisteren-snelkoppeling gevraagd');
    console.log('errors', errs);
    await pg.close();
  }
  await b.close();
})().catch(e => { console.log(e.message); process.exit(1); });
