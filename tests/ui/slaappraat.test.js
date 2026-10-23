// Slaappraat: starten (met toestemming), luisteren, stoppen, de nacht bekijken, afspelen, wissen, instellingen.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    ok(await pg.isVisible('#tile-sleep'), 'tegel op het startscherm');
    await pg.click('#tile-sleep'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'sleep') && (await pg.textContent('#sl-btn')) === 'Begin met luisteren', 'scherm: begin met luisteren');
    ok((await pg.textContent('#sl-nights')).includes('Nog geen nachten'), 'nog geen nachten');
    // Zonder microfoon: eerst toestemming
    await pg.evaluate(() => { Android._sl.mic = false; });
    await pg.click('#sl-btn'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._sl.phase === '' && Android._sl.mic === true) && (await pg.textContent('#toast')).includes('microfoon'), 'zonder microfoon: vraagt toestemming, start niet');
    // Starten
    await pg.click('#sl-btn'); await pg.waitForTimeout(700);
    ok(await pg.evaluate(() => Android._sl.phase === 'luistert') && (await pg.textContent('#sl-status')).includes('Luistert') && (await pg.textContent('#sl-btn')) === 'Stoppen', 'luistert, met stopknop');
    ok(await pg.isVisible('#sl-meter') && (await pg.textContent('#sl-status')).includes('stopt om'), 'meter en stoptijd te zien');
    await pg.evaluate(() => { Android._slHear('Ik moet de trein nog halen', 3); Android._slHear('', 6); Android._slHear('Nee, niet de rode', 2); }); await pg.waitForTimeout(1200);
    ok((await pg.textContent('#sl-status')).includes('3 keer iets gehoord'), 'telt mee terwijl hij luistert');
    await pg.screenshot({ path: __dirname + '/shots/slaappraat-' + scheme + '.png', fullPage: true });
    // Stoppen: daarna uitgeschreven
    await pg.click('#sl-btn'); await pg.waitForTimeout(800);
    ok(await pg.evaluate(() => Android._sl.phase === '') && (await pg.textContent('#sl-btn')) === 'Begin met luisteren', 'gestopt');
    ok((await pg.textContent('#sl-nights')).includes('2 keer gepraat') && (await pg.textContent('#sl-nights')).includes('1 geluid') && (await pg.textContent('#sl-nights')).includes('Ik moet de trein'), 'nacht: gepraat, geluid en het eerste wat je zei');
    // De nacht
    await pg.click('#sl-nights .strow'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'sleepnight') && (await pg.$$('#sln-list .sl-clip')).length === 2, 'nacht: alleen gepraat (2)');
    await pg.click('#sln-filter button[data-v=alles]'); await pg.waitForTimeout(80);
    ok((await pg.$$('#sln-list .sl-clip')).length === 3 && (await pg.textContent('#sln-list')).includes('Geluid (geen woorden)'), 'alles: ook het geluid');
    ok((await pg.$$('#sln-list .sl-clip:has-text("Geluid") button[aria-label^="Afspelen"]')).length === 0, 'geluid niet bewaard: niet af te spelen');
    await pg.click('#sln-list .sl-clip:has-text("trein") button[aria-label^="Afspelen"]'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => Android._sl.played !== '') && await pg.isVisible('#sln-list .sl-clip:has-text("trein") button[aria-label^="Stoppen"]'), 'afspelen, en de knop wordt stoppen');
    await pg.screenshot({ path: __dirname + '/shots/slaappraat-nacht-' + scheme + '.png', fullPage: true });
    await pg.click('#sln-list .sl-clip:has-text("rode") button[aria-label^="Wissen"]'); await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    ok((await pg.$$('#sln-list .sl-clip')).length === 2, 'stukje gewist');
    // Als notitie
    await pg.click('#sln-menu'); await pg.click('#sheet-acts button:has-text("Als notitie bewaren")'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => notesGet().some(n => n.title.startsWith('Slaappraat') && n.text.includes('trein'))), 'als notitie bewaard');
    // Terug: afspelen stopt
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => current === 'sleep' && Android._sl.played === ''), 'terug: afspelen gestopt');
    // Nog niet uitgeschreven (uitschrijven stond uit): later alsnog
    await pg.evaluate(() => { Android._sl.set.transcribe = false; });
    await pg.click('#sl-btn'); await pg.waitForTimeout(600);
    await pg.evaluate(() => { Android._slHear('Waar is de kat', 3); });
    await pg.click('#sl-btn'); await pg.waitForTimeout(700);
    await pg.click('#sl-nights .strow:first-child'); await pg.waitForTimeout(200);
    ok((await pg.textContent('#sln-tx')).includes('nog niet uitgeschreven'), 'nog niet uitgeschreven: knop om het nu te doen');
    await pg.click('#sln-tx button:has-text("Nu uitschrijven")'); await pg.waitForTimeout(400);
    ok((await pg.textContent('#sln-list')).includes('Waar is de kat'), 'nu uitgeschreven');
    // Nacht wissen
    await pg.click('#sln-menu'); await pg.click('#sheet-acts button:has-text("Deze nacht wissen")'); await pg.click('#modal-ok'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'sleep' && Android._sl.nights.length === 1), 'nacht gewist');
    // Instellingen
    await pg.click('#sl-sens button[data-v="3"]'); await pg.click('#sl-keep button[data-v="30"]'); await pg.click('#sl-noise');
    ok(await pg.evaluate(() => Android._sl.set.sens === 3 && Android._sl.set.keep === 30 && Android._sl.set.keepNoise === true) && (await pg.textContent('#sl-sens-n')).includes('duidelijk'), 'gevoeligheid, bewaren en geluiden');
    await pg.click('#sl-stop-on'); await pg.waitForTimeout(60);
    ok(await pg.evaluate(() => Android._sl.set.stopAt === '') && !(await pg.isVisible('#sl-stop')) && (await pg.textContent('#sl-stop-n')).includes('tot je stopt'), 'vanzelf stoppen uit');
    await pg.click('#sl-stop-on'); await pg.fill('#sl-stop', '06:45'); await pg.dispatchEvent('#sl-stop', 'change'); await pg.waitForTimeout(60);
    ok(await pg.evaluate(() => Android._sl.set.stopAt === '06:45'), 'stoptijd 06:45');
    // Zonder spraakmodel: uitleg
    await pg.evaluate(() => { Android._sl.model = ''; slLoad(); slRender(); });
    ok((await pg.textContent('#sl-tx-n')).includes('Gesprekken uitschrijven'), 'zonder spraakmodel: waar je het downloadt');
    // Zoeken vindt het scherm
    await pg.evaluate(() => { show('search'); }); await pg.fill('#gs-q', 'slaap'); await pg.evaluate(() => gsInput()); await pg.waitForTimeout(400);
    ok((await pg.textContent('#gs-res')).includes('Slaappraat'), 'te vinden bij Zoeken');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
