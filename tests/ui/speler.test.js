const { chromium } = require('playwright');
// De ene speler: mini-balk, volledig scherm, kleur, vegen, Hierna, radio en podcast nemen het van elkaar over.
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme, hasTouch: false });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    console.log('bar hidden at start', !(await pg.isVisible('#plbar')));

    // Radio starten: mini-balk verschijnt
    await pg.click('#tile-radio'); await pg.waitForTimeout(400);
    await pg.click('#rd-list .srow:nth-child(2) .sbtn'); await pg.waitForTimeout(1300);
    console.log('bar visible', await pg.isVisible('#plbar'), 'bar title', await pg.textContent('#plbar-t'), '|', await pg.textContent('#plbar-s'));
    console.log('body has-plbar', await pg.evaluate(() => document.body.classList.contains('has-plbar')));
    await pg.screenshot({ path: __dirname + '/shots/pl-bar-radio-' + scheme + '.png' });

    // Openen
    await pg.click('#plbar-open'); await pg.waitForTimeout(450);
    console.log('player open', await pg.isVisible('#player'), 'kind', await pg.textContent('#pl-kind'), 'from', await pg.textContent('#pl-from'));
    console.log('title', await pg.textContent('#pl-title'), '| sub', await pg.textContent('#pl-sub'));
    console.log('seek visible (shift)', await pg.isVisible('#pl-seekwrap'), 'left', await pg.textContent('#pl-left'), 'radio extras', await pg.isVisible('#pl-radio'));
    console.log('speed hidden for radio', !(await pg.isVisible('#pl-speed')));
    await pg.screenshot({ path: __dirname + '/shots/pl-radio-' + scheme + '.png' });
    await pg.screenshot({ path: __dirname + '/shots/pl-radio-full-' + scheme + '.png', fullPage: true });
    // Terugspoelen
    await pg.click('#pl-back'); await pg.waitForTimeout(400);
    console.log('after rew left', await pg.textContent('#pl-left'), 'golive visible', await pg.isVisible('#pl-golive'));
    await pg.click('#pl-golive'); await pg.waitForTimeout(400);
    console.log('after live', await pg.textContent('#pl-left'));
    // Favoriet in de speler, dan Hierna = je zenders
    await pg.click('#pl-fav'); await pg.waitForTimeout(200);
    console.log('fav on', await pg.evaluate(() => $('#pl-fav').classList.contains('on')), 'queue rows', await pg.$$eval('#pl-queue .pl-qrow', x => x.length));
    // Volgende zender (alleen één favoriet: dezelfde) → melding of wissel; voeg er een toe en wissel
    await pg.evaluate(() => { Android.radioToggleFav(JSON.stringify(Android._rd.list[3])); rdFavs = rdJson(Android.radioFavorites(), []); plQueueSig = ''; plRender(); });
    await pg.click('#pl-next'); await pg.waitForTimeout(1300);
    console.log('after next station', await pg.textContent('#pl-from'), 'favs', await pg.$$eval('#pl-queue .pl-qrow', x => x.length));
    // Slaaptimer vanuit de speler: venster ligt boven de speler
    await pg.click('#pl-sleep'); await pg.waitForTimeout(200);
    console.log('sleep dlg on top', await pg.evaluate(() => { const r = $('#sl-go').getBoundingClientRect(); return document.elementFromPoint(r.x + 5, r.y + 5) === $('#sl-go'); }), 'state', await pg.textContent('#sl-state'));
    await pg.click('#sl-quick button[data-m="20"]'); await pg.click('#sl-go'); await pg.waitForTimeout(400);
    console.log('sleep pill', await pg.textContent('#pl-sleep'));
    // Verkleinen met terug
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(400);
    console.log('closed', !(await pg.isVisible('#player')), 'bar back', await pg.isVisible('#plbar'), 'screen', await pg.evaluate(() => current));

    // Podcast starten: neemt het over van de radio, slaaptimer loopt door (in de app zelf; nep-brug houdt hem per speler)
    await pg.evaluate(() => show('podcasts')); await pg.waitForTimeout(300);
    await pg.click('#pc-list .srow:nth-child(1) .sbtn'); await pg.waitForTimeout(500);
    await pg.click('#pd-eps .pcep:nth-child(2) .pcepb'); await pg.waitForTimeout(600);
    console.log('radio stopped', await pg.evaluate(() => Android._rd.st.status), 'src', await pg.evaluate(() => Android.playerSrc()), 'bar', await pg.textContent('#plbar-t'));
    // Hierna: andere aflevering als volgende zetten
    await pg.click('#pd-eps .pcep:nth-child(4) .pcmore'); await pg.waitForTimeout(200);
    console.log('menu has queue', await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent).filter(t => /Hierna|volgende/.test(t))));
    await pg.click('#sheet-acts button:has-text("Als volgende afspelen")'); await pg.waitForTimeout(200);
    await pg.click('#pd-eps .pcep:nth-child(5) .pcmore'); await pg.click('#sheet-acts button:has-text("Toevoegen aan Hierna")'); await pg.waitForTimeout(200);
    await pg.click('#plbar-open'); await pg.waitForTimeout(450);
    console.log('pod kind', await pg.textContent('#pl-kind'), 'queue', await pg.$$eval('#pl-queue .pl-qrow b', x => x.map(e => e.textContent)));
    console.log('speed visible', await pg.isVisible('#pl-speed'), 'fav hidden', await pg.evaluate(() => getComputedStyle($('#pl-fav')).visibility), 'back label', await pg.getAttribute('#pl-back', 'aria-label'));
    console.log('color asked/applied', await pg.evaluate(() => getComputedStyle($('#player')).getPropertyValue('--pl').trim()));
    await pg.screenshot({ path: __dirname + '/shots/pl-pod-' + scheme + '.png' });
    // Seek met de schuif
    await pg.$eval('#pl-seek', el => { el.value = 500; el.dispatchEvent(new Event('input')); el.dispatchEvent(new Event('change')); });
    console.log('seek sent', await pg.evaluate(() => Android._pc.lastSeek));
    // Volgende = eerste uit Hierna
    const before = await pg.textContent('#pl-title');
    await pg.click('#pl-next'); await pg.waitForTimeout(900);
    console.log('next from queue', before !== await pg.textContent('#pl-title'), await pg.textContent('#pl-title'), 'queue left', await pg.$$eval('#pl-queue .pl-qrow', x => x.length));
    // Vegen op de hoes (muis als vinger): naar links = volgende
    const box = await pg.$eval('#pl-cover', el => { const r = el.getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 }; });
    await pg.mouse.move(box.x, box.y); await pg.mouse.down(); await pg.mouse.move(box.x - 60, box.y, { steps: 4 }); await pg.mouse.move(box.x - 160, box.y, { steps: 4 }); await pg.mouse.up();
    await pg.waitForTimeout(900);
    console.log('swipe next', await pg.textContent('#pl-title'), 'queue left', await pg.$$eval('#pl-queue .pl-qrow', x => x.length));
    // Geen volgende meer: melding
    await pg.click('#pl-next'); await pg.waitForTimeout(200);
    console.log('toast', await pg.textContent('#toast'));
    // Pauze in de speler en in de balk
    await pg.click('#pl-pp'); await pg.waitForTimeout(400);
    console.log('paused', await pg.evaluate(() => Android._pc.st.status), 'pp label', await pg.getAttribute('#pl-pp', 'aria-label'));
    // Veeg omlaag op de bovenrand = verkleinen
    const tb = await pg.$eval('#pl-top', el => { const r = el.getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 }; });
    await pg.mouse.move(tb.x, tb.y); await pg.mouse.down(); await pg.mouse.move(tb.x, tb.y + 120, { steps: 5 }); await pg.mouse.up(); await pg.waitForTimeout(400);
    console.log('swipe down closed', !(await pg.isVisible('#player')));
    await pg.click('#plbar-pp'); await pg.waitForTimeout(300);
    console.log('bar play', await pg.evaluate(() => Android._pc.st.status));
    await pg.screenshot({ path: __dirname + '/shots/pl-bar-pod-' + scheme + '.png' });
    // Stop via menu: balk weg
    await pg.click('#plbar-open'); await pg.waitForTimeout(400);
    await pg.click('#pl-stop'); await pg.waitForTimeout(1300);
    console.log('after stop bar', await pg.isVisible('#plbar'), 'player', await pg.isVisible('#player'));
    console.log('errors', errs);
  }
  await b.close();
})();
