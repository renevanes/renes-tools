// Documentenkluis → Brief scannen: hoeken van het papier zoeken, rechttrekken, scanfilter, pagina's ordenen en als één
// PDF in de kluis (in de map waar je staat). Het beeld blijft in het geheugen en gaat weg bij annuleren, op slot en weggaan.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const url = 'file://' + require('path').join(__dirname, 'ui', 'index.html');

  /* Een nagemaakte foto: donkere ondergrond met wat ruis, een wit vel (vier hoeken) met regels tekst, en een schaduw */
  const maakFoto = () => window.__maakFoto = (w, h, q, schaduw) => {
    const c = document.createElement('canvas'); c.width = w; c.height = h; const x = c.getContext('2d');
    x.fillStyle = '#3a3530'; x.fillRect(0, 0, w, h);
    for (let i = 0; i < 400; i++) { x.fillStyle = 'rgba(0,0,0,' + (i % 5) / 20 + ')'; x.fillRect((i * 97) % w, (i * 53) % h, 6, 6); }
    x.beginPath(); x.moveTo(q[0][0], q[0][1]); for (let i = 1; i < 4; i++) x.lineTo(q[i][0], q[i][1]); x.closePath(); x.fillStyle = '#f2f0ea'; x.fill();
    if (schaduw) { const g = x.createLinearGradient(0, 0, w, 0); g.addColorStop(0, 'rgba(0,0,0,0.35)'); g.addColorStop(1, 'rgba(0,0,0,0)'); x.save(); x.clip(); x.fillStyle = g; x.fillRect(0, 0, w, h); x.restore(); }
    // tekstregels binnen het vel (via de projectie van het vlak)
    const H = ksHomografie([[0, 0], [1, 0], [1, 1], [0, 1]], q); x.fillStyle = '#202020';
    for (let r = 0; r < 8; r++) for (let s = 0; s < 30; s++) { const [px, py] = ksPunt(H, 0.15 + s * 0.023, 0.15 + r * 0.08); x.fillRect(px, py, 4, 3); }
    return c;
  };

  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(url); await pg.waitForTimeout(300);
    await pg.evaluate(maakFoto);

    if (scheme === 'light') {
      // ---------- rekenwerk ----------
      const H = await pg.evaluate(() => { const q = [[10, 20], [300, 5], [320, 410], [0, 380]], M = ksHomografie([[0, 0], [100, 0], [100, 140], [0, 140]], q); return [[0, 0], [100, 0], [100, 140], [0, 140]].map(p => ksPunt(M, p[0], p[1])).map((p, i) => Math.hypot(p[0] - q[i][0], p[1] - q[i][1])); });
      ok(H.every(d => d < 1e-6), 'projectie legt de hoeken van de pagina precies op het vlak');
      // (bij een ruit is "linksboven" niet eenduidig: de beste van de vier draaiingen telt)
      const zoek = async (q, w = 800, h = 600) => pg.evaluate(([q, w, h]) => { const f = __maakFoto(w, h, q); const r = ksZoekHoeken(f); if (!r) return null;
        let best = null; for (let k = 0; k < 4; k++) { const d = r.map((p, i) => Math.hypot(p[0] - q[(i + k) % 4][0], p[1] - q[(i + k) % 4][1])); if (!best || Math.max(...d) < Math.max(...best)) best = d; } return best; }, [q, w, h]);
      let d = await zoek([[120, 60], [620, 90], [600, 560], [140, 540]]);
      ok(d && d.every(x => x < 14), 'hoeken van een scheef vel gevonden (afwijking ' + (d && d.map(Math.round)) + ' px op 800)');
      d = await zoek([[400, 20], [760, 300], [400, 580], [40, 300]]);
      ok(d && d.every(x => x < 14), 'ook een vel dat een kwart gedraaid ligt (ruit): ' + (d && d.map(Math.round)));
      d = await zoek([[60, 40], [700, 40], [700, 560], [60, 560]], 800, 600);
      ok(d && d.every(x => x < 14), 'een recht vel: ' + (d && d.map(Math.round)));
      ok(await pg.evaluate(() => { const c = document.createElement('canvas'); c.width = 400; c.height = 300; const x = c.getContext('2d'); x.fillStyle = '#444'; x.fillRect(0, 0, 400, 300); x.fillStyle = '#eee'; x.fillRect(10, 10, 30, 20); return ksZoekHoeken(c) === null; }), 'geen papier (alleen een klein licht vlekje): niets gevonden');
      ok(await pg.evaluate(() => ksOrden([[5, 9], [0, 0], [9, 0], [0, 9]]).map(p => p.join(',')).join(' ') === '0,0 9,0 5,9 0,9'), 'hoeken op volgorde: linksboven, rechtsboven, rechtsonder, linksonder');
      ok(await pg.evaluate(() => ksBol([[0, 0], [10, 0], [10, 10], [0, 10]]) && !ksBol([[0, 0], [10, 10], [10, 0], [0, 10]]) && !ksBol([[10, 0], [0, 0], [0, 10], [10, 10]])), 'over elkaar gesleepte of gespiegelde hoeken tellen niet als vlak');
      // rechttrekken + scan: A4-verhouding, witte achtergrond (ook in de schaduw), tekst donker
      const r = await pg.evaluate(async () => {
        const a = 0.1, hw = 200, hh = 283, q = [[-hw, -hh], [hw, -hh], [hw, hh], [-hw, hh]].map(([x, y]) => [350 + x * Math.cos(a) - y * Math.sin(a), 350 + x * Math.sin(a) + y * Math.cos(a)]), f = __maakFoto(700, 700, q, true); // een A4-vel, iets gedraaid
        const c = await ksRecht(f, ksOrden(ksZoekHoeken(f)));
        const x = c.getContext('2d'); x.putImageData(ksFilter(x.getImageData(0, 0, c.width, c.height), 'scan'), 0, 0);
        const px = (u, v) => x.getImageData(Math.round(u * c.width), Math.round(v * c.height), 1, 1).data;
        const rand = [px(0.05, 0.5), px(0.95, 0.5), px(0.5, 0.05), px(0.06, 0.95)].map(p => p[0]);
        let donker = 0; const d = x.getImageData(0, 0, c.width, c.height).data; for (let i = 0; i < d.length; i += 4) if (d[i] < 90) donker++;
        const grijs = [px(0.3, 0.3), px(0.7, 0.6)].every(p => p[0] === p[1] && p[1] === p[2]);
        return { w: c.width, h: c.height, rand, donker: donker / (d.length / 4), grijs };
      });
      ok(Math.abs(r.h / r.w - 1.414) < 0.05 && r.w <= 2000 && r.h <= 2000, 'rechtgezet met de verhouding van het vel: ' + r.w + '×' + r.h);
      ok(r.rand.every(v => v > 235), 'scan: het papier is wit, ook aan de kant met de schaduw: ' + r.rand);
      ok(r.donker > 0.002 && r.donker < 0.2, 'scan: de tekst blijft donker (' + (r.donker * 100).toFixed(1) + '% donker)');
      ok(r.grijs, 'scan in zwart-wit is grijs');
      ok(await pg.evaluate(() => { const c = document.createElement('canvas'); c.width = 30; c.height = 20; const k = ksKwart(c); return k.width === 20 && k.height === 30; }), 'draaien: breedte en hoogte wisselen');
    }

    // ---------- het scherm ----------
    await pg.evaluate(() => { Android._kl.open = true; Android._kl.docs = [{ id: 'k1abcdef', name: 'Paspoort.pdf', mime: 'application/pdf', size: 8200, t: Date.now() - 864e5, category: 'Wonen' }]; window.__testCamera = true; show('kluis'); });
    await pg.waitForTimeout(150);
    if (await pg.evaluate(() => typeof tsZichtbaar === 'function' && tsZichtbaar())) { await pg.click('#ts-open'); await pg.waitForFunction(() => $('#kl-open').style.display === 'block'); }
    ok(await pg.isVisible('#kl-scanknop'), scheme + ': knop Brief scannen in de kluis');
    await pg.click('#kl-scanknop');
    await pg.waitForFunction(() => ksCamOpen());
    await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => $('#s-kluis').inert === true || $('#s-kluis').hasAttribute('inert')), scheme + ': de camera ligt over de kluis (de kluis eronder is niet te bedienen)');
    ok(await pg.evaluate(() => tsLagen().includes('kl-cam')), scheme + ': de camera sluit bij het slotscherm (TS_LAGEN)');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(50);
    ok(await pg.evaluate(() => !ksCamOpen() && current === 'kluis' && Android._kl.camDicht >= 1 && !ks.stream), scheme + ': terug sluit de camera (en geeft hem vrij), je blijft in de kluis');
    // in de map Wonen een brief scannen
    await pg.evaluate(() => { klKies('map:Wonen'); ksNaFoto(__maakFoto(800, 600, [[120, 60], [620, 90], [600, 560], [140, 540]])); });
    await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => !$('#kl-scan').hidden && $('#kl-lijstkaart').hidden && !$('#ks-hoekstap').hidden && $('#ks-paginastap').hidden), scheme + ': na de foto: de hoeken bijstellen, in plaats van de lijst');
    ok(await pg.evaluate(() => document.querySelectorAll('#ks-vlak .ks-hoek').length === 4 && /Klopt het vlak/.test($('#ks-uitleg').textContent)), scheme + ': vier hoeken op het gevonden vlak');
    const raak = await pg.evaluate(() => { const r = $('#ks-vlak .ks-raak').getBoundingClientRect(); return [r.width, r.height]; });
    ok(raak[0] >= 44 && raak[1] >= 44, scheme + ': een hoek is groot genoeg om te pakken (' + raak.map(Math.round) + ')');
    // slepen
    const voor = await pg.evaluate(() => ks.hoeken[0].slice());
    const box = await pg.evaluate(() => { const r = $('#ks-vlak').getBoundingClientRect(); return { x: r.left, y: r.top, w: r.width, h: r.height, fw: ks.foto.width }; });
    const sx = box.x + voor[0] / box.fw * box.w, sy = box.y + voor[1] / box.fw * box.w;
    await pg.mouse.move(sx, sy); await pg.mouse.down(); await pg.mouse.move(sx + 20, sy + 15, { steps: 4 }); await pg.mouse.up();
    const na = await pg.evaluate(() => ks.hoeken[0].slice());
    ok(Math.abs((na[0] - voor[0]) / box.fw * box.w - 20) < 3 && Math.abs((na[1] - voor[1]) / box.fw * box.w - 15) < 3, scheme + ': een hoek verslepen: ' + voor.map(Math.round) + ' → ' + na.map(Math.round));
    await pg.focus('#ks-vlak .ks-hoek[data-i="1"]'); const k1 = await pg.evaluate(() => ks.hoeken[1][0]);
    await pg.keyboard.press('ArrowLeft');
    ok(await pg.evaluate(k1 => ks.hoeken[1][0] < k1 && document.activeElement.dataset.i === '1', k1), scheme + ': met de pijltjes kan het ook (focus blijft op de hoek)');
    // een hoek over een andere: geen bruikbaar vlak
    await pg.evaluate(() => { ks.hoeken[0] = [ks.hoeken[2][0] + 10, ks.hoeken[2][1] + 10]; ksTekenHoeken(); });
    ok(await pg.isDisabled('#ks-goed'), scheme + ': hoeken over elkaar: Pagina gebruiken kan niet');
    await pg.click('button:has-text("Hele foto")');
    ok(!(await pg.isDisabled('#ks-goed')), scheme + ': Hele foto zet de hoeken terug op de foto');
    await pg.click('#ks-goed');
    await pg.waitForFunction(() => !ks.foto && ks.paginas.length === 1, null, { timeout: 20000 });
    ok(!(await pg.isDisabled('#ks-bewaar')), scheme + ': Bewaren kan meteen');
    ok(await pg.evaluate(() => !$('#ks-paginastap').hidden && document.querySelectorAll('#ks-paginas .ks-pag').length === 1 && /^data:image\/jpeg/.test(ks.paginas[0].jpeg)), scheme + ': de pagina staat bij de pagina\'s');
    ok(await pg.evaluate(() => /Wonen/.test($('#ks-waar').textContent) && /Brief van/.test($('#ks-naam').placeholder)), scheme + ': zegt in welke map hij komt, met een voorgestelde naam: ' + await pg.evaluate(() => $('#ks-waar').textContent + ' | ' + $('#ks-naam').placeholder));
    // tweede pagina, in kleur
    await pg.evaluate(() => { ksNaFoto(__maakFoto(700, 700, [[400, 20], [680, 330], [380, 680], [20, 320]])); $('#ks-modus').value = 'kleur'; ksModus('kleur'); });
    ok(await pg.evaluate(() => $('#ks-nr').textContent.includes('2')), scheme + ': dit wordt pagina 2');
    await pg.click('#ks-goed');
    await pg.waitForFunction(() => !ks.foto && ks.paginas.length === 2, null, { timeout: 20000 });
    ok(await pg.evaluate(() => ks.paginas[1].modus === 'kleur' && store.get('klScanModus') === 'kleur'), scheme + ': de soort scan wordt onthouden');
    const w2 = await pg.evaluate(() => [ks.paginas[1].w, ks.paginas[1].h]);
    await pg.click('[aria-label="Pagina 2 draaien"]'); await pg.waitForFunction(w => ks.paginas[1].w === w, w2[1]);
    ok(await pg.evaluate(w2 => ks.paginas[1].w === w2[1] && ks.paginas[1].h === w2[0], w2), scheme + ': pagina draaien');
    const p2 = await pg.evaluate(() => ks.paginas[1].jpeg.length);
    await pg.click('[aria-label="Pagina 2 eerder"]');
    ok(await pg.evaluate(p2 => ks.paginas[0].jpeg.length === p2 && $('[aria-label="Pagina 1 eerder"]').disabled, p2), scheme + ': pagina eerder zetten');
    // terug met pagina's: eerst vragen
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => $('#modal').classList.contains('on') && ks.paginas.length === 2), scheme + ': terug vraagt eerst of de scan weg mag');
    await pg.evaluate(() => closeModal(null)); await pg.waitForTimeout(50);
    // bewaren
    await pg.fill('#ks-naam', 'Brief <b>gemeente</b>');
    await pg.click('#ks-bewaar');
    await pg.waitForFunction(() => Android._kl.gescand, null, { timeout: 5000 }); await pg.waitForTimeout(100);
    const g = await pg.evaluate(() => Android._kl.gescand);
    ok(g.paginas === 2 && g.map === 'Wonen' && g.naam === 'Brief <b>gemeente</b>', scheme + ': twee pagina\'s naar de kluis, in de map Wonen: ' + JSON.stringify(g));
    ok(await pg.evaluate(() => !ks.paginas.length && $('#kl-scan').hidden && !!klEditId && /gemeente/.test($('#kl-edit-title').textContent) && !$('#kl-edit-title b')), scheme + ': daarna de documentkaart van de nieuwe brief (naam als tekst)');
    ok(await pg.evaluate(() => !($('#ks-paginas').children.length) && $('#ks-foto').width === 1), scheme + ': geen pagina\'s meer in de pagina');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(50);

    // op slot terwijl je scant: de scan is weg
    await pg.evaluate(() => { ksNaFoto(__maakFoto(400, 300, [[40, 30], [360, 30], [360, 270], [40, 270]])); });
    await pg.click('#ks-goed'); await pg.waitForFunction(() => ks.paginas.length === 1, null, { timeout: 20000 });
    await pg.evaluate(() => { const v = window.klVanzelf; window.klVanzelf = () => false; Android.kluisClose(); klRender(); window.klVanzelf = v; }); await pg.waitForTimeout(50);
    ok(await pg.evaluate(() => !ks.paginas.length && $('#kl-scan').hidden && !$('#ks-paginas').children.length), scheme + ': kluis vanzelf op slot: de scan is weg');
    await pg.evaluate(() => { Android._kl.open = true; klRender(); ksNaFoto(__maakFoto(400, 300, [[40, 30], [360, 30], [360, 270], [40, 270]])); klLock(); }); await pg.waitForTimeout(50);
    ok(await pg.evaluate(() => !ks.foto && $('#kl-scan').hidden), scheme + ': op slot doen met de hand: de scan is weg');
    // weg van de kluis: ook weg
    await pg.evaluate(() => { Android._kl.open = true; klRender(); ksNaFoto(__maakFoto(400, 300, [[40, 30], [360, 30], [360, 270], [40, 270]])); show('home'); });
    await pg.waitForTimeout(50);
    ok(await pg.evaluate(() => !ks.foto && $('#kl-scan').hidden), scheme + ': weg van het kluisscherm: de foto is weg');
    // geen toestemming voor de camera
    await pg.evaluate(() => { window.__testCamera = false; show('kluis'); });
    await pg.waitForTimeout(100);
    await pg.evaluate(() => ksStart()); await pg.waitForTimeout(120);
    ok(await pg.evaluate(() => !ksCamOpen()), scheme + ': zonder toestemming gaat de camera niet open');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
  process.exit(errs.length ? 1 : 0);
})();
