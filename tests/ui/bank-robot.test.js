// Bankzaken, automatisch importeren: de robot (web/bank-robot.js) tegen een nagebootste bankwebsite.
// Hij mag nooit zelf inloggen; daarna: menu → Transacties downloaden → CAMT.053 + periode → Downloaden → bestand naar de app.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
(async () => {
  const b = await chromium.launch();
  const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  const errs = []; pg.on('pageerror', e => errs.push(e.message));
  const ok = (c, wat) => { if (!c) errs.push('mislukt: ' + wat); };
  const robot = fs.readFileSync(path.join(__dirname, '..', '..', 'web', 'bank-robot.js'), 'utf8').replace('__SLEUTEL__', 'geheim123');
  const banken = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'web', 'bank-banken.json'), 'utf8')).banken;
  const rabo = banken.find(x => x.id === 'rabobank');
  const cfg = JSON.stringify({ inlog: rabo.inlog, menu: rabo.menu, naarDownload: rabo.naarDownload, formaat: rabo.formaat, formaatReserve: rabo.formaatReserve, alleRekeningen: rabo.alleRekeningen, knop: rabo.knop, van: '2026-10-25' });
  const stap = async () => { await pg.evaluate(robot); return pg.evaluate('__br.stap(' + cfg + ')'); };

  // Inlogpagina: de robot wacht (vult niets in)
  await pg.setContent('<body><h1>Inloggen</h1><p>Inloggen met de Rabo App</p><input id="u"><input type="password" id="pw"><button id="in">Inloggen</button></body>');
  ok(await stap() === 'inlog', 'inlogpagina herkend');
  ok(await stap() === 'inlog' && await pg.inputValue('#pw') === '' && await pg.inputValue('#u') === '', 'vult zelf niets in');
  await pg.setContent('<body><p>Scan de QR-code met de Rabo App</p><img alt="qr"></body>');
  ok(await stap() === 'inlog', 'QR-inlog herkend');

  // Na inloggen: menu → transacties downloaden → formulier → downloaden
  await pg.setContent(`<body><nav><a href="#" id="m" onclick="document.getElementById('sub').hidden=false;return false">Rekeningen</a></nav>
    <div id="sub" hidden><a href="#" id="dl" onclick="toon();return false">Transacties downloaden</a></div>
    <p>Laatst ingelogd: gisteren. <a href="#">Uitloggen</a></p>
    <div id="form" hidden>
      <label>Rekening <select id="rek"><option value="1">NL02 RABO 0123 4567 89</option><option value="all">Alle rekeningen</option></select></label>
      <label>Bestandsformaat <select id="fmt"><option value="csv">CSV (kommagescheiden)</option><option value="camt">CAMT.053 (XML)</option><option value="mt">MT940</option></select></label>
      <label>Van <input type="date" id="van"></label><label>Tot <input type="date" id="tot"></label>
      <button id="go" onclick="maak()">Downloaden</button>
    </div>
    <script>
      window.ontvangen = [];
      window.BrBrug = { bestand(s, d, n){ window.ontvangen.push([s, d.slice(0, 40), n]); } };
      function toon(){ document.getElementById('form').hidden = false; }
      function maak(){
        const b = new Blob(['<Document><Stmt></Stmt></Document>'], { type: 'application/xml' });
        const a = document.createElement('a'); a.href = URL.createObjectURL(b); a.download = 'CAMT053_' + document.getElementById('fmt').value + '.xml'; a.click();
      }
    </script></body>`);
  ok(await stap() === 'menu', 'eerst het menu Rekeningen');
  ok(await stap() === 'naar', 'dan Transacties downloaden');
  ok(await stap() === 'formulier', 'formulier invullen');
  ok(await pg.inputValue('#fmt') === 'camt' && await pg.inputValue('#rek') === 'all' && await pg.inputValue('#van') === '2026-10-25', 'CAMT.053, alle rekeningen, vanaf de laatste import');
  const s = await stap();
  ok(s === 'download', 'dan Downloaden: ' + s);
  await pg.waitForTimeout(300);
  const ontv = await pg.evaluate(() => window.ontvangen);
  ok(ontv.length === 1 && ontv[0][0] === 'geheim123' && ontv[0][1].startsWith('data:application/xml') && ontv[0][2] === 'CAMT053_camt.xml', 'bestand met de sleutel naar de app: ' + JSON.stringify(ontv));
  // Niet eindeloos blijven klikken
  for (let i = 0; i < 5; i++) await stap();
  ok(await pg.evaluate(() => window.ontvangen.length) <= 3, 'klikt niet eindeloos op Downloaden');

  // Alleen CSV aangeboden: dan CSV
  await pg.setContent(`<body><label><input type="radio" name="f" value="pdf"> PDF</label><label><input type="radio" name="f" value="csv" id="csv"> CSV</label><button>Downloaden</button>
    <script>window.BrBrug = { bestand(){} };</script></body>`);
  ok(await stap() === 'formulier' && await pg.isChecked('#csv'), 'geen CAMT: CSV gekozen');
  console.log('errors', errs);
  await b.close();
})();
