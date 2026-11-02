// Check-in terugkijken: opmerkingen bij een periode, los van het antwoord. Bij een gemiste dag zonder antwoord, bij een
// ingevulde dag waar wijzigen en de notitie uit staan, wijzigen en wissen, de stip in de kalender en de grafiek,
// inspreken (ook: zonder spraakmodel naar Modellen zonder je tekst kwijt te raken), Zoeken, een gearchiveerde vraag,
// per week (en meerdere antwoorden: één knop), niet in de toekomst, en Alles back-uppen.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    // "voel": elke dag om 08:00, al 40 dagen; eergisteren ingevuld, gisteren gemist. Wijzigen en de notitie staan uit.
    const D = await pg.evaluate(() => {
      Android.checkinState();
      const q = Android._ci.qs.find(x => x.id === 'voel');
      Object.assign(q, { on: true, tpl: false, times: ['08:00'], until: '23:59', created: Date.now() - 40 * 864e5 });
      const key = n => { const d = new Date(); d.setDate(d.getDate() - n); return ciDayKey(d); };
      Android.checkinAnswer(JSON.stringify({ q: 'voel', p: key(2), o: 3 }));
      Android._ci.ans.forEach(a => { a.t -= 864e5; }); // niet "net gegeven": wijzigen staat echt uit
      Object.assign(q, { editable: false, note: false, voice: false });
      return { y: key(1), dby: key(2), tomorrow: key(-1) };
    });
    // Naar terugkijken, in de maand van gisteren (eergisteren kan in de maand ervoor vallen: dan die ook bekijken)
    await pg.evaluate(() => { show('checkin'); ciOpen('voel'); }); await pg.waitForTimeout(250);
    // Naar de maand van k, terug of vooruit (eerder kan een andere maand open staan, bijv. die van eergisteren)
    const toMonth = async k => { for (let i = 0; i < 3; i++) { const d = await pg.evaluate(k => Math.sign(k.slice(0, 7).localeCompare(ciDayKey(ciqMonthAt).slice(0, 7))), k); if (!d) break; await pg.click(d < 0 ? '#ciq-prev' : '#ciq-next'); await pg.waitForTimeout(150); } };
    await toMonth(D.y);
    const yc = '#ciq-grid .ci-d[data-k="' + D.y + '"]';
    ok(!(await pg.$(yc + ' .ci-rm')), 'gisteren: nog geen stip');
    // 1. Gemist, zonder antwoord: Opmerking toevoegen
    await pg.click(yc); await pg.waitForTimeout(150);
    const addBtn = '#ciq-day button[aria-label^="Opmerking toevoegen: Hoe voel je je?"]';
    ok(await pg.isVisible(addBtn) && await pg.isVisible('#ciq-day button:has-text("Achteraf invullen")'), 'gemiste dag: Opmerking toevoegen naast Achteraf invullen');
    await pg.click(addBtn); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#ci-rem') && (await pg.textContent('#ci-rem-t')) === 'Opmerking toevoegen' && (await pg.textContent('#ci-rem-p')).includes('gisteren') && await pg.evaluate(() => document.activeElement.id === 'ci-rem-text'), 'venster: titel, periode, cursor in het tekstvak');
    await pg.fill('#ci-rem-text', 'Was ziek thuis, daarom niets ingevuld');
    // Inspreken: de tekst komt erachter
    await pg.click('#ci-rem-mic'); await pg.waitForTimeout(100);
    ok(await pg.isVisible('#voice') && (await pg.textContent('#voice-t')) === 'Opmerking inspreken', 'inspreken: het spraakvenster');
    await pg.evaluate(() => { Android._voiceResult = { text: 'en veel geslapen' }; });
    await pg.click('#voice-btn'); await pg.waitForTimeout(100); await pg.click('#voice-btn'); await pg.waitForTimeout(250);
    ok(!(await pg.isVisible('#voice')) && (await pg.inputValue('#ci-rem-text')) === 'Was ziek thuis, daarom niets ingevuld en veel geslapen', 'ingesproken tekst achter de getypte');
    await pg.click('#ci-rem-save'); await pg.waitForTimeout(200);
    const r1 = await pg.evaluate(y => Android._ci.rem.find(r => r.q === 'voel' && r.p === y), D.y);
    ok(r1 && r1.voice && r1.text.endsWith('veel geslapen') && !(await pg.isVisible('#ci-rem')) && (await pg.textContent('#toast')).includes('Opmerking bewaard'), 'bewaard (als ingesproken), venster dicht');
    ok(!(await pg.evaluate(y => Android._ci.ans.some(a => a.q === 'voel' && a.p === y), D.y)), 'geen antwoord gemaakt: gisteren blijft gemist');
    const day = await pg.textContent('#ciq-day');
    ok(day.includes('Was ziek thuis') && /opmerking van \d{1,2} [a-z]{3} \d\d:\d\d/.test(day), 'in de dag: de opmerking met wanneer (' + (day.match(/opmerking van [^W]*/) || [''])[0].trim() + ')');
    ok(await pg.$(yc + ' .ci-rm') && (await pg.getAttribute(yc, 'aria-label')).endsWith(': gemist, met een opmerking') && (await pg.getAttribute(yc, 'class')).includes('miss'), 'kalender: stip, en in tekst (' + await pg.getAttribute(yc, 'aria-label') + ')');
    ok((await pg.textContent('#ciq-legend')).includes('opmerking') && (await pg.textContent('#ciq-calsum')).includes('1 opmerking'), 'legenda en samenvatting noemen de opmerking');
    // Inspreken zonder spraakmodel: via de link naar Modellen; de getypte tekst komt terug
    await pg.click(addBtn); await pg.waitForTimeout(150);
    await pg.fill('#ci-rem-text', 'Concept dat niet weg mag');
    await pg.click('#ci-rem-mic'); await pg.waitForTimeout(100);
    await pg.evaluate(() => { Android._voiceErr = 'model'; });
    await pg.click('#voice-btn'); await pg.waitForTimeout(100);
    await pg.click('#voice-state a'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current === 'models') && !(await pg.isVisible('#ci-rem')) && (await pg.textContent('#toast')).includes('nog niet bewaard'), 'naar Modellen: venster dicht, tekst bewaard als concept');
    await pg.evaluate(() => { Android._voiceErr = ''; jumpTo('checkinq'); }); await pg.waitForTimeout(250);
    await pg.click(addBtn); await pg.waitForTimeout(150);
    ok((await pg.inputValue('#ci-rem-text')) === 'Concept dat niet weg mag', 'terug: de tekst staat er weer');
    await pg.click('#ci-rem-cancel'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(y => Android._ci.rem.filter(r => r.p === y).length === 1, D.y), 'concept niet vanzelf bewaard');
    // 2. Ingevuld, met wijzigen en notitie uit: knop Opmerking bij het antwoord (geen Wijzigen)
    await toMonth(D.dby);
    const dc = '#ciq-grid .ci-d[data-k="' + D.dby + '"]';
    await pg.click(dc); await pg.waitForTimeout(150);
    ok(!(await pg.isVisible('#ciq-day button[aria-label^="Wijzigen"]')) && await pg.isVisible('#ciq-day button[aria-label^="Opmerking toevoegen: 🙂 Goed"]'), 'wijzigen uit: geen Wijzigen, wel Opmerking bij het antwoord');
    await pg.click('#ciq-day button[aria-label^="Opmerking toevoegen: 🙂 Goed"]'); await pg.waitForTimeout(150);
    ok(await pg.isVisible('#ci-rem') && !(await pg.isVisible('#ci-ans')), 'het opmerkingenvenster, niet het hele antwoordscherm');
    await pg.fill('#ci-rem-text', 'Achteraf: dat was de dag van de verhuizing');
    await pg.click('#ci-rem-save'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(k => Android._ci.rem.some(r => r.p === k && r.text.includes('verhuizing') && !r.voice) && Android._ci.ans.find(a => a.p === k).note === '', D.dby) && (await pg.textContent('#ciq-day')).includes('verhuizing'), 'opmerking bewaard; het antwoord zelf onveranderd');
    await pg.screenshot({ path: __dirname + '/shots/checkin-opmerking-' + scheme + '.png', fullPage: true });
    // Grafiek: stip bij de dag met een opmerking (en een antwoord)
    if (await pg.evaluate(() => ciqViewV) !== 'month') await pg.click('#ciq-view button[data-v=month]');
    await pg.waitForTimeout(100);
    ok((await pg.$$('#ciq-chart .ci-rmk')).length >= 1 && (await pg.getAttribute('#ciq-chart svg', 'aria-label')).includes('met een opmerking'), 'grafiek: stip bovenaan, en in de beschrijving');
    if (D.y.slice(0, 7) === D.dby.slice(0, 7)) ok((await pg.textContent('#ciq-chart tbody')).includes('geen antwoord · opmerking'), 'als tabel: ook de dag met alleen een opmerking');
    // 3. Wijzigen
    await pg.click('#ciq-day button[aria-label^="Opmerking wijzigen"]'); await pg.waitForTimeout(150);
    ok((await pg.textContent('#ci-rem-t')) === 'Opmerking wijzigen' && (await pg.inputValue('#ci-rem-text')).includes('verhuizing'), 'wijzigen: met de tekst erin');
    await pg.fill('#ci-rem-text', 'Verhuisd, moe maar blij');
    await pg.click('#ci-rem-save'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(k => { const r = Android._ci.rem.find(x => x.p === k); return r.text === 'Verhuisd, moe maar blij' && r.edited > 0; }, D.dby) && (await pg.textContent('#ciq-day')).includes('gewijzigd'), 'gewijzigd (met wanneer)');
    // Weggaan met tekst die niet bewaard is: eerst vragen
    await pg.click('#ciq-day button[aria-label^="Opmerking wijzigen"]'); await pg.waitForTimeout(150);
    await pg.fill('#ci-rem-text', 'Nog niet bewaard');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok((await pg.textContent('#modal-title')) === 'Opmerking niet bewaren?', 'terug met onbewaarde tekst: eerst vragen');
    await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    ok(!(await pg.isVisible('#ci-rem')) && await pg.evaluate(k => Android._ci.rem.find(x => x.p === k).text === 'Verhuisd, moe maar blij', D.dby), 'weggegooid: de oude tekst blijft');
    // Lege opmerking wordt niet bewaard
    await pg.click('#ciq-day button[aria-label^="Opmerking toevoegen: 🙂 Goed"]'); await pg.waitForTimeout(150);
    await pg.click('#ci-rem-save'); await pg.waitForTimeout(100);
    ok(await pg.isVisible('#ci-rem') && (await pg.textContent('#toast')).includes('Schrijf eerst'), 'leeg: niet bewaard, venster blijft');
    await pg.click('#ci-rem-cancel'); await pg.waitForTimeout(100);
    // 4. Zoeken vindt de opmerking en opent die dag
    await pg.evaluate(() => show('search')); await pg.fill('#gs-q', 'verhuisd'); await pg.evaluate(() => gsInput()); await pg.waitForTimeout(700);
    ok(await pg.isVisible('#gs-res .gi[data-k=checkin]') && (await pg.textContent('#gs-res')).includes('Opmerking: Verhuisd'), 'Zoeken: de opmerking');
    await pg.waitForTimeout(400); await pg.evaluate(() => document.querySelector('#gs-res .gi[data-k=checkin]').scrollIntoView({ block: 'center' }));
    await pg.click('#gs-res .gi[data-k=checkin]'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(k => current === 'checkinq' && ciqSelKey === k, D.dby) && (await pg.textContent('#ciq-day')).includes('Verhuisd'), 'treffer opent die dag');
    // 5. Wissen: alleen de opmerking
    await pg.click('#ciq-day button[aria-label^="Opmerking wissen"]'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#modal-text')).includes('het antwoord blijft staan'), 'wissen: zegt dat het antwoord blijft');
    await pg.click('#modal-ok'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(k => !Android._ci.rem.some(x => x.p === k) && Android._ci.ans.some(a => a.p === k), D.dby) && !(await pg.textContent('#ciq-day')).includes('Verhuisd'), 'gewist; het antwoord staat er nog');
    // 6. Niet in de toekomst (de brug weigert het ook als iemand het probeert)
    const fut = await pg.evaluate(k => JSON.parse(Android.checkinRemark(JSON.stringify({ q: 'voel', p: k, text: 'morgen' }))).error, D.tomorrow);
    ok(fut === 'Deze periode is nog niet begonnen', 'morgen: niet (' + fut + ')');
    // 7. Gearchiveerd: terugkijken met opmerkingen kan nog steeds
    await pg.evaluate(() => { Android._ci.qs.find(x => x.id === 'voel').archived = true; Android._ci.qs.find(x => x.id === 'voel').on = false; ciqLoad(); });
    await toMonth(D.y);
    await pg.click(yc); await pg.waitForTimeout(150);
    ok(!(await pg.isVisible('#ciq-day button:has-text("Achteraf invullen")')) && await pg.isVisible('#ciq-day button[aria-label^="Opmerking wissen"]') && await pg.isVisible(addBtn), 'gearchiveerd: geen achteraf invullen, wel opmerkingen');
    await pg.click(addBtn); await pg.waitForTimeout(150);
    await pg.fill('#ci-rem-text', 'Terugblik na het archiveren'); await pg.click('#ci-rem-save'); await pg.waitForTimeout(200);
    ok(await pg.evaluate(y => Android._ci.rem.filter(r => r.p === y).length === 2, D.y), 'gearchiveerd: opmerking toegevoegd');
    // 8. Per week, met meerdere antwoorden per periode: een stip in het vakje, en één knop Opmerking per periode
    const W = await pg.evaluate(() => {
      const w = Android._ci.qs.find(x => x.id === 'werk');
      Object.assign(w, { on: true, tpl: false, rhythm: 'weekly', wday: 5, times: ['08:00'], until: '23:59', perPeriod: 'many', created: Date.now() - 40 * 864e5 });
      const s = Android._ciSlots(w, Date.now() - 14 * 864e5, Date.now()).filter(x => x.end <= Date.now()).pop();
      Android.checkinAnswer(JSON.stringify({ q: 'werk', p: s.key, o: 0 })); Android.checkinAnswer(JSON.stringify({ q: 'werk', p: s.key, o: 2 }));
      const r = JSON.parse(Android.checkinRemark(JSON.stringify({ q: 'werk', p: s.key, text: 'Week met de deadline' })));
      return { key: s.key, day: ciDayKey(new Date(s.at)), ok: r.ok };
    });
    ok(W.ok, 'opmerking bij een week (' + W.key + ')');
    await pg.evaluate(() => { show('checkin'); ciOpen('werk'); }); await pg.waitForTimeout(250);
    await toMonth(W.day);
    const wc = '#ciq-grid .ci-d[data-k="' + W.key + '"]';
    ok(await pg.$(wc + ' .ci-rm') && (await pg.getAttribute(wc, 'aria-label')).endsWith(', met een opmerking'), 'weekvakje: stip, en in tekst (' + await pg.getAttribute(wc, 'aria-label') + ')');
    await pg.click(wc); await pg.waitForTimeout(150);
    ok((await pg.textContent('#ciq-day')).includes('Week met de deadline') && (await pg.$$('#ciq-day button[aria-label^="Opmerking toevoegen"]')).length === 1 && await pg.isVisible('#ciq-day button[aria-label^="Opmerking toevoegen: Hoe was je werk?"]'), 'twee antwoorden in die week: één knop Opmerking voor de periode');
    ok((await pg.textContent('#ciq-chart tbody')).includes('opmerking'), 'grafiek per week: de opmerking in de tabel');
    // 9. Vraag verwijderen: de opmerkingen gaan mee (zoals de antwoorden)
    await pg.evaluate(() => Android.checkinDelete('voel'));
    ok(await pg.evaluate(() => !Android._ci.rem.some(r => r.q === 'voel')), 'vraag verwijderd: opmerkingen ook weg');
    // 10. Alles back-uppen: in het overzicht en bij terugzetten; opmerkingen die niet te lezen waren: gemeld
    await pg.evaluate(() => { Android._wa.info.dest = true; show('backup'); }); await pg.waitForTimeout(200);
    ok((await pg.textContent('#bk-private')).includes('3 opmerkingen') && (await pg.textContent('#bk-private')).includes('labels en opmerkingen') && !(await pg.$('#bk-ci-rbroken')), 'backup: telt de opmerkingen');
    await pg.evaluate(() => { Android._bk.extra.ciRBroken = true; bkPrivRender(bkState(true)); }); await pg.waitForTimeout(50);
    ok(await pg.isVisible('#bk-ci-rbroken') && (await pg.textContent('#bk-ci-rbroken')).includes('Vragen en antwoorden gaan gewoon mee'), 'opmerkingen apart gezet: melding, de rest gaat mee');
    await pg.evaluate(() => { delete Android._bk.extra.ciRBroken; });
    await pg.click('#s-backup button[onclick*="restorePick(\'checkin\')"]'); await pg.waitForTimeout(250);
    ok((await pg.textContent('#modal-text')).includes('Opmerkingen: 4 nieuw, 2 staan er al'), 'terugzetten: opmerkingen in het overzicht');
    await pg.click('#modal-cancel'); await pg.waitForTimeout(100);
    await pg.close();
  }
  console.log('errors', errs);
  await b.close();
})();
