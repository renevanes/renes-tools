// Muziek herkennen: indeling (schermcheck 2026-11-20). Eenmalig instellen als kaart `card setup` met een sleutelveld met
// naam en een foutregel in plaats van een toast; daarna de grote knop bovenaan met meteen eronder wat er naar AudD gaat
// (zichtbaar, niet onderaan de lijst); sleutel wijzigen en het gebruik onder ⋯ in de kop; een kop boven de herkende
// nummers en het zoekveld pas bij een langere lijst (of als Zoeken er iets in zette). Toegankelijk: de knop zegt Stoppen
// tijdens het luisteren, de stand wordt voorgelezen, alleen https-links. Licht en donker, 390×844 en 360×640, en Engels.
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const lijst = n => { const nu = Date.now(), titels = ['Bohemian Rhapsody', 'Zoutelande', 'Radar Love', 'Ik wil je', 'Dancing Queen', 'Blue Monday', 'Hey Jude', 'Africa'];
    return titels.slice(0, n).map((x, i) => ({ t: nu - (i + 1) * 36e5, source: i % 3 ? 'mic' : 'radio:NPO Radio 2', title: x, artist: 'Artiest ' + i, album: 'Album', date: '1980', art: '', spotify: 'https://open.spotify.com/track/x' + i, link: 'https://lis.tn/x' })); };
  for (const scheme of ['light', 'dark']) for (const [w, h] of [[390, 844], [360, 640]]) {
    const tag = scheme + ' ' + w + ': ';
    const pg = await b.newPage({ viewport: { width: w, height: h }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + path.join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => show('music')); await pg.waitForTimeout(150);

    // Eenmalig instellen
    ok(await pg.evaluate(() => { const s = $('#mu-setup'); return s.classList.contains('setup') && !s.hidden && $('#mu-main').hidden && !$('#mu-menu').checkVisibility(); }), tag + 'zonder sleutel: alleen de kaart Eenmalig instellen (en geen menu)');
    ok(await pg.getAttribute('#mu-token', 'aria-label') === 'API-sleutel van AudD', tag + 'sleutelveld heeft een naam');
    ok(await pg.evaluate(() => document.querySelectorAll('#s-music .big:not(.ghost):not(.tonal)').length === 1), tag + 'één gevulde hoofdknop (Opslaan)');
    await pg.fill('#mu-token', 'kort'); await pg.click('#mu-setup button.big:not(.ghost)'); await pg.waitForTimeout(80);
    ok((await pg.textContent('#mu-token-msg')) === 'Dat lijkt geen geldige sleutel' && await pg.evaluate(() => document.activeElement.id === 'mu-token'), tag + 'te korte sleutel: foutregel bij het veld, focus terug');
    await pg.fill('#mu-token', 'abcdef1234567890'); await pg.click('#mu-setup button.big:not(.ghost)'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => !$('#mu-setup').checkVisibility() && $('#mu-main').checkVisibility() && $('#mu-menu').checkVisibility() && $('#mu-token-msg').textContent === ''), tag + 'opgeslagen: de knop verschijnt, de kaart en de foutregel zijn weg');

    // Leeg: knop bovenaan, privacy meteen eronder, lege lijst zonder zoekveld
    const vorm = await pg.evaluate(() => { const r = e => $(e).getBoundingClientRect(); return { btn: r('#mu-btn'), priv: r('#mu-privacy'), h: innerHeight }; });
    ok(vorm.btn.height >= 44 && vorm.priv.bottom <= vorm.h, tag + 'knop en wat er naar AudD gaat zonder scrollen in beeld (onderkant ' + Math.round(vorm.priv.bottom) + ')');
    ok(/fragment van 10 seconden gaat naar AudD/.test(await pg.textContent('#mu-privacy')), tag + 'zichtbaar wat de telefoon verlaat');
    ok(await pg.evaluate(() => $('#mu-search').hidden && $('#mu-hist-h').hidden && !!$('#mu-hist .empty')), tag + 'nog niets herkend: lege lijst, geen kop en geen zoekveld');
    ok(await pg.evaluate(() => !document.getElementById('mu-foot')), tag + 'geen voetregel met een klein linkje meer');

    // Herkennen: de knop heet Stoppen tijdens het luisteren; de stand is een status
    ok(await pg.getAttribute('#mu-state', 'role') === 'status', tag + 'stand wordt voorgelezen (role=status)');
    await pg.evaluate(() => { window.__muLabels = []; window.__muStatus = 0; const b = $('#mu-btn');
      new MutationObserver(() => window.__muLabels.push((b.classList.contains('busy') ? 'busy:' : '') + b.getAttribute('aria-label'))).observe(b, { attributes: true });
      new MutationObserver(() => window.__muStatus++).observe($('#mu-state'), { childList: true, characterData: true, subtree: true }); });
    await pg.click('#mu-btn'); // eerst de microfoon, daarna start hij vanzelf
    await pg.waitForSelector('#mu-result .mucard', { timeout: 8000 }).catch(() => {});
    ok(await pg.evaluate(() => window.__muLabels.includes('busy:Stoppen')), tag + 'tijdens het luisteren heet de knop Stoppen');
    ok(await pg.evaluate(() => window.__muStatus <= 6), tag + 'de stand verandert alleen bij een nieuwe stand (' + await pg.evaluate(() => window.__muStatus) + ' keer)');
    ok(await pg.getAttribute('#mu-btn', 'aria-label') === 'Herkennen', tag + 'daarna weer Herkennen');
    ok(await pg.evaluate(() => [...document.querySelectorAll('#mu-result .mulinks button')].every(x => /^https:/.test(x.dataset.u) && x.getBoundingClientRect().height >= 44 && /^Openen in /.test(x.getAttribute('aria-label')))), tag + 'links: https, 44 px hoog en een naam');
    ok(await pg.evaluate(() => !$('#mu-hist-h').hidden && $('#mu-hist-h').textContent === 'Herkende nummers (1)' && $('#mu-search').hidden), tag + 'één nummer: kop met aantal, nog geen zoekveld');

    // Alleen https uit het antwoord van AudD
    ok(await pg.evaluate(() => { const h = muCard({ title: 'X', artist: 'Y', spotify: 'javascript:alert(1)', apple: 'http://x', link: 'https://lis.tn/z', art: 'http://plaatje' });
      return !/javascript:|http:\/\//.test(h) && h.includes('https://lis.tn/z') && !h.includes('<img'); }), tag + 'geen javascript:- of http-adressen uit het antwoord');

    // Langere lijst: zoekveld met naam; zoeken vanuit Overal zoeken toont het veld ook bij een korte lijst
    await pg.evaluate(l => { Android._mu.hist = l; Android._mu.used = 37; show('home'); show('music'); }, lijst(8)); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => !$('#mu-search').hidden && $('#mu-search').getAttribute('aria-label') === 'Zoek in herkende nummers' && $('#mu-hist-h').textContent === 'Herkende nummers (8)'), tag + '8 nummers: zoekveld met naam, kop met aantal');
    await pg.fill('#mu-search', 'radar'); await pg.waitForTimeout(80);
    ok(await pg.$$eval('#mu-hist button', x => x.length) === 1, tag + 'zoeken filtert');
    await pg.evaluate(l => { Android._mu.hist = l; $('#mu-search').value = 'zout'; show('home'); show('music'); }, lijst(3)); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => !$('#mu-search').hidden && document.querySelectorAll('#mu-hist button').length === 1), tag + 'zoekwoord van Overal zoeken: veld blijft zichtbaar');
    await pg.evaluate(() => { $('#mu-search').value = ''; muRenderHistory(); });

    // Menu ⋯: sleutel wijzigen en het gebruik
    ok(await pg.evaluate(() => { const m = $('#mu-menu'), r = m.getBoundingClientRect(); return r.width >= 44 && r.height >= 44 && !!m.getAttribute('aria-label'); }), tag + 'menu ⋯ in de kop: 44 px en een naam');
    await pg.click('#mu-menu'); await pg.waitForTimeout(120);
    const sh = await pg.evaluate(() => ({ sub: $('#sheet-sub').textContent, acts: [...document.querySelectorAll('#sheet-acts button')].map(x => x.textContent) }));
    ok(sh.sub.startsWith('37 herkenningen gedaan via deze app'), tag + 'menu: gebruik als ondertitel (' + sh.sub.slice(0, 40) + ')');
    ok(sh.acts.join() === 'AudD-sleutel wijzigen,Je account bij AudD', tag + 'menu: ' + sh.acts);
    await pg.evaluate(() => closeSheet());
    await pg.screenshot({ path: path.join(__dirname, 'shots', 'muziek-indeling-' + w + '-' + scheme + '.png'), fullPage: true });

    // Engels
    await pg.evaluate(() => rgSave({ lang: 'en' })); await pg.waitForTimeout(150);
    await pg.evaluate(() => { show('home'); show('music'); }); await pg.waitForTimeout(200);
    ok(await pg.evaluate(() => $('#mu-hist-h').textContent === 'Recognised songs (3)' && $('#mu-search').getAttribute('aria-label') === 'Search recognised songs' && /^A 10-second clip goes to AudD/.test($('#mu-privacy').textContent) && $('#mu-btn').getAttribute('aria-label') === 'Recognise'), tag + 'Engels');
    await pg.close();
  }
  console.log('errors', errs);
  await b.close();
})().catch(e => { console.log(e.message); process.exit(1); });
