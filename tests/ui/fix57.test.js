// Bugfixes 1.57: notities samenvoegen, zoeken zonder detailschermen, onderbroken automatisering, datums,
// wekker-proef, WhatsApp-sleutel, radio-zoekfout, schakelaars zonder losse opslag.
const { chromium } = require('playwright');
const assert = require('node:assert');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);

    // ---- Notities: wijziging van buiten (widget streept af) + nieuwe notitie in de app: beide blijven
    await pg.evaluate(() => { show('notes'); notesGet(); });
    await pg.evaluate(() => {
      // buiten de app: Melk afgestreept, versie omhoog
      const ext = JSON.parse(Android._notes); ext[0].items[0].done = true; Android._notes = JSON.stringify(ext); Android._nv = (Android._nv || 1) + 1;
      // in de app: nieuwe notitie en een nieuw item bij Boodschappen
      notesGet().push({ id: 'n2', title: 'Nieuw', text: 'x', items: [], updated: Date.now() });
      noteById('a1').items.push({ id: 'i9', text: 'Eieren', done: false });
    });
    const ok = await pg.evaluate(() => notesSaveNow());
    assert.strictEqual(ok, true, 'na samenvoegen bewaard');
    const saved = await pg.evaluate(() => JSON.parse(Android._notes));
    assert.ok(saved.find(n => n.id === 'n2'), 'nieuwe notitie niet kwijt');
    const a1 = saved.find(n => n.id === 'a1');
    assert.strictEqual(a1.items.find(i => i.id === 'i1').done, true, 'afstrepen uit de widget blijft');
    assert.ok(a1.items.find(i => i.id === 'i9'), 'nieuw item blijft');
    // verwijderen in de app terwijl buiten de app iets veranderde: blijft verwijderd, herinnering pas daarna weg
    await pg.evaluate(() => {
      const ext = JSON.parse(Android._notes); ext.find(n => n.id === 'a1').items[1].done = false; Android._notes = JSON.stringify(ext); Android._nv++;
      window.__clr = []; Android.noteRemindClear = id => window.__clr.push(id);
      openNote('n2');
    });
    await pg.evaluate(() => deleteNote()); await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    const after = await pg.evaluate(() => JSON.parse(Android._notes));
    assert.ok(!after.find(n => n.id === 'n2'), 'verwijderde notitie komt niet terug');
    assert.strictEqual(after.find(n => n.id === 'a1').items.find(i => i.id === 'i2').done, false, 'wijziging van buiten blijft');
    assert.deepStrictEqual(await pg.evaluate(() => window.__clr), ['n2'], 'herinnering na bewaren gewist');
    // markeren bij zoeken met een teken dat langer wordt in kleine letters: geen verschoven markering
    assert.strictEqual(await pg.evaluate(() => hl('İstanbul trip', 'trip')), 'İstanbul trip');
    await pg.evaluate(() => goBack());

    // ---- Zoeken: detailschermen niet los openen
    await pg.evaluate(() => { gsIndex = null; gsBuildIndex(); });
    const scrs = await pg.evaluate(() => [...new Set(gsIndex.map(x => x.scr))]);
    for (const d of ['note', 'chat', 'smschat', 'track', 'txd', 'contact', 'cversion', 'autoed']) assert.ok(!scrs.includes(d), 'geen ' + d);
    assert.ok(scrs.includes('notes'));

    // ---- Oproepen: dagen volgens de kalender
    const days = await pg.evaluate(() => { const d = daysAgo(1); const x = new Date(); x.setHours(0,0,0,0); x.setDate(x.getDate() - 1); return [d === x.getTime(), isoDay(new Date(2026, 9, 25, 0, 30).getTime())]; });
    assert.deepStrictEqual(days, [true, '2026-10-25'], 'lokale datum, ook net na middernacht');

    // ---- Radiowekker: proef niet na een mislukte opslag
    await pg.evaluate(() => { show('ralarm'); window.__test = 0; Android.alarmTest = () => { window.__test++; return ''; }; Android.alarmSet = () => 'Kies een zender'; });
    await pg.evaluate(() => alTest()); await pg.waitForTimeout(80);
    assert.strictEqual(await pg.evaluate(() => window.__test), 0, 'geen proef na fout');
    await pg.evaluate(() => goBack());

    // ---- WhatsApp-sleutel: niet twee keer tegelijk, niet wegspringen
    await pg.evaluate(() => { window.__keys = 0; const o = Android.waSetKey; Android.waSetKey = function(h){ window.__keys++; return o.call(this, h); }; Android.waInfo = () => JSON.stringify({ filesAccess: true }); show('wa'); });
    await pg.evaluate(() => { $('#rd-key').value = 'a'.repeat(64); rdSaveKey(); rdSaveKey(); });
    assert.strictEqual(await pg.evaluate(() => window.__keys), 1, 'één controle tegelijk');
    await pg.evaluate(() => show('notes'));
    await pg.waitForTimeout(700);
    assert.strictEqual(await pg.evaluate(() => current), 'notes', 'niet naar chats gesprongen');
    await pg.evaluate(() => show('home'));

    // ---- Radio: zoekfout blijft niet op "Zoeken…" hangen
    await pg.evaluate(() => { show('radio'); rdStations = [{ id: 'x', name: 'Oud', url: 'https://x' }]; $('#rd-search').value = 'abc'; $('#rd-list').innerHTML = '<p>Zoeken…</p>'; onRadioStations({ q: 'abc', error: 'Geen verbinding' }); });
    const rl = await pg.textContent('#rd-list');
    assert.match(rl, /Geen verbinding/); assert.ok(!/Zoeken…/.test(rl), 'geen "Zoeken…" meer');
    await pg.evaluate(() => { $('#rd-search').value = ''; goBack(); });

    // ---- Automatisering: wegspringen tijdens bewerken → later verder of weggooien
    await pg.evaluate(() => { show('auto'); autoNew && autoNew(); });
    await pg.waitForTimeout(150);
    assert.strictEqual(await pg.evaluate(() => current), 'autoed', 'nieuwe automatisering open');
    {
      await pg.evaluate(() => { aeRule.name = 'Parkeren test'; openTool('notes'); });
      assert.strictEqual(await pg.evaluate(() => current), 'notes');
      assert.ok(await pg.evaluate(() => store.get('autoDraft', null) && store.get('autoDraft', null).left), 'concept bewaard');
      await pg.evaluate(() => onResumeApp()); await pg.waitForTimeout(100);
      assert.strictEqual(await pg.evaluate(() => current), 'notes', 'niet vanzelf terug in de bewerking');
      await pg.evaluate(() => show('auto')); await pg.waitForTimeout(150);
      assert.match(await pg.textContent('#modal-text'), /Parkeren test/);
      await pg.click('#modal-ok'); await pg.waitForTimeout(150);
      assert.strictEqual(await pg.evaluate(() => current), 'autoed');
      assert.strictEqual(await pg.evaluate(() => aeRule.name), 'Parkeren test');
      // locatie die binnenkomt voor een andere regel wordt genegeerd
      await pg.evaluate(() => { aeHereFor = { other: 1 }; const before = JSON.stringify(aeRule.place || null); window.__pl = before; onAutoHere({ lat: 1, lng: 2, acc: 5 }); });
      assert.strictEqual(await pg.evaluate(() => JSON.stringify(aeRule.place || null)), await pg.evaluate(() => window.__pl), 'plek niet in de verkeerde regel');
      await pg.evaluate(() => { aeRule = null; aeSnap = null; store.set('autoDraft', null); show('home'); });
    }

    // ---- Schakelaars waarvan de stand native is: geen losse sleutels
    await pg.evaluate(() => { show('settings'); });
    await pg.click('#st-notif-mute'); await pg.waitForTimeout(80);
    assert.strictEqual(await pg.evaluate(() => localStorage.getItem('rt.st-notif-mute')), null);
    await pg.click('#st-notif-mute'); await pg.waitForTimeout(80);

    assert.deepStrictEqual(errs, []);
    await pg.close();
  }
  console.log('errors []');
  await b.close();
})().catch(e => { console.error(e); console.log('errors [' + e.message + ']'); process.exit(1); });
