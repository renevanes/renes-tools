// WhatsApp backup: indeling (schermcheck 2026-11-13). Zonder toegang of zonder WhatsApp-map is "Alles back-uppen" niet te
// kiezen (de kaart erboven is de volgende stap); de uitleg over wat er gekopieerd wordt staat onder Hoe werkt dit?, de
// waarschuwing dat media onversleuteld staan blijft zichtbaar; zonder backup-map opent Starten de mapkiezer (ook in het
// Engels); Selectie meldt per onderdeel aangevinkt of niet en start niet zonder keuze; de mapnaam blijft onvertaald.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 360, height: 640 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const naar = async s => { await pg.evaluate(s => show(s), s); await pg.waitForTimeout(150); };
    await naar('wa');
    // Zonder toegang: de toegangskaart, en de hoofdknop is niet te kiezen
    ok(await pg.isVisible('#wa-perm') && await pg.isDisabled('#wa-all'), scheme + ': zonder toegang: eerst Toegang geven, Alles back-uppen uit');
    await pg.click('#wa-perm button'); await pg.waitForFunction(() => Android._wa.info.filesAccess && !$('#wa-all').disabled, null, { timeout: 5000 }).catch(() => {});
    ok(!(await pg.isVisible('#wa-perm')) && !(await pg.isDisabled('#wa-all')), scheme + ': na toegang: Alles back-uppen te kiezen');
    // Geen WhatsApp-map gevonden: ook niet te kiezen
    await pg.evaluate(() => { Android._wa._src = Android._wa.info.sources; Android._wa.info.sources = []; renderWa(false); });
    ok(await pg.isVisible('#wa-nosrc') && await pg.isDisabled('#wa-all'), scheme + ': geen WhatsApp-map: melding, knop uit');
    await pg.evaluate(() => { Android._wa.info.sources = Android._wa._src; renderWa(false); });
    // Uitleg ingeklapt, waarschuwing zichtbaar
    ok(await pg.evaluate(() => { const p = [...document.querySelectorAll('#s-wa p')].find(x => /nooit iets verwijderd/.test(x.textContent)); const d = p && p.closest('details.help'); return !!d && !d.open; }), scheme + ': "alleen nieuwe bestanden" onder Hoe werkt dit?');
    ok(await pg.isVisible('#wa-plain-hint'), scheme + ': waarschuwing onversleuteld blijft zichtbaar');
    // Zonder backup-map: Starten laat eerst een map kiezen en start nog niets
    await pg.click('#wa-all'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => Android._wa.info.dest && !Android._wa.st.running), scheme + ': zonder map: eerst de mapkiezer, geen backup');
    ok(/USB-stick Rene/.test(await pg.textContent('#wa-dest')) && await pg.textContent('#wa-dest-btn') === 'Andere map', scheme + ': gekozen map in de statuskaart');
    // Op 360×640 staat de hoofdknop zonder scrollen in beeld
    const r = await pg.evaluate(() => { const x = $('#wa-all').getBoundingClientRect(); return { bottom: x.bottom, h: innerHeight, sy: scrollY }; });
    ok(r.sy === 0 && r.bottom <= r.h, scheme + ': Alles back-uppen zonder scrollen in beeld (onderkant ' + Math.round(r.bottom) + ')');
    // De grootte breekt niet midden in "9,4 GB"
    await pg.waitForFunction(() => /GB/.test($('#wa-all-size').textContent), null, { timeout: 5000 }).catch(() => {});
    ok(await pg.evaluate(() => { const s = $('#wa-all-size span'); return !!s && getComputedStyle(s).whiteSpace === 'nowrap' && /GB in/.test(s.textContent); }), scheme + ': grootte als één geheel');

    // Selectie: aanvinkvakjes voor TalkBack, niet starten zonder keuze
    await naar('wasel');
    const vakjes = () => pg.evaluate(() => [...document.querySelectorAll('#wa-cats .cat')].map(x => x.getAttribute('role') + ':' + x.getAttribute('aria-checked') + ':' + x.classList.contains('on')));
    ok((await vakjes()).every(v => /^checkbox:(true:true|false:false)$/.test(v)), scheme + ': onderdelen zijn aanvinkvakjes met hun stand');
    ok(await pg.getAttribute('#wa-cats', 'role') === 'group', scheme + ': onderdelen als groep');
    await pg.click('#wa-cats .cat >> nth=1'); await pg.waitForTimeout(80);
    ok(await pg.getAttribute('#wa-cats .cat >> nth=1', 'aria-checked') === 'false', scheme + ': uitvinken meldt de nieuwe stand');
    for (let i = 0; i < 9; i++) if (await pg.getAttribute(`#wa-cats .cat >> nth=${i}`, 'aria-checked') === 'true') { await pg.click(`#wa-cats .cat >> nth=${i}`); await pg.waitForTimeout(40); }
    ok(await pg.isDisabled('#wa-sel-run') && await pg.evaluate(() => $('#wa-sum-sel').textContent === '0 van 9'), scheme + ': niets gekozen: Selectie back-uppen uit');
    await pg.click('#wa-cats .cat >> nth=0'); await pg.waitForTimeout(80);
    ok(!(await pg.isDisabled('#wa-sel-run')), scheme + ': iets gekozen: weer te kiezen');

    // Automatisch: duidelijke kop boven de keuze, als groep
    await naar('waauto');
    ok(/^Wat gaat er mee/.test(await pg.textContent('#wa-automode-h')) && await pg.getAttribute('#wa-automode', 'role') === 'group', scheme + ': Automatisch: "Wat gaat er mee" als groep');
    await pg.screenshot({ path: __dirname + '/shots/whatsapp-indeling-' + scheme + '.png' });

    // Engels: zonder map ook de mapkiezer; de mapnaam (gegevens) blijft zoals hij is
    await pg.evaluate(() => { Object.assign(Android._wa.info, { dest: false, destName: null }); rgSave({ lang: 'en' }); }); await pg.waitForTimeout(150);
    await naar('home'); await naar('wa');
    ok(await pg.textContent('#wa-dest') === 'Not chosen yet' && await pg.textContent('#wa-dest-btn') === 'Choose folder', scheme + ': Engels: nog geen map');
    await pg.evaluate(() => { Android.waPickFolder = function(){ Object.assign(this._wa.info, { dest: true, destName: 'Instellingen' }); setTimeout(() => onWaChanged(), 100); }; });
    await pg.click('#wa-all'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => Android._wa.info.dest && !Android._wa.st.running), scheme + ': Engels: zonder map ook eerst de mapkiezer');
    ok(await pg.textContent('#wa-dest') === 'Instellingen' && await pg.textContent('#wa-dest-btn') === 'Other folder', scheme + ': Engels: mapnaam onvertaald');
    await naar('waauto');
    ok(/^What is included/.test(await pg.textContent('#wa-automode-h')), scheme + ': Engels: kop bij Automatisch vertaald');
    await pg.close();
  }
  console.log('errors', errs); await b.close(); process.exit(errs.length ? 1 : 0);
})();
