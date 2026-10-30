const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const { default: AxeBuilder } = require('@axe-core/playwright');
const path = require('node:path');

(async () => {
  const browser = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const page = await context.newPage();
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.goto('file://' + path.join(__dirname, 'ui', 'index.html'));
    await page.waitForFunction(() => typeof uiCanReload === 'function');

    await page.evaluate(() => {
      Android._notes = JSON.stringify([{ id: 'controle', title: 'Oorspronkelijk', text: '', updated: 1, items: [{ id: 'item', text: 'Taak', done: false }] }]);
      Android._nv = 100; notes = null; notesDirty = false; notesBroken = false;
      notesGet(); openNote('controle');
      window.savedNotesSave = Android.notesSaveIf;
      Android.notesSaveIf = () => JSON.stringify({ err: 'Opslag tijdelijk vol' });
    });
    await page.fill('#note-title', 'Mijn bewerking');
    assert.equal(await page.evaluate(() => notesSaveNow()), false);
    assert.equal(await page.evaluate(() => notesUnsaved()), true);
    await page.evaluate(() => {
      const external = JSON.parse(Android._notes); external[0].items[0].done = true;
      Android._notes = JSON.stringify(external); Android._nv++;
      onNotesMaybeChanged();
      Android.notesSaveIf = window.savedNotesSave;
    });
    await page.waitForFunction(() => !notesUnsaved());
    const saved = await page.evaluate(() => JSON.parse(Android._notes)[0]);
    assert.equal(saved.title, 'Mijn bewerking', 'Lokale bewerking blijft na fout en widgetwijziging behouden');
    assert.equal(saved.items[0].done, true, 'Widgetwijziging wordt samengevoegd');

    // Tijdelijk onleesbaar tijdens een conflict: herstel de bron en sla dezelfde bewerking opnieuw op.
    await page.evaluate(() => {
      window.savedNotesLoad = Android.notesLoad;
      noteEdit('text', 'Blijft tijdens leesfout');
      Android._nv++; Android.notesLoad = () => 'onleesbaar';
    });
    assert.equal(await page.evaluate(() => notesSaveNow()), false);
    assert.equal(await page.locator('#s-note .notes-save-state').isVisible(), true);
    await page.evaluate(() => { Android.notesLoad = window.savedNotesLoad; });
    await page.locator('#s-note .notes-save-state button').click();
    await page.waitForFunction(() => !notesUnsaved());
    assert.equal(await page.evaluate(() => JSON.parse(Android._notes)[0].text), 'Blijft tijdens leesfout');

    await page.evaluate(() => {
      Android.notesSaveIf = () => JSON.stringify({ err: 'Opslag tijdelijk vol' });
      window.exports = 0; Android.notesExport = () => { window.exports++; return 'ok:1'; };
      Android._sec.on = false; noteEdit('title', 'Nog niet opgeslagen'); notesBackup();
    });
    assert.equal(await page.evaluate(() => window.exports), 0, 'Geen export van de oudere schijfversie na opslagfout');
    assert.equal(await page.evaluate(() => notesUnsaved()), true);
    await page.evaluate(() => { Android.notesSaveIf = window.savedNotesSave; notesSaveNow(); show('home'); });

    // Een update blijft klaarstaan bij globale PDF-bewerkingen, ook na weggaan uit de editor.
    await page.evaluate(() => {
      pdfEd.items = [{ type: 'text', text: 'Contract', page: 0 }]; pdfEd.saved = '[]';
      onUpdateStatus({ state: 'web', kind: 'web', versionName: '9.1' });
    });
    assert.equal(await page.evaluate(() => Android._uiReloads || 0), 0);
    assert.equal(await page.evaluate(() => pdfEd.items.length), 1);
    assert.match(await page.textContent('#upd-text'), /9\.1/);
    await page.evaluate(() => {
      window.apkCalls = 0; Android.installUpdate = () => window.apkCalls++;
      onUpdateStatus({ state: 'apk', kind: 'web', versionName: '10.0' }); installUpdate();
    });
    assert.equal(await page.evaluate(() => window.apkCalls), 1, 'Een klaarstaande interface blokkeert een nieuwere APK niet');
    await page.evaluate(() => onUpdateStatus({ state: 'web', kind: 'web', versionName: '9.2' }));
    assert.equal(await page.evaluate(() => updKind), 'apk', 'Een interface-resultaat wist APK-voortgang niet');
    assert.equal(await page.evaluate(() => Android._uiReloads || 0), 0);
    await page.evaluate(() => { updAvail = ''; updKind = ''; updPhase = ''; clearTimeout(updDog); });
    await page.evaluate(() => { pdfEd.saved = JSON.stringify(pdfEd.items); $('#pdf-edit-text').value = 'Nog niet toegevoegd'; updTryReload(true); });
    assert.equal(await page.evaluate(() => Android._uiReloads || 0), 0, 'Ook nog niet toegevoegde PDF-invoer blijft behouden');
    await page.evaluate(() => { $('#pdf-edit-text').value = ''; updTryReload(false); });
    await page.waitForFunction(() => Android._uiReloads === 1);
    await page.evaluate(() => { show('setlook'); });
    await page.selectOption('#st-zoom', '150');
    await page.evaluate(() => show('home'));
    assert.equal(await page.evaluate(() => uiCanReload()), true, 'Direct opgeslagen instellingen blokkeren de update niet');
    await page.evaluate(() => { updReady = ''; updReset(); show('redial'); });

    // Algemene vensters: beginfocus, Tab-grenzen, Escape en herstel van focus.
    await page.focus('#num');
    await page.evaluate(() => askConfirm('Bevestigen', 'Een controle', 'Bewaren', () => {}));
    assert.equal(await page.evaluate(() => document.activeElement.id), 'modal-cancel');
    assert.equal(await page.evaluate(() => $('#s-redial').inert), true);
    await page.focus('#modal-ok'); await page.keyboard.press('Tab');
    assert.equal(await page.evaluate(() => document.activeElement.id), 'modal-cancel');
    await page.keyboard.press('Shift+Tab');
    assert.equal(await page.evaluate(() => document.activeElement.id), 'modal-ok');
    await page.keyboard.press('Escape');
    assert.equal(await page.isVisible('#modal'), false);
    assert.equal(await page.evaluate(() => document.activeElement.id), 'num');
    assert.equal(await page.evaluate(() => $('#s-redial').inert), false);

    // Een venster sluiten mag het app-slot niet ongedaan maken.
    await page.evaluate(() => {
      askConfirm('Venster', '', 'OK', () => {});
      Android._st.code = '2468'; Android._st.locked = true; showLock();
      closeModal(null);
    });
    await page.waitForFunction(() => $('#s-redial').inert && $('#lockscr').classList.contains('on'));
    assert.equal(await page.evaluate(() => $('#s-redial').inert), true);
    await page.evaluate(() => { Android._st.locked = false; onUnlocked(); });
    await page.waitForFunction(() => !$('#s-redial').inert);

    // Gerichte toegankelijkheidscontrole met echte assertions.
    const viewport = await page.locator('meta[name="viewport"]').getAttribute('content');
    assert.doesNotMatch(viewport, /user-scalable\s*=\s*no|maximum-scale\s*=\s*1(?:\D|$)/);
    await page.evaluate(() => show('radio'));
    await page.waitForFunction(() => $('#rd-genres [role="radio"]'));
    assert.equal(await page.locator('#rd-genres [role="radio"][aria-pressed]').count(), 0);
    const a11y = await new AxeBuilder({ page }).withRules(['aria-allowed-attr', 'meta-viewport']).analyze();
    assert.deepEqual(a11y.violations, []);

    // Een opslagfout bij Apparaten geeft geen succesmelding en behoudt de invoer in de dialoog.
    await page.evaluate(() => {
      Android.apKnown = () => 'Bewaren lukt niet: opslag vol';
      apDev = { key: 'a:AA:BB:CC:DD:EE:FF', src: 'bt' };
      apdKnown(true);
    });
    await page.fill('#modal-input', 'Mijn oortjes'); await page.click('#modal-ok');
    assert.match(await page.textContent('#toast'), /Bewaren lukt niet/);
    assert.doesNotMatch(await page.textContent('#toast'), /Bewaard als/);
    assert.equal(await page.isVisible('#modal'), true);
    assert.equal(await page.inputValue('#modal-input'), 'Mijn oortjes', 'Invoer blijft beschikbaar voor opnieuw proberen');
    await page.evaluate(() => { Android.apKnown = () => ''; });
    await page.click('#modal-ok');
    assert.equal(await page.isVisible('#modal'), false);
    await page.evaluate(() => { Android.apWipe = () => 'Wissen is niet helemaal gelukt'; apsWipe(true); });
    await page.click('#modal-ok');
    assert.match(await page.textContent('#toast'), /Wissen is niet helemaal gelukt/);

    // Gewone PDF-flow: opgeslagen tekst mag in het invoerveld blijven zonder de update te blokkeren.
    await page.evaluate(() => { pdfEd.revision = ''; pdfEd.items = []; pdfEd.saved = '[]'; pdfEd.selected = -1; openTool('pdf'); });
    await page.click('#pdf-edit-pick');
    await page.waitForFunction(() => pdfEd.revision && !pdfEd.loading && pdfEd.meta[0]);
    await page.fill('#pdf-edit-text', 'Opgeslagen tekst'); await page.click('#pdf-edit-add-text');
    await page.click('#pdf-edit-save'); await page.waitForFunction(() => !pdfBusy && !pdfEditDirty());
    assert.equal(await page.inputValue('#pdf-edit-text'), 'Opgeslagen tekst');
    await page.evaluate(() => { show('home'); onUpdateStatus({ state: 'web', kind: 'web', versionName: '9.3' }); });
    const reloadState = await page.evaluate(() => ({ ready: uiCanReload(), count: Android._uiReloads, fields: [...document.querySelectorAll('[data-ui-edited="1"]')].map(e => ({ id: e.id, value: e.value, original: e.defaultValue })) }));
    assert.equal(reloadState.ready, true, JSON.stringify(reloadState));
    await page.waitForFunction(() => Android._uiReloads === 2);
    assert.deepEqual(errors, []);
    console.log(scheme, 'opslagfouten, samenvoegen, update-uitstel, dialoogfocus en toegankelijkheid; errors []');
    await context.close();
  }
  await browser.close();
})().catch(error => { console.error(error); process.exit(1); });
