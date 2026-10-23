// Instellingen: een overzicht in groepen met per onderwerp de stand van zaken, en per onderwerp een eigen scherm.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.click('#tabs button[data-t=settings]'); await pg.waitForTimeout(250);
    const groups = await pg.$$eval('#s-settings .stgl', x => x.map(e => e.textContent));
    ok(groups.join('|') === 'Algemeen|Beveiliging en privacy|Gegevens en diensten|Over de app', 'vier groepen');
    ok((await pg.$$('#s-settings .strow')).length === 12, 'twaalf onderwerpen');
    ok(await pg.evaluate(() => document.documentElement.scrollHeight < 1700), 'geen lange pagina meer');
    ok(!(await pg.isVisible('#st-zoom')) && !(await pg.isVisible('#st-perms')), 'de instellingen zelf staan op eigen schermen');
    const sums = await pg.$$eval('#s-settings .strow small', x => x.map(e => e.textContent));
    ok(sums.every(t => t.length > 0), 'elk onderwerp toont de stand van zaken');
    ok((await pg.textContent('#st-sum-lock')) === 'Uit' && (await pg.textContent('#st-sum-crash')).includes('Geen fouten'), 'stand: app-slot uit, geen fouten');
    await pg.screenshot({ path: __dirname + '/shots/instellingen-' + scheme + '.png', fullPage: true });
    // Elk onderwerp opent zijn eigen scherm, terug = het overzicht
    const subs = [['Meldingen', 'setnotif', '#st-notif-mute'], ['Weergave', 'setlook', '#st-zoom'], ['Telefoon-skin', 'setskin', '#st-home-btn'], ['App-slot', 'setlock', '#st-lock-btn'],
      ['Losse apps', 'setsolo', '#st-solo-list'], ['Toestemmingen', 'setperm', '#st-perms'], ['Backup-map', 'setbackup', '#st-dest'], ['Muziek herkennen', 'setmusic', '#st-audd'], ['Foutrapport', 'setcrash', '#st-crash']];
    for (const [t, scr, el] of subs) {
      await pg.click('#s-settings .strow:has-text("' + t + '")'); await pg.waitForTimeout(120);
      const good = await pg.evaluate(s => current === s, scr) && await pg.isVisible(el) && (await pg.textContent('#s-' + scr + ' h1')) === t;
      await pg.evaluate(() => goBack()); await pg.waitForTimeout(120);
      ok(good && await pg.evaluate(() => current === 'settings'), t + ': eigen scherm, terug naar het overzicht');
    }
    // Instelling veranderen: de stand in het overzicht klopt meteen
    await pg.click('#s-settings .strow:has-text("App-slot")'); await pg.click('#st-lock-btn'); await pg.waitForTimeout(200);
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    ok((await pg.textContent('#st-sum-lock')).startsWith('Aan'), 'app-slot aan: overzicht bijgewerkt');
    // Naar andere delen van de app, en terug naar de instellingen
    await pg.click('#s-settings .strow:has-text("Lokale AI")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'aiset'), 'lokale AI: de AI-instellingen');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'settings'), 'terug: weer de instellingen');
    await pg.click('#s-settings .strow:has-text("Versie en updates")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'info'), 'versie en updates');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'settings'), 'terug: weer de instellingen');
    // Zoeken vindt een instelling en opent het juiste scherm
    await pg.evaluate(() => { show('search'); $('#gs-q').value = 'tekstgrootte'; gsInput(); }); await pg.waitForTimeout(500);
    const hits = await pg.$$eval('#gs-res .gi', x => x.map(e => e.innerText.replace(/\n/g, ' / ')));
    ok(hits.filter(h => h === 'Weergave / Instellingen').length === 1 && !hits.includes('Weergave / Weergave'), 'zoeken: één treffer per instellingenscherm');
    await pg.click('#gs-res .gi:has-text("Weergave"):has-text("Instellingen")'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => current === 'setlook') && await pg.isVisible('#st-zoom'), 'zoeken: "tekstgrootte" opent Weergave');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
