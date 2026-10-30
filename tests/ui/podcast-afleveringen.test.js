// Afleveringen eerst: compacte kop bij één podcast (minder knoppen, de rest onder ⋯), Verder luisteren bovenaan,
// en op Luisteren de nieuwe afleveringen van alles wat je volgt.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    const rows = sel => pg.evaluate(s => [...document.querySelectorAll(s)].map(x => { const r = x.getBoundingClientRect(); return { top: r.top, bottom: r.bottom }; }), sel);
    const inView = l => l.filter(r => r.top >= 0 && r.bottom <= 844).length;

    // ---- Eén podcast, nog niet gevolgd: compacte kop, afleveringen meteen in beeld ----
    await pg.evaluate(() => pdOpen(Android._pc.top[0])); await pg.waitForTimeout(400);
    let l = await rows('#pd-eps .pcep');
    ok(l.length && l[0].top <= 300, 'eerste aflevering bovenaan in beeld (y=' + Math.round(l[0] && l[0].top) + ')');
    ok(inView(l) >= 6, 'minstens 6 afleveringen in beeld: ' + inView(l));
    ok(!(await pg.isVisible('#pd-resume')), 'nog niets beluisterd: geen Verder luisteren');
    ok(!(await pg.isVisible('#pd-dl')) && (await pg.getAttribute('#pd-sub', 'aria-pressed')) === 'false' && (await pg.textContent('#pd-sub')).trim() === 'Volgen', 'niet gevolgd: alleen Volgen');
    ok(/64 afleveringen · nieuwste vandaag/.test(await pg.textContent('#pd-meta')), 'aantal en nieuwste in de kop');
    ok(await pg.evaluate(() => getComputedStyle($('#pd-h1')).opacity === '0'), 'titel in de balk pas bij scrollen (staat al in de kop)');
    ok(await pg.evaluate(() => document.activeElement === $('#pd-title')), 'focus bij openen op de zichtbare titel, niet op de verborgen');
    await pg.evaluate(() => scrollTo(0, 40)); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => getComputedStyle($('#pd-h1')).opacity === '0'), 'een klein stukje gescrold (kop nog in beeld): nog geen titel in de balk');
    await pg.evaluate(() => scrollTo(0, 400)); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => getComputedStyle($('#pd-h1')).opacity === '1'), 'gescrold: titel in de balk');
    await pg.evaluate(() => scrollTo(0, 0)); await pg.waitForTimeout(200);
    // Knoppen groot genoeg om te tikken
    const small = await pg.evaluate(() => [...document.querySelectorAll('#s-podcast .pdchip, #s-podcast .pcmore, #pd-menu')].filter(x => x.offsetParent).map(x => x.getBoundingClientRect()).filter(r => r.height < 44 || r.width < 44).length);
    ok(small === 0, 'knoppen minstens 44 px');

    // ---- Menu ⋯: wat uit de kop verhuisde ----
    await pg.click('#pd-menu'); await pg.waitForTimeout(100);
    let acts = await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent));
    ok(['Over deze podcast', 'Afleveringen bijwerken', 'Instellingen voor downloaden', 'Op startscherm', 'Volgen'].every(t => acts.includes(t)), 'menu: over, bijwerken, downloads, startscherm, volgen: ' + acts.join(' | '));
    await pg.click('#sheet-acts button:has-text("Over deze podcast")'); await pg.waitForTimeout(150);
    ok(/Elke dag het belangrijkste nieuws/.test(await pg.textContent('#modal')), 'over deze podcast: de beschrijving');
    ok((await pg.textContent('#modal-ok')) === 'Sluiten' && !(await pg.isVisible('#modal-cancel')), 'alleen lezen: één knop Sluiten');
    await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    await pg.click('#pd-menu'); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Afleveringen bijwerken")'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => Android._pc.calls.some(c => c[0] === 'open' && c[2] === true)), 'bijwerken haalt de feed opnieuw op');
    ok(await pg.evaluate(() => document.activeElement === $('#pd-menu')), 'daarna staat de focus weer op ⋯');
    await pg.click('#pd-menu'); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Volgen")'); await pg.waitForTimeout(150);
    ok((await pg.getAttribute('#pd-sub', 'aria-pressed')) === 'true' && (await pg.textContent('#pd-sub')).trim() === 'Volgen' && await pg.isVisible('#pd-dl'), 'volgen via het menu: Volgen ingedrukt en Vanzelf downloaden');
    await pg.click('#pd-menu'); await pg.waitForTimeout(100);
    acts = await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent));
    ok(acts.includes('Niet meer volgen…') && acts.includes('Nieuwe afleveringen vanzelf downloaden'), 'gevolgd: ontvolgen en vanzelf downloaden in het menu');
    await pg.evaluate(() => closeSheet());

    // ---- Verder luisteren bij deze podcast: bovenaan, gemarkeerd ----
    await pg.evaluate(() => {
      const f = Android._pcFeed(Android._pc.top[0].feed), e = f.items[5];
      Android._pc.prog[e.key] = { p: 480000, d: e.dur * 1000, done: false };
      Android._pc.recent = [{ ep: e, pod: pcSlimPod(f.pod), t: Date.now() }];
      pdOpen(Android._pc.top[0]);
    });
    await pg.waitForTimeout(400);
    ok(await pg.isVisible('#pd-resume'), 'Verder luisteren zichtbaar');
    const first = await pg.evaluate(() => { const all = [...document.querySelectorAll('#s-podcast .pcep')]; return all[0] && all[0].closest('#pd-resume') ? all[0].getBoundingClientRect().top : -1; });
    ok(first >= 0 && first <= 300, 'Verder luisteren is de eerste rij (y=' + Math.round(first) + ')');
    ok(/Verder luisteren · nog \d+ min/.test(await pg.textContent('#pd-resume .pclab')) && /Aflevering 59/.test(await pg.textContent('#pd-resume b')), 'met titel en hoe lang nog');
    l = await rows('#s-podcast .pcep');
    ok(inView(l) >= 6, 'ook dan minstens 6 afleveringen in beeld: ' + inView(l));
    await pg.click('#pd-resume .pcepb'); await pg.waitForTimeout(400);
    ok(await pg.evaluate(() => Android._pc.st.ep && /Aflevering 59/.test(Android._pc.st.ep.title) && Android._pc.st.pos >= 480000), 'verder waar je was');
    ok(/Speelt nu/.test(await pg.textContent('#pd-resume .pclab')), 'de rij toont dat hij speelt');
    await pg.click('#pd-resume .pcepb'); await pg.waitForTimeout(400);
    ok(await pg.evaluate(() => Android._pc.st.status === 'paused'), 'nog eens tikken: pauze');
    await pg.screenshot({ path: __dirname + '/shots/podcast-afleveringen-' + scheme + '.png' });
    // Beluisterd: weg uit Verder luisteren
    await pg.click('#pd-resume .pcmore'); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Markeren als beluisterd")'); await pg.waitForTimeout(150);
    ok(!(await pg.isVisible('#pd-resume')) && await pg.evaluate(() => pdFeed.items[5].done === true), 'beluisterd: niet meer bij Verder luisteren, wel ✓ in de lijst');
    await pg.evaluate(() => { Android.podStop(); Android._pc.recent = []; pcPoll(true); });

    // ---- Luisteren: nieuwe afleveringen van alles wat je volgt ----
    await pg.evaluate(() => {
      const A = Android._pc;
      A.subs = [Object.assign({ fresh: 2 }, A.top[0]), Object.assign({ fresh: 0 }, A.top[1])];
      pcTabUser = false; show('home'); show('podcasts');
    });
    await pg.waitForTimeout(300);
    ok(await pg.isVisible('#pc-new-wrap'), 'Nieuwe afleveringen op Luisteren');
    const pos = await pg.evaluate(() => ({ cont: $('#pc-cont-wrap').getBoundingClientRect().top, nw: $('#pc-new-wrap').getBoundingClientRect().top, subs: $('#pc-subs-wrap').getBoundingClientRect().top, row: $('#pc-new .pcep').getBoundingClientRect() }));
    ok(pos.nw < pos.subs && pos.row.bottom <= 844, 'boven Mijn podcasts en zonder scrollen in beeld (y=' + Math.round(pos.row.top) + ')');
    ok((await pg.$$eval('#pc-new .pcep', x => x.length)) === 5 && (await pg.textContent('#pc-new-more')) === 'Alle 20', '5 nieuwste, met Alle 20');
    const firstTwo = await pg.evaluate(() => pcNew.slice(0, 4).map(r => r.pod.title));
    ok(firstTwo.includes('Nieuws van Vandaag') && firstTwo.includes('Tech in 20 minuten'), 'afleveringen van alle gevolgde podcasts door elkaar');
    ok(await pg.evaluate(() => pcNew.every((r, i) => !i || pcNew[i - 1].ep.date >= r.ep.date)), 'nieuwste eerst');
    ok((await pg.$$eval('#pc-new .pcep.isnew', x => x.length)) >= 2 && /Nieuws van Vandaag/.test(await pg.textContent('#pc-new .pcep:first-child small')), 'nieuw gemarkeerd, met de naam van de podcast');
    ok(await pg.evaluate(() => Android._pc.calls.some(c => c[0] === 'latest')), 'uit de bewaarde feeds (podLatest)');
    await pg.click('#pc-new-more'); await pg.waitForTimeout(100);
    ok((await pg.$$eval('#pc-new .pcep', x => x.length)) === 20 && (await pg.textContent('#pc-new-more')) === 'Minder', 'Alle: 20');
    await pg.click('#pc-new-more'); await pg.waitForTimeout(100);
    // Afspelen: de juiste aflevering, en daarna niet meer in de lijst
    const want = await pg.evaluate(() => pcNew[1].ep.key);
    await pg.click('#pc-new .pcep:nth-child(2) .pcepb'); await pg.waitForTimeout(400);
    ok(await pg.evaluate(k => Android._pc.st.ep && Android._pc.st.ep.key === k && Android._pc.st.status === 'playing', want), 'afspelen: de aangetikte aflevering');
    ok(await pg.evaluate(k => !pcNew.some(r => r.ep.key === k), want), 'wat speelt staat niet dubbel in de lijst');
    // Menu: beluisterd markeren haalt hem weg; naar de podcast
    const gone = await pg.evaluate(() => pcNew[0].ep.key);
    await pg.click('#pc-new .pcep:first-child .pcmore'); await pg.waitForTimeout(100);
    acts = await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent));
    ok(['Afspelen', 'Markeren als beluisterd', 'Uit deze lijst halen', '↓ Downloaden voor offline', 'Naar de podcast', 'Beschrijving'].every(t => acts.includes(t)), 'menu van een nieuwe aflevering');
    await pg.click('#sheet-acts button:has-text("Markeren als beluisterd")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(k => !pcNew.some(r => r.ep.key === k) && Android._pc.prog[k].done, gone), 'beluisterd: uit Nieuwe afleveringen');
    // Beschrijving: niet in de lijst, pas opgehaald als je erom vraagt
    ok(await pg.evaluate(() => pcNew.every(r => r.ep.desc === undefined || r.ep.desc === null)), 'lijst zonder beschrijvingen');
    await pg.click('#pc-new .pcep:first-child .pcmore'); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Beschrijving")'); await pg.waitForTimeout(150);
    ok(/Over aflevering/.test(await pg.textContent('#modal-text')) && (await pg.textContent('#modal-ok')) === 'Afspelen' && (await pg.textContent('#modal-cancel')) === 'Sluiten', 'beschrijving opgehaald, met Afspelen en Sluiten');
    await pg.click('#modal-cancel'); await pg.waitForTimeout(100);
    // Uit deze lijst halen: weg, en komt niet terug
    const hide = await pg.evaluate(() => pcNew[0].ep.key);
    await pg.click('#pc-new .pcep:first-child .pcmore'); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Uit deze lijst halen")'); await pg.waitForTimeout(150);
    await pg.evaluate(() => pcRenderListen());
    ok(await pg.evaluate(k => !pcNew.some(r => r.ep.key === k) && !Android._pc.prog[k], hide), 'uit de lijst gehaald (niet beluisterd): komt niet terug');
    // Uit Verder luisteren gehaald: ook niet terug bij Nieuwe afleveringen
    const forgot = await pg.evaluate(() => { const r = pcNew[2]; Android._pc.prog[r.ep.key] = { p: 60000, d: 1800000, done: false };
      Android._pc.recent.unshift({ ep: r.ep, pod: r.pod, t: Date.now() }); pcRenderListen(); return pcCont[0] && pcCont[0].ep.key === r.ep.key ? r.ep.key : ''; });
    await pg.click('#pc-cont .pcep:first-child .pcmore'); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Uit deze lijst halen")'); await pg.waitForTimeout(150);
    ok(!!forgot && await pg.evaluate(k => !pcCont.some(r => r.ep.key === k) && !pcNew.some(r => r.ep.key === k), forgot), 'uit Verder luisteren gehaald: niet ineens bij Nieuwe afleveringen');
    ok(await pg.evaluate(() => pcNew.every(r => r.ep.date >= Date.now() - 60 * 864e5)), 'niets ouder dan 60 dagen');
    // De teller "nieuw": een podcast bekijken zet hem op nul (podSeen), ook in Nieuwe afleveringen
    await pg.click('#pc-subs .pcsubw:first-child .pcsub'); await pg.waitForTimeout(400);
    ok(await pg.evaluate(() => current === 'podcast' && Android._pc.subs[0].fresh === 0), 'podcast bekeken: gezien');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(300);
    ok((await pg.$$eval('#pc-new .pcep.isnew', x => x.length)) === 0 && (await pg.$$eval('#pc-subs .badge', x => x.length)) === 0, 'daarna geen "nieuw" meer');
    // Mijn podcasts: één rij, tikken opent de podcast
    ok(await pg.evaluate(() => $('#pc-subs').classList.contains('pcrow')), 'Mijn podcasts als rij');
    await pg.evaluate(() => scrollTo(0, 0));
    await pg.screenshot({ path: __dirname + '/shots/podcast-luisteren-' + scheme + '.png' });
    // Niets meer nieuw: het blok verdwijnt
    await pg.evaluate(() => { Android._pc.subs = []; pcRenderListen(); });
    ok(!(await pg.isVisible('#pc-new-wrap')) && await pg.isVisible('#pc-listen-empty'), 'niets gevolgd: geen Nieuwe afleveringen');
    await pg.close();
  }
  // Smalle telefoons: beide knoppenrijen op één regel, afleveringen nog steeds bovenaan
  for (const [w, h] of [[360, 640], [320, 640]]) {
    const pg = await b.newPage({ viewport: { width: w, height: h } });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => { Android._pc.subs = [Object.assign({ fresh: 1 }, Android._pc.top[0])]; pdOpen(Android._pc.top[0]); }); await pg.waitForTimeout(400);
    const m = await pg.evaluate(() => {
      const rows = [...document.querySelectorAll('#pd-eps .pcep')].map(x => x.getBoundingClientRect());
      return { acts: $('.pdacts').getBoundingClientRect().height, bar: $('.pdbar').getBoundingClientRect().height, first: rows[0].top, vis: rows.filter(r => r.top >= 0 && r.bottom <= innerHeight).length, wide: document.documentElement.scrollWidth };
    });
    ok(m.acts <= 46 && m.bar <= 46, w + ' px: knoppen op één regel (' + m.acts + ', ' + m.bar + ')');
    ok(m.first <= 300 && m.vis >= 5, w + '×' + h + ': eerste aflevering op y=' + Math.round(m.first) + ', ' + m.vis + ' in beeld');
    ok(m.wide <= w, w + ' px: niet breder dan het scherm');
    ok(await pg.isVisible('#pd-dl .kort') && (await pg.getAttribute('#pd-dl', 'aria-label')).includes('vanzelf downloaden'), w + ' px: kort woord, volledige naam voor TalkBack');
    await pg.evaluate(() => show('podcasts')); await pg.waitForTimeout(300);
    // Eigen regel voor de naam van de podcast: past op 360 helemaal, op 320 nog ruim leesbaar (geen paar letters)
    ok(await pg.evaluate(w => { const r = document.querySelector('#pc-new .pcnp'); return !!r && (w >= 360 ? r.scrollWidth <= r.clientWidth + 1 : r.clientWidth >= 100); }, w), w + ' px: naam van de podcast leesbaar bij Nieuwe afleveringen');
    await pg.screenshot({ path: __dirname + '/shots/podcast-smal-' + w + '.png' });
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
