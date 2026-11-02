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
  // Eigen veldaanwijzingen gaan voor
  await pg.setContent('<form><input id=a name=x><input id=b name=y type=password><button id=go type=button onclick="window.__go=1">Gaan</button></form>');
  await js("__fr.vul('gebruiker', 'u1', {velden:{gebruiker:'#a'}})");
  ok(await pg.inputValue('#a') === 'u1', 'eigen veld voor de gebruikersnaam');
  ok((await js("__fr.verstuur('wachtwoord', {velden:{knop:'#go'}})")) === 'eigen knop' && await pg.evaluate(() => window.__go) === 1, 'eigen inlogknop');
  console.log('errors', errs);
  await b.close(); srv.close();
})();
