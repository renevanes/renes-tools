// Alles back-uppen en WhatsApp backup als overzicht (ronde 2 van de interface): statuskaart met één hoofdknop, rijen naar
// eigen schermen (hdr sm) met de stand in de rij, terug naar het overzicht, de sleutel van Leesbare chats als eenmalig
// instellen op het scherm Leesbare chats, één plek voor terugzetten (ook vanuit Gegevens terugzetten), Instellingen → Backup-map,
// zoeken en de losse app. Licht en donker, 390 en 360 px, en in het Engels.
const { chromium } = require('playwright');
const path = require('path');

const W = +process.env.UI_WACHT || 1;
const FOLD = 844;
(async () => {
  const b = await chromium.launch();
  const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const url = 'file://' + path.join(__dirname, 'ui', 'index.html');
  const open = async (scheme, width, init) => {
    const ctx = await b.newContext({ viewport: { width: width || 390, height: FOLD }, colorScheme: scheme, timezoneId: 'Europe/Amsterdam' });
    const pg = await ctx.newPage();
    pg.on('pageerror', e => errs.push(scheme + ': ' + e.message));
    if (init) await pg.addInitScript(init);
    await pg.goto(url); await pg.waitForTimeout(400 * W);
    return { ctx, pg };
  };
  const naar = async (pg, id) => { await pg.evaluate(id => show(id), id); await pg.waitForTimeout(150 * W); };
  const cur = pg => pg.evaluate(() => current);
  const hoog = pg => pg.evaluate(() => document.documentElement.scrollHeight);
  // Gevulde hoofdknoppen op het open scherm (zelfde telling als ui-patronen)
  const prim = pg => pg.evaluate(() => {
    const s = document.querySelector('.screen.on'), probe = document.createElement('div');
    probe.style.background = 'var(--primary)'; document.body.append(probe); const p = getComputedStyle(probe).backgroundColor; probe.remove();
    return [...s.querySelectorAll('button, [role=button]')].filter(e => e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden' &&
      getComputedStyle(e).backgroundColor === p && !e.matches('[role=switch],[role=tab],[role=radio],[role=checkbox],[aria-pressed],[aria-selected],.tg') && e.textContent.trim().length > 1).map(e => e.textContent.trim());
  });
  const breed = pg => pg.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1);

  for (const scheme of ['light', 'dark']) {
    const tag = scheme;
    const { ctx, pg } = await open(scheme);
    await pg.evaluate(() => { Android._wa.info.dest = true; Android._wa.info.destName = 'USB-stick Rene'; Android._wa.info.filesAccess = true; });

    // ---------- Alles back-uppen: overzicht ----------
    await pg.click('#tile-backup'); await pg.waitForTimeout(250 * W);
    ok(await cur(pg) === 'backup', tag + ': tegel opent Alles back-uppen');
    let h = await hoog(pg);
    ok(h <= 1.5 * FOLD, tag + ': Alles back-uppen is ' + (h / FOLD).toFixed(2) + ' schermen (≤ 1,5)');
    ok(JSON.stringify(await prim(pg)) === '["Nu alles back-uppen"]', tag + ': Alles back-uppen: één hoofdknop (' + (await prim(pg)).join(' | ') + ')');
    ok(await pg.evaluate(() => { const r = document.querySelector('#bk-run').getBoundingClientRect(); return r.bottom <= innerHeight && r.top > 0; }), tag + ': Nu alles back-uppen boven de vouw');
    ok(await pg.isVisible('#bk-herstel-st') && /Nog niet compleet/.test(await pg.textContent('#bk-herstel-st')), tag + ': status voor herinstallatie staat in de statuskaart');
    ok(await pg.evaluate(() => document.querySelectorAll('#s-backup .card.stlist .strow').length) === 3, tag + ': drie rijen');
    // De stand op de rijen; versleutelen staat er altijd bij, niet afgekapt
    const sumParts = await pg.textContent('#bk-sum-parts'), sumSet = await pg.textContent('#bk-sum-set');
    ok(/^\d+ van 14 · privé uit$/.test(sumParts), tag + ': Wat gaat er mee: stand "' + sumParts + '"');
    ok(/niet versleuteld/.test(sumSet) && /USB-stick/.test(sumSet), tag + ': Automatisch en versleutelen: stand "' + sumSet + '"');
    ok(await pg.evaluate(() => { const e = document.querySelector('#bk-sum-set'); return getComputedStyle(e).whiteSpace === 'normal' && e.scrollWidth <= e.clientWidth + 1; }), tag + ': stand van versleutelen niet afgekapt');
    // Wat ontbreekt voor herinstallatie: een tik brengt je naar de plek waar je het regelt
    await pg.click('#bk-herstel-list button.bkfix:has-text("Versleutelen")'); await pg.waitForTimeout(150 * W);
    ok(await cur(pg) === 'bkset', tag + ': ontbrekend Versleutelen → Automatisch en versleutelen');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(120 * W);

    // Rij → eigen scherm (kleine kop) → terug; de stand werkt bij
    for (const [row, scr] of [['#bk-row-parts', 'bkparts'], ['#bk-row-set', 'bkset'], ['#bk-row-restore', 'bkrestore']]) {
      await pg.click(row); await pg.waitForTimeout(150 * W);
      const sm = await pg.evaluate(s => document.querySelector('#s-' + s + ' .hdr').classList.contains('sm'), scr);
      ok(await cur(pg) === scr && sm, tag + ': ' + row + ' opent ' + scr + ' (kleine kop)');
      const p = await prim(pg);
      ok(p.length <= 1, tag + ': ' + scr + ': hooguit één hoofdknop (' + p.join(' | ') + ')');
      ok(!(await breed(pg)), tag + ': ' + scr + ': niets breder dan het scherm');
      if (scr === 'bkparts') {
        await pg.click('#bk-parts input[data-p="music"]'); await pg.waitForTimeout(80 * W);
        ok(await pg.isVisible('#bk-private') && /Privé/.test(await pg.textContent('#s-bkparts .bk-privh')), tag + ': privé-onderdelen apart en duidelijk gemarkeerd');
      }
      if (scr === 'bkset') {
        ok(await pg.isVisible('#bk-enc-warn') && /Wachtwoord vergeten = backup kwijt/.test(await pg.textContent('#bk-enc-warn')), tag + ': waarschuwing over het wachtwoord zichtbaar');
        ok(await pg.evaluate(() => document.querySelector('#bk-auto').matches('input.switch[role=switch]')), tag + ': schakelaars zijn input.switch');
        await pg.click('#bk-auto'); await pg.waitForTimeout(150 * W);
      }
      if (scr === 'bkrestore') {
        ok(await pg.evaluate(() => document.querySelectorAll('#bk-restore-list .strow').length) === 10, tag + ': tien losse onderdelen, één rij per onderdeel');
        ok(await pg.isVisible('#bk-herstel-btn') && /Vervangt alles in Tools/.test(await pg.textContent('#s-bkrestore .card .note.warn')), tag + ': Alle appgegevens terugzetten, met zichtbare waarschuwing');
      }
      await pg.evaluate(() => goBack()); await pg.waitForTimeout(150 * W);
      ok(await cur(pg) === 'backup', tag + ': terug naar Alles back-uppen');
    }
    const sumParts2 = await pg.textContent('#bk-sum-parts'), sumSet2 = await pg.textContent('#bk-sum-set');
    ok(sumParts2 !== sumParts && parseInt(sumParts2) === parseInt(sumParts) - 1, tag + ': stand bijgewerkt na uitvinken ("' + sumParts + '" → "' + sumParts2 + '")');
    ok(/^Elke nacht/.test(sumSet2) && sumSet2 !== sumSet, tag + ': stand bijgewerkt na Elke nacht aan ("' + sumSet2 + '")');
    // Laatste backup: wat misging zichtbaar, wat lukte ingeklapt
    await pg.evaluate(() => bkRenderLast({ dest: true, last: { t: Date.now(), sms: { ok: true, count: 12 }, notes: { ok: true, count: 4 }, calls: { ok: false, msg: 'Geen toegang' } } })); await pg.waitForTimeout(60 * W);
    ok(await pg.isVisible('#bk-parts-res .bkres .bad') && !(await pg.evaluate(() => document.querySelector('#bk-parts-res details').open)), tag + ': mislukt onderdeel zichtbaar, de rest ingeklapt');
    await pg.screenshot({ path: __dirname + '/shots/backup-hub-' + scheme + '.png', fullPage: true });

    // Gegevens terugzetten (handmatig) → Annuleren: terug bij Terugzetten
    await naar(pg, 'bkrestore');
    await pg.click('#bk-herstel-btn'); await pg.waitForTimeout(150 * W);
    ok(await cur(pg) === 'herstel', tag + ': Alle appgegevens terugzetten opent Gegevens terugzetten');
    await pg.click('#hs-skip'); await pg.waitForTimeout(150 * W);
    ok(await cur(pg) === 'bkrestore', tag + ': Annuleren: terug bij Terugzetten');

    // Instellingen → Backup-map: hetzelfde scherm, terug naar de instellingen
    await naar(pg, 'settings');
    ok(/USB-stick/.test(await pg.textContent('#st-sum-backup')) && /Elke nacht/.test(await pg.textContent('#st-sum-backup')), tag + ': Instellingen: stand van de backup op de rij');
    await pg.click('#st-backup'); await pg.waitForTimeout(150 * W);
    ok(await cur(pg) === 'bkset' && await pg.isVisible('#st-dest') && /USB-stick/.test(await pg.textContent('#st-dest')), tag + ': Instellingen → Backup-map opent Automatisch en versleutelen (met de map)');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(120 * W);
    ok(await cur(pg) === 'settings', tag + ': terug naar de instellingen');

    // ---------- WhatsApp backup: overzicht ----------
    await naar(pg, 'wa'); await pg.waitForTimeout(250 * W);
    h = await hoog(pg);
    ok(h <= 1.5 * FOLD, tag + ': WhatsApp backup is ' + (h / FOLD).toFixed(2) + ' schermen (≤ 1,5)');
    ok(JSON.stringify(await prim(pg)) === '["Alles back-uppen"]', tag + ': WhatsApp backup: één hoofdknop (' + (await prim(pg)).join(' | ') + ')');
    ok(/USB-stick Rene/.test(await pg.textContent('#wa-dest')) && await pg.isVisible('#wa-dest-btn'), tag + ': backup-map in de statuskaart');
    const waSel = await pg.textContent('#wa-sum-sel'), waAuto = await pg.textContent('#wa-sum-auto'), waRd = await pg.textContent('#wa-sum-rd');
    ok(waSel === '4 van 9' && waAuto === 'Uit' && waRd === 'Sleutel niet ingesteld', tag + ': stand op de rijen ("' + waSel + '", "' + waAuto + '", "' + waRd + '")');
    for (const [row, scr] of [['#wa-row-sel', 'wasel'], ['#wa-row-auto', 'waauto'], ['#wa-row-restore', 'warestore'], ['#wa-row-rd', 'chats']]) {
      await pg.click(row); await pg.waitForTimeout(150 * W);
      const sm = await pg.evaluate(s => document.querySelector('#s-' + s + ' .hdr').classList.contains('sm'), scr);
      ok(await cur(pg) === scr && sm, tag + ': ' + row + ' opent ' + scr + ' (kleine kop)');
      const p = await prim(pg);
      ok(p.length <= 1, tag + ': ' + scr + ': hooguit één hoofdknop (' + p.join(' | ') + ')');
      ok(!(await breed(pg)), tag + ': ' + scr + ': niets breder dan het scherm');
      if (scr === 'wasel') { await pg.click('#wa-cats .cat >> nth=2'); await pg.waitForTimeout(150 * W); }
      if (scr === 'waauto') { await pg.click('#wa-auto'); await pg.waitForTimeout(150 * W); }
      if (scr === 'warestore') ok(await pg.evaluate(() => document.querySelectorAll('#s-warestore ol.steps > li').length) === 4, tag + ': terugzetten in vier genummerde stappen');
      await pg.evaluate(() => goBack()); await pg.waitForTimeout(200 * W);
      ok(await cur(pg) === 'wa', tag + ': terug naar WhatsApp backup');
    }
    ok(await pg.textContent('#wa-sum-sel') === '5 van 9', tag + ': selectie bijgewerkt (' + await pg.textContent('#wa-sum-sel') + ')');
    ok(/^Elke nacht · alles/.test(await pg.textContent('#wa-sum-auto')), tag + ': automatisch bijgewerkt (' + await pg.textContent('#wa-sum-auto') + ')');
    // Selectie back-uppen: de voortgang staat op het overzicht
    await naar(pg, 'wasel');
    await pg.click('#wa-sel-run'); await pg.waitForTimeout(300 * W);
    ok(await cur(pg) === 'wa' && await pg.isVisible('#wa-run'), tag + ': Selectie back-uppen: terug naar het overzicht met de voortgang');
    await pg.evaluate(() => Android.waCancel()); await pg.waitForTimeout(900 * W);
    await pg.screenshot({ path: __dirname + '/shots/wa-hub-' + scheme + '.png', fullPage: true });

    // ---------- Leesbare chats: de sleutel als eenmalig instellen ----------
    await naar(pg, 'chats');
    ok(await pg.evaluate(() => document.querySelector('#rd-card').open && !document.querySelector('#rd-card').classList.contains('done')), tag + ': chats: eenmalig instellen staat open zonder sleutel');
    ok(!(await pg.isVisible('#chat-body')) && (await pg.textContent('#rd-state')) === 'Sleutel niet ingesteld', tag + ': zonder sleutel geen lege chatlijst, stand "niet ingesteld"');
    ok(JSON.stringify(await prim(pg)) === '["Controleer sleutel en maak leesbaar"]', tag + ': chats: één hoofdknop');
    await pg.fill('#rd-key', 'abc'); await pg.click('#rd-save'); await pg.waitForTimeout(150 * W);
    ok(/64 tekens/.test(await pg.textContent('#toast')) && await cur(pg) === 'chats', tag + ': te korte sleutel: melding');
    await pg.fill('#rd-key', 'ab12 '.repeat(16)); await pg.click('#rd-save'); await pg.waitForTimeout(700 * W);
    ok(await pg.evaluate(() => !document.querySelector('#rd-card').open && document.querySelector('#rd-card').classList.contains('done')), tag + ': sleutel goed: eenmalig instellen klapt in');
    ok(await pg.isVisible('#chat-body') && await pg.evaluate(() => document.querySelectorAll('#chat-list button').length > 0) && (await pg.inputValue('#rd-key')) === '', tag + ': chatlijst zichtbaar, sleutel niet meer in het veld');
    ok((await pg.textContent('#rd-state')) === 'Sleutel ingesteld', tag + ': stand "Sleutel ingesteld"');
    await pg.click('#rd-card > summary'); await pg.waitForTimeout(80 * W);
    ok(await pg.isVisible('#rd-auto') && await pg.isVisible('#s-chats button:has-text("Sleutel wissen")'), tag + ': opengeklapt: automatisch bijwerken en sleutel wissen');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(200 * W);
    ok(/^Sleutel ingesteld/.test(await pg.textContent('#wa-sum-rd')), tag + ': WhatsApp backup: rij Leesbare chats zegt "' + await pg.textContent('#wa-sum-rd') + '"');

    // ---------- Zoeken: de nieuwe schermen en de rijen zijn te vinden ----------
    const zoek = q => pg.evaluate(q => { gsIndex = null; return gsApp(q).items.map(x => gsIndex[x.i].scr); }, q);
    for (const [q, scr] of [['automatisch en versleutelen', 'bkset'], ['wat gaat er mee', 'bkparts'], ['leesbare chats', 'chats'], ['whatsapp sleutel', 'chats'], ['whatsapp selectie', 'wasel'], ['terugzetten alles back-uppen', 'bkrestore']]) {
      const hits = await zoek(q);
      ok(hits.includes(scr), tag + ': zoeken "' + q + '" vindt ' + scr + ' (' + hits.join(', ') + ')');
    }
    await ctx.close();

    // ---------- 360 px: niets breder dan het scherm ----------
    const s3 = await open(scheme, 360);
    await s3.pg.evaluate(() => { Android._wa.info.dest = true; Android._wa.info.filesAccess = true; });
    const te = [];
    for (const id of ['backup', 'bkparts', 'bkset', 'bkrestore', 'wa', 'wasel', 'waauto', 'warestore', 'chats']) { await naar(s3.pg, id); if (await breed(s3.pg)) te.push(id); }
    ok(!te.length, tag + ': 360 px: niets breder dan het scherm' + (te.length ? ' (' + te + ')' : ''));
    await naar(s3.pg, 'backup');
    ok((await hoog(s3.pg)) <= 1.5 * FOLD, tag + ': 360 px: Alles back-uppen ≤ 1,5 scherm (' + ((await hoog(s3.pg)) / FOLD).toFixed(2) + ')');
    await s3.ctx.close();

    // ---------- Engels ----------
    const en = await open(scheme);
    await en.pg.evaluate(() => show('setregio')); await en.pg.selectOption('#rg-lang', 'en'); await en.pg.waitForTimeout(200 * W);
    await en.pg.evaluate(() => { Android._wa.info.dest = true; Android._wa.info.filesAccess = true; });
    await naar(en.pg, 'backup');
    const t1 = await en.pg.evaluate(() => [document.querySelector('#s-backup h1').textContent, document.querySelector('#bk-row-set b').textContent, document.querySelector('#bk-sum-parts').textContent, document.querySelector('#bk-sum-set').textContent]);
    ok(t1[1] === 'Automatic and encryption' && /^\d+ of 14 · private off$/.test(t1[2]) && /not encrypted/.test(t1[3]), 'en ' + tag + ': Alles back-uppen in het Engels (' + t1.join(' | ') + ')');
    ok((await hoog(en.pg)) <= 1.5 * FOLD, 'en ' + tag + ': ≤ 1,5 scherm');
    await naar(en.pg, 'wa');
    const t2 = await en.pg.evaluate(() => [document.querySelector('#wa-row-sel b').textContent, document.querySelector('#wa-sum-sel').textContent, document.querySelector('#wa-sum-rd').textContent, document.querySelector('#wa-sum-restore').textContent]);
    ok(t2[0] === 'Selection' && /^\d+ of 9$/.test(t2[1]) && t2[2] === 'Key not set' && t2[3] === 'In 4 steps', 'en ' + tag + ': WhatsApp backup in het Engels (' + t2.join(' | ') + ')');
    ok((await hoog(en.pg)) <= 1.5 * FOLD, 'en ' + tag + ': WhatsApp backup ≤ 1,5 scherm');
    await naar(en.pg, 'chats');
    ok((await en.pg.textContent('#rd-state')) === 'Key not set' && (await en.pg.textContent('#rd-save')) === 'Check key and make readable', 'en ' + tag + ': Leesbare chats in het Engels');
    await en.pg.screenshot({ path: __dirname + '/shots/backup-hub-en-' + scheme + '.png' });
    await en.ctx.close();
  }

  // ---------- Losse app WhatsApp backup: de eigen schermen werken ----------
  const so = await open('light', 390, () => { window.__testSolo = 'wa'; });
  ok(await cur(so.pg) === 'wa', 'losse app: WhatsApp backup');
  for (const [row, scr] of [['#wa-row-sel', 'wasel'], ['#wa-row-auto', 'waauto'], ['#wa-row-rd', 'chats'], ['#wa-row-restore', 'warestore']]) {
    ok(await so.pg.isVisible(row), 'losse app: rij ' + row + ' zichtbaar');
    await so.pg.click(row); await so.pg.waitForTimeout(150 * W);
    ok(await cur(so.pg) === scr, 'losse app: ' + row + ' opent ' + scr);
    await so.pg.evaluate(() => goBack()); await so.pg.waitForTimeout(150 * W);
  }
  ok(await cur(so.pg) === 'wa', 'losse app: terug op het overzicht');
  await so.ctx.close();
  await b.close();
  console.log('errors', errs);
})().catch(e => { console.error(e); process.exitCode = 1; });
