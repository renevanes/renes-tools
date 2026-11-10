// Spraak (Instellingen → Spraak): één plek voor voorlezen en spraak naar tekst. Stem, snelheid en achtergrond, de
// stemmen als modelregels (zelfde als Modellen), het spraakmodel, de stem per taal voor de e-reader, en het register.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    const ok = (c, m) => { if (!c) errs.push('mislukt: ' + m); };
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    const until = (f, arg) => pg.waitForFunction(f, arg, { timeout: 5000 }).then(() => true, () => false);
    const txt = async sel => (await pg.textContent(sel)).replace(/\s+/g, ' ');

    // Via Instellingen: één rij Spraak met de stem in gebruik
    await pg.evaluate(() => { Android._tx.models.base = true; Android._tx.model = 'base'; show('settings'); }); await pg.waitForTimeout(150);
    ok((await txt('#st-sum-spraak')).includes('Ronnie'), 'Instellingen toont de stem (' + await txt('#st-sum-spraak') + ')');
    ok(!(await pg.isVisible('#s-settings button.strow:has-text("Lokaal voorlezen")')), 'geen losse rij Lokaal voorlezen meer');
    await pg.click('#s-settings button.strow:has-text("Spraak")'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => current === 'spraak' && !sp.settings), 'Spraak is een eigen scherm (geen venster)');

    // Stem, snelheid, achtergrond en proberen
    ok(await pg.evaluate(() => [...$('#sv-voice').options].map(o => o.value).join()) === 'tts-nl-ronnie', 'alleen gedownloade stemmen in de keuze');
    ok(await pg.isVisible('#sv-cats .md-cat[data-kind="tts"]') && await pg.isVisible('#sv-cats .md-cat[data-kind="spraak"]'), 'stemmen en spraakmodellen als kaarten van Modellen');
    ok((await txt('#sv-cats .md-cat[data-kind="spraak"]')).includes('In gebruik'), 'spraakmodel in gebruik');
    await pg.locator('#sv-speed').evaluate(e => { e.value = '1.3'; e.dispatchEvent(new Event('input', { bubbles: true })); });
    ok(await pg.evaluate(() => Android._speech.speed === 1.3) && (await txt('#sv-speed-value')).includes('1,3'), 'snelheid');
    await pg.click('#sv-background'); ok(await pg.evaluate(() => Android._speech.background === true && $('#sv-background').getAttribute('aria-checked') === 'true'), 'boeken blijven voorlezen');
    await pg.click('#sv-background');
    await pg.click('#sv-preview'); ok(await pg.evaluate(() => Android._speech.said.slice(-1)[0]?.source === 'preview'), 'stem proberen');
    await pg.evaluate(() => spStop());

    // Een betere stem downloaden: verschijnt in de keuze en kan in gebruik
    await pg.click('#sv-cats [data-voice="tts-nl-miro"] button:has-text("Downloaden")');
    ok(await until(() => Android._speech.voices.find(v => v.id === 'tts-nl-miro').state === 'ready'), 'Miro gedownload');
    await pg.evaluate(() => onAiModels('')); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => [...$('#sv-voice').options].map(o => o.value).join()) === 'tts-nl-miro,tts-nl-ronnie', 'beste kwaliteit eerst in de keuze');
    await pg.selectOption('#sv-voice', 'tts-nl-miro'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._speech.voice === 'tts-nl-miro') && (await txt('#sv-cats [data-voice="tts-nl-miro"]')).includes('In gebruik'), 'Miro in gebruik, ook in de regel');

    // Stem per taal: de laatst gekozen Engelse stem voor een Engels boek, de Nederlandse blijft voor de rest
    await pg.evaluate(() => { const v = Android._speech.voices.find(x => x.id === 'tts-en-ljspeech'); v.state = 'ready'; spChoose('tts-en-ljspeech'); spChoose('tts-nl-miro'); });
    ok(await pg.evaluate(() => { const m = spReadModels(); return m.voice === 'tts-nl-miro' && m.lang.en === 'tts-en-ljspeech' && m.lang.nl === 'tts-nl-miro'; }), 'per taal onthouden');

    // Register: de assistent kan de stem en snelheid op Spraak vinden en aanpassen
    ok(await pg.evaluate(() => { const s = SET_REG.find(x => x.id === 'voorlezen.stem'); return s && s.scr === 'spraak' && s.set('tts-nl-ronnie') === '' && Android._speech.voice === 'tts-nl-ronnie' && s.set('tts-nl-dii') !== ''; }), 'register: stem kiezen (alleen een gedownloade)');

    // Het stemmenvenster (in een tool) gebruikt dezelfde regels en verwijst naar Spraak
    await pg.evaluate(() => { show('notes'); spSettings(); }); await pg.waitForTimeout(100);
    ok(await pg.locator('#speech-voices [data-voice="tts-nl-dii"]').count() === 1, 'zelfde stemregels in het venster');
    await pg.click('#speech-settings button:has-text("Alle spraakinstellingen")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'spraak' && !sp.settings), 'venster → Spraak');
    await pg.evaluate(() => goBack()); ok(await pg.evaluate(() => current === 'notes'), 'terug naar waar je was');

    // Verwijderen vraagt eerst
    await pg.evaluate(() => show('spraak')); await pg.waitForTimeout(100);
    await pg.click('#sv-cats [data-voice="tts-nl-miro"] button:has-text("Verwijderen")'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._speech.voices.find(v => v.id === 'tts-nl-miro').state === 'ready'), 'nog niet weg zonder bevestigen');

    console.log(scheme, 'errors', errs);
    if (errs.length) process.exitCode = 1;
    await pg.close();
  }
  await b.close();
})().catch(e => { console.error(e); process.exit(1); });
