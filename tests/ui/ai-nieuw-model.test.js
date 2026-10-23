// Lokale AI: een nieuwer model (meegekomen met een app-update) wordt gemeld, nooit vanzelf gedownload; oude bestanden opruimen.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    // Zonder nieuwer model: geen melding
    await pg.evaluate(() => { localStorage.removeItem('rt.aiNewSeen'); Android._ai.st.klein = { state: 'ready' }; show('ai'); }); await pg.waitForTimeout(150);
    ok((await pg.textContent('#ai-newmodel')) === '', 'geen nieuw model: geen melding');
    // Een update brengt "Klein 2" mee, dat "Klein en snel" vervangt
    await pg.evaluate(() => { Android._ai.models.push(['klein2', 'Klein 2', 'Qwen 2B · sneller', 1.3e9, 'klein', 'Beter Nederlands, even snel']); Android._ai.st.klein2 = { state: 'none' }; show('home'); show('ai'); }); await pg.waitForTimeout(150);
    ok((await pg.textContent('#ai-newmodel')).includes('Nieuw model: Klein 2') && (await pg.textContent('#ai-newmodel')).includes('Beter Nederlands'), 'melding bij de assistent');
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
    ok((await pg.textContent('#ais-chat .aimodel[data-id=klein2]')).includes('Nieuw, vervangt Klein en snel'), 'taalmodel: nieuw, en wat het vervangt');
    await pg.click('#ais-chat .aimodel[data-id=klein2] button:has-text("Downloaden")'); await pg.waitForTimeout(900);
    ok(await pg.evaluate(() => Android._ai.st.klein2.state === 'ready'), 'zelf gedownload');
    await pg.click('#ais-chat .aimodel[data-id=klein2] button:has-text("Gebruiken")'); await pg.waitForTimeout(600);
    ok((await pg.textContent('#modal-title')).includes('Klein en snel verwijderen?'), 'in gebruik genomen: vraagt of het oude weg mag');
    await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._ai.st.klein.state === 'none' && Android._ai.chat === 'klein2'), 'oude model verwijderd, nieuwe in gebruik');
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
