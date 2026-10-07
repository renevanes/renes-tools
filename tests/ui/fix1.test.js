// Stap 1 en 2 van de verbeterronde: geen code via namen, Terug sluit dialogen, ongedaan maken, wijzigingen bewaren, pollen pauzeert.
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch();
  try {
    for (const colorScheme of ['light', 'dark']) {
      const page = await browser.newPage({ viewport: { width: 390, height: 844 }, colorScheme });
      const errors = []; page.on('pageerror', e => errors.push(e.message));
      await page.goto('file://' + require('path').join(__dirname, 'ui', 'index.html'));
      await page.waitForFunction(() => typeof jsq === 'function');

      // 1. Kwaadaardige groeps- en sms-namen voeren geen code uit
      const evil = '&quot;),window.pwned=1,(&quot;';
      const evil2 = "x');window.pwned=2;//";
      await page.evaluate(([e, e2]) => {
        Android._rd.chats = [{ id: 7, name: e, group: true, last: Date.now(), n: 1, lastText: 'hoi' }, { id: 8, name: e2, last: Date.now(), n: 1 }];
        Android._sms.convs = [{ thread: 3, name: e, last: Date.now(), count: 1, lastText: 'x' }];
        show('chats'); loadChatList();
      }, [evil, evil2]);
      await page.evaluate(() => document.querySelectorAll('#chat-list button').forEach(b => { b.click(); show('chats'); }));
      await page.evaluate(() => { show('sms'); if (typeof loadSmsList === 'function') loadSmsList(); });
      await page.evaluate(() => document.querySelectorAll('#sms-list button').forEach(b => { b.click(); show('sms'); }));
      assert.ok(await page.evaluate(() => document.querySelectorAll('#chat-list button, #sms-list button').length) >= 2, 'geen lijst gerenderd');
      assert.equal(await page.evaluate(() => window.pwned), undefined, 'naam voerde code uit');
      assert.equal(await page.evaluate(() => jsq("a'\"&<b>")), '&quot;a&#39;\\&quot;&amp;&lt;b&gt;&quot;');

      // 2. Notitie-id's uit een vreemd bestand voeren geen code uit
      await page.evaluate(() => { const n = notesGet(); n.push({ id: "z');window.pwned=3;//", title: 'Vreemd', text: '', items: [{ id: "i');window.pwned=4;//", text: 'item', done: false }], updated: Date.now() }); show('notes'); renderNotes && renderNotes(); });
      await page.evaluate(() => openNote("z');window.pwned=3;//"));
      await page.evaluate(() => document.querySelectorAll('li[data-id] input[type=checkbox]').forEach(c => c.dispatchEvent(new Event('change'))));
      assert.equal(await page.evaluate(() => window.pwned), undefined, 'notitie-id voerde code uit');

      // 3. Terug sluit eerst een open dialoog, het scherm eronder blijft staan
      await page.evaluate(() => show('settings'));
      await page.evaluate(() => askConfirm('Zeker?', 'Test', 'OK', () => { window.confirmed = 1; }));
      assert.equal(await page.evaluate(() => goBack()), true);
      assert.equal(await page.evaluate(() => current), 'settings');
      assert.equal(await page.isVisible('#modal.on'), false);
      assert.equal(await page.evaluate(() => window.confirmed), undefined);

      // 4. Lijstitem verwijderen kan ongedaan worden gemaakt
      await page.evaluate(() => { const n = notesGet(); n.push({ id: 'u1', title: 'Boodschappen', text: '', items: [{ id: 'a', text: 'Melk', done: false }, { id: 'b', text: 'Brood', done: true }], updated: Date.now() }); openNote('u1'); });
      await page.evaluate(() => delItem('a'));
      assert.equal(await page.evaluate(() => noteById('u1').items.length), 1);
      await page.waitForSelector('#undo.on', { state: 'visible', timeout: 2000 });
      await page.click('#undo-b');
      assert.deepEqual(await page.evaluate(() => noteById('u1').items.map(i => i.text)), ['Melk', 'Brood']);
      await page.evaluate(() => clearDone());
      assert.deepEqual(await page.evaluate(() => noteById('u1').items.map(i => i.text)), ['Melk']);
      await page.click('#undo-b');
      assert.equal(await page.evaluate(() => noteById('u1').items.length), 2);
      const del = await page.$('.items .del'); const box = del && await del.boundingBox();
      assert.ok(box && box.width >= 44 && box.height >= 44, 'verwijderknop te klein');

      // 5. Automatisering: Terug met wijzigingen vraagt eerst
      await page.evaluate(() => { aeRule = { id: 'r1', name: 'Test', trig: 'bt_on', mode: 'notify', bt: [], on: true }; aeSnap = JSON.stringify(aeRule); show('autoed'); aeRule.name = 'Gewijzigd'; });
      assert.equal(await page.evaluate(() => goBack()), true);
      assert.equal(await page.evaluate(() => current), 'autoed');
      assert.equal(await page.isVisible('#modal.on'), true);
      assert.equal(await page.textContent('#modal-cancel'), 'Weggooien');
      await page.click('#modal-cancel');
      assert.equal(await page.evaluate(() => current), 'auto');
      await page.evaluate(() => { askConfirm('a', 'b', 'OK', () => {}); });
      assert.equal(await page.textContent('#modal-cancel'), 'Annuleren');
      await page.evaluate(() => closeModal(null));

      // 6. Pollen staat stil als de app op de achtergrond is
      await page.evaluate(() => { window.ticks = 0; window._t = setInterval(() => window.ticks++, 50); onPauseApp(); });
      await page.waitForTimeout(300);
      assert.equal(await page.evaluate(() => window.ticks), 0);
      await page.evaluate(() => onResumeApp());
      await page.waitForTimeout(300);
      assert.ok(await page.evaluate(() => window.ticks) > 0);
      await page.evaluate(() => clearInterval(window._t));

      // 7. Opname-timer wordt niet elke seconde voorgelezen
      assert.equal(await page.getAttribute('#recorder-state', 'aria-live'), null);
      assert.equal(await page.getAttribute('#recorder-live', 'aria-live'), 'polite');

      assert.deepEqual(errors, []);
      console.log('errors []');
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
