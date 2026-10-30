// Instellingenregister (58-instellingen-register.js): elke schakelaar of keuze op een scherm staat in het register,
// of bewust in SET_EXCLUDE (met een reden). Zo wordt een nieuwe instelling niet vergeten bij de assistent.
// Daarnaast: elke instelling is goed beschreven, en wat de assistent mag aanpassen, gaat ook echt heen en terug.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  pg.on('pageerror', e => errs.push(e.message));
  await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
  // Lijsten met schakelaars per item vullen: een automatisering, de spraakmodellen, de bronnen van de AI
  await pg.evaluate(() => {
    Android._au.rules.push({ id: 'r1', name: 'Parkeren', on: true, mode: 'notify', trig: 'time', at: '08:00', act: 'app' });
    Android._tx.models.base = true; Android._tx.model = 'base'; Android._tx.files = true;
    Android._ai.st.klein35 = { state: 'ready' }; Android._ai.chat = 'klein35';
    // Wekker met een eigen zender: niet afhankelijk van of de zenderlijst van de radio al geladen is (traag in CI)
    Android._al.station = { name: 'Testzender', url: 'http://radio.invalid/stream' };
  });

  // 1. Dekking: alle schermen langs
  const r = await pg.evaluate(() => {
    const covers = setAll().map(e => e.ctl).filter(Boolean).concat(SET_GEN.map(g => g.cover), SET_EXCLUDE.map(x => x[0]));
    const SEL = '.tg, [role=switch], select, input[type=checkbox], input[type=radio], input[type=time], [role=radiogroup], .chips';
    const miss = [], seen = [];
    document.querySelectorAll('section.screen[id^="s-"]').forEach(sec => {
      const name = sec.id.slice(2);
      try { show(name); } catch (e) { }
      sec.querySelectorAll(SEL).forEach(el => {
        // Knoppen in een groep keuzeknoppen: de groep telt
        const grp = el.parentElement && el.parentElement.closest('.chips, [role=radiogroup]');
        if (grp && grp !== el && sec.contains(grp)) return;
        seen.push(name);
        const hit = covers.some(c => { try { return el.matches(c) || !!el.closest(c); } catch (e) { return false; } });
        if (!hit) miss.push(name + ': ' + (el.id ? '#' + el.id : el.outerHTML.slice(0, 90)));
      });
    });
    show('home');
    return { miss, n: seen.length, screens: new Set(seen).size };
  });
  ok(r.n > 60 && r.screens > 15, 'schermen langsgelopen: ' + r.n + ' schakelaars en keuzes op ' + r.screens + ' schermen');
  ok(!r.miss.length, 'elke schakelaar en keuze staat in het register of bewust erbuiten' + (r.miss.length ? ': ' + r.miss.join(' | ') : ''));

  // 2. Elke instelling goed beschreven
  const bad = await pg.evaluate(() => {
    const out = [], ids = new Set();
    setAll().forEach(e => {
      const t = e.type || 'aan', w = msg => out.push(e.id + ': ' + msg);
      if (!/^[a-z0-9][a-z0-9._-]{0,59}$/.test(e.id)) w('id');
      if (ids.has(e.id)) w('dubbele id'); ids.add(e.id);
      if (!document.getElementById('s-' + e.scr) && e.id !== 'radio.terugspoelen') w('scherm ' + e.scr + ' bestaat niet');
      if (!e.label || !e.desc) w('naam of uitleg ontbreekt');
      if (!['aan', 'keuze', 'tijd', 'tekst', 'openen'].includes(t)) w('soort ' + t);
      if ((t === 'aan' || t === 'keuze' || t === 'tijd') && (typeof e.get !== 'function' || typeof e.set !== 'function') && !e.safe) w('get/set ontbreekt');
      if (e.ctl && !document.querySelector(e.ctl.split(',')[0].trim()) && !/\.tg|input\[/.test(e.ctl)) w('element ' + e.ctl + ' niet gevonden');
      if (t === 'keuze' && typeof e.vals !== 'function' && !(e.vals || []).length) w('waarden ontbreken');
      if (e.safe && !['geheim', 'beveiliging', 'privacy'].includes(e.safe)) w('safe ' + e.safe);
      if (e.safe && !e.why) w('reden (why) ontbreekt bij gevoelige instelling');
    });
    return { out, n: setAll().length };
  });
  ok(bad.n >= 80, 'het register heeft ' + bad.n + ' instellingen');
  ok(!bad.out.length, 'elke instelling heeft id, scherm, naam, uitleg, soort en get/set' + (bad.out.length ? ': ' + bad.out.join(' | ') : ''));
  ok(await pg.evaluate(() => ['slot.aan', 'slot.na', 'slot.code', 'losse.apps', 'toestemmingen', 'backup.versleutelen', 'wa.sleutel', 'vpn.tunnels', 'muziek.sleutel'].every(id => { const e = setEntry(id); return e && setSafe(e, 'aan') && setSafe(e, 'uit'); })),
    'beveiliging en geheimen: nooit door de assistent aan te passen');
  ok(await pg.evaluate(() => setSafe(setEntry('backup.prive.ai'), 'aan') && !setSafe(setEntry('backup.prive.ai'), 'uit') && setSafe(setEntry('vpn.onbekend'), 'uit') && !setSafe(setEntry('vpn.onbekend'), 'aan') && setSafe(setEntry('ai.bron.sms'), 'aan')),
    'alleen de verzwakkende kant op gevoelig (privé mee in de backup, VPN minder vaak, berichten van anderen)');
  ok(await pg.evaluate(() => setGet(setEntry('slot.code')) === '' && setGet(setEntry('wa.sleutel')) === ''), 'geheimen: geen waarde');
  ok(await pg.evaluate(() => { const ids = ['gebruik.foto.mislukt', 'gebruik.foto.ontgrendeld', 'gebruik.apparaatbeheer']; return ids.every(id => { const e = setEntry(id); if (!e) return false; return setVals(e).concat([['aan'], ['uit']]).every(v => setSafe(e, v[0])); }); }),
    'Gebruik: foto en apparaatbeheer kan de assistent nooit aanpassen');

  // 3. Wat de assistent mag aanpassen: heen en terug, via dezelfde functies als het scherm
  const rt = await pg.evaluate(() => {
    const out = [], done = [];
    setAll().forEach(e => {
      if (!setCanChange(e) || (e.avail && e.avail()) || e.id === 'ai.model') return;
      const t = setType(e), cur = setGet(e);
      const alt = t === 'tijd' ? (cur === '06:45' ? '07:15' : '06:45') : (setVals(e).map(x => x[0]).find(v => v !== cur && !setSafe(e, v)));
      if (alt == null || setSafe(e, alt)) return;
      let err = ''; try { err = e.set(alt) || ''; } catch (x) { err = 'fout: ' + x.message; }
      if (err) { out.push(e.id + ' → ' + alt + ': ' + err); return; }
      if (setGet(e) !== alt) { out.push(e.id + ': na zetten op ' + alt + ' staat het op ' + setGet(e)); return; }
      if (cur !== '' && !setSafe(e, cur)) { try { e.set(cur); } catch (x) { } if (setGet(e) !== cur) out.push(e.id + ': terugzetten op ' + cur + ' lukte niet (' + setGet(e) + ')'); }
      done.push(e.id);
    });
    return { out, done };
  });
  ok(rt.done.length >= 45, rt.done.length + ' instellingen heen en terug gezet');
  ok(!rt.out.length, 'heen en terug klopt overal' + (rt.out.length ? ': ' + rt.out.join(' | ') : ''));
  ok(await pg.evaluate(() => Android._bk.auto === false && Android._zoom === 0 && store.get('t-stop', true) === true), 'na terugzetten: weer zoals het was');
  await b.close();
  console.log('errors', errs);
})();
