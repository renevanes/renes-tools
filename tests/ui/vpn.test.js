// VPN: tunnel toevoegen (bestand), bewerken, aan/uit met toestemming, vanzelf-instellingen, tegel, snelkoppeling, Automatiseren
const { chromium } = require('playwright');
const assert = (ok, what) => { if (!ok) throw new Error('mislukt: ' + what); console.log('✓ ' + what); };
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const scr = () => pg.evaluate(() => document.querySelector('.screen.on').id);
    const vis = sel => pg.evaluate(s => { const e = document.querySelector(s); return !!e && getComputedStyle(e).display !== 'none'; }, sel);
    assert(await pg.isVisible('#tile-vpn'), 'tegel VPN op het startscherm');
    await pg.click('#tile-vpn'); await pg.waitForTimeout(250);
    assert(await scr() === 's-vpn' && await vis('#vp-intro') && !(await vis('#vp-main')), 'zonder tunnel: uitleg en toevoegen');
    await pg.screenshot({ path: __dirname + '/shots/vpn-intro-' + scheme + '.png' });
    // bestand kiezen → bewerken → opslaan
    await pg.click('#vp-intro button:has-text("Bestand")'); await pg.waitForTimeout(300);
    assert(await scr() === 's-vpnedit' && (await pg.inputValue('#ve-name')) === 'mullvad-nl', 'bestand geopend in het bewerkscherm met naam');
    assert((await pg.textContent('#ve-check')).startsWith('✓ Klopt') && await vis('#ve-pubbox'), 'controle: klopt, publieke sleutel zichtbaar');
    await pg.fill('#ve-text', (await pg.inputValue('#ve-text')).replace(/Endpoint.*\n/, '')); await pg.waitForTimeout(500);
    assert((await pg.textContent('#ve-check')).includes('Endpoint'), 'zonder Endpoint: melding');
    await pg.click('#ve-save'); await pg.waitForTimeout(150);
    assert(await scr() === 's-vpnedit', 'met fout niet opgeslagen');
    await pg.fill('#ve-text', (await pg.inputValue('#ve-text')) + 'Endpoint = nl.example.net:51820\n'); await pg.waitForTimeout(450);
    await pg.screenshot({ path: __dirname + '/shots/vpn-edit-' + scheme + '.png' });
    await pg.click('#ve-save'); await pg.waitForTimeout(300);
    assert(await scr() === 's-vpn' && await vis('#vp-main') && (await pg.$$('#vp-list .srow')).length === 1, 'opgeslagen: tunnel in de lijst');
    // aanzetten (eerst toestemming van Android)
    await pg.click('#vp-toggle'); await pg.waitForTimeout(100);
    assert((await pg.textContent('#toast')).includes('toestemming') || (await pg.textContent('#toast')).includes('OK'), 'vraagt toestemming');
    await pg.waitForTimeout(2200);
    assert((await pg.textContent('#vp-title')).startsWith('Verbonden') && await vis('#vp-stats') && (await pg.textContent('#vp-toggle')) === 'Uitzetten', 'verbonden met cijfers');
    await pg.screenshot({ path: __dirname + '/shots/vpn-on-' + scheme + '.png', fullPage: true });
    await pg.click('#vp-toggle'); await pg.waitForTimeout(600);
    assert((await pg.textContent('#vp-title')) === 'VPN uit', 'uitgezet');
    // vanzelf
    await pg.check('#vp-a-wifi'); await pg.waitForTimeout(100);
    assert(await pg.evaluate(() => Android._vp.auto.wifi === true) && await vis('#vp-locnote'), 'aan op onbekende wifi, vraagt locatie altijd');
    await pg.click('#vp-trust'); await pg.waitForTimeout(100);
    assert((await pg.textContent('#vp-trusted')).includes('Thuisnetwerk') && !(await vis('#vp-trust')), 'huidige wifi vertrouwd');
    await pg.click('#vp-trusted button'); await pg.waitForTimeout(100);
    assert(!(await pg.textContent('#vp-trusted')).includes('📶'), 'vertrouwde wifi weer weg');
    // tegel en snelkoppeling
    await pg.click('#vp-quick button:has-text("Tegel")'); await pg.waitForTimeout(200);
    assert((await pg.textContent('#toast')).includes('Tegel toegevoegd'), 'tegel toegevoegd');
    await pg.click('#vp-quick button:has-text("Snelkoppeling")'); await pg.waitForTimeout(100);
    assert((await pg.evaluate(() => Android._pinned)) === 'vpn-toggle', 'snelkoppeling aan/uit');
    // menu: bewerken en verwijderen
    await pg.click('#vp-list .sbtn'); await pg.waitForTimeout(150);
    const acts = await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent));
    assert(acts.some(a => a.includes('Bewerken')) && acts.some(a => a.includes('publieke sleutel')), 'menu met bewerken en sleutel: ' + acts.join(' | '));
    await pg.click('#sheet-acts button:has-text("publieke sleutel")'); await pg.waitForTimeout(100);
    assert((await pg.evaluate(() => Android._copied || '')).length === 44, 'publieke sleutel gekopieerd');
    // Automatiseren: actie VPN aan met tunnelkeuze
    await pg.evaluate(() => { show('auto'); }); await pg.waitForTimeout(150);
    await pg.evaluate(() => { aeNew && aeNew(); }).catch(() => {});
    await pg.evaluate(() => { if (typeof aeEdit === 'function') aeEdit(null); }).catch(() => {});
    const hasOpt = await pg.evaluate(() => !!document.querySelector('#ae-act option[value="vpn_on"]') && !!document.querySelector('#ae-act option[value="vpn_off"]'));
    assert(hasOpt, 'Automatiseren heeft VPN aan/uit');
    await pg.evaluate(() => { aeRule = { trig: 'manual', act: 'vpn_on', name: 'x' }; aeRender(); });
    assert(await vis('#ae-vpnbox') && (await pg.$$eval('#ae-vpn option', x => x.length)) === 2, 'tunnel kiezen bij VPN aan');
    assert((await pg.evaluate(() => aeCheck())).includes('VPN-tunnel'), 'zonder tunnel: melding');
    await pg.evaluate(() => { const sl = document.querySelector('#ae-vpn'); sl.selectedIndex = 1; sl.onchange(); });
    assert((await pg.evaluate(() => aeCheck())) === '', 'met tunnel: klopt');
    // verwijderen
    await pg.evaluate(() => show('vpn')); await pg.waitForTimeout(150);
    await pg.click('#vp-list .sbtn'); await pg.waitForTimeout(150);
    await pg.click('#sheet-acts button:has-text("Verwijderen")'); await pg.waitForTimeout(150);
    await pg.click('#modal-ok'); await pg.waitForTimeout(200);
    assert(await vis('#vp-intro'), 'verwijderd: weer de uitleg');
    // plakken en zelf maken
    await pg.click('#vp-intro button:has-text("zelf maken"), #vp-intro button:has-text("Zelf maken")'); await pg.waitForTimeout(400);
    assert(await scr() === 's-vpnedit' && (await pg.inputValue('#ve-text')).includes('PrivateKey = cHJp') && await vis('#ve-pubbox'), 'zelf maken: sjabloon met eigen sleutel');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    await pg.click('#vp-intro button:has-text("QR-code scannen")'); await pg.waitForTimeout(200);
    assert((await pg.textContent('#toast')).includes('afbeelding') && !(await vis('#vp-scan')), 'geen camera: tip voor afbeelding');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    assert(await scr() === 's-home', 'terug naar start');
    console.log('errors', errs);
    await pg.close();
  }
  await b.close();
})().catch(e => { console.log(e.message); process.exit(1); });
