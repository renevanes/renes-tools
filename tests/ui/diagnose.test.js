// Diagnose (Instellingen → Foutrapport en statistieken → Diagnose): wat een tool vastlegde, alleen in het geheugen; bekijken,
// kopiëren en per stuk versturen na een vraag; vanzelf sturen staat uit en vraagt eerst; zichtbaar wat er wegging.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => show('setcrashdiag')); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#st-diag') && (await pg.textContent('#st-diag-lijst')).includes('Nog niets vastgelegd'), 'leeg: uitleg wat je doet');
    ok((await pg.getAttribute('#st-diag-auto', 'aria-checked')) === 'false' && (await pg.textContent('#st-diag-sub')).includes('er gaat niets weg zonder dat jij erop tikt'), 'vanzelf sturen staat standaard uit');
    ok((await pg.textContent('#st-diag')).includes('namen, adressen en nummers'), 'zichtbaar dat een diagnose inhoud bevat');
    await pg.evaluate(() => { Android.diagnoseLeg('betalen', 'Rekening gelezen', 'naam ontvanger\n<img src=x onerror=window.__xss=1>Jansen B.V.'); dgRender(); });
    ok((await pg.textContent('#st-diag-lijst')).includes('Betalen: Rekening gelezen'), 'een vastgelegd stuk staat in de lijst, met de tool');
    ok(await pg.isDisabled('#st-diag-lijst button:has-text("Versturen")') && (await pg.textContent('#st-diag-waar')).includes('Nog geen bestemming'), 'zonder bestemming kan versturen niet; kopiëren wel');
    await pg.click('#st-diag-lijst button:has-text("Bekijken")'); await pg.waitForTimeout(80);
    ok((await pg.textContent('.dg-tekst')).includes('<img src=x') && !(await pg.evaluate(() => window.__xss)) && (await pg.getAttribute('.dg-tekst', 'translate')) === 'no', 'bekijken: precies de tekst, als tekst (geen HTML), niet vertaald');
    await pg.click('#st-diag-lijst button:has-text("Kopiëren")');
    ok((await pg.evaluate(() => Android._diag.gekopieerd)) === 'd1', 'kopiëren gaat per stuk');
    // Met een bestemming: versturen vraagt eerst en noemt waarheen
    await pg.evaluate(() => { Android._stat.repo = 'renevanes/renes-tools-statistiek'; Android._stat.token = true; dgRender(); });
    ok((await pg.textContent('#st-diag-waar')).includes('renevanes/renes-tools-statistiek'), 'met een bestemming: staat erbij waarheen');
    await pg.click('#st-diag-lijst button:has-text("Versturen")'); await pg.waitForTimeout(120);
    const vraag = await pg.textContent('#modal');
    ok(vraag.includes('renevanes/renes-tools-statistiek') && vraag.includes('namen, adressen en nummers') && !(await pg.evaluate(() => Android._diag.sent.length)), 'versturen: eerst een vraag met de bestemming; er is nog niets weg');
    await pg.click('#modal-ok'); await pg.waitForTimeout(250);
    ok((await pg.evaluate(() => Android._diag.sent.length)) === 1 && await pg.isDisabled('#st-diag-lijst button:has-text("Verstuurd")'), 'na de bevestiging verstuurd; niet nog een keer');
    await pg.evaluate(() => show('setcrashweg')); await pg.waitForTimeout(80);
    const weg = await pg.textContent('#st-diag-verstuurd');
    ok(weg.includes('Betalen') && weg.includes('tekens') && !weg.includes('Jansen'), 'wat de telefoon verliet: tijd, tool en omvang, niet de inhoud');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(60);
    ok(await pg.evaluate(() => current === 'setcrash' && /Laatst: .*Betalen/.test($('#fc-sum-weg').textContent)), 'terug op het overzicht: de rij zegt wat er het laatst wegging');
    await pg.evaluate(() => show('setcrashdiag')); await pg.waitForTimeout(80);
    // Vanzelf sturen: eerst een vraag; annuleren laat het uit
    await pg.click('#st-diag-auto'); await pg.waitForTimeout(120);
    ok((await pg.textContent('#modal')).includes('zonder vraag') && !(await pg.evaluate(() => Android._diag.auto)), 'vanzelf sturen: eerst een vraag met wat het betekent');
    await pg.click('#modal-ok'); await pg.waitForTimeout(120);
    ok((await pg.evaluate(() => Android._diag.auto)) && (await pg.getAttribute('#st-diag-auto', 'aria-checked')) === 'true' && (await pg.textContent('#st-diag-sub')).includes('gaat meteen naar'), 'aangezet: de stand staat erbij');
    await pg.evaluate(() => { Android.diagnoseLeg('betalen', 'QR-codes gezocht', 'Gevonden: 2'); dgRender(); });
    ok((await pg.evaluate(() => Android._diag.sent.length)) === 2 && (await pg.textContent('#st-diag-verstuurd')).split('tekens').length === 3, 'vanzelf sturen aan: een nieuw stuk gaat meteen, en staat bij wat de telefoon verliet');
    await pg.evaluate(() => show('setcrashdoel')); await pg.waitForTimeout(60);
    await pg.fill('#st-stat-repo', 'renevanes/iets-anders'); await pg.fill('#st-stat-token', 'github_pat_' + 'a'.repeat(30)); await pg.click('#s-setcrashdoel button:has-text("Opslaan")'); await pg.waitForTimeout(120);
    await pg.evaluate(() => show('setcrashdiag')); await pg.waitForTimeout(60);
    ok(!(await pg.evaluate(() => Android._diag.auto)) && (await pg.getAttribute('#st-diag-auto', 'aria-checked')) === 'false', 'een andere bestemming zet vanzelf sturen uit');
    await pg.click('#st-diag-auto'); await pg.waitForTimeout(120); await pg.click('#modal-ok'); await pg.waitForTimeout(120);
    await pg.click('#st-diag-auto'); await pg.waitForTimeout(80);
    ok(!(await pg.evaluate(() => Android._diag.auto)), 'uitzetten gaat zonder vraag');
    await pg.click('#st-diag-wis'); await pg.waitForTimeout(80);
    ok((await pg.textContent('#st-diag-lijst')).includes('Nog niets vastgelegd') && await pg.isDisabled('#st-diag-wis'), 'alles wissen');
    // Vastleggen uitzetten: tools leggen niets meer vast; met stukken in het geheugen eerst een vraag
    await pg.evaluate(() => { Android.diagnoseLeg('betalen', 'Rekening gelezen', 'x'); dgRender(); });
    await pg.click('#fc-leg'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#modal')).includes('gewist') && (await pg.evaluate(() => Android._fout.diagLeg)), 'vastleggen uitzetten: eerst een vraag (wat er staat gaat weg)');
    await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    await pg.evaluate(() => { Android.diagnoseLeg('betalen', 'Rekening gelezen', 'y'); dgRender(); });
    ok(!(await pg.evaluate(() => Android._fout.diagLeg)) && (await pg.getAttribute('#fc-leg', 'aria-checked')) === 'false' && (await pg.textContent('#st-diag-lijst')).includes('Nog niets vastgelegd'), 'uit: niets meer vastgelegd');
    await pg.click('#fc-leg'); await pg.waitForTimeout(80);
    await pg.selectOption('#fc-bewaar', '15'); await pg.waitForTimeout(60);
    ok((await pg.evaluate(() => Android._fout.diagLeg && Android._fout.diagBewaar === 15)), 'weer aan, en de bewaartijd is te kiezen');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
