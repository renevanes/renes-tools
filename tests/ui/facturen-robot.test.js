// De robot van Facturen ophalen (web/facturen-robot.js) tegen een nagebootste leverancier: startpagina met inlogknop,
// e-mail en wachtwoord in twee stappen (React-achtig: waarde via de setter), code in zes losse vakjes, menu naar de
// facturen en drie soorten downloadknoppen (gewone link, PDF die de pagina zelf maakt, PDF in een nieuw venster).
const { chromium } = require('playwright');
const http = require('http');
const fs = require('fs');
const path = require('path');
const ROBOT = fs.readFileSync(path.join(__dirname, '..', '..', 'web', 'facturen-robot.js'), 'utf8');
const PDF = '%PDF-1.4\n% factuur\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n';
const page = b => '<!doctype html><html><head><meta charset="utf-8"><title>Mijn Leverancier</title></head><body>' + b + '</body></html>';
const srv = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x'), sess = (req.headers.cookie || '').includes('sess=ok');
  const html = b => { res.writeHead(200, { 'content-type': 'text/html' }); res.end(page(b)); };
  if (u.pathname === '/') return html(sess ? '<a href="/uitloggen">Uitloggen</a> <nav><a href="/overzicht">Overzicht</a> <a href="/facturen">Mijn facturen</a></nav>' : '<h1>Welkom</h1><a href="/login">Inloggen</a>');
  if (u.pathname === '/login') return html(`<div id="app"></div><script>
    // Zoals React: de waarde telt pas na een input-event
    let stap = 'mail', mail = '';
    function teken(){ const a = document.getElementById('app');
      if (stap === 'mail') a.innerHTML = '<form id=f><label>E-mailadres <input id=m name=username type=text></label><button type=button id=v>Volgende</button></form>';
      if (stap === 'pw') a.innerHTML = '<form id=f><input type=password id=p><div role=alert id=err></div><button type=button id=v>Inloggen</button></form>';
      if (stap === 'code') a.innerHTML = '<form id=f><p>Vul de code in</p>' + [0,1,2,3,4,5].map(i => '<input maxlength=1 inputmode=numeric class=d>').join('') + '<button type=button id=v>Bevestigen</button></form>';
      let val = ''; a.querySelectorAll('input').forEach(i => i.addEventListener('input', () => { i.dataset.v = i.value; }));
      document.getElementById('v').onclick = () => {
        if (stap === 'mail') { mail = document.getElementById('m').dataset.v || ''; if (mail === 'rene@bedrijf.nl') { stap = 'pw'; teken(); } }
        else if (stap === 'pw') { if (document.getElementById('p').dataset.v === 'geheim') { stap = 'code'; teken(); } else document.getElementById('err').textContent = 'Onjuist wachtwoord'; }
        else if (stap === 'code') { const c = Array.from(document.querySelectorAll('.d')).map(i => i.dataset.v || '').join(''); if (c === '482913') { document.cookie = 'sess=ok; path=/'; location.href = '/'; } }
      };
    }
    teken();</script>`);
  if (u.pathname === '/facturen') return html(sess ? `<h1>Facturen</h1><table>
      <tr><td>September</td><td><a href="/f/sep.pdf">Download PDF</a></td></tr>
      <tr><td>Oktober</td><td><button aria-label="Factuur oktober downloaden" id=blob><svg><title>download</title></svg></button></td></tr>
      <tr><td>Augustus</td><td><button title="PDF openen" onclick="window.open('/f/aug.pdf')">⬇</button></td></tr>
      <tr><td><a href="/details/1">Bekijk details</a></td></tr></table>
      <script>document.getElementById('blob').onclick = () => { const b = new Blob([${JSON.stringify(PDF)}], { type: 'application/pdf' });
        const a = document.createElement('a'); a.href = URL.createObjectURL(b); a.download = 'Factuur 2026-10.pdf'; document.body.appendChild(a); a.click(); a.remove(); };</script>` : '<a href="/login">Inloggen</a>');
  if (u.pathname.startsWith('/f/')) { res.writeHead(200, { 'content-type': 'application/pdf' }); return res.end(PDF); }
  res.writeHead(404); res.end();
});
(async () => {
  await new Promise(r => srv.listen(0, '127.0.0.1', r));
  const base = 'http://127.0.0.1:' + srv.address().port;
  const b = await chromium.launch();
  const ctx = await b.newContext({ acceptDownloads: true });
  await ctx.addInitScript(() => { window.__got = []; window.FrBrug = { blob: (s, d, n) => window.__got.push([String(d).slice(0, 40), n, s]) };
    const o = window.open; window.open = function(u){ window.__open = String(u); return o.apply(this, arguments); }; });
  const pg = await ctx.newPage();
  const errs = []; pg.on('pageerror', e => errs.push(e.message));
  const ok = (c, wat) => { console.log((c ? '✓ ' : '✗ ') + wat); if (!c) errs.push('mislukt: ' + wat); };
  // Zoals FacturenRun.js(): robot + opdracht, uitkomst als tekst
  const js = async expr => { try { return await pg.evaluate(ROBOT + '\n;' + expr); } catch (e) { return 'FOUT ' + e.message; } };
  const stand = async () => JSON.parse(await js('__fr.stand({})'));
  const cfg = '{}';
  await pg.goto(base + '/');
  let st = await stand();
  ok(!st.pw && !st.user && !st.code && st.knop && !st.uit, 'startpagina: geen velden, wel een inlogknop');
  ok((await js('__fr.inlogKnop()')) === 'Inloggen', 'op Inloggen geklikt');
  await pg.waitForURL('**/login'); await pg.waitForTimeout(200);
  st = await stand();
  ok(st.user && st.userLeeg && !st.pw && !st.code, 'stap 1: alleen e-mailadres');
  await js("__fr.vul('gebruiker', 'rene@bedrijf.nl', " + cfg + ")");
  ok((await js("__fr.verstuur('gebruiker', " + cfg + ")")) !== 'niets', 'volgende');
  await pg.waitForTimeout(200);
  st = await stand();
  ok(st.pw && !st.code, 'stap 2: wachtwoord');
  await js("__fr.vul('wachtwoord', 'fout', " + cfg + ")"); await js("__fr.verstuur('wachtwoord', " + cfg + ")"); await pg.waitForTimeout(150);
  st = await stand();
  ok(st.pw && st.fout === 'Onjuist wachtwoord', 'foutmelding van de website gelezen');
  await js("__fr.vul('wachtwoord', 'geheim', " + cfg + ")"); await js("__fr.verstuur('wachtwoord', " + cfg + ")"); await pg.waitForTimeout(200);
  st = await stand();
  ok(st.code && !st.pw, 'stap 3: code gevraagd');
  ok((await js("__fr.vul('code', '482913', " + cfg + ")")) === 'vakjes 6', 'code over zes vakjes verdeeld');
  await js("__fr.verstuur('code', " + cfg + ")");
  await pg.waitForURL(base + '/'); await pg.waitForTimeout(200);
  st = await stand();
  ok(!st.pw && !st.user && !st.code && st.uit, 'ingelogd (uitloggen zichtbaar)');
  ok((await js("__fr.menu('facturen|factuur')")).includes('facturen'), 'menu: Mijn facturen');
  await pg.waitForURL('**/facturen'); await pg.waitForTimeout(200);
  const k = JSON.parse(await js("__fr.knoppen('download|\\\\bpdf\\\\b|\\\\.pdf', 12)"));
  ok(k.length === 3, '3 downloadknoppen (niet "Bekijk details"): ' + k.map(x => x.tekst).join(' | '));
  ok(k[0].href === base + '/f/sep.pdf' && !k[1].href && !k[2].href, 'link met adres; knoppen zonder adres');
  const popup = pg.waitForEvent('popup');
  await js('__fr.klik(' + k[1].i + ')'); await pg.waitForTimeout(300);
  const got = await pg.evaluate(() => window.__got);
  ok(got.length >= 1 && got[0][0].startsWith('data:application/pdf;base64,'), 'PDF die de pagina maakt (blob) doorgegeven');
  ok(got.some(g => g[1] === 'Factuur 2026-10.pdf'), 'met de bestandsnaam van de download');
  ok(got.every(g => g[2] === '__SLEUTEL__'), 'met de sleutel van de run');
  await js('__fr.klik(' + k[2].i + ')');
  await popup; ok(await pg.evaluate(() => window.__open) === '/f/aug.pdf', 'nieuw venster met de PDF geopend (de app vangt dat adres op)');
  ok((await js('__fr.klik(99)')) === 'weg', 'onbekende knop');
  // Zoals KPN: de codevraag ligt als venster óver het wachtwoordscherm (dat blijft zichtbaar), met een cookiemelding
  // eronder, vijf losse vakjes zonder formulier, "Onthoud dit apparaat" en de knop "Log extra veilig in".
  await pg.setContent(`<style>.v{position:fixed;inset:0;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center}
    .m{background:#fff;padding:20px}.d{width:44px;height:44px}</style>
    <form id=lf><input name=username value="rene@bedrijf.nl"><input type=password id=pw value="geheim"><button type=submit>Inloggen</button></form>
    <div class=v id=ck><div class=m><h2>Cookies en privacy</h2><button id=nee onclick="window.__ck='nee';document.getElementById('ck').remove()">Nee, dank je</button><button onclick="window.__ck='ja'">Accepteren</button></div></div>
    <div class=v id=mod><div class=m><h2>Verificatie in 2 stappen</h2><p>Vul de code in die je via sms hebt ontvangen</p>
      <div class=boxes><input class=d><input class=d><input class=d><input class=d><input class=d></div>
      <label><input type=checkbox id=onth> Onthoud dit apparaat</label>
      <button id=go onclick="window.__code=Array.from(document.querySelectorAll('.d')).map(i=>i.value).join('')">Log extra veilig in</button></div></div>`);
  st = await stand();
  ok(st.code && !st.pw && !st.user, 'codevenster over het wachtwoordscherm: code, niet het wachtwoord');
  ok(await pg.evaluate(() => window.__ck) === 'nee', 'cookiemelding: Nee, dank je');
  ok((await js("__fr.vul('code', '48291', {})")) === 'vakjes 5', 'code over vijf vakjes zonder formulier');
  ok((await js("__fr.vul('wachtwoord', 'geheim', { domeinen: ['ziggo.nl', 'vodafone.nl'] })")) === 'andere website', 'niet invullen buiten de domeinen van de leverancier');
  ok(await pg.isChecked('#onth'), 'Onthoud dit apparaat aangevinkt');
  ok((await js("__fr.verstuur('code', {})")) === 'knop' && await pg.evaluate(() => window.__code) === '48291', 'Log extra veilig in geklikt met de code');
  // Gewone pagina met "Akkoord" buiten een cookiemelding: niet aanklikken
  await pg.setContent('<p>Voorwaarden</p><button id=ak onclick="window.__ak=1">Akkoord</button><input type=password>');
  await stand();
  ok(!(await pg.evaluate(() => window.__ak)), 'geen willekeurige Akkoord-knop');
  // Gewone inlogpagina's mogen niet als codestap gelden (review)
  for (const [html, wat] of [
    ['<form><input name=username><input name=postcode><input type=password></form>', 'postcode naast wachtwoord'],
    ['<form><input name=klantnummer inputmode=numeric><input type=password></form>', 'klantnummer naast wachtwoord'],
    ['<form><input name=email><input maxlength=2 style="width:40px"><input maxlength=2 style="width:40px"><input maxlength=4 style="width:60px"><input type=color><input type=password></form>', 'geboortedatum in vakjes naast wachtwoord']]) {
    await pg.setContent(html); st = await stand();
    ok(st.pw && !st.code, 'gewoon inloggen: ' + wat);
  }
  await pg.setContent('<form><input name=email><input maxlength=2 style="width:40px"><input maxlength=2 style="width:40px"><input maxlength=4 style="width:60px"></form>');
  st = await stand(); ok(!st.code, 'datum in vakjes is geen code');
  // Zwevend label over het veld: veld telt wel
  await pg.setContent('<form><div style="position:relative;width:300px"><input name=email style="width:300px;height:40px"><span style="position:absolute;left:8px;top:10px">E-mailadres</span></div><div style="position:relative;width:300px"><input type=password style="width:300px;height:40px"><span style="position:absolute;left:8px;top:10px">Wachtwoord</span></div></form>');
  st = await stand(); ok(st.pw && st.user, 'velden met zwevend label gevonden');
  // Cookiebeleid in de voettekst + Akkoord-knop bij voorwaarden: niet klikken
  await pg.setContent('<main><h1>Nieuwe voorwaarden</h1><button onclick="window.__ak2=1">Akkoord</button><button onclick="window.__ak2=1">Accepteren</button></main><footer><a href="#">Cookiebeleid</a></footer>');
  await stand(); ok(!(await pg.evaluate(() => window.__ak2)), 'voorwaarden niet geaccepteerd door de cookieregel');
  // Onthoud alleen het eigen vakje
  await pg.setContent('<div><label><input type=checkbox id=a1> Ja, stuur me aanbiedingen</label><label><input type=checkbox id=a2> Onthoud dit apparaat</label><input class=d style="width:40px" maxlength=1><input class=d style="width:40px" maxlength=1><input class=d style="width:40px" maxlength=1><input class=d style="width:40px" maxlength=1><button>Bevestigen</button></div>');
  await js("__fr.vul('code', '1234', {})");
  ok(await pg.isChecked('#a2') && !(await pg.isChecked('#a1')), 'alleen Onthoud dit apparaat aangevinkt, geen aanbiedingen');
  // Knoppen die niet bedoeld zijn
  await pg.setContent('<form><input type=password><a href="#" onclick="window.__w=\'uit\'">Log uit en log opnieuw in</a><button type=button onclick="window.__w=\'digid\'">Login met DigiD</button><button type=button onclick="window.__w=\'ok\'">Inloggen</button></form>');
  await js("__fr.verstuur('wachtwoord', {})"); ok(await pg.evaluate(() => window.__w) === 'ok', 'Inloggen, niet uitloggen of DigiD');
  // Eigen veldaanwijzingen gaan voor
  await pg.setContent('<form><input id=a name=x><input id=b name=y type=password><button id=go type=button onclick="window.__go=1">Gaan</button></form>');
  await js("__fr.vul('gebruiker', 'u1', {velden:{gebruiker:'#a'}})");
  ok(await pg.inputValue('#a') === 'u1', 'eigen veld voor de gebruikersnaam');
  ok((await js("__fr.verstuur('wachtwoord', {velden:{knop:'#go'}})")) === 'eigen knop' && await pg.evaluate(() => window.__go) === 1, 'eigen inlogknop');
  console.log('errors', errs);
  await b.close(); srv.close();
})();
