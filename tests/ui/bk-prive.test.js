// Alles back-uppen: geheugen en gesprekken van de assistent en Slaappraat (privé, standaard uit), waarschuwing zonder
// versleutelen, keuze tekst of geluid, en terugzetten (gewoon en uit een versleutelde backup).
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    await pg.evaluate(() => { Android._wa.info.dest = true; });
    await pg.click('#tile-backup'); await pg.waitForTimeout(200);
    const priv = () => pg.evaluate(() => ({ ai: !!Android._bk.parts.ai, sleep: !!Android._bk.parts.sleep, audio: Android._bk.sleepAudio,
      aiBox: document.querySelector('#bk-private input[data-p="ai"]').checked, sleepBox: document.querySelector('#bk-private input[data-p="sleep"]').checked,
      radios: document.querySelectorAll('#bk-private input[type=radio]').length, warn: !!document.querySelector('#bk-priv-warn'), enc: !!document.querySelector('#bk-priv-enc') }));
    console.log('standaard uit', JSON.stringify(await priv()));
    console.log('grootte', (await pg.textContent('#bk-private')).replace(/\s+/g, ' ').trim());
    // Zonder versleutelen: eerst bevestigen
    await pg.click('#bk-private input[data-p="ai"]'); await pg.waitForTimeout(150);
    console.log('vraag', await pg.textContent('#modal-title'), '|', await pg.textContent('#modal-text'));
    console.log('nog niet aan', JSON.stringify(await priv()));
    await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    const p1 = await priv();
    console.log('na bevestigen', JSON.stringify(p1), 'waarschuwing', p1.warn ? await pg.textContent('#bk-priv-warn') : '-');
    if (!p1.ai || !p1.aiBox || !p1.warn) throw new Error('assistent niet aangezet of geen waarschuwing');
    // Slaappraat: na bevestigen keuze tekst / geluid, standaard alleen tekst
    await pg.click('#bk-private input[data-p="sleep"]'); await pg.waitForTimeout(150); await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    const p2 = await priv();
    console.log('slaappraat', JSON.stringify(p2), 'tekst gekozen', await pg.isChecked('#bk-slaud-0'));
    if (!p2.sleep || p2.radios !== 2 || p2.audio) throw new Error('slaappraat-keuze klopt niet');
    await pg.click('#bk-slaud-1'); await pg.waitForTimeout(150);
    console.log('met geluid', await pg.evaluate(() => Android._bk.sleepAudio), (await pg.textContent('#bk-private')).replace(/\s+/g, ' ').includes('Elke backup wordt zo'));
    await pg.screenshot({ path: __dirname + '/shots/bk-prive-' + scheme + '.png', fullPage: true });
    // Versleutelen aan: geen waarschuwing meer, wel "gaat versleuteld mee"; uitvinken en opnieuw aanvinken vraagt niets
    await pg.evaluate(() => { Android._sec.on = true; onSecure(''); }); await pg.waitForTimeout(150);
    const p3 = await priv();
    console.log('versleuteld', JSON.stringify(p3));
    if (p3.warn || !p3.enc) throw new Error('waarschuwing hoort weg te zijn');
    await pg.click('#bk-private input[data-p="ai"]'); await pg.waitForTimeout(100);
    await pg.click('#bk-private input[data-p="ai"]'); await pg.waitForTimeout(100);
    console.log('zonder vraag aan', await pg.evaluate(() => Android._bk.parts.ai), 'dialoog open', await pg.isVisible('#modal-ok'));
    // Versleutelen uitzetten: de dialoog noemt de privé-onderdelen en zet ze uit; weer aanzetten vraagt opnieuw
    await pg.click('#bk-enc-off'); await pg.waitForTimeout(150);
    const offText = await pg.textContent('#modal-text');
    console.log('uitzetten', offText.replace(/\n/g, ' / '));
    if (!/Het geheugen en de gesprekken van de assistent, en Slaappraat, gaan dan niet meer mee, want die zijn privé/.test(offText)) throw new Error('dialoog noemt privé-onderdelen niet');
    await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    const p4 = await priv();
    console.log('na uitzetten', JSON.stringify(p4), await pg.textContent('#toast'));
    if (p4.ai || p4.sleep || p4.aiBox || p4.warn) throw new Error('privé-onderdelen horen uit te staan');
    for (const k of ['ai', 'sleep']) { await pg.click('#bk-private input[data-p="' + k + '"]'); await pg.waitForTimeout(150); console.log('opnieuw', k, await pg.textContent('#modal-title')); await pg.click('#modal-ok'); await pg.waitForTimeout(150); }
    console.log('weer onversleuteld', JSON.stringify(await priv()));
    console.log('toestemming', JSON.stringify(await pg.evaluate(() => Android._bk.plain)));
    if (!(await pg.evaluate(() => Android._bk.plain.ai && Android._bk.plain.sleep))) throw new Error('toestemming voor onversleuteld niet gezet');
    // Alleen het geheugen: meervoud
    const een = await pg.evaluate(() => [bkPrivNoLonger(['ai']), bkPrivNoLonger(['sleep'])]);
    console.log('één onderdeel', een.join(' | '));
    if (!/^Het geheugen en de gesprekken van de assistent gaan .* die zijn privé. Wil je ze/.test(een[0]) || !/^Slaappraat gaat .* dat is privé. Wil je het/.test(een[1])) throw new Error('enkelvoud/meervoud klopt niet');
    // Overzicht wegtikken: niets blijft klaarstaan (restoreCancel)
    await pg.click('#s-backup button:has-text("Slaappraat")'); await pg.waitForTimeout(300);
    await pg.click('#modal-cancel'); await pg.waitForTimeout(150);
    console.log('geannuleerd', await pg.evaluate(() => Android._restoreCancelled));
    if (await pg.evaluate(() => Android._restoreCancelled) !== 1) throw new Error('restoreCancel niet aangeroepen');
    // Terugzetten: assistent
    await pg.click('button:has-text("Geheugen en gesprekken van de assistent")'); await pg.waitForTimeout(300);
    console.log('assistent terugzetten', await pg.textContent('#modal-title'), '|', (await pg.textContent('#modal-text')).replace(/\n/g, ' / '));
    await pg.click('#modal-ok'); await pg.waitForTimeout(300);
    console.log('toast', await pg.textContent('#toast'));
    // Terugzetten: Slaappraat
    await pg.click('#s-backup button:has-text("Slaappraat")'); await pg.waitForTimeout(300);
    console.log('slaappraat terugzetten', await pg.textContent('#modal-title'), '|', (await pg.textContent('#modal-text')).replace(/\n/g, ' / '));
    await pg.click('#modal-ok'); await pg.waitForTimeout(300);
    console.log('toast', await pg.textContent('#toast'));
    // Uit een versleutelde backup
    await pg.click('button:has-text("Versleutelde backup openen")'); await pg.waitForTimeout(300);
    await pg.fill('#modal-input', 'goedwachtwoord'); await pg.click('#modal-ok'); await pg.waitForTimeout(400);
    const acts = await pg.$$eval('#sheet-acts button', x => x.map(e => e.innerText.trim()).filter(Boolean));
    console.log('acties', JSON.stringify(acts));
    if (!acts.some(t => /assistent/.test(t)) || !acts.some(t => /Slaappraat/.test(t))) throw new Error('acties voor assistent of Slaappraat ontbreken');
    await pg.click('button:has-text("Slaappraat terugzetten")'); await pg.waitForTimeout(400);
    console.log('uit archief', await pg.evaluate(() => Android._arcRestore), '|', await pg.textContent('#modal-title'), '|', (await pg.textContent('#modal-text')).replace(/\n/g, ' / '));
    await pg.click('#modal-ok'); await pg.waitForTimeout(300);
    console.log('toast', await pg.textContent('#toast'));
    // Assistent uit het archief met niets nieuws in het geheugen, wel gesprekken
    await pg.evaluate(() => Android.archiveRestore('ai', 'Assistent/assistent.json')); await pg.waitForTimeout(300);
    console.log('assistent uit archief', (await pg.textContent('#modal-text')).replace(/\n/g, ' / '));
    await pg.click('#modal-ok'); await pg.waitForTimeout(300);
    // Geluid niet uit het archief te halen: een melding die bij het archief past
    await pg.evaluate(() => onRestorePreview({ kind: 'sleep', total: 2, fresh: 1, dup: 1, old: 0, running: 0, keep: 14, clips: 3, audio: 0, audioBytes: 0, withAudio: true, fromArchive: true, sample: [Date.now() - 864e5] })); await pg.waitForTimeout(150);
    const arcText = await pg.textContent('#modal-text');
    console.log('archief zonder geluid', arcText.replace(/\n/g, ' / '));
    if (!/versleutelde backup/.test(arcText) || /backup-map/.test(arcText)) throw new Error('melding past niet bij het archief');
    await pg.click('#modal-cancel'); await pg.waitForTimeout(150);
    // Laatste backup met deze onderdelen
    await pg.evaluate(() => { const l = { t: Date.now(), stopped: true, ai: { ok: true, count: 20, msg: '20 (niet versleuteld)' }, sleep: { ok: false, count: 0, msg: 'Gestopt: Android beëindigde de backup (volgende keer opnieuw)' } }; bkRenderLast({ last: l, dest: true }); });
    console.log('uitkomst', JSON.stringify(await pg.$$eval('#bk-parts-res .bkres', x => x.map(e => e.innerText.replace(/\n/g, ' ')))));
    console.log('errors', errs);
  }
  await b.close();
})();
