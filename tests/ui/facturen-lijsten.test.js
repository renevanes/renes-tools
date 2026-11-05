// De echte robot tegen aangroeiende lijsten, paginering en webcomponenten; geen klantaccount nodig.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
const ROBOT = fs.readFileSync(path.join(__dirname, '../../web/facturen-robot.js'), 'utf8');
const MEER = fs.readFileSync(path.join(__dirname, '../../app/src/nl/rene/tools/Facturen.java'), 'utf8').match(/static final String MEER = "([^"]+)"/)[1];
const PATROON = 'download|\\bpdf\\b|\\.pdf';
const regels = (van, tot) => Array.from({ length: tot - van + 1 }, (_, n) => `<tr><td>Factuur 2026-${van + n}</td><td><button onclick="window.__downloads.push(${van + n})">Download PDF</button></td></tr>`).join('');

(async () => {
  const b = await chromium.launch();
  const errors = [];
  try {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
    pg.on('pageerror', e => errors.push(e.message));
    await pg.route('https://leverancier.test/**', r => r.fulfill({ contentType: 'text/html', body: '<!doctype html><body></body>' }));
    await pg.goto('https://leverancier.test/facturen');
    await pg.clock.install();
    const js = expr => pg.evaluate(ROBOT + '\n;' + expr);
    const lijst = async () => JSON.parse(await js(`__fr.lijst(${JSON.stringify(PATROON)}, ${JSON.stringify(MEER)})`));
    const verder = (s, soort) => js(`__fr.verderLijst(${JSON.stringify(PATROON)}, ${JSON.stringify(MEER)}, ${JSON.stringify(s.kenmerk)}, ${JSON.stringify(soort)})`);
    const download = async (s, begin = 0) => {
      for (const k of s.knoppen.slice(begin)) assert.equal(await js(`__fr.klik(${k.i}, ${JSON.stringify(k.sleutel)})`), 'ok');
    };

    await pg.setContent(`<table><tbody>${regels(1, 5)}</tbody></table><button id="meer">Toon meer</button>`);
    await pg.evaluate(extra => {
      window.__downloads = []; let klik = 0;
      document.getElementById('meer').onclick = () => {
        document.querySelector('table').setAttribute('aria-busy', 'true'); document.getElementById('meer').disabled = true;
        setTimeout(() => {
          document.querySelector('tbody').insertAdjacentHTML('beforeend', extra[klik++]);
          document.querySelector('table').removeAttribute('aria-busy');
          if (klik === 2) document.getElementById('meer').remove(); else document.getElementById('meer').disabled = false;
        }, 5000);
      };
    }, [regels(6, 10), regels(11, 12)]);
    let s = await lijst(); await download(s);
    assert.equal(await verder(s, 'meer'), 'ok');
    await pg.clock.fastForward(2500);
    let wacht = await lijst(); assert.equal(wacht.bezig, true); assert.equal(wacht.knoppen.length, 5);
    await pg.clock.fastForward(2600);
    let nieuw = await lijst(); assert.equal(nieuw.knoppen.length, 10); assert.notEqual(nieuw.kenmerk, s.kenmerk);
    await download(nieuw, 5); assert.equal(await verder(nieuw, 'meer'), 'ok');
    await pg.clock.fastForward(5100); s = await lijst(); await download(s, 10);
    assert.equal(s.meer, false); assert.equal(s.volgende, false);
    assert.deepEqual(await pg.evaluate(() => window.__downloads), Array.from({ length: 12 }, (_, i) => i + 1));
    console.log('✓ traag uitklappen, twee klikken en alle twaalf facturen precies één keer gedownload');

    await pg.setContent(`<table><tbody>${regels(1, 10)}</tbody></table><mkz-pagination><nav class="pagination" aria-label="Pagination Navigation"><a aria-label="Volgende" style="display:block;width:30px;height:30px">›</a></nav></mkz-pagination><form><button>Volgende</button></form>`);
    await pg.evaluate(extra => {
      window.__downloads = [];
      document.querySelector('a').onclick = () => {
        document.querySelector('tbody').innerHTML = extra;
        document.querySelector('a').parentElement.setAttribute('aria-disabled', 'true');
      };
    }, regels(11, 20));
    s = await lijst(); assert.equal(s.volgende, true); await download(s);
    assert.equal(await verder(s, 'volgende'), 'ok'); nieuw = await lijst();
    assert.equal(nieuw.knoppen.length, s.knoppen.length); assert.notEqual(nieuw.kenmerk, s.kenmerk);
    assert.equal(nieuw.volgende, false); await download(nieuw);
    assert.deepEqual(await pg.evaluate(() => window.__downloads), Array.from({ length: 20 }, (_, i) => i + 1));
    console.log('✓ twee KPN-pagina’s met tien regels en uitgeschakelde Volgende op de laatste pagina');

    await pg.setContent('<form><button>Volgende</button></form><nav class="pagination"><a aria-label="Volgende" href="https://ander.test/">›</a></nav>');
    assert.equal((await lijst()).volgende, false);
    console.log('✓ geen inlogknop of paginalink naar een andere website aangeklikt');

    await pg.setContent(`<table><tbody>${regels(1, 2)}</tbody></table><test-laadknop>Toon meer</test-laadknop>`);
    await pg.evaluate(() => {
      customElements.define('test-laadknop', class extends HTMLElement {
        constructor(){ super(); this.attachShadow({ mode: 'open' }).innerHTML = '<button><slot></slot></button>'; }
      });
      document.querySelector('test-laadknop').onclick = () => { window.__slot = true; };
    });
    s = await lijst(); assert.equal(s.meer, true); assert.equal(await verder(s, 'meer'), 'ok');
    assert.equal(await pg.evaluate(() => window.__slot), true);
    console.log('✓ Toon meer met tekst via een slot in een webcomponent');

    // Werkelijke KPN-markup: geen href of tekst, wel een file-pdf-icoon.
    await pg.setContent('<table><tr><td>Januari 2026</td><td><mkz-pdf-download><a><kpn-icon name="kpn:ui:file-pdf" aria-label="" style="display:inline-block;width:24px;height:24px"></kpn-icon></a></mkz-pdf-download></td></tr></table>');
    s = await lijst(); assert.equal(s.knoppen.length, 1);
    console.log('✓ werkelijke tekstloze KPN-downloadlink met file-pdf-icoon gevonden');

    const slotRijen = maanden => maanden.map(m => `<tr><td>${m} 2026</td><td><test-laadknop>Download PDF</test-laadknop></td></tr>`).join('');
    await pg.setContent('<table><tbody>' + slotRijen(['Januari', 'Februari']) + '</tbody></table>');
    s = await lijst(); assert.equal(s.knoppen.length, 2);
    await pg.evaluate(html => { document.querySelector('tbody').innerHTML = html; }, slotRijen(['Maart', 'April']));
    nieuw = await lijst(); assert.equal(nieuw.knoppen.length, 2); assert.notEqual(nieuw.kenmerk, s.kenmerk);
    console.log('✓ interne knoppen herkennen hun factuurregel buiten de shadow-root');

    await pg.evaluate(() => { document.querySelector('test-laadknop').setAttribute('disabled', ''); });
    assert.equal((await lijst()).knoppen.length, 1);
    console.log('✓ uitgeschakelde webcomponentknop wordt overgeslagen');

    // Het aanbieden van een blob is nog geen afgeronde download; pas de FileReader levert bytes op.
    await pg.evaluate(() => {
      window.__blobData = [];
      window.FrBrug = { blob: (sleutel, data) => window.__blobData.push(data) };
      const Orig = window.FileReader;
      window.__gewoneFileReader = Orig;
      window.FileReader = class extends Orig { readAsDataURL(blob){ setTimeout(() => super.readAsDataURL(blob), 5000); } };
      URL.createObjectURL(new Blob(['%PDF-1.4\nvoorbeeld'], { type: 'application/pdf' }));
    });
    assert.equal(await pg.evaluate(() => window.__blobData.length), 0);
    await pg.clock.fastForward(5100);
    await pg.waitForFunction(() => window.__blobData.length > 0);
    assert((await pg.evaluate(() => window.__blobData[0])).startsWith('data:application/pdf;base64,'));
    console.log('✓ trage blob meldt pas resultaat als de bytes zijn gelezen');

    await pg.setContent('<button id="pdf1">Download PDF 1</button><button id="pdf2">Download PDF 2</button>');
    await pg.evaluate(() => {
      window.__blobData = []; window.__blobUrls = [];
      window.FrBrug = { blob: (sleutel, data, naam, token) => window.__blobData.push({ data, naam, token }) };
      const Orig = window.__gewoneFileReader;
      window.FileReader = class extends Orig {
        readAsDataURL(blob){ const delay = blob.size === 18 ? 3000 : 7000; setTimeout(() => super.readAsDataURL(blob), delay); }
      };
      [1, 2].forEach(n => {
        document.getElementById('pdf' + n).onclick = () => {
          const blob = new Blob(['%PDF-1.4\n' + 'x'.repeat(n === 1 ? 9 : 19)], { type: 'application/pdf' });
          const u = URL.createObjectURL(blob); window.__blobUrls.push(u);
          const a = document.createElement('a'); a.href = u; a.download = 'F' + n + '.pdf'; document.body.appendChild(a); a.click(); a.remove();
        };
      });
    });
    s = await lijst();
    assert.equal(await js(`__fr.klik(0, ${JSON.stringify(s.knoppen[0].sleutel)}, 1)`), 'ok');
    assert.equal(await js(`__fr.klik(1, ${JSON.stringify(s.knoppen[1].sleutel)}, 2)`), 'ok');
    await pg.clock.fastForward(3100);
    await pg.waitForFunction(() => window.__blobData.length === 1);
    assert.deepEqual(await pg.evaluate(() => window.__blobData.map(x => x.token)), [1]);
    await pg.evaluate(() => {
      const a = document.createElement('a'); a.href = window.__blobUrls[0]; a.download = 'F1.pdf'; document.body.appendChild(a); a.click(); a.remove();
    });
    await js('__fr.blob(window.__blobUrls[0], "F1.pdf")');
    assert.equal(await js('__fr.downloadToken(window.__blobUrls[0])'), 1);
    await pg.clock.fastForward(4100);
    await pg.waitForFunction(() => window.__blobData.length === 2);
    assert.deepEqual(await pg.evaluate(() => window.__blobData.map(x => [x.token, x.naam])), [[1, 'F1.pdf'], [2, 'F2.pdf']]);
    console.log('✓ overlappende blobs worden ontdubbeld en houden hun eigen downloadvolgnummer');

    const detail = await b.newPage();
    await detail.route('https://leverancier.test/**', r => r.fulfill({ contentType: 'text/html', body: '<!doctype html><h1>Factuurdetail</h1>' }));
    await detail.goto('https://leverancier.test/detail');
    await detail.evaluate(() => { window.__details = []; window.FrBrug = { blob: (sleutel, data, naam, token) => window.__details.push(token) }; });
    await detail.evaluate(ROBOT.replace('__DOWNLOAD_TOKEN__', '7') + '\n;URL.createObjectURL(new Blob(["%PDF-1.4\\nvoorbeeld"], {type:"application/pdf"}));');
    await detail.waitForFunction(() => window.__details.length === 1);
    assert.deepEqual(await detail.evaluate(() => window.__details), [7]);
    await detail.close();
    console.log('✓ verse robot op een factuurdetail bewaart het native downloadvolgnummer');

    const vooraf = await b.newPage();
    await vooraf.setContent('<button id="cache">Download PDF</button>');
    await vooraf.evaluate(() => { window.__vooraf = []; window.FrBrug = { blob: (sleutel, data, naam, token) => window.__vooraf.push(token) }; });
    await vooraf.evaluate(ROBOT + '\n;window.__u=URL.createObjectURL(new Blob(["%PDF-1.4\\nvooraf"],{type:"application/pdf"}));document.getElementById("cache").onclick=()=>{const a=document.createElement("a");a.href=window.__u;a.download="F.pdf";a.click()};__fr.knoppen("download",50);');
    await vooraf.waitForFunction(() => window.__vooraf.length === 1);
    await vooraf.evaluate('__fr.klik(0, "", 1)');
    await vooraf.waitForFunction(() => window.__vooraf.length === 2);
    assert.deepEqual(await vooraf.evaluate(() => window.__vooraf), [0, 1]);
    await vooraf.close();
    console.log('✓ vooraf gemaakte PDF wordt bij de eerste klik aan die download gekoppeld');

    await pg.setContent(`<table><tbody>${regels(1, 2)}</tbody></table><button>Toon meer</button>`);
    s = await lijst();
    await pg.evaluate(extra => { document.querySelector('tbody').innerHTML = extra; }, regels(3, 4));
    assert.equal(await verder(s, 'meer'), 'gewijzigd');
    assert.equal(await js(`__fr.klik(0, ${JSON.stringify(s.knoppen[0].sleutel)})`), 'gewijzigd');
    console.log('✓ gewijzigde lijst wordt vlak voor klikken herkend');
  } finally { await b.close(); }
  console.log('errors', errors);
  assert.equal(errors.length, 0);
})().catch(e => { console.error(e); process.exitCode = 1; });
