// Play-versie: tools zonder toestemming uit beeld, geen eigen updates. De gewone versie blijft ongewijzigd.
const { chromium } = require('playwright');
const assert = (ok, what) => { if (!ok) throw new Error('mislukt: ' + what); console.log('✓ ' + what); };
const WEG = ['redial', 'sms', 'calls', 'recorder', 'vpn', 'ai', 'tracks', 'gebruik', 'auto', 'contacts', 'sleep', 'bank'];
const TEGEL = Object.fromEntries(WEG.map(t => [t, 'tile-' + t]));
(async () => {
  const b = await chromium.launch();
  const errs = [];
  const open = async (play, init, colorScheme = 'light') => {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme, hasTouch: true });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.addInitScript(([p, o]) => { if (p) window.__testPlay = true; if (o) window.__testOpen = o; }, [play, init || '']);
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    return pg;
  };
  for (const colorScheme of ['light', 'dark']) for (const play of [false, true]) {
    const naam = play ? 'Play-versie' : 'gewone versie';
    const pg = await open(play, '', colorScheme);
    const zichtbaar = id => pg.$eval('#' + id, el => el.checkVisibility()).catch(() => false);
    for (const t of WEG) assert((await zichtbaar(TEGEL[t])) === !play, naam + ': tegel ' + t + (play ? ' verborgen' : ' zichtbaar'));
    assert(await zichtbaar('tile-notes'), naam + ': notities zichtbaar');
    // Openen (ook een subscherm zoals een sms-gesprek)
    for (const s of WEG.concat(['smschat'])) {
      await pg.evaluate(() => show('home')); await pg.evaluate(n => show(n), s); await pg.waitForTimeout(80);
      const scherm = await pg.evaluate(() => current);
      assert(play ? scherm === 'home' : scherm === s, naam + ': ' + s + ' openen ' + (play ? 'gaat niet' : 'gaat'));
    }
    // Zoeken en app-snelkoppelingen
    assert((await pg.evaluate(() => 'sms' in GS && 'calls' in GS)) === !play, naam + ': sms en oproepen in Overal zoeken ' + (play ? 'weg' : 'er'));
    assert((await pg.evaluate(() => SET_REG.some(r => r.scr === 'redial'))) === !play, naam + ': instellingen van Auto redial in zoeken ' + (play ? 'weg' : 'er'));
    assert((await pg.evaluate(() => SOLO_TOOLS.includes('redial'))) === !play, naam + ': Auto redial als losse app ' + (play ? 'weg' : 'er'));
    await pg.evaluate(() => { toggleFav('redial'); setDynamicShortcuts(); }); // als favoriet zou hij er in de gewone versie zeker bij staan
    const dyn = JSON.parse(await pg.evaluate(() => Android._dyn)).map(x => x[0]);
    assert(dyn.includes('redial') === !play, naam + ': app-icoon-keuzes ' + (play ? 'zonder' : 'met') + ' Auto redial: ' + dyn);
    await pg.evaluate(() => toggleFav('redial'));
    // Updates
    await pg.evaluate(() => show('info')); await pg.waitForTimeout(150);
    assert((await zichtbaar('chk-btn')) === !play, naam + ': knop "Controleren op updates" ' + (play ? 'weg' : 'er'));
    assert(!!(await pg.$('#play-upd')) === play, naam + ': uitleg over Google Play ' + (play ? 'er' : 'niet'));
    assert((await pg.evaluate(() => cmContactKan())) === !play, naam + ': Stamgegevens ' + (play ? 'zonder' : 'met') + ' Kies uit Contacten');
    if (play) {
      for (const tool of ['notes', 'pdf', 'radio', 'podcasts']) {
        await pg.evaluate(n => show(n), tool);
        assert(await pg.evaluate(() => current) === tool, 'Play: ' + tool + ' bereikbaar');
      }
      assert(!await pg.$eval('[onclick="voiceOpen(null)"]', e => e.checkVisibility()), 'Play biedt geen microfoon zonder recht aan');
      await pg.evaluate(() => { show('notes'); Android._wa.info.dest = false; notesBackup(); });
      assert(await pg.evaluate(() => Android._wa.info.dest), 'Play: exportmap direct bij notities kiezen');
      await pg.evaluate(() => { show('pdf'); pdfOutMenu('test.pdf'); });
      assert(!await pg.locator('#sheet').innerText().then(t => t.includes('Bewaren in documentenkluis')), 'Play: PDF-menu zonder verborgen kluis');
      await pg.evaluate(() => closeSheet());
      await pg.evaluate(() => show('setregio'));
      assert(!await pg.$eval('#rg-home-btn', e => e.checkVisibility()), 'Play: geen onbereikbare plaatskeuze');
      await pg.evaluate(() => { gsIndex = null; gsBuildIndex(); });
      assert(await pg.evaluate(() => !gsIndex.some(x => playWeg(x.scr))), 'Play: zoekindex bevat alleen bereikbare functies');
      for (const name of ['vpn', 'ai', 'auto', 'podspotify', 'herstel', 'onbekend']) {
        await pg.evaluate(n => { show('home'); show(n); }, name);
        assert(await pg.evaluate(() => current) === 'home', 'Play: directe route geweigerd ' + name);
      }
      await pg.evaluate(() => { Android.openUrl = u => { window.testPrivacy = u; }; openPrivacy(); });
      assert((await pg.evaluate(() => window.testPrivacy)).endsWith('/privacybeleid-play.md'), 'Play: eigen privacybeleid');
      await pg.evaluate(() => onUpdateStatus({ state: 'play', kind: 'web', manual: true })); await pg.waitForTimeout(80);
      assert(!(await zichtbaar('upd-banner2')), naam + ': geen updatebalk na "updates via Play"');
    }
    // Gebruik: apparaatbeheer en stille foto's
    await pg.evaluate(() => show('gebruikontgr')); await pg.waitForTimeout(150);
    assert((await zichtbaar('gb-admin')) === !play, naam + ': apparaatbeheer ' + (play ? 'weg' : 'er'));
    assert((await zichtbaar('gb-test')) === !play, naam + ': foto bij ontgrendelen ' + (play ? 'weg' : 'er'));
    await pg.evaluate(() => show('gebruik')); await pg.waitForTimeout(150);
    assert((await pg.$eval('#gb-tabs [data-tab="fotos"]', el => el.checkVisibility())) === !play, naam + ": tabblad Foto's " + (play ? 'weg' : 'er'));
    if (play) await pg.screenshot({ path: __dirname + '/shots/play-versie-' + colorScheme + '.png' });
    await pg.close();
  }
  // Bij het starten een tool openen (snelkoppeling, widget, melding, losse app)
  for (const play of [false, true]) {
    const pg = await open(play, 'sms');
    const scherm = await pg.evaluate(() => current);
    assert(play ? scherm !== 'sms' : scherm === 'sms', (play ? 'Play-versie' : 'gewone versie') + ': sms-backup bij het starten ' + (play ? 'niet geopend' : 'geopend') + ' (' + scherm + ')');
    await pg.close();
  }
  console.log('errors', errs);
  await b.close();
})().catch(e => { console.log(e.message); process.exit(1); });
