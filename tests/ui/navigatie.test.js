// Terug is overal één stap. Start (in een losse app: Hoofdscherm) staat ernaast als die stap
// nog niet het begin van deze app is. Licht, donker en in het Engels.
const { chromium } = require('playwright');
const path = require('path');
const W = +process.env.UI_WACHT || 1;

(async () => {
  const b = await chromium.launch();
  const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const url = 'file://' + path.join(__dirname, 'ui', 'index.html');
  const skin = 'file://' + path.join(__dirname, '..', '..', 'web', 'start.html');
  const open = async (scheme, solo) => {
    const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme, timezoneId: 'Europe/Amsterdam' });
    const pg = await ctx.newPage();
    pg.on('pageerror', e => errs.push((solo || scheme) + ': ' + e.message));
    if (solo) await pg.addInitScript(tool => { window.__testSolo = tool; }, solo);
    await pg.goto(url);
    await pg.waitForTimeout(280 * W);
    return { ctx, pg };
  };
  const klik = async (pg, sel) => { await pg.click(sel); await pg.waitForTimeout(80 * W); };

  for (const scheme of ['light', 'dark']) {
    const { ctx, pg } = await open(scheme);
    const tag = scheme;

    const vorm = await pg.evaluate(() => {
      const bad = [];
      const slot = document.querySelectorAll('#toolslot .nav-home').length;
      if (slot) bad.push('slotscherm heeft Start');
      for (const s of document.querySelectorAll('section.screen')) {
        const id = s.id.slice(2), homes = [...s.querySelectorAll(':scope > .hdr .nav-home')];
        if (id === 'home') { if (homes.length) bad.push('home heeft Start'); continue; }
        if (homes.length !== 1) bad.push(id + ': ' + homes.length + ' knoppen');
        else if (homes[0].previousElementSibling !== s.querySelector(':scope > .hdr .back')) bad.push(id + ': Start staat niet direct na de pijl');
        else if (!homes[0].querySelector('[data-icon="grid"]')) bad.push(id + ': geen raster');
      }
      return bad;
    });
    ok(!vorm.length, tag + ': elke kop heeft de pijl en, waar nodig, Start ernaast' + (vorm.length ? ' (' + vorm.slice(0, 8).join('; ') + ')' : ''));

    const ids = await pg.evaluate(() => [...document.querySelectorAll('section.screen')].map(s => s.id.slice(2)));
    const zicht = [];
    for (let i = 0; i < ids.length; i += 30) {
      const part = await pg.evaluate(chunk => {
        const bad = [];
        for (const id of chunk) {
          if ($('#modal').classList.contains('on')) closeModal(null);
          try { show(id); } catch (e) { bad.push(id + ' openen: ' + e.message); continue; }
          if (current !== id) continue;
          const b = document.querySelector('#s-' + id + ' .nav-home');
          const want = navThuisZicht(id);
          if (!b) { if (id !== 'home') bad.push(id + ': geen knop'); continue; }
          if (b.hidden !== !want) bad.push(id + ': zichtbaar ' + !b.hidden + ', verwacht ' + want);
          if (want && b.getAttribute('aria-label') !== 'Start') bad.push(id + ': label ' + b.getAttribute('aria-label'));
          const zien = getComputedStyle(b).display !== 'none';
          if (zien !== want) bad.push(id + ': display ' + (zien ? 'aan' : 'uit'));
        }
        return bad;
      }, ids.slice(i, i + 30));
      zicht.push(...part);
    }
    ok(!zicht.length, tag + ': Start alleen als één stap terug niet het begin is' + (zicht.length ? ' (' + zicht.slice(0, 10).join('; ') + ')' : ''));

    await pg.evaluate(() => show('carset'));
    ok(await pg.evaluate(() => current === 'carset' && navThuisZicht('carset') && !document.querySelector('#s-carset .nav-home').hidden), tag + ': Mijn auto, instellingen: Start staat aan');
    await klik(pg, '#s-carset .nav-home');
    ok(await pg.evaluate(() => current === 'home' && jumpStack.length === 0), tag + ': Start vanaf de instellingen gaat naar het startscherm');
    await pg.evaluate(() => show('carset'));
    await pg.evaluate(() => goBack());
    ok(await pg.evaluate(() => current === 'car' && document.querySelector('#s-car .nav-home').hidden), tag + ': de pijl gaat naar Mijn auto, en daar is Start weg');
    await pg.evaluate(() => goBack());
    ok(await pg.evaluate(() => current === 'home'), tag + ': vanaf Mijn auto gaat de pijl naar het startscherm');

    await pg.evaluate(() => show('facstuk'));
    await pg.evaluate(() => goBack());
    ok(await pg.evaluate(() => current === 'facdos'), tag + ': een factuur gaat één stap terug naar het dossier');
    await pg.evaluate(() => show('facstuk'));
    await klik(pg, '#s-facstuk .nav-home');
    ok(await pg.evaluate(() => current === 'home'), tag + ': Start vanaf een factuur slaat het dossier over');

    await pg.evaluate(() => {
      apDev = { src: 'bt', key: 'a:F4:12:9A:00:11:22', d: { key: 'a:F4:12:9A:00:11:22', name: 'Galaxy Buds2 Pro', type: 'oortjes', maker: 'Samsung' } };
      show('apdev');
    });
    ok(await pg.evaluate(() => current === 'apdev' && !document.querySelector('#s-apdev .nav-home').hidden), tag + ': een apparaat toont Start, want terug gaat naar Apparaten');
    await pg.evaluate(() => goBack());
    ok(await pg.evaluate(() => current === 'apparaten'), tag + ': een apparaat gaat terug naar Apparaten');
    await pg.evaluate(() => show('apset'));
    await pg.evaluate(() => goBack());
    ok(await pg.evaluate(() => current === 'apparaten'), tag + ': de instellingen van Apparaten gaan terug naar Apparaten');

    const facWas = await pg.evaluate(() => BACK.faclok);
    await pg.evaluate(() => { show('facturen'); faNaar('faclok'); });
    ok(await pg.evaluate(() => current === 'faclok' && navStap('faclok') === 'facturen' && navThuisZicht('faclok')), tag + ': een scherm dat je vanuit Facturen opent, gaat één stap terug daarheen');
    await pg.evaluate(() => goBack());
    ok(await pg.evaluate(() => current === 'facturen'), tag + ': die stap komt uit bij Facturen');
    await pg.evaluate(was => { BACK.faclok = was; }, facWas);

    await pg.evaluate(() => { show('search'); jumpTo('settings'); });
    ok(await pg.evaluate(() => current === 'settings' && !document.body.classList.contains('tab-root') && document.querySelector('#s-settings .nav-home').hidden), tag + ': via een sprong naar een tabblad blijft de pijl, zonder Start');
    await pg.evaluate(() => goBack());
    ok(await pg.evaluate(() => current === 'search'), tag + ': de pijl gaat terug naar Zoeken');
    await pg.evaluate(() => { show('search'); jumpTo('note'); });
    await klik(pg, '#s-note .nav-home');
    ok(await pg.evaluate(() => current === 'home' && jumpStack.length === 0), tag + ': Start vanaf een sprong gaat naar het begin en vergeet de sprong');

    await pg.evaluate(() => { show('notes'); openSheet('Meer', '', [['Niets', () => {}]]); });
    ok(await pg.evaluate(() => { const open = $('#sheet').classList.contains('on'); const bleef = navHome() === true && current === 'notes' && !$('#sheet').classList.contains('on'); return open && bleef; }), tag + ': Start sluit eerst een menu, en blijft op het scherm');
    await pg.evaluate(() => navHome());
    ok(await pg.evaluate(() => current === 'home'), tag + ': de volgende Start gaat naar het begin');

    await pg.evaluate(() => show('formulieren'));
    await pg.evaluate(() => fmStart());
    ok(await pg.evaluate(() => current === 'formulieren' && fm.view === 'stap' && navThuisZicht('formulieren')), tag + ': een formulierstap toont Start');
    await pg.evaluate(() => goBack());
    ok(await pg.evaluate(() => current === 'formulieren' && fm.view === 'start'), tag + ': de pijl gaat naar het overzicht van het formulier');
    await pg.evaluate(() => fmStart());
    await pg.evaluate(() => navHome());
    ok(await pg.evaluate(() => current === 'home'), tag + ': Start vanaf een formulierstap gaat naar het startscherm');

    await pg.evaluate(() => {
      aeRule = { id: 't', name: 'Test', on: true, trig: 'bt_off', bt: [], btNames: [], usePlace: false, place: null, days: 0, from: '', to: '', mode: 'ask', app: null, steps: [], msg: '', cool: 15 };
      aeSnap = JSON.stringify(aeRule);
      show('autoed');
    });
    await pg.evaluate(() => navHome());
    ok(await pg.evaluate(() => current === 'home' && !$('#modal').classList.contains('on')), tag + ': een ongewijzigde automatisering gaat met Start meteen naar het begin');
    await pg.evaluate(() => {
      aeRule = { id: 't', name: 'Test', on: true, trig: 'bt_off', bt: [], btNames: [], usePlace: false, place: null, days: 0, from: '', to: '', mode: 'ask', app: null, steps: [], msg: '', cool: 15 };
      aeSnap = JSON.stringify(aeRule);
      aeRule.name = 'Anders';
      show('autoed');
      navHome();
    });
    ok(await pg.evaluate(() => current === 'autoed' && $('#modal').classList.contains('on') && $('#modal-title').textContent.includes('opslaan')), tag + ': Start vraagt eerst of de automatisering bewaard moet');
    await pg.evaluate(() => closeModal(null));
    ok(await pg.evaluate(() => current === 'autoed' && aeDirty()), tag + ': annuleren laat de bewerking staan');
    await pg.evaluate(() => navHome());
    await klik(pg, '#modal-cancel');
    ok(await pg.evaluate(() => current === 'home' && !aeRule), tag + ': weggooien gaat daarna naar het startscherm');

    await pg.evaluate(() => { cedOpen(null); $('#ced-text').value = 'Hoe gaat het?'; cedSync(); navHome(); });
    ok(await pg.evaluate(() => current === 'checkined' && $('#modal').classList.contains('on')), tag + ': Start vraagt eerst of de check-in-vraag bewaard moet');
    await klik(pg, '#modal-cancel');
    ok(await pg.evaluate(() => current === 'home'), tag + ': weggooien van de vraag gaat naar het startscherm, niet alleen naar Check-in');

    await pg.setViewportSize({ width: 360, height: 844 });
    const krap = await pg.evaluate(() => {
      const bad = [];
      for (const id of ['facstuk', 'formulieren', 'carset', 'cmkaart', 'wcbewerk', 'ercontents']) {
        try { show(id); } catch (e) { bad.push(id); continue; }
        if (current !== id) continue;
        const row = document.querySelector('#s-' + id + ' .hrow');
        if (row && row.scrollWidth > row.clientWidth + 1) bad.push(id + ' ' + row.scrollWidth + '>' + row.clientWidth);
      }
      return bad;
    });
    ok(!krap.length, tag + ': de kop past op 360 px' + (krap.length ? ' (' + krap.join('; ') + ')' : ''));
    await pg.evaluate(() => show('facstuk'));
    await pg.screenshot({ path: path.join(__dirname, 'shots', 'navigatie-factuur-' + scheme + '.png') });
    if (scheme === 'light') {
      await pg.evaluate(() => show('car'));
      await pg.screenshot({ path: path.join(__dirname, 'shots', 'navigatie-auto.png') });
    }
    await ctx.close();
  }

  {
    const { ctx, pg } = await open('light');
    await pg.evaluate(() => { show('carset'); rgSave({ lang: 'en' }); });
    await pg.waitForTimeout(200 * W);
    ok(await pg.evaluate(() => current === 'carset' && document.querySelector('#s-carset .nav-home').getAttribute('aria-label') === 'Start'), 'Engels: de knop heet Start');
    await ctx.close();
  }

  {
    const { ctx, pg } = await open('light', 'apparaten');
    ok(await pg.evaluate(() => current === 'apparaten' && document.querySelector('#s-apparaten .nav-home').hidden && goBack() === false), 'losse app Apparaten: op het eerste scherm geen Hoofdscherm, en terug sluit');
    await pg.evaluate(() => show('apset'));
    ok(await pg.evaluate(() => current === 'apset' && document.querySelector('#s-apset .nav-home').hidden), 'losse app: de instellingen zijn één stap, dus geen tweede knop');
    await pg.evaluate(() => goBack());
    ok(await pg.evaluate(() => current === 'apparaten'), 'losse app: de pijl gaat naar Apparaten, niet naar het startscherm van Tools');
    await pg.evaluate(() => show('apdev'));
    await pg.evaluate(() => goBack());
    ok(await pg.evaluate(() => current === 'apparaten'), 'losse app: een apparaat gaat terug naar Apparaten');
    await ctx.close();
  }

  {
    const { ctx, pg } = await open('light', 'facturen');
    await pg.evaluate(() => show('facdos'));
    ok(await pg.evaluate(() => current === 'facdos' && document.querySelector('#s-facdos .nav-home').hidden), 'losse app Facturen: het dossier is één stap naar Facturen');
    await pg.evaluate(() => show('facstuk'));
    ok(await pg.evaluate(() => current === 'facstuk' && !document.querySelector('#s-facstuk .nav-home').hidden && document.querySelector('#s-facstuk .nav-home').getAttribute('aria-label') === 'Hoofdscherm'), 'losse app: dieper heet de knop Hoofdscherm');
    await pg.evaluate(() => goBack());
    ok(await pg.evaluate(() => current === 'facdos'), 'losse app: de pijl gaat naar het dossier');
    await pg.evaluate(() => show('facstuk'));
    await klik(pg, '#s-facstuk .nav-home');
    ok(await pg.evaluate(() => current === 'facturen'), 'losse app: Hoofdscherm gaat naar Facturen');
    await pg.evaluate(() => { show('facstuk'); rgSave({ lang: 'en' }); });
    await pg.waitForTimeout(200 * W);
    ok(await pg.evaluate(() => current === 'facstuk' && document.querySelector('#s-facstuk .nav-home').getAttribute('aria-label') === 'Main screen'), 'Engels, losse app: Main screen');
    await ctx.close();
  }

  {
    const { ctx, pg } = await open('light', 'backup');
    await pg.evaluate(() => show('herstel'));
    ok(await pg.evaluate(() => current === 'herstel' && document.querySelector('#s-herstel .nav-home').hidden), 'losse app Backup: terugzetten is één stap, dus geen tweede knop');
    await pg.evaluate(() => goBack());
    ok(await pg.evaluate(() => current === 'backup'), 'losse app Backup: terug vanaf terugzetten komt bij Backup, en blijft niet hangen');
    await ctx.close();
  }

  {
    const { ctx, pg } = await open('light', 'player');
    await pg.evaluate(() => show('ralarm'));
    ok(await pg.evaluate(() => current === 'ralarm' && document.querySelector('#s-ralarm .nav-home').hidden), 'losse app Radio: een wekker is één stap terug naar Radio');
    await pg.evaluate(() => goBack());
    ok(await pg.evaluate(() => current === 'radio'), 'losse app Radio: de pijl komt bij Radio');
    await ctx.close();
  }

  for (const scheme of ['light', 'dark']) {
    const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme, timezoneId: 'Europe/Amsterdam' });
    const pg = await ctx.newPage();
    pg.on('pageerror', e => errs.push('skin ' + scheme + ': ' + e.message));
    await pg.goto(skin);
    await pg.waitForTimeout(400 * W);
    const pijl = 'M15 18l-6-6 6-6';
    const koppen = await pg.evaluate(() => ['#look h2 .ib', '#pick h2 .ib', '#wpick h2 .ib'].map(s => {
      const b = document.querySelector(s), p = b && b.querySelector('svg path');
      return { sel: s, d: p ? p.getAttribute('d') : '', label: b ? b.getAttribute('aria-label') : '' };
    }));
    ok(koppen.every(k => k.d === pijl && k.label.length > 2), 'skin ' + scheme + ': Look, Apps kiezen en Widgets hebben dezelfde pijl');
    await pg.evaluate(() => openLook());
    ok(await pg.evaluate(() => !$('#pick .nav-home') || $('#pick .nav-home').hidden), 'skin ' + scheme + ': Look zelf heeft geen Hoofdscherm');
    await pg.evaluate(() => pickApps('pinned'));
    ok(await pg.evaluate(() => $('#look').classList.contains('on') && $('#pick').classList.contains('on') && !$('#pick .nav-home').hidden && getComputedStyle($('#pick .nav-home')).display !== 'none'), 'skin ' + scheme + ': Apps kiezen vanuit Look toont Hoofdscherm');
    if (scheme === 'light') await pg.screenshot({ path: path.join(__dirname, 'shots', 'navigatie-skin.png') });
    await klik(pg, '#pick .nav-home');
    ok(await pg.evaluate(() => !$('#pick').classList.contains('on') && !$('#look').classList.contains('on')), 'skin ' + scheme + ': Hoofdscherm sluit Apps kiezen en Look');
    await pg.evaluate(() => { homeSheet(); });
    await klik(pg, '#sacts button:has-text("Apps op het startscherm")');
    ok(await pg.evaluate(() => $('#pick').classList.contains('on') && $('#pick .nav-home').hidden && getComputedStyle($('#pick .nav-home')).display === 'none'), 'skin ' + scheme + ': Apps kiezen vanaf het startscherm is al één stap, dus geen tweede knop');
    await ctx.close();
  }

  await b.close();
  console.log('errors', errs);
  if (errs.length) process.exitCode = 1;
})().catch(e => { console.error(e); process.exit(1); });
