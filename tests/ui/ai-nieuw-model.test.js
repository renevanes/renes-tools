// Lokale AI: een nieuwer model (Qwen3.5, meegekomen met een app-update) wordt gemeld, nooit vanzelf gedownload; oude bestanden opruimen.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    // Alleen het nieuwste model (Qwen3.5): geen melding
    await pg.evaluate(() => { localStorage.removeItem('rt.aiNewSeen'); Android._ai.st.klein35 = { state: 'ready' }; show('ai'); }); await pg.waitForTimeout(150);
    ok((await pg.textContent('#ai-newmodel')) === '', 'geen nieuw model: geen melding');
    // Wie het oude "Klein en snel" (Qwen3) heeft, krijgt Qwen3.5 als opvolger gemeld
    await pg.evaluate(() => { Android._ai.st.klein35 = { state: 'none' }; Android._ai.st.klein = { state: 'ready' }; show('home'); show('ai'); }); await pg.waitForTimeout(150);
    const melding = await pg.textContent('#ai-newmodel');
    ok(melding.includes('Nieuw model: Klein en snel (Qwen3.5)') && melding.includes('beter in Nederlands') && melding.includes('Vervangt Klein en snel'), 'melding bij de assistent');
    ok(!melding.includes('Groter en slimmer'), 'geen melding voor het grote model als je dat niet hebt');
    ok(await pg.evaluate(() => !Android._ai.calls.some(c => c[0] === 'dl')), 'niets vanzelf gedownload');
    await pg.screenshot({ path: __dirname + '/shots/ai-nieuw-model-' + scheme + '.png' });
    await pg.click('#ai-newmodel button:has-text("Niet nu")'); await pg.waitForTimeout(80);
    ok((await pg.textContent('#ai-newmodel')) === '', '"Niet nu": melding weg');
    await pg.evaluate(() => { show('home'); show('ai'); }); await pg.waitForTimeout(80);
    ok((await pg.textContent('#ai-newmodel')) === '', 'en blijft weg');
    // Bij Instellingen: in het overzicht en bij Taalmodel
    await pg.evaluate(() => show('aiset')); await pg.waitForTimeout(120);
    ok((await pg.textContent('#ai-sum-model')).includes('nieuw model beschikbaar'), 'overzicht: nieuw model beschikbaar');
    await pg.click('#s-aiset .strow:has-text("Taalmodel")'); await pg.waitForTimeout(120);
    ok((await pg.textContent('#ais-chat .aimodel[data-id=klein35]')).includes('Nieuw, vervangt Klein en snel'), 'taalmodel: nieuw, en wat het vervangt');
    ok(!(await pg.$('#ais-chat .aimodel[data-id=groot]')) && !!(await pg.$('#ais-chat .aimodel[data-id=groot35]')), 'het oude grote model (niet gedownload) niet meer te kiezen, het nieuwe wel');
    ok(!(await pg.textContent('#ais-chat .aimodel[data-id=groot35]')).includes('let op'), 'genoeg geheugen: geen waarschuwing');
    await pg.evaluate(() => { Android._ai.ram = 6e9; aiRefresh(); }); await pg.waitForTimeout(100);
    ok((await pg.textContent('#ais-chat .aimodel[data-id=groot35]')).includes('let op: je telefoon heeft 6 GB'), 'groot model, weinig geheugen: waarschuwing');
    ok(!(await pg.textContent('#ais-chat .aimodel[data-id=klein35]')).includes('let op'), 'klein model: geen waarschuwing');
    await pg.evaluate(() => { Android._ai.ram = 8e9; aiRefresh(); }); await pg.waitForTimeout(100);
    await pg.click('#ais-chat .aimodel[data-id=klein35] button:has-text("Downloaden")'); await pg.waitForTimeout(900);
    ok(await pg.evaluate(() => Android._ai.st.klein35.state === 'ready'), 'zelf gedownload');
    ok((await pg.textContent('#ais-chat .aimodel[data-id=klein]')).includes('Vervangen door Klein en snel (Qwen3.5)'), 'oude model: vervangen door het nieuwe');
    await pg.click('#ais-chat .aimodel[data-id=klein35] button:has-text("Gebruiken")'); await pg.waitForTimeout(600);
    ok((await pg.textContent('#modal-title')).includes('Klein en snel verwijderen?'), 'in gebruik genomen: vraagt of het oude weg mag');
    await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._ai.st.klein.state === 'none' && Android._ai.chat === 'klein35'), 'oude model verwijderd, nieuwe in gebruik');
    ok(!(await pg.$('#ais-chat .aimodel[data-id=klein]')), 'oude model daarna niet meer in de lijst');
    // Nieuwe gebruiker: geen "vervangt …" bij een model dat je nooit had
    await pg.evaluate(() => { Android._ai.st.klein = { state: 'none' }; aiRefresh(); }); await pg.waitForTimeout(100);
    ok(!(await pg.textContent('#ais-chat .aimodel[data-id=klein35]')).includes('vervangt') && !(await pg.textContent('#ais-chat .aimodel[data-id=groot35]')).includes('vervangt'), 'oude model niet in de lijst: geen "vervangt"');
    // Een vervangen model met een mislukte download: alleen weghalen, niet opnieuw downloaden
    await pg.evaluate(() => { Android._ai.st.groot = { state: 'failed', reason: 'Downloaden mislukt' }; aiRefresh(); }); await pg.waitForTimeout(100);
    ok(!!(await pg.$('#ais-chat .aimodel[data-id=groot] button:has-text("Verwijderen")')) && !(await pg.$('#ais-chat .aimodel[data-id=groot] button:has-text("Downloaden")')), 'mislukt oud model: Verwijderen in plaats van Downloaden');
    await pg.click('#ais-chat .aimodel[data-id=groot] button:has-text("Verwijderen")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._ai.st.groot.state === 'none') && !(await pg.$('#ais-chat .aimodel[data-id=groot]')), 'weggehaald en niet meer in de lijst');
    // Oude modelbestanden opruimen
    await pg.evaluate(() => { Android._ai.orphans = 1.3e9; enterAiSet(); }); await pg.waitForTimeout(100);
    ok((await pg.textContent('#ais-old')).includes('1,3 GB'), 'oude bestanden te zien');
    await pg.click('#ais-old button:has-text("Opruimen")'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#toast')).includes('1,3 GB vrijgemaakt') && (await pg.textContent('#ais-old')) === '', 'opgeruimd');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
