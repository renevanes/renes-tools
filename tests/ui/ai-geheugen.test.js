// Assistent: geheugen. Onthoud/vergeet, leren met toestemming of vanzelf, alles zichtbaar en aan te passen.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => { Android._ai.st.klein = { state: 'ready' }; show('aiset'); }); await pg.waitForTimeout(200);
    ok((await pg.textContent('#ai-sum-mem')) === 'Nog niets · vraagt eerst', 'overzicht: geheugen leeg, vraagt eerst');
    await pg.click('#s-aiset .strow:has-text("Geheugen")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'aisetmem') && (await pg.textContent('#aim-list')).includes('weet nog niets'), 'geheugen: leeg, met uitleg');
    ok((await pg.getAttribute('#aim-use', 'aria-checked')) === 'true' && (await pg.getAttribute('#aic-learn button[data-v=vragen]', 'aria-checked')) === 'true', 'standaard: aan, eerst vragen');
    // Zelf toevoegen, aanpassen, vergeten en terugzetten
    await pg.click('#s-aisetmem button:has-text("Zelf iets toevoegen")'); await pg.fill('#modal-input', 'werkt bij ESAPE'); await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#aim-list')).includes('Werkt bij ESAPE') && (await pg.textContent('#aim-list')).includes('zelf toegevoegd'), 'zelf toegevoegd, met herkomst');
    await pg.click('#aim-list button[aria-label^="Aanpassen"]'); await pg.fill('#modal-input', 'Werkt bij ESAPE in Rotterdam'); await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    ok((await pg.textContent('#aim-list')).includes('Werkt bij ESAPE in Rotterdam'), 'aangepast');
    await pg.click('#aim-list button[aria-label^="Vergeten"]'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._ai.mem.length === 0) && (await pg.textContent('#undo-t')).includes('Vergeten'), 'vergeten, met ongedaan maken');
    await pg.click('#undo-b'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._ai.mem.length === 1) && (await pg.textContent('#aim-list')).includes('Werkt bij ESAPE in Rotterdam'), 'teruggezet');
    // "Onthoud dat …" in een gesprek
    await pg.evaluate(() => show('ai')); await pg.waitForTimeout(150);
    await pg.fill('#ai-in', 'Onthoud dat mijn zoon Daan heet'); await pg.evaluate(() => aiGrow()); await pg.click('#ai-send'); await pg.waitForTimeout(1200);
    const last = '#ai-msgs .aimsg:last-child';
    ok((await pg.textContent(last + ' .aimem')).includes('Onthouden: Mijn zoon Daan heet'), 'onthoud: meteen te zien bij het antwoord');
    ok(await pg.evaluate(() => { const m = Android._ai.mem.find(x => x.text === 'Mijn zoon Daan heet'); return m && m.src === 'gevraagd' && !!m.chat && m.chat === aiChat.id; }), 'onthouden, met het gesprek erbij');
    ok(await pg.isVisible(last + ' .aiacts button:has-text("Met je geheugen")'), 'antwoord laat zien dat het geheugen is gebruikt');
    // Leren met toestemming
    await pg.fill('#ai-in', 'Ik woon sinds kort in Rotterdam, heb je tips?'); await pg.evaluate(() => aiGrow()); await pg.click('#ai-send'); await pg.waitForTimeout(1300);
    ok((await pg.textContent(last + ' .aimemask')).includes('Woont in Rotterdam.') && await pg.evaluate(() => !Android._ai.mem.some(m => m.text === 'Woont in Rotterdam.')), 'eerst vragen: voorstel, nog niet onthouden');
    await pg.click(last + ' .aimemask button:has-text("Onthouden")'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._ai.mem.some(m => m.text === 'Woont in Rotterdam.' && m.src === 'geleerd')) && (await pg.textContent(last + ' .aimemask')).includes('Ongedaan maken'), 'voorstel goedgekeurd: onthouden');
    await pg.screenshot({ path: __dirname + '/shots/ai-geheugen-chat-' + scheme + '.png' });
    // Bewaard bij het gesprek: na opnieuw openen nog te zien
    const saved = await pg.evaluate(() => JSON.parse(Android.aiChatGet(aiChat.id)).msgs.filter(m => m.mem).length);
    ok(saved === 2, 'wat onthouden werd, staat bij het bewaarde gesprek');
    // Vanuit het antwoord naar het geheugen, en terug naar het gesprek
    await pg.click(last + ' .aiacts button:has-text("Met je geheugen")'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'aisetmem') && (await pg.$$('#aim-list .aimemitem')).length === 3, 'geheugen: alles wat de assistent weet');
    await pg.screenshot({ path: __dirname + '/shots/ai-geheugen-' + scheme + '.png', fullPage: true });
    const lnk = '#aim-list .aimemitem:has-text("Woont in Rotterdam") .linkish';
    ok((await pg.textContent('#aim-list .aimemitem:has-text("Woont in Rotterdam")')).includes('geleerd uit een gesprek'), 'herkomst: geleerd uit een gesprek');
    await pg.click(lnk); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => current === 'ai' && aiChat.msgs.length === 4), 'herkomst opent het gesprek');
    // Vanzelf leren, en ongedaan maken
    await pg.evaluate(() => { Android._ai.cfg = Object.assign({}, Android._ai.cfg, { learn: 'vanzelf' }); Android._ai.learnFacts = ['Favoriete kleur is groen.']; aiLoad(); });
    await pg.fill('#ai-in', 'Mijn favoriete kleur is groen'); await pg.evaluate(() => aiGrow()); await pg.click('#ai-send'); await pg.waitForTimeout(1300);
    ok((await pg.textContent(last + ' .aimem')).includes('Onthouden: Favoriete kleur is groen.') && await pg.evaluate(() => Android._ai.mem.some(m => m.text === 'Favoriete kleur is groen.')), 'vanzelf: meteen onthouden en te zien');
    await pg.click(last + ' .aimem button:has-text("Ongedaan maken")'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => !Android._ai.mem.some(m => m.text === 'Favoriete kleur is groen.')) && (await pg.textContent(last + ' .aimem')).includes('niet onthouden'), 'vanzelf onthouden: ongedaan te maken');
    // Geheugen uit: niets gebruiken, niets leren
    await pg.evaluate(() => jumpTo('aisetmem')); await pg.waitForTimeout(120);
    await pg.click('#aim-use'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => Android._ai.cfg.mem === 'uit') && await pg.isDisabled('#aic-learn button[data-v=vragen]'), 'geheugen uit: leren niet te kiezen');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    ok((await pg.textContent('#ai-sum-mem')) === 'Uit', 'overzicht: uit');
    await pg.evaluate(() => show('ai')); await pg.waitForTimeout(100);
    await pg.fill('#ai-in', 'Ik heb een hond'); await pg.evaluate(() => aiGrow()); await pg.click('#ai-send'); await pg.waitForTimeout(1300);
    ok(!(await pg.$(last + ' .aimem')) && !(await pg.$(last + ' .aiacts button:has-text("Met je geheugen")')), 'geheugen uit: niets gebruikt, niets geleerd');
    // Alles vergeten
    await pg.evaluate(() => { Android._ai.cfg.mem = 'aan'; jumpTo('aisetmem'); }); await pg.waitForTimeout(120);
    await pg.click('#aim-clear'); await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => Android._ai.mem.length === 0) && (await pg.textContent('#aim-list')).includes('weet nog niets'), 'alles vergeten');
    // Vergeten in een gesprek, en terugzetten
    await pg.evaluate(() => { Android.aiMemAdd('Woont in Delft.', 'geleerd', '', ''); show('ai'); aiNew(); }); await pg.waitForTimeout(80);
    await pg.fill('#ai-in', 'Vergeet dat ik in Delft woon'); await pg.evaluate(() => aiGrow()); await pg.click('#ai-send'); await pg.waitForTimeout(1200);
    ok((await pg.textContent(last + ' .aimem')).includes('Vergeten: Woont in Delft.') && await pg.evaluate(() => !Android._ai.mem.some(m => m.text === 'Woont in Delft.')), 'vergeet: weg, en te zien bij het antwoord');
    await pg.click(last + ' .aimem button:has-text("Ongedaan maken")'); await pg.waitForTimeout(80);
    ok(await pg.evaluate(() => Android._ai.mem.some(m => m.text === 'Woont in Delft.')) && (await pg.textContent(last + ' .aimem')).includes('Teruggezet'), 'vergeten: terug te zetten');
    // Nieuwe vraag terwijl hij nog aan het leren is: het leren stopt en niets komt bij het nieuwe antwoord terecht
    await pg.evaluate(() => { Android._ai.cfg.learn = 'vragen'; Android._ai.learnMs = 1500; aiLoad(); });
    await pg.fill('#ai-in', 'Ik heb twee katten'); await pg.evaluate(() => aiGrow()); await pg.click('#ai-send'); await pg.waitForTimeout(1050);
    ok((await pg.textContent(last + ' .aimem')).includes('Kijken of er iets te onthouden is'), 'na het antwoord: kijkt of er iets te onthouden is');
    await pg.fill('#ai-in', 'Wat is 2 plus 2?'); await pg.evaluate(() => aiGrow()); await pg.click('#ai-send'); await pg.waitForTimeout(1800);
    const st = await pg.evaluate(() => { const ms = aiChat.msgs; return { prev: ms[ms.length - 3].mem || {}, cur: ms[ms.length - 1].mem }; });
    ok(!st.prev.thinking && !st.prev.ask && !st.cur, 'nieuwe vraag: leren gestopt, niets bij het verkeerde antwoord');
    // Gesprek verwijderd terwijl hij leert: komt niet terug
    await pg.fill('#ai-in', 'Ik speel graag tennis'); await pg.evaluate(() => aiGrow()); await pg.click('#ai-send'); await pg.waitForTimeout(1050);
    const gone = await pg.evaluate(() => aiChat.id);
    await pg.evaluate(id => { show('aichats'); const i = aicList.findIndex(c => c.id === id); aicMenu(i); }, gone); await pg.waitForTimeout(100);
    await pg.click('#sheet-acts button:has-text("Verwijderen")'); await pg.waitForTimeout(1700);
    ok(await pg.evaluate(id => !Android._ai.chats.some(c => c.id === id), gone), 'verwijderd gesprek komt niet terug door een late melding');
    await pg.evaluate(() => { Android._ai.learnMs = 150; });
    // Menu in het gesprek
    await pg.evaluate(() => show('ai')); await pg.waitForTimeout(100);
    await pg.click('#ai-menu'); await pg.waitForTimeout(100);
    ok((await pg.$$eval('#sheet-acts button', x => x.map(e => e.textContent))).some(t => /^Geheugen \(\d+\)$/.test(t)), 'menu: geheugen');
    await pg.close();
  }
  await b.close();
  console.log('errors', errs);
})();
