// Modellen beheren: alles op één plek (taal-, zoek- en spraakmodellen), opslag, verwijderen, gebruiken,
// een download die blijft wachten op internet via de app laten downloaden, opruimen.
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

    // Stand: klein (in gebruik) en het zoekmodel op de telefoon, spraakmodel snel; groot blijft wachten op internet
    await pg.evaluate(() => {
      const A = Android._ai; A.st.klein = { state: 'ready' }; A.st.zoek = { state: 'ready' }; A.chat = 'klein';
      A.st.groot = { state: 'paused', reason: 'Wacht op internet', bytes: 6e8, total: 2497280256 };
      Android._tx.models.base = true; Android._tx.model = 'base';
      Android._md.last['ai:klein'] = Date.now() - 2 * 86400000; Android._md.leftovers = 3e8;
    });
    // Ook bij Taalmodel: een download die wacht, kan via de app
    await pg.evaluate(() => { show('aiset'); show('aisetmodel'); }); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#ais-chat [data-id="groot"] button:has-text("Via de app")'), 'Taalmodel: Via de app bij een wachtende download');
    ok(await pg.isVisible('#s-aisetmodel button:has-text("Alle modellen beheren")'), 'Taalmodel: naar Modellen');
    // Via Instellingen
    await pg.evaluate(() => show('settings')); await pg.waitForTimeout(150);
    ok((await txt('#st-sum-models')).includes('3 modellen') && (await txt('#st-sum-models')).includes('1 aan het downloaden'), 'samenvatting bij Instellingen (' + (await txt('#st-sum-models')) + ')');
    await pg.click('button.strow:has-text("Modellen")'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current === 'models'), 'scherm Modellen open');
    const sum = await txt('#md-sum');
    ok(/Modellen: 2,0 GB/.test(sum) && sum.includes('Vrij op je telefoon: 20,0 GB'), 'opslag (' + sum + ')');
    ok(sum.includes('300 MB'), 'resten van oude downloads');
    const have = await txt('#md-have');
    ok(have.includes('Klein en snel') && have.includes('Slim zoeken') && have.includes('Spraakmodel snel'), 'alles wat op de telefoon staat (' + have.slice(0, 160) + ')');
    ok(have.includes('2 dagen geleden gebruikt') && have.includes('nog niet gebruikt'), 'laatst gebruikt');
    ok(await pg.isVisible('#md-have [data-key="ai:klein"] .aisel') && await pg.isVisible('#md-have [data-key="spraak:base"] .aisel'), 'in gebruik gemarkeerd');
    // De download die op internet blijft wachten: uitleg en Via de app downloaden
    const busy = await txt('#md-busy');
    ok(busy.includes('Wacht op internet') && busy.includes('24%') && busy.includes('downloadbeheer van Android blijft wachten'), 'wachten op internet met uitleg (' + busy.slice(0, 200) + ')');
    await pg.screenshot({ path: __dirname + '/shots/modellen-' + scheme + '.png', fullPage: true });
    await pg.click('#md-busy button:has-text("Via de app downloaden")');
    ok(await pg.evaluate(() => Android._ai.calls.some(c => c[0] === 'dlApp' && c[1] === 'groot')), 'via de app gestart');
    ok(await until(() => /via de app/.test(document.querySelector('#md-busy').textContent)), 'voortgang: via de app');
    ok(await until(() => document.querySelector('#md-have [data-key="ai:groot"]') !== null), 'daarna op de telefoon');
    // Niet op wifi: eerst vragen
    await pg.evaluate(() => { Android._ai.st.groot35 = { state: 'paused', reason: 'Wacht op internet', bytes: 0, total: 3e9 }; Android._md.metered = true; Android._ai.wifiOnly = true; mdRefresh(); });
    await pg.click('#md-busy button:has-text("Via de app downloaden")'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#modal')).includes('Via mobiel internet?'), 'op mobiel internet: eerst vragen');
    await pg.click('#modal button:has-text("Alleen via wifi uitzetten")'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._ai.wifiOnly === false && Android._ai.calls.some(c => c[0] === 'dlApp' && c[1] === 'groot35')), 'wifi-only uit en via de app gestart');
    await pg.evaluate(() => { Android._md.metered = false; });
    ok(await until(() => document.querySelector('#md-have [data-key="ai:groot35"]') !== null), 'groot35 klaar');
    // Gebruiken: ander taalmodel en ander spraakmodel
    await pg.click('#md-have [data-key="ai:groot"] button:has-text("Gebruiken")'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._ai.chat === 'groot') && await pg.isVisible('#md-have [data-key="ai:groot"] .aisel'), 'ander taalmodel in gebruik');
    // Verwijderen met uitleg over wat er dan niet meer werkt
    await pg.click('#md-have [data-key="ai:zoek"] button:has-text("Verwijderen")'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#modal')).includes('Slim zoeken werkt dan niet meer'), 'uitleg bij het zoekmodel');
    await pg.click('#modal button:has-text("Verwijderen")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._ai.st.zoek.state === 'none') && (await txt('#md-get')).includes('Slim zoeken'), 'zoekmodel weg, staat bij Beschikbaar');
    await pg.click('#md-have [data-key="spraak:base"] button:has-text("Verwijderen")'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#modal')).includes('schrijven dan pas weer uit'), 'uitleg bij het enige spraakmodel');
    await pg.click('#modal button:has-text("Verwijderen")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => !Android._tx.models.base), 'spraakmodel weg');
    // Spraakmodel downloaden vanaf hier
    await pg.click('#md-get [data-key="spraak:small"] button:has-text("Downloaden")');
    ok(await until(() => /Spraakmodel nauwkeurig/.test(document.querySelector('#md-busy').textContent) || document.querySelector('#md-have [data-key="spraak:small"]')), 'spraakmodel downloaden');
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
    ok(await pg.evaluate(() => mdHint({ state: 'paused', reason: 'Wacht op internet' }, { online: false }).includes('geen internet') && mdHint({ state: 'paused', reason: 'Wacht op wifi' }, {}).includes('niet op wifi') && mdHint({ state: 'paused', via: 'app', reason: 'Onderbroken' }, {}).includes('Verder')), 'mdHint');
    console.log('errors', errs); await pg.close();
  }
  await b.close();
})();
