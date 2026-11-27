// Foto bewerken: de gedeelde bewerker (bijsnijden, draaien, rechtzetten, licht, afdekken), het voorstel dat de app zelf
// vindt (een scheve pas op een tafel, een paspoort op zijn kant), de tool (kiezen, bewerken, bewaren) en de koppelingen
// met Stamgegevens (kaartfoto bewerken, voorstel na een nieuwe foto) en ID-kopie (voorstel meteen toegepast, aanpassen).
const { chromium } = require('playwright'); const path = require('path');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + path.join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const until = (f, arg, ms) => pg.waitForFunction(f, arg, { timeout: ms || 8000 }).then(() => true, () => false);

    // ---------- 1. Rekenwerk ----------
    const r = await pg.evaluate(async () => {
      const o = fbLeeg(); o.q = [[0.1, 0.2], [0.8, 0.15], [0.9, 0.9], [0.05, 0.85]]; o.vlakken = [[0.1, 0.1, 0.2, 0.3]]; o.verhouding = 1.5;
      const p = fbKopie(o); for (let i = 0; i < 4; i++) fbDraaiOps(p, 1);
      const p2 = fbKopie(o); fbDraaiOps(p2, 1); const een = fbKopie(p2); fbDraaiOps(p2, -1);
      const same = (a, c) => JSON.stringify(fbSchoon(a)) === JSON.stringify(fbSchoon(c));
      // Scheve pas (500×316, 0,12 rad) op een donkere tafel van 800×600
      const foto = Android._foBeeld(), v = await fbVoorstel(foto, 'idkaart', 'front');
      const hoek = (x, y) => { const c = Math.cos(0.12), s = Math.sin(0.12); return [(400 + x * c - y * s) / 800, (300 + x * s + y * c) / 600]; };
      const verwacht = [hoek(-250, -158), hoek(250, -158), hoek(250, 158), hoek(-250, 158)];
      const fout = v ? Math.max(...v.ops.q.map((q, i) => Math.hypot(q[0] - verwacht[i][0], q[1] - verwacht[i][1]))) : 9;
      const uit = v ? await fbRender(foto, v.ops, 600) : null, px = (c, x, y) => { const d = c.getContext('2d').getImageData(x, y, 1, 1).data; return d[0] * 0.299 + d[1] * 0.587 + d[2] * 0.114; };
      // Een paspoort (fotopagina) op zijn kant: de strook tekens hoort onderaan
      const pag = document.createElement('canvas'); pag.width = 500; pag.height = 352; const x = pag.getContext('2d');
      x.fillStyle = '#f3efe2'; x.fillRect(0, 0, 500, 352); x.fillStyle = '#222'; x.font = '18px sans-serif'; x.fillText('KONINKRIJK DER NEDERLANDEN', 120, 30);
      x.fillRect(20, 60, 110, 140); x.font = 'bold 19px monospace';
      x.fillText('P<NLDJANSEN<<PIETER<JAN<<<<<<<<<<<<<', 8, 300); x.fillText('NXC12AB345NLD8001014M3401018<<<<<<6', 8, 330);
      const stand = document.createElement('canvas'); stand.width = 460; stand.height = 640; const y = stand.getContext('2d');
      y.fillStyle = '#2b2b33'; y.fillRect(0, 0, 460, 640); y.translate(230, 320); y.rotate(-Math.PI / 2); y.drawImage(pag, -250, -176);
      const vp = await fbVoorstel(stand, 'paspoort', 'front'), recht = vp ? await fbRender(stand, vp.ops, 500) : null;
      // Een gewone, wat donkere foto: alleen de belichting
      const dof = document.createElement('canvas'); dof.width = 200; dof.height = 150; const z = dof.getContext('2d');
      const gr = z.createLinearGradient(0, 0, 200, 0); gr.addColorStop(0, '#202020'); gr.addColorStop(1, '#707070'); z.fillStyle = gr; z.fillRect(0, 0, 200, 150);
      const vg = await fbVoorstel(dof, '', '');
      return { rond: same(p, o), heen: same(p2, o), kwart: een.draai === 90 && Math.abs(een.verhouding - 1 / 1.5) < 1e-9,
        maat: fbMaat(fbLeeg().q, 800, 600, 0, 400).join('x'), verh: (m => m[0] / m[1])(fbMaat(fbLeeg().q, 800, 600, 85.6 / 54, 2000)),
        redenen: v ? v.redenen.join() : '', fout, uitVerh: uit ? uit.width / uit.height : 0, midden: uit ? px(uit, uit.width / 2 | 0, uit.height / 4 | 0) : 0, rand: uit ? px(uit, 4, uit.height - 5) : 0,
        pRedenen: vp ? vp.redenen.join() : '', strook: recht ? fbStrook(recht) : '', liggend: recht ? recht.width > recht.height : false,
        gRedenen: vg ? vg.redenen.join() : '', niveau: vg ? vg.ops.niveau.join() : '' };
    });
    ok(r.rond && r.heen && r.kwart, 'vier kwartslagen = weer hetzelfde; heen en terug ook; de verhouding draait mee');
    ok(r.maat === '400x300' && Math.abs(r.verh - 85.6 / 54) < 0.01, 'maat van de uitkomst: hooguit de langste kant, met de gekozen verhouding');
    ok(r.redenen.includes('bijsnijden') && r.fout < 0.03, 'scheve pas gevonden, hoeken op de pas (fout ' + r.fout.toFixed(3) + ', ' + r.redenen + ')');
    ok(Math.abs(r.uitVerh - 85.6 / 54) < 0.03 && r.midden > 140 && r.rand > 120, 'uitkomst: rechtgetrokken pas in ID-kaartverhouding, zonder tafel (' + r.uitVerh.toFixed(3) + ', ' + Math.round(r.rand) + ')');
    ok(r.pRedenen.includes('draaien') && r.liggend && r.strook === 'onder', 'paspoort op zijn kant: rechtop, strook onderaan (' + r.pRedenen + ', ' + r.strook + ')');
    ok(r.gRedenen === 'licht' && r.niveau !== '0,255', 'gewone foto: alleen de belichting (' + r.niveau + ')');

    // ---------- 2. De bewerker ----------
    await pg.evaluate(() => { window.__fb = fotoBewerk({ src: Android._foBeeld().toDataURL('image/jpeg', 0.9), soort: 'idkaart', voorstel: true, titel: 'Test' }); });
    ok(await until(() => $('#fbdlg').classList.contains('on') && /Voorstel:/.test($('#fb-status').textContent)), 'bewerker open met het voorstel: ' + await pg.textContent('#fb-status'));
    ok(await pg.evaluate(() => fb.terug.length === 1 && fb.ops.q[0][0] > 0.05 && document.activeElement && document.activeElement.closest('#fbdlg') !== null), 'voorstel toegepast (ongedaan te maken), focus in het venster');
    const greep = await pg.$eval('#fb-vlak .fb-greep[data-i="0"] .ks-raak', e => { const r = e.getBoundingClientRect(); return [r.x + r.width / 2, r.y + r.height / 2, r.width]; });
    ok(greep[2] >= 44, 'greep minstens 44 px (' + Math.round(greep[2]) + ')');
    const voor = await pg.evaluate(() => fb.ops.q[0].slice());
    await pg.mouse.move(greep[0], greep[1]); await pg.mouse.down(); await pg.mouse.move(greep[0] + 30, greep[1] + 20, { steps: 4 }); await pg.mouse.up();
    const na = await pg.evaluate(() => fb.ops.q[0].slice());
    ok(na[0] > voor[0] && na[1] > voor[1], 'hoek gesleept');
    await pg.focus('#fb-vlak .fb-greep[data-i="1"]'); const k0 = await pg.evaluate(() => fb.ops.q[1][0]); await pg.keyboard.press('ArrowLeft');
    ok(await pg.evaluate(k => Math.abs(fb.ops.q[1][0] - (k - 0.01)) < 1e-6, k0), 'hoek met de pijltjes');
    await pg.click('#fb-tab-draai'); await pg.click('#fb-p-draai button:has-text("Kwartslag naar rechts")');
    ok(await pg.evaluate(() => fb.ops.draai === 90), 'kwartslag naar rechts');
    await pg.click('#fb-ongedaan'); ok(await pg.evaluate(() => fb.ops.draai === 0), 'ongedaan maken');
    await pg.fill('#fb-hoek', '10'); await pg.dispatchEvent('#fb-hoek', 'change');
    ok(await pg.evaluate(() => fb.hoek === 10 && fb.ops.q.every(p => p[0] >= 0 && p[0] <= 1 && p[1] >= 0 && p[1] <= 1)), 'rechtzetten: het vlak draait en blijft binnen de foto');
    await pg.click('#fb-tab-licht'); await pg.click('#fb-grijs');
    ok(await pg.evaluate(() => fb.ops.grijs && $('#fb-grijs').getAttribute('aria-checked') === 'true'), 'zwart-wit');
    await pg.click('#fb-tab-vlak'); await pg.waitForTimeout(400);
    const vb = await pg.$eval('#fb-vlak', e => { const r = e.getBoundingClientRect(); return [r.x, r.y, r.width, r.height]; });
    await pg.mouse.move(vb[0] + vb[2] * 0.2, vb[1] + vb[3] * 0.3); await pg.mouse.down(); await pg.mouse.move(vb[0] + vb[2] * 0.5, vb[1] + vb[3] * 0.5, { steps: 4 }); await pg.mouse.up();
    ok(await pg.evaluate(() => fb.ops.vlakken.length === 1 && document.querySelectorAll('#fb-vlak .fb-zwart').length >= 1), 'zwart vlak getrokken');
    await pg.screenshot({ path: __dirname + '/shots/foto-bewerken-' + scheme + '.png' });
    await pg.click('#fb-ok');
    const res = await pg.evaluate(() => window.__fb);
    ok(res && res.gewijzigd && res.ops.vlakken.length === 1 && res.ops.grijs && res.ops.v === 1 && !(await pg.evaluate(() => $('#fbdlg').classList.contains('on'))), 'Klaar: de bewerking komt terug, venster dicht');
    // Annuleren met wijzigingen vraagt eerst; terug (goBack) doet hetzelfde
    await pg.evaluate(() => { window.__fb2 = 'open'; fotoBewerk({ src: Android._foBeeld().toDataURL('image/jpeg', 0.9) }).then(x => window.__fb2 = x); });
    await until(() => $('#fbdlg').classList.contains('on'));
    // Onder de foto staat meteen hoe hij wordt: na bijsnijden even breed/hoog als de uitsnede, op Afdekken weg
    await pg.evaluate(() => { fbHeel(); fb.ops.q = [[0.25, 0.25], [0.75, 0.25], [0.75, 0.5], [0.25, 0.5]]; fbTeken(); });
    await until(() => { const d = $('#fb-straks-doek'), g = fbGedraaid(fb.foto, fb.ops.draai, fb.cache); return d.width > 1 && Math.abs(d.width / d.height - 2 * g.width / g.height) < 0.05; });
    ok(await pg.evaluate(() => !$('#fb-straks').hidden && $('#fb-straks').getBoundingClientRect().height > 40), 'bijsnijden: voorbeeld van de uitkomst onder de foto');
    await pg.screenshot({ path: __dirname + '/shots/foto-bewerken-bijsnijden-' + scheme + '.png' });
    ok(await pg.evaluate(() => { fbTab('vlak'); const weg = $('#fb-straks').hidden; fbTab('snij'); fbHeel(); return weg && !$('#fb-straks').hidden; }), 'Afdekken: geen tweede voorbeeld');
    // Zwarte vlakken gaan weg (met een melding) als de uitsnede verandert: anders liggen ze op iets anders
    ok(await pg.evaluate(() => { fb.ops.vlakken = [[0.1, 0.1, 0.1, 0.1]]; fbVerhouding(String(85.6 / 54)); return !fb.ops.vlakken.length && /zwarte vlakken zijn weg/.test($('#fb-status').textContent); }), 'uitsnede veranderd: zwarte vlakken weg, met een melding');
    ok(await pg.evaluate(() => { fb.ops.vlakken = [[0.1, 0.1, 0.1, 0.1]]; fbDraai(1); fbDraai(-1); return fb.ops.vlakken.length === 1 && Math.abs(fb.ops.vlakken[0][0] - 0.1) < 1e-9; }), 'een kwartslag draait de vlakken mee');
    // Automatisch verbeteren overschrijft niet wat je intussen zelf deed
    ok(await pg.evaluate(async () => { const p = fbAuto(); fbDraai(1); await p; return fb.ops.draai !== 0 && /niet toegepast/.test($('#fb-status').textContent); }), 'voorstel niet over je eigen aanpassing heen');
    await pg.click('#fb-tab-draai'); await pg.click('#fb-p-draai button:has-text("Kwartslag naar links")');
    await pg.evaluate(() => goBack()); ok(await until(() => $('#modal').classList.contains('on')), 'terug met wijzigingen: eerst een vraag');
    await pg.click('#modal-ok'); ok(await until(() => window.__fb2 === null && !$('#fbdlg').classList.contains('on')), 'gestopt: niets terug');

    // ---------- 3. De tool ----------
    await pg.evaluate(() => show('foto')); await pg.waitForTimeout(200);
    ok(await pg.isVisible('#fob-leeg button') && !(await pg.isVisible('#fob-kaart')), 'leeg: één knop om een foto te kiezen');
    await pg.click('#fob-leeg button'); await pg.waitForTimeout(150);
    if (await pg.isVisible('#sheet')) await pg.locator('#sheet-acts button', { hasText: 'Bestand op de telefoon' }).first().click();
    ok(await until(() => $('#fbdlg').classList.contains('on') && /Voorstel:/.test($('#fb-status').textContent)), 'na kiezen meteen de bewerker, met een voorstel');
    await pg.click('#fb-ok');
    ok(await until(() => !$('#fob-bewaar').disabled), 'terug op de tool, Bewaren kan');
    ok((await pg.inputValue('#fob-naamin')).startsWith('Testfoto'), 'naam voorgesteld');
    await pg.click('#fob-bewaar');
    await until(() => tsZichtbaar() && ts.tool === 'kluis' && !$('#ts-open').disabled || !$('#fob-klaar').hidden, null, 3000);
    if (await pg.evaluate(() => tsZichtbaar())) await pg.click('#ts-open');
    ok(await until(() => !$('#fob-klaar').hidden), 'bewaard: Bekijken, Delen, Opslaan');
    const bewaar = await pg.evaluate(() => Android._fo.calls.find(c => c[0] === 'bewaar'));
    ok(bewaar && bewaar[2].q && bewaar[2].v === 1 && bewaar[3].startsWith('Testfoto'), 'Java krijgt de bewerking, niet de pixels');
    await pg.evaluate(() => show('home'));
    ok(await pg.evaluate(() => Android._fo.calls.some(c => c[0] === 'wis') && !fob.sessie), 'tool verlaten: de gekozen foto is weg');

    // ---------- 4. Stamgegevens ----------
    const foto = await pg.evaluate(() => Android._foBeeld().toDataURL('image/jpeg', 0.85));
    await pg.evaluate(foto => {
      const id = n => '00000000-0000-0000-0000-00000000000' + n;
      Android._cm.records = [{ id: id(1), type: 'persoon', label: 'Rene', revision: 1, fields: { naam: 'Van Es', voornamen: 'Rene' } },
        { id: id(2), type: 'identiteit', label: 'ID-kaart Rene', revision: 1, fields: { persoon: id(1), documentsoort: 'identiteitskaart', document: 'IX1', geldig: '2034-01-01' }, images: { front: foto } }];
    }, foto);
    await pg.evaluate(() => show('masterdata')); await pg.click('#ts-open'); await until(() => cmOpen() && cm.recordsReady);
    const T = require('./_stamtabs')(pg); await T.kaart('00000000-0000-0000-0000-000000000002');
    ok(await pg.isVisible('#cmc-bewerk'), 'kaartje: Foto bewerken');
    await pg.click('#cmc-bewerk'); ok(await until(() => fbAan()), 'één foto: meteen de bewerker');
    ok(await pg.evaluate(() => $('#fb-tab-vlak').hidden), 'geen afdekken bij een kaartfoto (de app leest hem nog)');
    await pg.click('#fb-tab-draai'); await pg.click('#fb-p-draai button:has-text("Kwartslag naar rechts")'); await pg.click('#fb-ok');
    // Eerst voor en na naast elkaar; pas na nog een keer Bewaren wordt de foto vervangen
    ok(await until(() => !$('#fb-verg').hidden && !!$('#fb-verg-na').getAttribute('src') && !$('#fb-ok').disabled) && await pg.evaluate(() => !(Android._cm.bewerkt || []).length && $('#fb-werk').hidden), 'eerst een voorbeeld: voor en na naast elkaar, nog niets bewaard');
    ok(/Eerdere versies/.test(await pg.textContent('#fb-verg-uitleg')), 'zegt dat de foto van nu bewaard blijft');
    await pg.screenshot({ path: __dirname + '/shots/foto-bewerken-controle-' + scheme + '.png' });
    await pg.click('#fb-verg-terug'); ok(await pg.evaluate(() => $('#fb-verg').hidden && !$('#fb-werk').hidden && fb.ops.draai === 90), 'Verder bewerken: terug in de bewerker, niets kwijt');
    await pg.click('#fb-ok'); await until(() => !$('#fb-verg').hidden && !$('#fb-ok').disabled); await pg.click('#fb-ok');
    ok(await until(() => (Android._cm.bewerkt || []).length === 1 && !cm.mutating && current === 'cmkaart'), 'bewaard via masterPhotoBewerk');
    // De vorige foto staat bij Eerdere versies; bekijken naast de foto van nu en terugzetten
    ok(await until(() => !!$('#cmc-versies') && /Eén eerdere versie/.test($('#cmc-versies').textContent)), 'kaartje: Eén eerdere versie');
    await pg.click('#cmc-versies'); await pg.locator('#sheet-acts button', { hasText: 'Voorkant van' }).first().click();
    ok(await until(() => fbAan() && fb.modus === 'los' && !!$('#fb-verg-na').getAttribute('src') && $('#fb-verg-terug').hidden && $('#fb-ok').textContent === 'Terugzetten'), 'eerdere versie naast de foto van nu, met Terugzetten');
    await pg.click('#fb-ok');
    ok(await until(() => Android._cm.terug === 1 && !cm.mutating && current === 'cmkaart' && !fbAan()), 'teruggezet');
    ok(await pg.evaluate(() => Android._cm.oud.length === 1 && Android._cm.events.some(e => e.action === 'photo_restore')), 'de foto van daarnet is nu zelf een eerdere versie; in Activiteiten');
    ok(await pg.evaluate(() => Android._cm.bewerkt[0].side === 'front' && Android._cm.bewerkt[0].ops.draai === 90 && Android._cm.events.some(e => e.action === 'photo_edit')), 'de bewerking (draai) en in Activiteiten: Kaartfoto bewerkt');
    // Na een nieuwe kaartfoto: een voorstel op het kaartje, met Toepassen
    await pg.evaluate(() => { cmiPhotoAfterUnlock({ saved: true, id: '00000000-0000-0000-0000-000000000002', side: 'front' }); cmc.fotos = {}; cmKaartLoad('00000000-0000-0000-0000-000000000002'); });
    ok(await until(() => !!$('#cmf-front')), 'voorstel op het kaartje na een nieuwe foto');
    ok(/Voorstel voor de voorkant: bijgesneden/.test(await pg.textContent('#cmf-front')), 'zegt wat het voorstel doet');
    await pg.locator('#cmf-front button', { hasText: 'Toepassen' }).click();
    ok(await until(() => (Android._cm.bewerkt || []).length === 2 && !cm.mutating), 'Toepassen bewaart het voorstel');
    ok(await pg.evaluate(() => Android._cm.bewerkt[1].ops.q[0][0] > 0.05 && !$('#cmf-front')), 'met het gevonden vlak; voorstel weg');
    // Ook op het bewerkscherm (blok Foto's): per kant een knop Bewerken
    await T.blok('00000000-0000-0000-0000-000000000002', 'fotos');
    ok(await until(() => !$('#cmi-front-bewerk').hidden && $('#cmi-back-bewerk').hidden), "bewerkscherm: Voorzijde bewerken zodra de foto er staat (geen achterkant: geen knop)");
    await pg.click('#cmi-front-bewerk'); await until(() => fbAan());
    await pg.click('#fb-tab-draai'); await pg.click('#fb-p-draai button:has-text("Kwartslag naar links")'); await pg.click('#fb-ok');
    await until(() => !$('#fb-verg').hidden && !$('#fb-ok').disabled); await pg.click('#fb-ok');
    ok(await until(() => (Android._cm.bewerkt || []).length === 3 && !cm.mutating && Android._cm.bewerkt[2].ops.draai === 270 && !$('#cmi-front-paper').hidden), 'bewaard vanaf het bewerkscherm; de foto staat er weer');
    await pg.evaluate(() => cmLock()); await pg.waitForTimeout(100);

    // ---------- 5. ID-kopie ----------
    await pg.evaluate(() => { Android._idc.scene = () => Android._foBeeld(); window.__testIdPages = 1; show('idcopy'); });
    await pg.evaluate(() => idCopyKiezer());
    ok(await until(() => idc.pages.length === 1 && idc.pages[0].auto === true && !idc.loading), 'ID-kopie: voorstel meteen toegepast');
    ok(/Voorstel toegepast/.test(await pg.textContent('#id-status')) && await pg.isVisible('#id-bewerk'), 'status zegt het, met Bijsnijden en rechtzetten');
    const opsVoor = await pg.evaluate(() => JSON.stringify(idc.pages[0].ops));
    await pg.click('#id-bewerk'); ok(await until(() => fbAan() && fb.ops.q[0][0] > 0.05), 'bewerker opent met het toegepaste voorstel (op de pagina zonder bewerking)');
    ok(await pg.evaluate(() => Android._idc.bewerkt.some(x => x.bron)), 'de pagina zonder bewerking opgehaald');
    await pg.click('#fb-orig'); await pg.click('#fb-ok');
    ok(await until(() => idc.pages[0].ops === null && !idc.loading), 'Origineel: geen bewerking meer');
    await pg.click('#id-bewerk'); await until(() => fbAan()); await pg.click('#fb-auto'); await until(() => /Voorstel:/.test($('#fb-status').textContent)); await pg.click('#fb-ok');
    ok(await until(() => idc.pages[0].ops && !idc.loading), 'opnieuw het voorstel: ' + (opsVoor ? 'ok' : ''));
    // Lukt de bewerking niet (meer), dan gaat de pagina zonder bewerking verder in plaats van vast te lopen
    await pg.evaluate(() => { Android._idc.fout = 'Ongeldige bewerking'; idCopyLoad(); });
    ok(await until(() => idc.pages[0].ops === null && !idc.loading && !!idc.meta && /lukte niet/.test($('#id-status').textContent)), 'bewerking geweigerd: pagina weer zonder bewerking');
    await pg.evaluate(() => { Android._idc.fout = ''; idc.pages[0].ops = fbSchoon(Object.assign(fbLeeg(), { q: [[0.1, 0.1], [0.9, 0.1], [0.9, 0.9], [0.1, 0.9]] })); idc.pages[0].opsTurn = idc.pages[0].turn; idCopyLoad(); });
    await until(() => !idc.loading && !!idc.meta);
    await pg.fill('#id-recipient', 'Test'); await pg.fill('#id-purpose', 'Test'); await pg.click('#id-add').catch(() => {});
    await pg.evaluate(() => idCopyAdd([0.1, 0.1, 0.2, 0.2])); await pg.check('#id-checked'); await pg.click('#id-save');
    ok(await until(() => Android._idc.saved && Android._idc.saved.pages[0].ops && Android._idc.saved.pages[0].ops.v === 1), 'de bewerking gaat mee bij het maken van de kopie (Java past hem toe)');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
  process.exit(errs.length ? 1 : 0);
})();
