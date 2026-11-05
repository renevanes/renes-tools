// Slaappraat als overzicht: hoofdscherm met alleen beginnen, proberen en de nachten; alle instellingen via de schuifjes
// (sleepset), de opname-afstelling onder een ingeklapt Geavanceerd. Hoogtes, één hoofdknop, elke instelling bewaart en
// komt terug, zoeken en de assistent wijzen een instelling onder Geavanceerd aan. Licht, donker, 360 en 390 px, Engels.
const { chromium } = require('playwright');
const path = require('path');

const W = +process.env.UI_WACHT || 1;
const FOLD = 844;

(async () => {
  const b = await chromium.launch();
  const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const open = async (scheme, width, en) => {
    const ctx = await b.newContext({ viewport: { width, height: FOLD }, colorScheme: scheme, timezoneId: 'Europe/Amsterdam' });
    const pg = await ctx.newPage();
    pg.on('pageerror', e => errs.push(scheme + ' ' + width + ': ' + e.message));
    await pg.goto('file://' + path.join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300 * W);
    if (en) { await pg.evaluate(() => show('setregio')); await pg.selectOption('#rg-lang', 'en'); await pg.waitForTimeout(200 * W); }
    // Vier nachten met gepraat en een geluid
    await pg.evaluate(() => { const S = Android._sl, now = Date.now(); for (let i = 1; i <= 4; i++) { const st = now - i * 864e5 - 3 * 36e5;
      S.nights.push({ id: String(st), start: st, end: st + 7.5 * 36e5, clips: [{ id: 'c' + (st + 1e6), t: st + 1e6, dur: 3, audio: true, done: true, kind: 'praten', text: 'Waar zijn mijn sleutels gebleven' }, { id: 'c' + (st + 2e6), t: st + 2e6, dur: 5, audio: true, done: true, kind: 'geluid', text: '' }] }); } });
    return { ctx, pg };
  };
  const naar = async (pg, id) => { await pg.evaluate(id => show(id), id); await pg.waitForTimeout(150 * W); };
  // Gevulde knoppen in de kleur van --primary (zoals ui-patronen.test.js)
  const primair = pg => pg.evaluate(() => {
    const s = document.querySelector('.screen.on'), probe = document.createElement('div');
    probe.style.background = 'var(--primary)'; document.body.append(probe); const prim = getComputedStyle(probe).backgroundColor; probe.remove();
    return [...s.querySelectorAll('button, [role=button]')].filter(e => e.getClientRects().length && getComputedStyle(e).backgroundColor === prim &&
      !e.matches('[role=switch],[role=tab],[role=radio],[role=checkbox],.tg') && e.textContent.trim().length > 1).map(e => e.textContent.trim());
  });

  for (const [scheme, width, en] of [['light', 390, false], ['dark', 390, false], ['light', 360, false], ['dark', 360, true]]) {
    const tag = scheme + ' ' + width + (en ? ' (Engels)' : '');
    const { ctx, pg } = await open(scheme, width, en);

    // ---------- Hoofdscherm: status, één hoofdknop, proberen (tonaal), de nachten; kort ----------
    await naar(pg, 'sleep');
    const hoofd = await pg.evaluate(() => ({ h: document.documentElement.scrollHeight, breed: document.documentElement.scrollWidth > innerWidth + 1,
      rows: document.querySelectorAll('#sl-nights .strow').length, probe: document.querySelector('#sl-probe').classList.contains('tonal'),
      kaarten: document.querySelectorAll('#s-sleep main > .card').length, help: !!document.querySelector('#s-sleep main > details.help') && !document.querySelector('#s-sleep main > details.help').open }));
    ok(hoofd.h <= 1.5 * FOLD && !hoofd.breed, tag + ': Slaappraat is ' + (hoofd.h / FOLD).toFixed(2) + ' schermen met 4 nachten (≤ 1,5), niet breder dan het scherm');
    ok(hoofd.rows === 4 && hoofd.probe && hoofd.kaarten === 2, tag + ': statuskaart, Probeer 30 seconden tonaal, en de nachten (' + JSON.stringify(hoofd) + ')');
    ok(hoofd.help, tag + ': Goed om te weten staat ingeklapt achter de uitleg');
    const p1 = await primair(pg);
    ok(p1.length === 1 && /Begin met luisteren|Start listening/.test(p1[0]), tag + ': één gevulde hoofdknop: ' + p1.join(' | '));
    // Wat er opgenomen wordt en waar het blijft: zichtbaar, niet in de uitleg
    const noot = await pg.textContent('#sl-note');
    ok(await pg.isVisible('#sl-note') && (en ? /on your phone/.test(noot) && /2 weeks/.test(noot) : /op je telefoon/.test(noot) && /2 weken/.test(noot)), tag + ': zichtbaar wat er bewaard wordt en waar ("' + noot + '")');
    ok(!(await pg.$('#s-sleep main .chips')) && !(await pg.$('#s-sleep main .tg')), tag + ': geen instellingen meer op het hoofdscherm');
    // Lege lijst
    await pg.evaluate(() => { Android._slKeep = Android._sl.nights; Android._sl.nights = []; slLoad(); slRender(); });
    ok(await pg.evaluate(() => { const e = document.querySelector('#sl-nights .empty'); return !!(e && e.querySelector('.ui-icon') && !e.querySelector('button')); }), tag + ': geen nachten: lege lijst met pictogram, zonder tweede knop');
    await pg.evaluate(() => { Android._sl.nights = Android._slKeep; slLoad(); slRender(); });

    // ---------- Schuifjes → instellingen, en terug ----------
    ok(await pg.evaluate(() => { const b = document.querySelector('#s-sleep .hdr .hset'); return !!(b && b.dataset.to === 'sleepset' && b.querySelector('[data-icon="settings"]')); }), tag + ': schuifjes in de kop');
    await pg.click('#s-sleep .hset'); await pg.waitForTimeout(150 * W);
    ok(await pg.evaluate(() => current === 'sleepset' && document.querySelector('#s-sleepset > .hdr').classList.contains('sm') && !!document.querySelector('#s-sleepset .hdr .back path[d="M15 18l-6-6 6-6"]')), tag + ': instellingen: kleine kop met de gedeelde terugknop');
    const set = await pg.evaluate(() => ({ h: document.documentElement.scrollHeight, breed: document.documentElement.scrollWidth > innerWidth + 1, adv: document.querySelector('#sls-adv').open,
      kaal: [...document.querySelectorAll('#s-sleepset select')].filter(s => !s.classList.contains('sel')).length, chips: document.querySelectorAll('#s-sleepset .chips').length }));
    ok(!set.adv && set.h <= 2 * FOLD && !set.breed, tag + ': instellingen zijn ' + (set.h / FOLD).toFixed(2) + ' schermen met Geavanceerd ingeklapt (≤ 2)');
    ok(!set.kaal && !set.chips, tag + ': keuzelijsten met de gedeelde stijl, geen rijen knopjes');
    ok((await primair(pg)).length === 0, tag + ': instellingen: geen gevulde hoofdknop');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100 * W);
    ok(await pg.evaluate(() => current === 'sleep'), tag + ': terug naar Slaappraat');

    // ---------- Geavanceerd in- en uitklappen ----------
    await naar(pg, 'sleepset');
    const sum = pg.locator('#sls-adv > summary');
    ok(!(await pg.isVisible('#sls-clip')) && (await sum.getAttribute('aria-expanded')) === 'false', tag + ': Geavanceerd ingeklapt (aria-expanded="false")');
    ok(/8 (uur|hours)/.test(await pg.textContent('#sls-adv-n')) && /15%/.test(await pg.textContent('#sls-adv-n')), tag + ': de stand staat op Geavanceerd ("' + await pg.textContent('#sls-adv-n') + '")');
    await sum.focus(); await pg.keyboard.press('Enter'); await pg.waitForTimeout(80 * W);
    ok(await pg.isVisible('#sls-clip') && await pg.isVisible('#sls-reset') && (await sum.getAttribute('aria-expanded')) === 'true', tag + ': Enter klapt Geavanceerd open');
    ok(await pg.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), tag + ': uitgeklapt niet breder dan het scherm');
    ok((await primair(pg)).length === 0, tag + ': uitgeklapt: geen gevulde hoofdknop');
    await sum.click(); await pg.waitForTimeout(80 * W);
    ok(!(await pg.isVisible('#sls-clip')) && (await sum.getAttribute('aria-expanded')) === 'false', tag + ': en weer dicht');
    // Leesbaar in beide thema's
    ok(await pg.evaluate(() => { const s = document.querySelector('#sls-adv > summary'), cs = getComputedStyle(s); return cs.color !== cs.backgroundColor && cs.backgroundColor !== 'rgba(0, 0, 0, 0)'; }), tag + ': Geavanceerd leesbaar (eigen kleur op een eigen vlak)');
    await pg.screenshot({ path: __dirname + '/shots/sleep-hub-' + scheme + '-' + width + (en ? '-en' : '') + '.png', fullPage: true });

    // ---------- Elke instelling (het register) staat op de instellingen, bewaart en komt terug ----------
    if (!en && width === 390) {
      const ids = await pg.evaluate(() => SET_REG.filter(e => e.id.startsWith('slaap.')).map(e => e.id));
      ok(ids.length >= 20 && await pg.evaluate(() => SET_REG.filter(e => e.id.startsWith('slaap.')).every(e => e.scr === 'sleepset' && e.ctl && document.querySelector('#s-sleepset ' + e.ctl))), tag + ': alle ' + ids.length + ' instellingen van Slaappraat staan op het instellingenscherm');
      const VOOR = { '#sl-rem': '#sl-rem-on', '#sl-stop': '#sl-stop-on', '#sls-dbat': ['#sls-when', 'accu'], '#sl-direct': '#sl-tx' };
      const mis = [];
      for (const id of ids) {
        await naar(pg, 'sleepset');
        const e = await pg.evaluate(id => { const e = setEntry(id); return { ctl: e.ctl, type: e.type || 'aan', vals: typeof e.vals === 'function' ? e.vals() : e.vals, voor: e.get() }; }, id);
        await pg.evaluate(c => uiOpenTo(document.querySelector('#s-sleepset ' + c)), e.ctl);
        // Hangt hij af van een andere instelling (rij verborgen), zet die dan eerst goed
        if (!(await pg.isVisible('#s-sleepset ' + e.ctl)) && VOOR[e.ctl]) {
          const v = VOOR[e.ctl]; if (Array.isArray(v)) await pg.selectOption(v[0], v[1]); else await pg.click(v);
          await pg.waitForTimeout(60 * W);
        }
        const voor = await pg.evaluate(id => setEntry(id).get(), id);
        let doel;
        if (e.type === 'keuze') { doel = e.vals.map(v => v[0]).find(v => v !== voor); await pg.selectOption('#s-sleepset ' + e.ctl, doel === 'volg' ? '' : doel); }
        else if (e.type === 'tijd') { doel = voor === '06:15' ? '05:45' : '06:15'; await pg.fill('#s-sleepset ' + e.ctl, doel); await pg.dispatchEvent('#s-sleepset ' + e.ctl, 'change'); }
        else { doel = voor === 'aan' ? 'uit' : 'aan'; await pg.click('#s-sleepset ' + e.ctl); }
        await pg.waitForTimeout(60 * W);
        // Opnieuw openen: het scherm leest alles weer uit Android
        await naar(pg, 'sleep'); await naar(pg, 'sleepset');
        const na = await pg.evaluate(id => setEntry(id).get(), id);
        const dom = await pg.evaluate(c => { const el = document.querySelector('#s-sleepset ' + c); return el.tagName === 'BUTTON' ? (el.getAttribute('aria-checked') === 'true' ? 'aan' : 'uit') : el.value; }, e.ctl);
        const domOk = e.type === 'keuze' ? dom === (doel === 'volg' ? '' : doel) : dom === doel;
        if (na !== doel || !domOk) mis.push(id + ' (' + voor + ' → ' + doel + ': ' + na + ', scherm ' + dom + ')');
      }
      ok(!mis.length, tag + ': elke instelling bewaart en staat er na opnieuw openen weer' + (mis.length ? ': ' + mis.join('; ') : ' (' + ids.length + ')'));
      // Terug naar standaard: alleen Geavanceerd
      await pg.evaluate(c => uiOpenTo(document.querySelector('#sls-reset')));
      await pg.click('#sls-reset'); await pg.click('#modal-ok'); await pg.waitForTimeout(120 * W);
      ok(await pg.evaluate(() => { const S = Android._sl.set; return S.clipMax === 60 && S.pre === 2 && S.format === 'aac' && S.directWhen === 'accu' && S.sens !== 2; }), tag + ': Geavanceerd terug naar standaard laat de rest staan');
      // Onopvallend luisteren blijft de standaard (en werkt): weer aan
      await pg.evaluate(() => { Android._sl.set.quiet = true; slLoad(); slsRender(); });
      ok((await pg.getAttribute('#sl-quiet', 'aria-checked')) === 'true' && await pg.isVisible('#sl-quietrow'), tag + ': onopvallend luisteren aan, met Melding verder wegstoppen');

      // ---------- De assistent wijst een instelling onder Geavanceerd aan ----------
      await naar(pg, 'sleep'); await pg.evaluate(() => { document.querySelector('#sls-adv').open = false; setOpen('slaap.voorloop'); }); await pg.waitForTimeout(450 * W);
      ok(await pg.evaluate(() => current === 'sleepset' && document.querySelector('#sls-adv').open) && await pg.isVisible('#sls-pre'), tag + ': assistent: naar de instellingen, Geavanceerd klapt open bij Voorloop');
      ok(await pg.evaluate(() => setPath('sleepset')) === 'Slaappraat → Slaappraat-instellingen', tag + ': pad in het register: Slaappraat → Slaappraat-instellingen');

      // ---------- Zoeken: een instelling onder Geavanceerd ----------
      await pg.evaluate(() => { document.querySelector('#sls-adv').open = false; show('search'); });
      await pg.fill('#gs-q', 'voorloop'); await pg.evaluate(() => gsInput()); await pg.waitForTimeout(500 * W);
      const hit = pg.locator('#gs-res .gi[data-k=app]', { hasText: 'Opnemen' }).first();
      ok(await hit.count() === 1 && /Slaappraat-instellingen/.test(await hit.textContent()), tag + ': Zoeken "voorloop": Opnemen bij Slaappraat-instellingen');
      await hit.click(); await pg.waitForTimeout(400 * W);
      ok(await pg.evaluate(() => current === 'sleepset' && document.querySelector('#sls-adv').open) && await pg.isVisible('#sls-pre'), tag + ': de treffer opent de instellingen met Geavanceerd open');
      await pg.evaluate(() => show('search')); await pg.fill('#gs-q', 'gevoeligheid'); await pg.evaluate(() => gsInput()); await pg.waitForTimeout(500 * W);
      ok(await pg.locator('#gs-res .gi[data-k=app]', { hasText: 'Wat er bewaard wordt' }).count() === 1, tag + ': Zoeken "gevoeligheid": Wat er bewaard wordt bij Slaappraat-instellingen');
      await pg.fill('#gs-q', 'slaappraat'); await pg.evaluate(() => gsInput()); await pg.waitForTimeout(500 * W);
      ok(/Slaappraat-instellingen/.test(await pg.textContent('#gs-res')), tag + ': Zoeken "slaappraat": ook de instellingen');
    }

    // ---------- Engels ----------
    if (en) {
      await naar(pg, 'sleep');
      ok((await pg.textContent('#sl-btn')) === 'Start listening' && (await pg.getAttribute('#s-sleep .hset', 'aria-label')) === 'Sleep talk settings', tag + ': hoofdscherm en schuifjes in het Engels');
      await naar(pg, 'sleepset');
      ok(/Sleep talk settings/.test(await pg.textContent('#s-sleepset h1')) && /Advanced/.test(await pg.textContent('#sls-adv > summary')) && /At most 8 hours/.test(await pg.textContent('#sls-adv-n')), tag + ': instellingen en Geavanceerd in het Engels');
      ok(/Sensitivity/.test(await pg.textContent('#s-sleepset')) && /What is kept/.test(await pg.textContent('#s-sleepset')), tag + ': kopjes vertaald');
    }
    await ctx.close();
  }
  await b.close();
  console.log('errors', errs);
})().catch(e => { console.error(e); process.exitCode = 1; });
