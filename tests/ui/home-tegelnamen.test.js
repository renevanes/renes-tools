// Startscherm: lange tegelnamen (Automatiseringen, Documentenkluis) breken nooit midden in een woord af;
// zo nodig wordt alleen die naam iets kleiner. Ook in compact, smal, en in het Engels.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  // Per naam: in hoeveel stukken het langste woord over de regels valt, en de lettergrootte
  const meet = pg => pg.evaluate(() => [...document.querySelectorAll('#home-groups .tile > b')].filter(e => e.getClientRects().length).map(e => {
    let gebroken = [];
    for (const n of e.childNodes) {
      if (n.nodeType !== 3) continue;
      const re = /[^\s-]+-?/g; let m; // een streepje is een nette plek om af te breken (SMS-backup)
      while ((m = re.exec(n.data))) {
        const r = document.createRange(); r.setStart(n, m.index); r.setEnd(n, m.index + m[0].length);
        const tops = new Set([...r.getClientRects()].map(x => Math.round(x.top)));
        if (tops.size > 1) gebroken.push(m[0]);
      }
    }
    return { naam: e.textContent.trim(), fs: parseFloat(getComputedStyle(e).fontSize), gebroken };
  }));
  for (const scheme of ['light', 'dark']) {
    for (const breed of [320, 360, 390]) {
      // Past een woord ook op 10 px niet (heel smal scherm, of een breed lettertype zoals in CI), dan mag het als laatste redmiddel breken
      const netjes = s => s.filter(x => x.gebroken.length && x.fs > 10);
      const ctx = await b.newContext({ viewport: { width: breed, height: 800 }, colorScheme: scheme });
      const pg = await ctx.newPage();
      pg.on('pageerror', e => errs.push(e.message));
      await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
      let s = await meet(pg);
      ok(s.length > 20 && !netjes(s).length, scheme + ' ' + breed + 'px: geen naam midden in een woord afgebroken ' + JSON.stringify(netjes(s)));
      if (breed === 390) ok(s.every(x => !x.gebroken.length), scheme + ' 390px: gewone tegels: nergens een woord afgebroken, ook niet op 10 px');
      const auto = s.find(x => x.naam === 'Automatiseringen'), kort = s.find(x => x.naam === 'Agenda');
      ok(auto && kort && kort.fs >= 14 && auto.fs <= kort.fs && auto.fs >= 10, scheme + ' ' + breed + 'px: alleen een te lange naam kleiner (' + (auto && auto.fs) + ' / ' + (kort && kort.fs) + ')');
      if (breed <= 390) ok(auto.fs < kort.fs, scheme + ' ' + breed + 'px: Automatiseringen past door kleiner te worden');
      // Compact: kleinere tegels, drie naast elkaar
      await pg.evaluate(() => { const c = homeCfg(); c.compact = true; homeSave(c); }); await pg.waitForTimeout(150);
      s = await meet(pg);
      ok(!netjes(s).length, scheme + ' ' + breed + 'px compact: geen woord afgebroken ' + JSON.stringify(netjes(s).map(x => x.naam)));
      // In het Engels (andere woorden): opnieuw passend, zonder herladen
      await pg.evaluate(() => { const c = homeCfg(); c.compact = false; homeSave(c); }); await pg.waitForTimeout(100);
      await pg.evaluate(() => rgSave({ lang: 'en' })); await pg.waitForTimeout(400);
      s = await meet(pg);
      ok(s.length > 20 && !netjes(s).length, scheme + ' ' + breed + 'px Engels: geen woord afgebroken ' + JSON.stringify(netjes(s).map(x => x.naam)));
      if (breed === 360) await pg.screenshot({ path: __dirname + '/shots/home-tegelnamen-' + scheme + '.png' });
      await ctx.close();
    }
  }
  console.log('errors', errs);
  await b.close();
  if (errs.length) process.exit(1);
})();
