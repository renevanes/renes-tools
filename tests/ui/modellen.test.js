// Modellen beheren: per soort (assistent, spraak naar tekst, slim zoeken, voorlezen) wat het doet, of je het
// nodig hebt, welke versie je hebt en welke er is; bijwerken naar een nieuwere versie, verder na een onderbreking,
// verwijderen met uitleg, opruimen, en de voorleesstem van Android.
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
    const cat = k => '#md-cats .md-cat[data-kind="' + k + '"]';

    // Stand: het oude kleine model (Qwen3) in gebruik, zoekmodel, spraakmodel snel; groot onderbroken
    await pg.evaluate(() => {
      const A = Android._ai; A.st.klein = { state: 'ready' }; A.st.zoek = { state: 'ready' }; A.chat = 'klein';
      A.st.groot = { state: 'paused', via: 'app', reason: 'Geen verbinding. Tik op Verder om door te gaan', bytes: 6e8, total: 2497280256 };
      Android._tx.models.base = true; Android._tx.model = 'base';
      Android._md.last['ai:klein'] = Date.now() - 2 * 86400000; Android._md.leftovers = 3e8;
    });
    // Ook bij Taalmodel: een onderbroken download kan verder
    await pg.evaluate(() => { show('aiset'); show('aisetmodel'); }); await pg.waitForTimeout(200);
    ok(!(await pg.isVisible('#ais-chat [data-id="groot"] button:has-text("Verder")')) && await pg.isVisible('#ais-chat [data-id="groot"] button:has-text("Stoppen")'), 'Taalmodel: oudere versie onderbroken: alleen Stoppen');
    ok(!(await pg.isVisible('#ais-chat button:has-text("Via de app")')), 'geen aparte keuze "via de app" meer');
    // Via Instellingen
    await pg.evaluate(() => show('settings')); await pg.waitForTimeout(150);
    ok((await txt('#st-sum-models')).includes('3 modellen'), 'samenvatting bij Instellingen (' + (await txt('#st-sum-models')) + ')');
    await pg.click('button.strow:has-text("Modellen")'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => current === 'models' && Android._md.calls.includes('aiResume')), 'scherm open, onderbroken downloads gaan vanzelf verder');
    // Soorten in een vaste volgorde, met uitleg en of je ze nodig hebt
    const kinds = await pg.$$eval('#md-cats .md-cat', x => x.map(e => e.dataset.kind));
    ok(kinds.join() === 'ai,spraak,zoek,tts', 'vier soorten (' + kinds + ')');
    ok((await txt(cat('ai'))).includes('Nodig voor de assistent: één taalmodel') && (await txt(cat('zoek'))).includes('Niet nodig om de assistent te gebruiken'), 'uitleg: nodig of niet');
    ok((await txt(cat('ai') + ' .md-catst')).includes('In gebruik: Klein en snel · Qwen3 1.7B'), 'welke je gebruikt, met versie (' + (await txt(cat('ai') + ' .md-catst')) + ')');
    // Welke versie je hebt en welke er is
    const klein = cat('ai') + ' .md-linebox[data-line="ai:klein35"]';
    ok((await txt(klein)).includes('Je hebt versie: Qwen3 1.7B · oudere versie') && (await txt(klein)).includes('2 dagen geleden gebruikt'), 'je hebt de oudere versie (' + (await txt(klein)).slice(0, 140) + ')');
    ok((await txt(klein + ' .md-newer')).includes('Nieuwere versie: Qwen3.5 2B') && (await txt(klein + ' .md-newer')).includes('Nederlands'), 'nieuwere versie met waarom');
    // Voorlezen: Android-stem gecontroleerd
    ok(await until(() => /Nederlandse stem \(Spraakservices van Google\)/.test(document.querySelector('#md-cats .md-cat[data-kind="tts"]').textContent)), 'voorlezen: Nederlandse stem gevonden');
    await pg.click(cat('tts') + ' button:has-text("Stem en snelheid")');
    ok(await pg.evaluate(() => Android._md.calls.includes('ttsSettings')), 'instellingen van de stem');
    // Een oudere versie onderbroken: niet verder, wel stoppen; de nieuwste wordt aangeboden
    const groot = cat('ai') + ' .md-linebox[data-line="ai:groot35"]';
    ok((await txt(groot)).includes('Geen verbinding') && (await txt(groot)).includes('24%') && (await txt(groot)).includes('Qwen3 4B (oudere versie)'), 'onderbroken oudere versie met voortgang (' + (await txt(groot)).slice(0, 160) + ')');
    ok(!(await pg.isVisible(groot + ' button:has-text("Verder")')) && await pg.isVisible(groot + ' .md-newer button:has-text("Downloaden")') && (await txt(groot)).includes('Download liever deze'), 'nieuwste aangeboden in plaats van verder met de oude');
    await pg.screenshot({ path: __dirname + '/shots/modellen-' + scheme + '.png', fullPage: true });
    await pg.click(groot + ' button:has-text("Stoppen")'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#modal')).includes('Downloaden stoppen?'), 'stoppen: eerst vragen (600 MB binnen)');
    await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._ai.st.groot.state === 'none') && (await txt(groot)).includes('Nog niet op je telefoon · nieuwste versie: Qwen3.5 4B'), 'oude download weg, nieuwste te downloaden');
    // Niet op wifi: eerst kiezen; wachten houdt Alleen via wifi aan
    await pg.evaluate(() => { Android._md.metered = true; Android._ai.wifiOnly = true; mdRefresh(); });
    await pg.click(groot + ' button:has-text("Downloaden")'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#modal')).includes('Niet op wifi') && (await pg.textContent('#modal')).includes('Alleen via wifi gaat dan uit'), 'niet op wifi: eerst kiezen, met wat er verandert');
    await pg.click('#modal-cancel'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._ai.wifiOnly === true && Android._ai.calls.some(c => c[0] === 'dl' && c[1] === 'groot35')), 'wachten op wifi: wifi-only blijft aan');
    await pg.evaluate(() => { Android._md.metered = false; });
    ok(await until(() => /Je hebt versie: Qwen3.5 4B/.test(document.querySelector('#md-cats .md-linebox[data-line="ai:groot35"]').textContent)), 'groot klaar');
    // Bijwerken naar de nieuwere versie, dan gebruiken en de oude weg
    await pg.click(klein + ' button:has-text("Bijwerken")');
    ok(await pg.evaluate(() => Android._ai.calls.some(c => c[0] === 'dl' && c[1] === 'klein35')), 'bijwerken downloadt de nieuwere versie');
    ok(await until(() => /Je hebt versie: Qwen3.5 2B · nieuwste versie/.test(document.querySelector('#md-cats .md-linebox[data-line="ai:klein35"]').textContent)), 'nieuwste versie op de telefoon');
    ok((await txt(klein)).includes('Ook nog op je telefoon: Qwen3 1.7B'), 'oude versie staat er ook nog');
    await pg.click(klein + ' .md-ver button:has-text("Gebruiken")'); await pg.waitForTimeout(600);
    ok((await pg.textContent('#modal')).includes('Qwen3 1.7B verwijderen?'), 'na gebruiken: oude versie weg?');
    await pg.click('#modal-ok'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => Android._ai.chat === 'klein35' && Android._ai.st.klein.state === 'none') && !(await txt(klein)).includes('Ook nog'), 'nieuwe in gebruik, oude weg');
    // Spraak naar tekst: twee soorten (elk één versie), de ene in gebruik
    ok((await txt(cat('spraak'))).includes('In gebruik: Snel · Whisper base') && (await txt(cat('spraak'))).includes('Nauwkeurig'), 'spraakmodellen');
    ok((await txt(cat('spraak'))).includes('Je hebt versie: Whisper base, OpenAI 2022') && (await txt(cat('spraak'))).includes('Nog niet op je telefoon · versie: Whisper small, OpenAI 2022'), 'spraakmodellen: versie');
    ok((await txt(cat('spraak'))).includes('Motor: whisper.cpp 1.9.5') && (await txt(cat('ai'))).includes('Motor: llama.cpp') && !(await txt(cat('tts'))).includes('Motor:'), 'motorversie per soort');
    // Verwijderen met uitleg
    await pg.click(cat('zoek') + ' button:has-text("Verwijderen")'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#modal')).includes('Slim zoeken werkt dan niet meer'), 'uitleg bij het zoekmodel');
    await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    ok((await txt(cat('zoek') + ' .md-catst')).includes('Niet gedownload (niet nodig)'), 'zoekmodel weg: niet nodig');
    // Een fout die niet vanzelf overgaat: mislukt, met Opnieuw (niet "gaat vanzelf verder")
    await pg.evaluate(() => { Android._ai.st.zoek = { state: 'failed', reason: 'Download mislukt (HTTP 404)', via: 'app', bytes: 0, total: 639150592 }; mdRefresh(); });
    ok((await txt(cat('zoek') + ' .md-catst')).includes('Downloaden mislukt') && await pg.isVisible(cat('zoek') + ' button:has-text("Opnieuw")') && !(await txt(cat('zoek'))).includes('vanzelf'), 'mislukt: Opnieuw, geen belofte dat het vanzelf gaat');
    await pg.evaluate(() => { Android._ai.st.zoek = { state: 'none' }; mdRefresh(); });
    // Voorlezen zonder Nederlandse stem
    await pg.evaluate(() => { Android._md.ttsNext = { state: 'nodutch', engine: 'Samsung TTS' }; mdTtsCheck(); });
    ok(await until(() => /Geen Nederlandse stem/.test(document.querySelector('#md-cats .md-cat[data-kind="tts"]').textContent)), 'geen Nederlandse stem');
    await pg.click(cat('tts') + ' button:has-text("Nederlandse stem toevoegen")');
    ok(await pg.evaluate(() => Android._md.calls.includes('ttsInstall')), 'stem toevoegen');
    // Terug in de app: de app kijkt vanzelf opnieuw
    await pg.evaluate(() => { Android._md.ttsNext = { state: 'ok', engine: 'Samsung TTS' }; onResumeApp(); });
    ok(await until(() => /Nederlandse stem \(Samsung TTS\)/.test(document.querySelector('#md-cats .md-cat[data-kind="tts"]').textContent)), 'na terugkomen opnieuw gekeken');
    // Opruimen
    await pg.click('#md-sum button:has-text("Opruimen")'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#toast')).includes('300 MB vrijgemaakt'), 'opgeruimd');
    // Bereikbaar vanuit Lokale AI en Zoeken
    await pg.evaluate(() => { show('aiset'); }); await pg.waitForTimeout(150);
    await pg.click('#s-aiset button.strow:has-text("Modellen beheren")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'models'), 'vanuit Lokale AI');
    await pg.evaluate(() => { show('search'); }); await pg.fill('#gs-q', 'spraakmodel'); await pg.evaluate(() => gsInput()); await pg.waitForTimeout(400);
    ok((await pg.textContent('#gs-res')).includes('Modellen'), 'te vinden bij Zoeken');
    // Zuivere hulpfuncties
    ok(await pg.evaluate(() => mdAgo(0) === 'nog niet gebruikt' && mdAgo(Date.now() - 3600e3) === 'vandaag gebruikt' && mdAgo(Date.now() - 86400e3 * 1.5) === 'gisteren gebruikt' && mdAgo(Date.now() - 86400e3 * 40) === 'meer dan een maand niet gebruikt'), 'mdAgo');
    ok(await pg.evaluate(() => { const l = mdLines([{ id: 'a', kind: 'ai' }, { id: 'b', kind: 'ai', replaces: 'a' }, { id: 'c', kind: 'ai' }, { id: 'z', kind: 'zoek' }], 'ai'); return l.length === 2 && l[0].map(m => m.id).join() === 'b,a' && l[1][0].id === 'c'; }), 'mdLines: nieuwste eerst');
    ok(await pg.evaluate(() => mdHint({ state: 'paused', reason: 'Wacht op wifi' }, {}).includes('niet op wifi') && mdHint({ state: 'paused', waiting: true }, {}).includes('vanzelf') && mdHint({ state: 'paused', reason: 'Onderbroken' }, { online: true }).includes('Verder')), 'mdHint');
    ok(await pg.evaluate(() => mdLineName({ label: 'Klein en snel (Qwen3.5)' }) === 'Klein en snel' && mdLineName({ label: 'Spraakmodel nauwkeurig' }) === 'Nauwkeurig'), 'mdLineName');
    console.log('errors', errs); await pg.close();
  }
  await b.close();
})();
