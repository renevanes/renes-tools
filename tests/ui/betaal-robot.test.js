// Betalen, klaarzetten bij de bank: de robot (web/betaal-robot.js) tegen een nagebootste bankwebsite.
// Hij mag nooit zelf inloggen en nooit iets versturen: alleen naar het formulier gaan en de velden invullen.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
(async () => {
  const b = await chromium.launch();
  const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  const errs = []; pg.on('pageerror', e => errs.push(e.message));
  const ok = (c, wat) => { console.log((c ? '✓ ' : '✗ ') + wat); if (!c) errs.push('mislukt: ' + wat); };
  const robot = fs.readFileSync(path.join(__dirname, '..', '..', 'web', 'betaal-robot.js'), 'utf8');
  const rabo = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'web', 'bank-banken.json'), 'utf8')).banken.find(x => x.id === 'rabobank');
  const basis = Object.assign({ inlog: rabo.inlog }, ...['naar', 'menu', 'naam', 'iban', 'bedrag', 'omschrijving', 'kenmerk'].map(k => ({ [k]: rabo.betalen[k] })));
  const bet = { naam: 'Waterbedrijf Voorbeeld B.V.', iban: 'NL91ABNA0417164300', bedrag: '123,45', kenmerk: '', omschrijving: 'Factuur F2026-0042' };
  // Zoals Java het doet: de instellingen en de betaling gaan als JSON in de aanroep (</ wordt <\/)
  const js = o => JSON.stringify(o).replace(/<\//g, '<\\/');
  const stap = async (betaling, extra) => { await pg.evaluate(robot); return pg.evaluate('__bt.stap(' + js(Object.assign({}, basis, extra || {})) + ',' + js(betaling || bet) + ')'); };
  const FORMULIER = `<form id="f" onsubmit="window.verstuurd = (window.verstuurd || 0) + 1; return false">
      <label for="naam">Naam ontvanger</label><input id="naam">
      <label for="iban">IBAN</label><input id="iban">
      <label>Bedrag <input id="bedrag" inputmode="decimal"></label>
      <label for="oms">Omschrijving</label><textarea id="oms"></textarea>
      <button type="submit" id="ok">Overboeken</button><button id="ok2">Volgende</button>
    </form>`;

  // Inlogpagina: de robot wacht en vult niets in
  await pg.setContent('<body><h1>Inloggen</h1><p>Inloggen met de Rabo App</p><input id="u"><input type="password" id="pw"><button id="in">Inloggen</button></body>');
  ok(await stap() === 'inlog' && await stap() === 'inlog' && await pg.inputValue('#pw') === '' && await pg.inputValue('#u') === '', 'inlogpagina: wachten, niets invullen');
  await pg.setContent('<body><p>Scan de QR-code met de Rabo App</p><img alt="qr"></body>');
  ok(await stap() === 'inlog', 'inloggen met de QR-code herkend');

  // Na inloggen: menu → Overboeken → het formulier invullen, en daarna niets meer
  await pg.setContent(`<body><nav><a href="#" id="m" onclick="document.getElementById('sub').hidden = false; window.menu = 1; return false">Betalen</a></nav>
    <div id="sub" hidden><a href="#" id="nw" onclick="document.getElementById('wrap').hidden = false; window.naar = (window.naar || 0) + 1; return false">Overboeken</a></div>
    <p>Laatst ingelogd: gisteren. <a href="#" onclick="window.uit = 1; return false">Uitloggen</a></p>
    <div id="wrap" hidden>${FORMULIER}</div></body>`);
  ok(await stap() === 'menu', 'eerst het menu Betalen');
  ok(await stap() === 'naar', 'dan Overboeken');
  ok(await stap() === 'invullen', 'het formulier invullen');
  ok(await pg.inputValue('#naam') === bet.naam && await pg.inputValue('#iban') === bet.iban && await pg.inputValue('#bedrag') === '123,45' && await pg.inputValue('#oms') === bet.omschrijving, 'naam, IBAN, bedrag en omschrijving staan erin');
  ok(await stap() === 'ingevuld', 'daarna: ingevuld');
  for (let i = 0; i < 6; i++) await stap(null, { geenNavigatie: true });
  ok(await pg.evaluate(() => window.verstuurd === undefined && window.uit === undefined && window.naar === 1), 'verstuurt nooit zelf, logt niet uit en klikt niet nog eens');

  // Wat Rene zelf verandert blijft staan (niet opnieuw invullen)
  await pg.fill('#naam', 'Andere naam');
  ok(await stap() === 'controleer' && await pg.inputValue('#naam') === 'Andere naam', 'een veld dat is aangepast wordt niet overschreven');

  // Een knop Overboeken in een formulier of van het soort versturen is geen navigatie
  await pg.setContent(`<body><p>Controleer je overboeking</p><form onsubmit="window.verstuurd = 1; return false"><button id="a">Overboeken</button></form>
    <button type="submit" id="b" onclick="window.verstuurd = 2">Overboeken</button></body>`);
  ok(await stap() === 'wacht' && await pg.evaluate(() => window.verstuurd === undefined), 'een knop Overboeken die iets verstuurt wordt nooit aangeklikt');
  await pg.setContent('<body><a href="#" onclick="window.geklikt = 1; return false">Overboeken</a></body>');
  ok(await stap(null, { geenNavigatie: true }) === 'wacht' && await pg.evaluate(() => window.geklikt === undefined), 'is het formulier in beeld geweest, dan klikt de robot nergens meer op');

  // Betalingskenmerk: in het eigen veld, of anders in de omschrijving; het bedrag met een punt in een getalveld
  const metKenmerk = Object.assign({}, bet, { kenmerk: '8234567890123456', omschrijving: '' });
  await pg.setContent(`<body><form onsubmit="return false"><input aria-label="Naam van de begunstigde" id="naam"><input placeholder="IBAN" id="iban">
    <input name="bedrag" type="number" step="0.01" id="bedrag"><input aria-label="Omschrijving" id="oms"><input aria-label="Betalingskenmerk" id="km"></form></body>`);
  ok(await stap(metKenmerk) === 'invullen' && await pg.inputValue('#km') === '8234567890123456' && await pg.inputValue('#oms') === '' && await pg.inputValue('#bedrag') === '123.45', 'kenmerk in het kenmerkveld; bedrag met een punt in een getalveld');
  ok(await stap(metKenmerk) === 'ingevuld', 'en dan ingevuld');
  await pg.setContent(`<body>${FORMULIER}</body>`);
  ok(await stap(metKenmerk) === 'invullen' && await pg.inputValue('#oms') === '8234567890123456', 'geen kenmerkveld: het kenmerk in de omschrijving');

  // Tekst van een rekening is alleen een waarde in een veld, nooit code of opmaak
  const gek = Object.assign({}, bet, { naam: '"><img src=x onerror="window.raak=1"></script><script>window.raak=2</script>' });
  await pg.setContent(`<body>${FORMULIER}</body>`);
  ok(await stap(gek) === 'invullen' && await pg.inputValue('#naam') === gek.naam && await pg.evaluate(() => window.raak === undefined && !document.querySelector('img')), 'een naam met opmaak of code blijft gewone tekst');

  // Geen wachtwoord- of codevelden invullen
  await pg.setContent(`<body><form onsubmit="return false"><label>IBAN <input id="iban"></label><label>Bedrag <input id="bedrag"></label><label>Naam ontvanger <input id="naam" type="password"></label></form></body>`);
  await stap();
  ok(await pg.inputValue('#naam') === '', 'een wachtwoordveld wordt nooit ingevuld');

  console.log('errors', errs);
  await b.close();
})();
