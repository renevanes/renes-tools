// Startscherm aanpassen in tabbladen (Tools, Overzicht, Weergave): regels van het overzicht aan/uit en in volgorde,
// backup-regel alleen bij een probleem, Sinds gisteravond per regel, onderdelen van het startscherm in volgorde,
// uitleg onder de tegels, tip, vastgezette notities en het aantal bij Recent gebruikt. Bewaard, ook na herladen.
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const browser = await chromium.launch();
  const W = +(process.env.UI_WACHT || 1);
  for (const scheme of ['light', 'dark']) {
    const pg = await browser.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errors = []; pg.on('pageerror', e => errors.push(e.message));
    const ok = (c, m) => { if (!c) errors.push('mislukt: ' + m); else console.log('✓ ' + m); };
    await pg.goto('file://' + path.join(__dirname, 'ui', 'index.html'));
    await pg.evaluate(() => { store.set('home', null); Android.backupState = () => JSON.stringify({ last: { t: Date.now() - 1.5 * 864e5 } }); Android.alarmState = () => JSON.stringify({ on: true, nextAt: Date.now() + 36e5 });
      Android.overnight = () => JSON.stringify({ backup: { t: Date.now() - 6 * 36e5 }, missed: [{ who: 'Jan', t: Date.now() - 5 * 36e5 }] }); show('home'); });
    await pg.waitForTimeout(150 * W);
    const dash = () => pg.$$eval('#home-dash button', x => x.map(b => b.dataset.t));
    const nacht = () => pg.$$eval('#home-night button[data-t]', x => x.map(b => b.dataset.t));
    const blokken = () => pg.evaluate(() => [...document.querySelectorAll('#s-home main > *')].map(e => e.id).filter(id => ['home-ov', 'home-recent', 'home-notes', 'home-groups'].includes(id)));
    ok((await dash()).join() === 'backup,ralarm', 'standaard: backup en wekker in het overzicht');
    ok((await nacht()).join() === 'backup,calls', 'standaard: Sinds gisteravond met backup en gemiste oproep');
    ok((await blokken()).join() === 'home-ov,home-recent,home-notes,home-groups', 'standaardvolgorde van de onderdelen');
    const alles = () => pg.evaluate(() => [...document.querySelectorAll('#s-home main > [id^="home-"]')].map(e => e.id).filter(id => id !== 'home-dash').join());
    ok((await alles()) === 'home-ov,home-new,home-start,home-recent,home-notes,home-groups,home-tip', 'zoals altijd: Nieuwe indeling en Aan de slag na het overzicht');
    // Kapotte waarden in rt.home: terug naar de standaard
    await pg.evaluate(() => { const c = homeCfg(); c.ov = 'x'; c.blok = 'tools'; c.recentN = '6'; homeSave(c); });
    ok(await pg.evaluate(() => homeOv().volg.join() === 'backup,wekker,herinner,redial,radio,zelftest' && homeBlok().join() === 'ov,recent,notes,tools' && homeRecentN() === 4), 'kapotte instellingen: de standaard');
    await pg.evaluate(() => { const c = homeCfg(); c.ov = { volg: ['zz', 'radio', 'radio'], uit: ['zz'] }; homeSave(c); });
    ok(await pg.evaluate(() => homeOv().volg.join() === 'radio,backup,wekker,herinner,redial,zelftest' && !homeOv().uit.length), 'onbekende en dubbele id\'s vallen weg');
    await pg.evaluate(() => { const c = homeCfg(); delete c.ov; delete c.blok; delete c.recentN; homeSave(c); });

    // Tabbladen: beginnen op Tools, de andere panelen verborgen; pijltjestoetsen wisselen
    await pg.evaluate(() => show('homeedit')); await pg.waitForTimeout(150 * W);
    ok(await pg.isVisible('#he-groups') && !(await pg.isVisible('#he-ov-list')) && !(await pg.isVisible('#he-blok-list')), 'begint op Tools, niet één lange lijst');
    await pg.focus('#he-tab-tools'); await pg.keyboard.press('ArrowRight');
    ok(await pg.isVisible('#he-ov-list') && (await pg.getAttribute('#he-tab-ov', 'aria-selected')) === 'true' && await pg.evaluate(() => document.activeElement.id === 'he-tab-ov'), 'pijl naar rechts: Overzicht');

    // Overzicht: wekker uit, volgorde omdraaien, backup alleen bij een probleem
    await pg.click('#he-ov-list .herow[data-id="wekker"] .tg');
    ok((await dash()).join() === 'backup' && await pg.evaluate(() => homeOv().uit.includes('wekker')), 'wekker uit het overzicht');
    await pg.click('#he-ov-list .herow[data-id="wekker"] .tg');
    await pg.click('#he-ov-list .herow[data-id="wekker"] .mv[data-dir="-1"]');
    ok((await dash()).join() === 'ralarm,backup' && (await pg.$$eval('#he-ov-list .herow', x => x.map(r => r.dataset.id))).slice(0, 2).join() === 'wekker,backup', 'wekker boven de backup');
    ok(await pg.evaluate(() => document.activeElement && document.activeElement.closest('.herow') && document.activeElement.closest('.herow').dataset.id === 'wekker'), 'focus blijft bij de verplaatste regel');
    await pg.selectOption('#he-ov-bk', 'probleem');
    ok((await dash()).join() === 'ralarm', 'backup van gisteren is in orde: alleen bij een probleem = weg');
    await pg.selectOption('#he-ov-bkdagen', '1'); await pg.evaluate(() => renderDash());
    ok((await dash()).join() === 'ralarm,backup' && /al even geleden/.test(await pg.textContent('#home-dash')), 'ouder dan 1 dag: de backup-regel komt terug als waarschuwing');
    // Sinds gisteravond: gemiste oproepen uit, daarna het hele blok uit
    await pg.click('#he-nacht-list .herow[data-id="gemist"] .tg');
    ok((await nacht()).join() === 'backup', 'gemiste oproepen uit Sinds gisteravond');
    await pg.click('#he-nacht');
    ok(!(await pg.isVisible('#he-nacht-list')) && await pg.evaluate(() => $('#home-night').style.display === 'none'), 'Sinds gisteravond helemaal uit');
    // Overzicht helemaal uit en weer standaard
    await pg.click('#he-dash'); ok(/Sinds gisteravond/.test(await pg.textContent('#he-dash-stand')), 'uit: de tekst zegt dat Sinds gisteravond apart gaat');
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(100 * W);
    ok(await pg.evaluate(() => $('#home-ov').hidden), 'overzicht en Sinds gisteravond uit: geen kaart bovenaan');
    await pg.evaluate(() => { const c = homeCfg(); c.ov.nacht = true; homeSave(c); });
    ok(await pg.evaluate(() => !$('#home-ov').hidden && $('#home-dash').style.display === 'none' && $('#home-night').style.display !== 'none'), 'overzicht uit, Sinds gisteravond aan: alleen die regels');
    await pg.evaluate(() => show('homeedit')); await pg.click('#he-tab-ov'); await pg.click('button:has-text("Overzicht standaard")'); await pg.click('#modal-ok');
    ok((await dash()).join() === 'backup,ralarm' && (await nacht()).join() === 'backup,calls', 'Overzicht standaard: alles weer aan, standaardvolgorde');

    // Weergave: onderdelen in een andere volgorde, notities en tip uit, uitleg onder de tegels uit, aantal Recent
    await pg.click('#he-tab-weer');
    await pg.click('#he-blok-list .herow[data-id="tools"] .mv[data-dir="-1"]'); await pg.click('#he-blok-list .herow[data-id="tools"] .mv[data-dir="-1"]'); await pg.click('#he-blok-list .herow[data-id="tools"] .mv[data-dir="-1"]');
    ok((await blokken()).join() === 'home-groups,home-ov,home-recent,home-notes', 'tools bovenaan het startscherm');
    ok((await alles()).startsWith('home-new,home-start,home-groups'), 'eenmalige kaarten bovenaan als het overzicht niet eerst staat');
    await pg.evaluate(() => { notes = [{ id: 'n1', title: 'Boodschappen', items: [{ text: 'melk' }] }]; Android.notePins = () => '["n1"]'; renderNotePins(); });
    ok(await pg.evaluate(() => $('#home-notes').style.display !== 'none'), 'vastgezette notitie staat erop');
    await pg.click('#he-notes'); await pg.evaluate(() => renderNotePins());
    ok(await pg.evaluate(() => $('#home-notes').style.display === 'none' && homeCfg().notes === false), 'vastgezette notities uit');
    await pg.click('#he-recent'); ok(await pg.evaluate(() => document.activeElement && document.activeElement.id === 'he-recent'), 'focus blijft op de schakelaar Recent gebruikt'); await pg.click('#he-recent');
    await pg.click('#he-tip'); await pg.click('#he-sub');
    ok(await pg.evaluate(() => $('#home-tip').hidden && $('#home-groups').classList.contains('kaal')), 'tip en uitleg onder de tegels uit');
    await pg.evaluate(() => { store.set('homeRecent', ['radio', 'pdf', 'vpn', 'notes', 'sms', 'calls']); }); await pg.selectOption('#he-recentn', '6');
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(100 * W);
    ok((await pg.$$eval('#home-recent .hrtool', x => x.length)) === 6, 'Recent gebruikt toont er 6');
    ok(await pg.evaluate(() => { const s = getComputedStyle(document.querySelector('#home-groups .tile small')); return s.display === 'none'; }), 'op het startscherm geen uitleg onder de tegels');
    await pg.screenshot({ path: __dirname + '/shots/startscherm-aanpassen-' + scheme + '.png', fullPage: true });

    // Bewaard na herladen; Standaard herstellen (Tools) laat de weergave en het overzicht staan
    await pg.reload(); await pg.waitForTimeout(250 * W);
    ok((await blokken()).join() === 'home-groups,home-ov,home-recent,home-notes' && await pg.evaluate(() => $('#home-tip').hidden && homeCfg().notes === false && homeRecentN() === 6), 'blijft na herladen');
    await pg.evaluate(() => { heOvSet(o => { o.bkDagen = 7; }); show('homeedit'); }); await pg.click('button:has-text("Standaard herstellen")'); await pg.click('#modal-ok');
    ok(await pg.evaluate(() => homeCfg().tip === false && homeCfg().sub === false && homeBlok()[0] === 'tools' && homeOv().bkDagen === 7), 'Standaard herstellen raakt de weergave en het overzicht niet');
    await pg.click('#he-tab-weer'); await pg.click('button:has-text("Standaardvolgorde")');
    ok((await blokken()).join() === 'home-ov,home-recent,home-notes,home-groups', 'Standaardvolgorde terug');

    // Instellingen via de assistent: een schakelaar op een verborgen tabblad wordt zichtbaar
    await pg.evaluate(() => { show('home'); setOpen('start.tip'); }); await pg.waitForTimeout(400 * W);
    ok(await pg.isVisible('#he-tip'), 'naar een instelling op een ander tabblad: dat tabblad gaat open');

    // Tikvlakken en breedte
    const maat = () => pg.evaluate(() => [...document.querySelectorAll('#he-ov-list .mv, #he-blok-list .mv, #s-homeedit .pctabs button')].filter(e => e.getClientRects().length).every(e => { const r = e.getBoundingClientRect(); return r.width >= 44 && r.height >= 44 && r.right <= innerWidth; }));
    await pg.click('#he-tab-ov'); const m1 = await maat(); await pg.click('#he-tab-weer');
    ok(m1 && await maat(), 'tikvlakken minstens 44×44 en binnen beeld');
    await pg.setViewportSize({ width: 320, height: 740 });
    ok(await pg.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'past op een smal scherm');

    // Engels
    await pg.evaluate(() => rgSave({ lang: 'en' })); await pg.waitForTimeout(300 * W);
    ok([await pg.textContent('#he-tab-tools'), await pg.textContent('#he-tab-ov'), await pg.textContent('#he-tab-weer')].join() === 'Tools,Overview,Layout', 'tabbladen in het Engels');
    await pg.click('#he-tab-ov'); ok((await pg.textContent('#he-ov-list .herow[data-id="wekker"] .nm')).startsWith('Alarm'), 'regels in het Engels');
    // Pijltjes met omloop, Home en End
    await pg.focus('#he-tab-ov'); await pg.keyboard.press('ArrowRight'); await pg.keyboard.press('ArrowRight');
    ok(await pg.evaluate(() => document.activeElement.id === 'he-tab-tools'), 'pijl voorbij het laatste tabblad: weer het eerste');
    await pg.keyboard.press('End'); ok(await pg.evaluate(() => document.activeElement.id === 'he-tab-weer'), 'End: het laatste tabblad');
    // Play-versie: geen tabblad Overzicht en geen onderdeel Overzicht
    await pg.evaluate(() => { window._pl = isPlay; isPlay = () => true; show('home'); show('homeedit'); });
    ok(!(await pg.isVisible('#he-tab-ov')) && !(await pg.isVisible('#he-blok-list .herow[data-id="ov"]')) && await pg.evaluate(() => $('#s-homeedit .pctabs').classList.contains('two')), 'Play: geen Overzicht');
    await pg.evaluate(() => { isPlay = _pl; show('home'); });
    await pg.evaluate(() => rgSave({ lang: 'nl' }));
    console.log(scheme, 'errors', errors.length ? errors : '[]'); if (errors.length) console.log('errors', errors); else console.log('errors []');
    await pg.close();
  }
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
