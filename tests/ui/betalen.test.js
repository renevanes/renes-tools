// Betalen: een rekening toevoegen (foto, bestand, zelf), het voorstel nalopen, QR-code (betaallink en SEPA-QR),
// klaarzetten bij de bank, kopiëren, betaald zetten, instellingen. Tekst van een rekening blijft altijd tekst.
const { chromium } = require('playwright');
const path = require('path');
const W = +process.env.UI_WACHT || 1;
const QR_LINK = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAJQAAACUAQAAAABdRz15AAABLUlEQVR4Xu2WsW3FQAxDqSA9bwPvP5Y3oCZgwDtXCVLJ5T+4kA34gZZEyWX8Pv315xHwefZvDqpqJWOr1gmHuaIN9CpZO5zyUAsFYfUO5zwAvArNp08m3/v9MALr69xMeHnXQqNvcIczXhmFkPZpgJr6w96qGv2Ek++FTYAmCDmRNeTZcLCGAh7zBNEyzPTzXB9sCYFR0pwnkoIO64X8gfKuCMA38md5YyLtDX2GUgoYEF/IX1iyrFzT/MVvbpau4zZg5rc0skJJdXcmZ/28vSX5yrxaWPdwvkTf6b7YDcTcbzlUJgL3QJjOg/Tx8Qcjb8xjRhZjjTh4qC/1qBWBd6EL0/327KNOMbJGXuKh2erANNtH0Wcsl1aKMt6X2H8Y3CQ5A39W3/r8T472+Q8jyPEvHTqYvAAAAABJRU5ErkJggg==';
const QR_EPC = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAALQAAAC0AQAAAAAVtjufAAABtklEQVR4Xu1XMY7DMAyjitvpH+T/z+oPqBfoQCXdehvHc4sCzsCoJEXLNfi2+vX1MfD//F5BHqrqFFDVVdW7jeJzBkQPMdS12xz+D4AiutQqqXC8zeE/PAvnYE5zFOff6wg8R1SfLL75GRjZrJR/B0F9a1CAsT+rASrWv5h7URDBefYK+v/Y94bXoKdxkv7fejEGJyWOZshc/cY3M/blfgVMFl/j4ocQSRfPydYvV20B1qQ3Syl9zY/oN9AWEifOD+hkI5YgUU64WP2v1VSNloQ30Fe0fzF2JJzJwkhcIXL1G1n3S/RpZWTxXf+mA0yRNc7ikzAuiNXWvZDlx7AEnT9ek+5fQlZ4ybFb3RLB/nJjGXxlgGZJCuLb/wY1RZs+YXxn2iaERd4ozdZv/7j21WDkgyDKj62pjU0tO4jzs65Zfzrqtp2D+hoaWGrsT/hvJPHvE3g/e7ak88eE3+PVw1BS35fVHMCj57zRDrp3cL79zM+ee9Bi947PsfuF0+wxjzxC+DBI5qfrd9v28QnWEFieplP+fObnvqYJXEBzKsvPHi0OHfPkBkjmf/3fr9P35a/rL7/9ApiahZPCsySpAAAAAElFTkSuQmCC';
(async () => {
  const browser = await chromium.launch(), errors = [];
  for (const colorScheme of ['light', 'dark']) {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, colorScheme, timezoneId: 'Europe/Amsterdam' });
    page.on('pageerror', e => errors.push(colorScheme + ': ' + e.message));
    const ok = (v, m) => { console.log((v ? '✓ ' : '✗ ') + m); if (!v) errors.push(colorScheme + ': ' + m); };
    const wacht = ms => page.waitForTimeout(ms * W);
    // Een camera voor de test: een getekend vlak als beeld
    await page.addInitScript(() => {
      window.__testCamera = true;
      const md = navigator.mediaDevices || {};
      md.getUserMedia = async () => { const c = document.createElement('canvas'); c.width = 640; c.height = 480; const x = c.getContext('2d'); let n = 0;
        const teken = () => { x.fillStyle = '#fff'; x.fillRect(0, 0, 640, 480); x.fillStyle = '#000'; x.fillText('rekening ' + (n++), 20, 40); }; teken(); window.__camTimer = setInterval(teken, 100);
        return c.captureStream(10); };
      if (!navigator.mediaDevices) Object.defineProperty(navigator, 'mediaDevices', { value: md });
    });
    await page.goto('file://' + path.join(__dirname, 'ui/index.html'));
    await wacht(250);
    const sheet = async tekst => { await page.locator('#sheet-acts button', { hasText: tekst }).first().click(); };
    const tekst = sel => page.textContent(sel);

    ok(await page.evaluate(() => HOME_DEFAULT.groups.find(g => g.name === 'Documenten & geld').tools.includes('betalen') && TILE_TOOL['tile-betalen'] === 'betalen' && !!TOOLS.betalen && UI_TOOL_ICONS.betalen === 'sign'
      && BACK.betalen === 'home' && BACK.betaling === 'betalen' && BACK.betalenset === 'betalen' && SOLO_TOOLS.includes('betalen')), 'aangemeld: groep, tegel, TOOLS, pictogram, terug en losse app');
    await page.click('#tile-betalen'); await wacht(150);
    ok(await page.evaluate(() => current === 'betalen'), 'tegel opent Betalen');
    ok((await tekst('#bt-priv')).includes('betaalt nooit zelf') && (await tekst('#bt-priv')).includes('niet bewaard'), 'zichtbaar: Tools betaalt nooit zelf en bewaart de rekening niet');
    let rijen = await page.evaluate(() => [...document.querySelectorAll('#bt-lijst .sn-item')].map(b => b.textContent));
    ok(rijen.length === 1 && rijen[0].includes('Waterbedrijf Test') && /45[,.]10/.test(rijen[0]) && rijen[0].includes('uiterlijk'), 'lijst: naam, bedrag en uiterste datum: ' + rijen);

    // ---- Bestand kiezen: het voorstel komt in een nieuwe betaling
    await page.click('#bt-nieuw'); await wacht(120);
    const keuzes = await page.evaluate(() => [...document.querySelectorAll('#sheet-acts button')].map(b => b.textContent));
    ok(keuzes[0] === 'Foto maken' && keuzes[1] === 'Zelf invullen' && keuzes.includes('Bestand op de telefoon'), 'toevoegen: foto, zelf invullen of een bestand: ' + keuzes);
    await sheet('Bestand op de telefoon'); await wacht(40);
    ok((await tekst('#bt-stand')).includes('lezen'), 'tijdens het lezen staat er wat er gebeurt');
    await page.waitForFunction(() => current === 'betaling'); await wacht(120);
    ok(await page.inputValue('#btd-naam') === 'Energie Voorbeeld B.V.' && await page.inputValue('#btd-iban') === 'NL91 ABNA 0417 1643 00' && await page.inputValue('#btd-bedrag') === '123,45'
      && await page.inputValue('#btd-omschrijving') === 'Factuur F2026-0042' && (await page.inputValue('#btd-verval')).length === 10, 'het voorstel staat in de velden');
    ok(await page.isVisible('#btd-bron') && (await tekst('#btd-bron')).includes('Kijk elk veld na'), 'zichtbaar: gelezen uit een bestand, nakijken');
    ok(await page.isVisible('#btd-nieuw'), 'nieuwe ontvanger: waarschuwing');
    ok(await page.evaluate(() => [...document.querySelectorAll('#btd-ibans button')].map(b => b.textContent).join()) === 'NL39 RABO 0300 0652 64', 'het andere gelezen rekeningnummer is te kiezen');
    ok((await tekst('#btd-klaar')).includes('Rabobank') && !(await page.isDisabled('#btd-klaar')) && await page.isHidden('#btd-link') && await page.isHidden('#btd-linkkaart'), 'Klaarzetten bij Rabobank; geen betaallink');
    ok(await page.evaluate(() => document.querySelectorAll('#s-betaling .big:not(.tonal):not(.ghost):not([hidden])').length) === 1, 'één hoofdknop');

    // ---- Een IBAN dat niet klopt wordt niet bewaard
    await page.fill('#btd-iban', 'NL91 ABNA 0417 1643 01'); await page.dispatchEvent('#btd-iban', 'change'); await wacht(60);
    ok(await page.isVisible('#btd-fout') && (await tekst('#btd-fout')).includes('IBAN'), 'een IBAN met een fout: melding');
    ok(await page.evaluate(() => btVind(bt.id).iban) === 'NL91ABNA0417164300', 'en het oude IBAN blijft bewaard');
    await page.click('#btd-klaar'); await wacht(80);
    ok(await page.evaluate(() => !Android._bt.calls.some(c => c[0] === 'klaarzetten') && !document.querySelector('#modal.on')), 'met een fout in de velden gaat er niets naar de bank');
    await page.fill('#btd-iban', 'nl91abna0417164300'); await page.dispatchEvent('#btd-iban', 'change'); await wacht(60);
    ok(await page.isHidden('#btd-fout'), 'goed IBAN: melding weg');
    await page.fill('#btd-bedrag', '1.250,5'); await page.dispatchEvent('#btd-bedrag', 'change'); await wacht(60);
    ok(await page.evaluate(() => btVind(bt.id).bedrag) === '1250,50', 'bedrag met punt en komma wordt 1250,50');

    // ---- Kopiëren naar de app van de bank
    await page.click('#btd-kopieer'); await wacht(100); await sheet('IBAN kopiëren'); await wacht(60);
    ok(await page.evaluate(() => Android._copied) === 'NL91ABNA0417164300', 'IBAN gekopieerd');

    // ---- Klaarzetten: eerst de vraag bij een nieuwe ontvanger, dan naar de bank; terug: is het gelukt?
    await page.click('#btd-klaar'); await wacht(100);
    ok((await tekst('#modal-title')) === 'Nieuwe ontvanger' && (await tekst('#modal-text')).includes('NL91 ABNA 0417 1643 00') && (await tekst('#modal-text')).includes('Energie Voorbeeld B.V.'), 'nieuwe ontvanger: vraag met IBAN en naam');
    await page.click('#modal-ok'); await wacht(100);
    const id = await page.evaluate(() => bt.id);
    ok(await page.evaluate(i => Android._bt.calls.some(c => c[0] === 'klaarzetten' && c[1] === i), id), 'klaarzetten gaat naar de bank');
    await page.evaluate(() => onResumeApp()); await wacht(120);
    ok((await tekst('#modal-title')) === 'Is de betaling gelukt?' && await page.evaluate(() => btVind(bt.id).status) === 'klaargezet', 'terug van de bank: klaargezet, en de vraag of het gelukt is');
    await page.click('#modal-ok'); await wacht(100);
    ok(await page.evaluate(() => btVind(bt.id).status) === 'betaald' && (await tekst('#btd-stand')).includes('Betaald op') && await page.isHidden('#btd-klaar') && (await tekst('#btd-betaald')) === 'Toch nog niet betaald', 'ja: betaald');
    await page.evaluate(() => goBack()); await wacht(100);
    await page.click('#bt-filter [data-f="betaald"]'); await wacht(60);
    ok((await tekst('#bt-lijst')).includes('Energie Voorbeeld B.V.') && (await tekst('#bt-lijst')).includes('betaald op'), 'filter Betaald toont hem');
    await page.click('#bt-filter [data-f="open"]'); await wacht(60);

    // ---- Tekst van een rekening is gegevens: nooit HTML
    await page.evaluate(() => { Android._bt.herken = { naam: '<img src=x onerror="window.__raak=1">', omschrijving: '<b>vet</b>', iban: 'DE89370400440532013000', ibans: ['DE89370400440532013000', '"><img src=x onerror="window.__raak=2">'] }; });
    await page.click('#bt-nieuw'); await wacht(100); await sheet('Bestand op de telefoon');
    await page.waitForFunction(() => current === 'betaling' && $('#btd-iban').value.startsWith('DE89')); await wacht(100);
    await page.evaluate(() => goBack()); await wacht(100);
    ok(await page.evaluate(() => window.__raak === undefined && !document.querySelector('#s-betalen img, #s-betaling img, #s-betaling b b') && $('#bt-lijst').textContent.includes('<img src=x')), 'een naam met opmaak of code staat er als gewone tekst');
    await page.evaluate(() => { Android._bt.herken = null; });

    // ---- QR-code: een betaallink van een bekende betaaldienst
    await page.evaluate(() => btVoorstel({ bron: 'foto', naam: 'Zorgverzekeraar Test', bedrag: '88,00', ibans: [] }, 'https://www.ideal.nl/betalen?trxid=test123')); await wacht(150);
    ok(await page.evaluate(() => current === 'betaling') && await page.isVisible('#btd-linkkaart') && (await tekst('#btd-linkhost')) === 'www.ideal.nl' && (await tekst('#btd-linkuitleg')).includes('bekende betaaldienst') && await page.isVisible('#btd-link'), 'betaallink: het adres staat erbij, bekende betaaldienst');
    ok((await tekst('#btd-bron')).includes('foto'), 'gelezen van een foto');
    await page.click('#btd-link'); await wacht(100);
    ok((await tekst('#modal-text')).includes('www.ideal.nl'), 'voor het openen: vraag met het adres');
    await page.click('#modal-ok'); await wacht(100);
    ok(await page.evaluate(() => Android._bt.calls.some(c => c[0] === 'link' && c[1] === 'https://www.ideal.nl/betalen?trxid=test123') && btVind(bt.id).status === 'klaargezet'), 'de link opent pas na de vraag; de betaling staat op klaargezet');
    // ---- een onbekend adres krijgt een waarschuwing; iets anders dan https is geen betaallink
    await page.evaluate(() => { show('betalen'); btVoorstel({ bron: 'foto', ibans: [] }, 'https://ideal.nl.voorbeeld.example/betalen'); }); await wacht(150);
    ok((await tekst('#btd-linkhost')) === 'ideal.nl.voorbeeld.example' && (await tekst('#btd-linkuitleg')).includes('kent dit adres niet'), 'onbekend adres: waarschuwing');
    await page.evaluate(() => { Android.betalenWis(bt.id); show('betalen'); btVoorstel({ bron: 'foto', naam: 'Geen link', ibans: [] }, 'javascript:alert(1)'); }); await wacht(150);
    ok(await page.isHidden('#btd-linkkaart') && await page.evaluate(() => !btVind(bt.id).link), 'een QR-code zonder https-adres geeft geen betaallink');

    // ---- QR-code met betaalgegevens (SEPA-QR) gaat voor de gelezen tekst
    await page.evaluate(() => { show('betalen'); btVoorstel({ bron: 'foto', naam: 'Verkeerd gelezen', iban: 'NL91ABNA0417164300', bedrag: '1,00', kenmerk: '123456789', ibans: ['NL91ABNA0417164300'] },
      'BCD\n002\n1\nSCT\n\nQR Voorbeeld B.V.\nDE89370400440532013000\nEUR77.10\n\n\nRekening 77'); }); await wacht(150);
    ok(await page.inputValue('#btd-naam') === 'QR Voorbeeld B.V.' && await page.inputValue('#btd-iban') === 'DE89 3704 0044 0532 0130 00' && await page.inputValue('#btd-bedrag') === '77,10'
      && await page.inputValue('#btd-omschrijving') === 'Rekening 77' && await page.inputValue('#btd-kenmerk') === '', 'SEPA-QR: naam, IBAN, bedrag en omschrijving uit de code');

    // ---- De QR-lezer vindt een code op een grote foto (klein, in een hoek)
    const gelezen = await page.evaluate(async ([link, epc]) => {
      const laad = src => new Promise(r => { const i = new Image(); i.onload = () => r(i); i.src = src; });
      const blad = async (src, x, y, schaal) => { const im = await laad(src), c = document.createElement('canvas'); c.width = 1800; c.height = 2500; const g = c.getContext('2d');
        g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height); g.fillStyle = '#222'; g.font = '28px sans-serif'; for (let r = 0; r < 40; r++) g.fillText('Factuurregel ' + r + ' bedrag 12,34 omschrijving van de levering', 80, 200 + r * 44);
        g.imageSmoothingEnabled = false; g.drawImage(im, x, y, im.width * schaal, im.height * schaal); return c; };
      const a = await btQrUitCanvas(await blad(link, 1400, 2100, 1.5)), b = await btQrUitCanvas(await blad(epc, 120, 60, 1.25));
      const leeg = document.createElement('canvas'); leeg.width = 800; leeg.height = 600; leeg.getContext('2d').fillRect(0, 0, 10, 10);
      return [a, b, await btQrUitCanvas(leeg), await btQrUitBeeld('https://voorbeeld.example/x.png')];
    }, [QR_LINK, QR_EPC]);
    ok(gelezen[0] === 'https://www.ideal.nl/betalen?trxid=test123', 'QR-code met een betaallink gelezen van de foto: ' + gelezen[0]);
    ok(gelezen[1].startsWith('BCD\n002') && gelezen[1].includes('DE89370400440532013000'), 'QR-code met betaalgegevens gelezen van de foto');
    ok(gelezen[2] === '' && gelezen[3] === '', 'geen QR-code: niets; een beeld van internet wordt niet geladen');

    // ---- Foto maken met de camera in de app
    await page.evaluate(() => show('betalen')); await wacht(80);
    const voor = await page.evaluate(() => Android._bt.lijst.length);
    await page.click('#bt-nieuw'); await wacht(100); await sheet('Foto maken');
    await page.waitForFunction(() => btCamOpen() && !$('#bt-camfoto').disabled && $('#bt-video').videoWidth > 0, null, { timeout: 8000 * W });
    ok(await page.evaluate(() => getComputedStyle($('#bt-cam')).display !== 'none'), 'de camera opent in de app');
    await page.click('#bt-camfoto');
    await page.waitForFunction(() => current === 'betaling'); await wacht(100);
    ok(await page.evaluate(n => !btCamOpen() && !bt.stream && Android._bt.calls.some(c => c[0] === 'foto' && c[1] === 'data:image/jpeg;base64,') && Android._bt.calls.some(c => c[0] === 'cameraDicht') && Android._bt.lijst.length === n + 1 && btVind(bt.id).bron === 'foto', voor),
      'foto: de camera gaat dicht, de foto gaat naar de tekstherkenning en er komt een betaling bij');
    // De camera gaat ook dicht als je weggaat
    await page.evaluate(() => { show('betalen'); btCamera(); });
    await page.waitForFunction(() => btCamOpen() && !!bt.stream, null, { timeout: 8000 * W });
    await page.evaluate(() => show('home')); await wacht(80);
    ok(await page.evaluate(() => !btCamOpen() && !bt.stream), 'weg van Betalen: de camera stopt');
    await page.evaluate(() => { window.__testCamera = false; show('betalen'); btCamera(); }); await wacht(150);
    ok(await page.evaluate(() => !btCamOpen() && $('#toast').textContent.includes('Zonder camera')), 'geen toestemming voor de camera: uitleg, geen leeg scherm');
    await page.evaluate(() => { window.__testCamera = true; });

    // ---- Lezen mislukt: melding, geen betaling
    const n1 = await page.evaluate(() => { Android._bt.fout = 'Dit bestand is niet te lezen. Vul de betaling zelf in.'; return Android._bt.lijst.length; });
    await page.click('#bt-nieuw'); await wacht(100); await sheet('Bestand op de telefoon'); await wacht(250);
    ok(await page.evaluate(n => current === 'betalen' && Android._bt.lijst.length === n && !bt.lezen && $('#toast').textContent.includes('niet te lezen'), n1), 'niet te lezen: melding en geen lege betaling');
    await page.evaluate(() => { delete Android._bt.fout; });

    // ---- Zelf invullen; leeg weer terug laat niets achter
    const n2 = await page.evaluate(() => Android._bt.lijst.length);
    await page.click('#bt-nieuw'); await wacht(100); await sheet('Zelf invullen'); await wacht(120);
    ok(await page.evaluate(() => current === 'betaling' && $('#btd-bron').hidden && $('#btd-klaar').disabled) && (await tekst('#btd-stand')).includes('Vul de naam'), 'zelf invullen: leeg, klaarzetten kan nog niet');
    await page.evaluate(() => goBack()); await wacht(100);
    ok(await page.evaluate(n => Android._bt.lijst.length === n, n2), 'leeg teruggaan laat geen betaling achter');

    // ---- Instellingen: een bank waar het niet automatisch kan, eigen rekeningnummers, na het klaarzetten
    await page.click('#bt-set'); await wacht(120);
    ok(await page.evaluate(() => current === 'betalenset' && $('#bts-bank').value === 'rabobank') && (await tekst('#bts-bankuitleg')).includes('voor je invullen') && (await tekst('#bts-slot')).length > 3, 'instellingen: Rabobank, met wat Tools daar doet en de stand van Extra ontgrendelen');
    await page.fill('#bts-eigen', 'NL00 TEST 0000 0000 00'); await page.dispatchEvent('#bts-eigen', 'change'); await wacht(60);
    ok(await page.isVisible('#bts-fout'), 'een eigen rekeningnummer dat niet klopt: melding');
    await page.fill('#bts-eigen', 'NL39 RABO 0300 0652 64'); await page.dispatchEvent('#bts-eigen', 'change'); await wacht(60);
    await page.selectOption('#bts-na', 'betaald'); await page.selectOption('#bts-bank', 'ing'); await wacht(80);
    ok(await page.evaluate(() => { const i = Android._bt.inst; return i.bank === 'ing' && i.naKlaar === 'betaald' && i.eigen.join() === 'NL39RABO0300065264'; }) && (await tekst('#bts-bankuitleg')).includes('niet zelf in') && await page.isHidden('#bts-fout'), 'bewaard: ING, meteen betaald, eigen rekeningnummer');
    await page.evaluate(() => goBack()); await wacht(80);
    await page.click('#bt-lijst .sn-item'); await wacht(120);
    ok(await page.isHidden('#btd-klaar') && await page.evaluate(() => $('#btd-kopieer').className === 'big') && (await tekst('#btd-stand')).includes('ING'), 'bij ING: geen klaarzetten, kopiëren is de hoofdknop, met uitleg');
    await page.evaluate(() => { Android.betalenInst(JSON.stringify({ bank: 'rabobank', naKlaar: 'betaald', eigen: [] })); show('betalen'); }); await wacht(60);
    // meteen betaald zetten: geen vraag
    await page.evaluate(() => { const b = Android._bt.lijst.find(x => x.naam === 'Waterbedrijf Test'); btOpen(b.id); }); await wacht(100);
    ok(await page.isHidden('#btd-nieuw'), 'naar dit IBAN is al eens betaald: geen waarschuwing voor een nieuwe ontvanger');
    await page.click('#btd-klaar'); await wacht(100);
    ok(await page.evaluate(() => !document.querySelector('#modal.on')), 'en dus ook geen extra vraag');
    await page.evaluate(() => onResumeApp()); await wacht(120);
    ok(await page.evaluate(() => btVind(bt.id).status === 'betaald' && !document.querySelector('#modal.on')), 'instelling "meteen betaald": geen vraag');
    await page.click('#btd-betaald'); await wacht(60);
    ok(await page.evaluate(() => btVind(bt.id).status) === 'open', 'toch nog niet betaald: weer te betalen');

    // ---- Verwijderen
    const n3 = await page.evaluate(() => Android._bt.lijst.length);
    await page.click('#btd-menu'); await wacht(100); await sheet('Verwijderen'); await wacht(100);
    ok((await tekst('#modal-text')).includes('bij je bank verandert er niets'), 'verwijderen: alleen in Tools');
    await page.click('#modal-ok'); await wacht(100);
    ok(await page.evaluate(n => current === 'betalen' && Android._bt.lijst.length === n - 1, n3), 'verwijderd');

    // ---- Een oudere app zonder Betalen
    await page.evaluate(() => { for (const k of Object.keys(Android)) if (/^betalen/.test(k)) Android['oud_' + k] = Android[k]; Android.betalenStaat = undefined; show('home'); show('betalen'); }); await wacht(80);
    ok(await page.isVisible('#bt-oud') && await page.isHidden('#bt-nieuw'), 'oudere app: uitleg in plaats van een knop die niets doet');
    await page.evaluate(() => clearInterval(window.__camTimer));
    await page.close();
  }
  await browser.close();
  console.log('errors', JSON.stringify(errors));
  process.exit(errors.length ? 1 : 0);
})();
