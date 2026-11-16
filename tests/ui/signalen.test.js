// Signalen: inbox, één bericht (agenda, notitie, later, voorlezen, lokale AI), bronnen, proef, stille uren, melding openen.
const { chromium } = require('playwright');
const path = require('path');
const W = +process.env.UI_WACHT || 1;
(async () => {
  const browser = await chromium.launch(), errors = [];
  for (const colorScheme of ['light', 'dark']) {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, colorScheme, timezoneId: 'Europe/Amsterdam' });
    page.on('pageerror', e => errors.push(colorScheme + ': ' + e.message));
    const ok = (v, m) => { console.log((v ? '✓ ' : '✗ ') + m); if (!v) errors.push(colorScheme + ': ' + m); };
    const wacht = ms => page.waitForTimeout(ms * W);
    await page.goto('file://' + path.join(__dirname, 'ui/index.html'));
    await wacht(250);

    ok(await page.evaluate(() => HOME_DEFAULT.groups.find(g => g.name === 'Dag & gezondheid').tools.includes('signalen') && TILE_TOOL['tile-signalen'] === 'signalen' && !!TOOLS.signalen && UI_TOOL_ICONS.signalen === 'alert'
      && BACK.signalen === 'home' && BACK.snitem === 'signalen' && BACK.snset === 'signalen' && BACK.snbron === 'snset'), 'aangemeld: groep, tegel, TOOLS, pictogram en terug');
    await page.click('#tile-signalen');
    await wacht(150);
    ok(await page.evaluate(() => current === 'signalen'), 'tegel opent Signalen');
    ok((await page.textContent('#sn-priv')).includes('geen dienst tussen'), 'zichtbaar: Tools kijkt zelf, zonder dienst ertussen');
    ok((await page.textContent('#sn-stand')).includes('Eén bron aan') && (await page.textContent('#sn-stand')).includes('laatst nagekeken'), 'stand: één bron aan, laatst nagekeken');
    ok(await page.isHidden('#sn-meld'), 'geen balk over meldingen als ze aan staan');

    // Inbox: Open toont nieuw en gelezen, niet wat afgehandeld is; tekst van de website blijft tekst
    const open = await page.evaluate(() => [...document.querySelectorAll('#sn-lijst .sn-item')].map(b => ({ t: b.querySelector('b').textContent, nieuw: b.classList.contains('nieuw'), h: b.getBoundingClientRect().height })));
    ok(open.length === 2 && open[0].nieuw && !open[1].nieuw && open.every(x => x.h >= 44), 'Open: twee berichten, het nieuwe gemarkeerd, tikvlak minstens 44 px');
    ok(open[1].t.includes('<img') && await page.evaluate(() => !window.__snXss && !document.querySelector('#sn-lijst img, #sn-lijst script')), 'titel met HTML van de website staat er als tekst');
    await page.click('#sn-filter [data-f="klaar"]');
    ok((await page.textContent('#sn-lijst')).includes('vorige maand') && await page.evaluate(() => document.querySelectorAll('#sn-lijst .sn-item').length === 1), 'Afgehandeld toont alleen het afgehandelde bericht');
    await page.click('#sn-filter [data-f="later"]');
    ok((await page.textContent('#sn-lijst')).includes('Niets voor later'), 'Later is leeg');
    await page.click('#sn-filter [data-f="open"]');

    // Eén bericht: datum uit het bericht, gelezen bij openen
    await page.click('#sn-lijst .sn-item.nieuw');
    await wacht(120);
    ok(await page.evaluate(() => current === 'snitem' && Android._sn.items[0].status === 'gelezen'), 'bericht opent en telt als gelezen');
    ok((await page.textContent('#sni-wanneer')).includes('Datum in het bericht') && /14[:.]00/.test(await page.textContent('#sni-wanneer')), 'datum en tijd uit het bericht staan erbij');
    ok(await page.isVisible('#sni-agenda') && (await page.textContent('#s-snitem')).includes('Kijk op de website zelf of het klopt'), 'knop voor de agenda, met de waarschuwing dat de tekst van de website komt');

    // In agenda zetten: afspraak met de datum en tijd, één keer
    const voor = await page.evaluate(() => Android._agInit().items.length);
    await page.click('#sni-agenda');
    await wacht(100);
    const afspraak = await page.evaluate(() => { const st = Android._agInit(), it = st.items[st.items.length - 1]; return { n: st.items.length, titel: it.titel, dag: it.dag, van: it.van, tot: it.tot, heel: it.heleDag, notitie: it.notitie, verwacht: rgDayAdd(rgToday(), 5) }; });
    ok(afspraak.n === voor + 1 && afspraak.titel.includes('Vrijlating zeehond') && afspraak.dag === afspraak.verwacht && afspraak.van === '14:00' && afspraak.tot === '15:00' && !afspraak.heel && afspraak.notitie.startsWith('https://'), 'afspraak in de agenda: titel, dag, 14:00 tot 15:00, link als notitie (' + JSON.stringify(afspraak) + ')');
    ok(await page.isDisabled('#sni-agenda') && (await page.textContent('#sni-agenda')).includes('Staat in je agenda') && await page.evaluate(() => Android._sn.items[0].inAgenda === true), 'daarna: staat in je agenda, niet nog een keer');

    // Notitie maken
    const notities = await page.evaluate(() => notesGet().length);
    await page.click('#sni-notitie');
    await wacht(80);
    ok(await page.evaluate(n => { const l = notesGet(); return l.length === n + 1 && l[l.length - 1].title.includes('Vrijlating zeehond') && l[l.length - 1].text.includes('https://www.aseal.nl/nieuws/vrijlating-zeehond/') && current === 'snitem'; }, notities), 'notitie gemaakt met titel, tekst en link; je blijft bij het bericht');

    // Voorlezen gaat via de gedeelde voorleesfunctie
    await page.evaluate(() => { window.__snLees = null; const echt = spText; spText = (tekst, bron) => { window.__snLees = { tekst, bron }; }; sniLees(); spText = echt; });
    ok(await page.evaluate(() => window.__snLees && window.__snLees.tekst.includes('Vrijlating zeehond') && window.__snLees.tekst.includes('strandopgang')), 'voorlezen: titel en tekst naar de gedeelde voorleesfunctie');

    // Lokale AI: samenvatting als tekst, gestopt bij weggaan
    await page.click('#sni-ai');
    ok(await page.isVisible('#sni-aikaart') && (await page.textContent('#sni-aitekst')).includes('leest het bericht'), 'samenvatten: zichtbaar dat de lokale AI bezig is');
    await wacht(160);
    ok((await page.textContent('#sni-aitekst')).includes('zaterdag om 14.00 uur') && (await page.textContent('#sni-aikaart')).includes('kan zich vergissen'), 'samenvatting staat er, met de waarschuwing');
    await page.evaluate(() => { Android._sn.traag = 5000; sniAi(); goBack(); });
    await wacht(80);
    ok(await page.evaluate(() => Android._sn.aiGestopt === true && current === 'signalen'), 'weg van het bericht: de samenvatting stopt');
    await page.evaluate(() => { Android._sn.traag = 60; });

    // Later herinneren: keuzes, daarna onder Later
    await page.click('#sn-lijst .sn-item');
    await wacht(100);
    await page.click('#sni-later');
    await wacht(100);
    const keuzes = await page.evaluate(() => [...document.querySelectorAll('#sheet-acts button')].map(b => b.textContent));
    ok(keuzes.includes('Over een uur') && keuzes.some(k => k.startsWith('Morgen om')) && keuzes.includes('Over een week') && keuzes.some(k => k.startsWith('De dag ervoor')), 'later: over een uur, morgen, over een week en de dag ervoor (' + keuzes.join(' | ') + ')');
    await page.click('#sheet-acts button:has-text("Over een uur")');
    await wacht(120);
    ok(await page.evaluate(() => { const it = Android._sn.items[0]; return current === 'signalen' && it.later > Date.now() + 3500000 && it.gemeld === false; }), 'over een uur: herinnering gezet, terug naar de lijst');
    ok(await page.evaluate(() => document.querySelectorAll('#sn-lijst .sn-item').length === 1), 'het bericht staat niet meer onder Open');
    await page.click('#sn-filter [data-f="later"]');
    ok((await page.textContent('#sn-lijst')).includes('Herinnering:') && (await page.textContent('#sn-lijst')).includes('Vrijlating zeehond'), 'onder Later, met het moment van de herinnering');

    // Afgehandeld en terugzetten, verwijderen
    await page.click('#sn-lijst .sn-item');
    await wacht(100);
    await page.click('#sni-klaar');
    await wacht(100);
    ok(await page.evaluate(() => Android._sn.items[0].status === 'klaar' && Android._sn.items[0].later === 0), 'afgehandeld: ook de herinnering vervalt');
    await page.click('#sn-filter [data-f="klaar"]');
    await page.click('#sn-lijst .sn-item');
    await wacht(100);
    ok((await page.textContent('#sni-klaar')).includes('Terug naar Open'), 'een afgehandeld bericht kan terug');
    await page.click('#sni-klaar');
    await wacht(100);
    ok(await page.evaluate(() => Android._sn.items[0].status === 'gelezen'), 'terug naar Open');
    await page.click('#sn-filter [data-f="open"]');
    await page.evaluate(() => snOpen('i0000000000000002'));
    await wacht(100);
    ok(await page.isHidden('#sni-agenda') && await page.isHidden('#sni-wanneer'), 'bericht zonder datum: geen knop voor de agenda');
    ok(await page.evaluate(() => $('#sni-titel').textContent.includes('<img') && $('#sni-tekst').textContent.includes('<script>') && !window.__snXss && !document.querySelector('#s-snitem .card img, #s-snitem .card script')), 'titel en tekst van de website blijven tekst');
    await page.click('#sni-menu');
    await wacht(80);
    await page.click('#sheet-acts button:has-text("Verwijderen")');
    await wacht(100);
    ok(await page.evaluate(() => Android._sn.items.length === 2 && current === 'signalen'), 'verwijderen haalt het bericht weg');

    // Nu nakijken: zichtbaar bezig, daarna het nieuwe bericht
    await page.evaluate(() => { Android._sn.traag = 300; Android._sn.nieuw.push({ id: 'i00000000000000aa', bron: 's00000001', bronNaam: 'A Seal: vrijlatingen', titel: 'Vrijlating twee zeehonden', tekst: 'Nieuw.', link: 'https://www.aseal.nl/nieuws/twee/', gevonden: Date.now(), dag: '', tijd: '', status: 'nieuw', later: 0, gemeld: false }); });
    await page.click('#sn-nu');
    ok((await page.textContent('#sn-stand')).includes('Bezig met nakijken') && await page.evaluate(() => $('#sn-stand').classList.contains('wacht')), 'nu nakijken: zichtbaar dat hij bezig is');
    ok(await page.evaluate(() => { snNu(); return $('#toast').textContent.includes('al bezig'); }), 'twee keer tegelijk kan niet');
    await wacht(450);
    ok((await page.textContent('#sn-lijst')).includes('Vrijlating twee zeehonden') && (await page.textContent('#toast')).includes('Eén nieuw bericht') && !(await page.textContent('#sn-stand')).includes('Bezig'), 'het nieuwe bericht staat bovenaan');
    await page.evaluate(() => { Android._sn.traag = 60; });

    // Instellingen: bronnen, stille uren, dagoverzicht
    await page.click('#sn-set');
    await wacht(120);
    ok(await page.evaluate(() => current === 'snset') && (await page.textContent('#sns-bronnen')).includes('A Seal') && (await page.textContent('#sns-bronnen')).includes('elke 6 uur') && (await page.textContent('#sns-bronnen')).includes('meteen een melding'), 'instellingen: de bron met hoe vaak en hoe gemeld');
    ok(await page.evaluate(() => $('#sns-stil').getAttribute('aria-checked') === 'true' && $('#sns-van').value === '22:00' && $('#sns-tot').value === '07:30' && $('#sns-overzicht').value === '18:00'), 'stille uren 22:00 tot 07:30 en het dagoverzicht om 18:00 als begin');
    await page.fill('#sns-van', '23:00');
    await page.dispatchEvent('#sns-van', 'change');
    await page.click('#sns-stil');
    ok(await page.evaluate(() => Android._sn.inst.stilVan === '23:00' && Android._sn.inst.stilAan === false), 'stille uren aanpassen en uitzetten wordt bewaard');

    // Website toevoegen met het sjabloon, proef, bewaren
    await page.click('#sns-nieuw');
    await wacht(120);
    ok(await page.evaluate(() => current === 'snbron') && (await page.textContent('#snb-h1')).includes('Website toevoegen') && await page.isVisible('#snb-sjabloonkaart') && await page.isHidden('#snb-menu'), 'nieuwe bron: sjabloon te kiezen, geen verwijderen');
    ok(await page.evaluate(() => $('#snb-elke').value === '6' && $('#snb-wifi').getAttribute('aria-checked') === 'true' && $('#snb-melden').value === 'direct'), 'begin: elke 6 uur, alleen via wifi, meteen een melding');
    await page.click('#snb-bewaar');
    ok((await page.textContent('#toast')).includes('https-adres'), 'zonder adres: duidelijke fout');
    await page.selectOption('#snb-sjabloon', 'aseal');
    ok(await page.evaluate(() => $('#snb-url').value === 'https://www.aseal.nl/nieuws' && $('#snb-woorden').value === 'vrijlating, vrijgelaten' && $('#snb-alleen').value === 'https://www.aseal.nl/nieuws/'), 'sjabloon A Seal vult adres en trefwoorden in');
    await page.click('#snb-proef');
    ok((await page.textContent('#snb-proefstand')).includes('wordt opgehaald'), 'proef: zichtbaar dat de pagina wordt opgehaald');
    await wacht(160);
    ok((await page.textContent('#snb-proefstand')).includes('2 berichten passen') && (await page.textContent('#snb-proeflijst')).includes('14 november 2026') && !(await page.textContent('#snb-proeflijst')).includes('Openingstijden'), 'proef: alleen wat bij de trefwoorden past, met de datum');
    ok(await page.evaluate(() => Android._sn.bronnen.length === 1), 'een proef bewaart niets');
    await page.fill('#snb-url', 'https://kapot.example.nl/nieuws');
    await page.click('#snb-proef');
    await wacht(160);
    ok((await page.textContent('#snb-proefstand')).includes('404'), 'proef op een pagina die niet werkt: de fout');
    await page.fill('#snb-url', 'https://www.aseal.nl/activiteiten');
    await page.fill('#snb-naam', 'Activiteiten');
    await page.fill('#snb-woorden', 'open dag, Rondleiding');
    await page.selectOption('#snb-elke', '24');
    await page.selectOption('#snb-melden', 'overzicht');
    await page.click('#snb-belangrijk');
    await page.click('#snb-bewaar');
    await wacht(200);
    const nieuw = await page.evaluate(() => { const b = Android._sn.bronnen[1]; return b && { naam: b.naam, w: b.woorden.join('|'), elke: b.elke, melden: b.melden, bel: b.belangrijk, scan: Android._sn.scans.slice(-1)[0] === b.id, scherm: current }; });
    ok(nieuw && nieuw.naam === 'Activiteiten' && nieuw.w === 'open dag|Rondleiding' && nieuw.elke === 24 && nieuw.melden === 'overzicht' && nieuw.bel && nieuw.scherm === 'snset', 'bron bewaard met trefwoorden, elke dag, dagoverzicht en belangrijk');
    ok(nieuw && nieuw.scan, 'na het bewaren kijkt Tools de nieuwe bron meteen een eerste keer na');
    ok((await page.textContent('#sns-bronnen')).includes('Activiteiten') && (await page.textContent('#sns-bronnen')).includes('in het dagoverzicht'), 'de nieuwe bron staat in de lijst');

    // Bron wijzigen, uitzetten en verwijderen
    await page.click('#sns-bronnen .strow:has-text("Activiteiten")');
    await wacht(120);
    ok(await page.evaluate(() => $('#snb-naam').value === 'Activiteiten' && $('#snb-belangrijk').getAttribute('aria-checked') === 'true') && await page.isHidden('#snb-sjabloonkaart') && await page.isVisible('#snb-menu'), 'bron openen: de bewaarde waarden, geen sjabloon');
    await page.click('#snb-aan');
    await page.click('#snb-bewaar');
    await wacht(150);
    ok(await page.evaluate(() => Android._sn.bronnen[1].aan === false) && (await page.textContent('#sns-bronnen .strow:has-text("Activiteiten")')).includes('Uit'), 'uitzetten: de bron staat op Uit');
    await page.click('#sns-bronnen .strow:has-text("Activiteiten")');
    await wacht(120);
    await page.click('#snb-menu');
    await wacht(80);
    await page.click('#sheet-acts button:has-text("Bron verwijderen")');
    await wacht(120);
    ok((await page.textContent('#modal, #confirm, body')).includes('blijven staan'), 'verwijderen vraagt het eerst en zegt dat de berichten blijven');
    await page.evaluate(() => { const b = [...document.querySelectorAll('button')].filter(x => x.getClientRects().length && x.textContent.trim() === 'Verwijderen').pop(); b.click(); });
    await wacht(150);
    ok(await page.evaluate(() => Android._sn.bronnen.length === 1 && current === 'snset'), 'bron verwijderd');

    // Een bron die niet werkt: zichtbaar op het overzicht en bij de bron
    await page.evaluate(() => { Android._sn.bronnen[0].fout = 'De website antwoordt met 404'; show('signalen'); });
    await wacht(100);
    ok((await page.textContent('#sn-stand')).includes('kon niet nagekeken worden'), 'overzicht zegt dat een bron niet nagekeken kon worden');
    await page.evaluate(() => openTool('signalen-bron:s00000001'));
    await wacht(150);
    ok(await page.evaluate(() => current === 'snbron') && (await page.textContent('#snb-stand')).includes('404'), 'melding van een bron die niet werkt opent die bron, met de fout');
    await page.evaluate(() => { goBack(); return current; });
    ok(await page.evaluate(() => current === 'snset'), 'terug vanaf de bron: de instellingen');
    await page.evaluate(() => { Android._sn.bronnen[0].fout = ''; });

    // Een melding van een bericht opent dat bericht
    await page.evaluate(() => { show('home'); openTool('signalen:i00000000000000aa'); });
    await wacht(150);
    ok(await page.evaluate(() => current === 'snitem') && (await page.textContent('#sni-titel')).includes('twee zeehonden'), 'melding van een bericht opent dat bericht');
    await page.evaluate(() => { show('home'); openTool('signalen:bestaatniet'); });
    await wacht(120);
    ok(await page.evaluate(() => current === 'signalen'), 'melding van een bericht dat weg is: de lijst');

    // Meldingen niet toegestaan: een balk; alles uit in de app: in de stand
    await page.evaluate(() => { Android._sn.perm = false; show('home'); show('signalen'); });
    await wacht(100);
    ok(await page.isVisible('#sn-meld') && (await page.textContent('#sn-meld')).includes('Meldingen staan uit'), 'zonder toestemming voor meldingen: een balk');
    await page.evaluate(() => { Android._sn.perm = true; Android._sn.gedempt = true; show('home'); show('signalen'); });
    await wacht(100);
    ok(await page.isHidden('#sn-meld') && (await page.textContent('#sn-stand')).includes('alle meldingen van Tools staan uit'), 'alle meldingen uit in de app: dat staat in de stand');

    // Geen bronnen: uitnodiging om een website toe te voegen
    await page.evaluate(() => { Android._sn.gedempt = false; Android._sn.bronnen = []; Android._sn.items = []; show('home'); show('signalen'); });
    await wacht(100);
    ok((await page.textContent('#sn-lijst')).includes('Volg een website') && await page.isVisible('#sn-lijst button:has-text("Website toevoegen")') && (await page.textContent('#sn-stand')).includes('Je volgt nog niets'), 'leeg: uitleg en één knop om een website toe te voegen');
    await page.evaluate(() => snNu());
    ok((await page.textContent('#toast')).includes('Voeg eerst een website toe'), 'nu nakijken zonder bronnen: eerst een website toevoegen');
    await page.close();
  }
  await browser.close();
  console.log('errors', JSON.stringify(errors));
  process.exit(errors.length ? 1 : 0);
})();
