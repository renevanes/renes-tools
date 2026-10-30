// De assistent als eigen tab onderin: openen, aria-current, past op 320 px, het invoervak niet onder de tabbalk, niet in een losse app.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const url = 'file://' + require('path').join(__dirname, 'ui', 'index.html');
  for (const scheme of ['light', 'dark']) {
    for (const width of [390, 320]) {
      const pg = await b.newPage({ viewport: { width, height: 720 }, colorScheme: scheme });
      pg.on('pageerror', e => errs.push(e.message));
      await pg.goto(url); await pg.waitForTimeout(300);
      const tabs = await pg.$$eval('#tabs button', bs => bs.map(x => x.dataset.t));
      ok(tabs.join(',') === 'home,search,ai,backup,settings', width + ' px: vijf tabs, de assistent in het midden (' + tabs + ')');
      // Alles past: geen label afgekapt of buiten beeld, geen horizontaal scrollen
      const fit = await pg.evaluate(() => {
        const nav = document.querySelector('#tabs'), out = [];
        if (nav.scrollWidth > nav.clientWidth + 1) out.push('nav ' + nav.scrollWidth + '>' + nav.clientWidth);
        document.querySelectorAll('#tabs button').forEach(bt => { const r = bt.getBoundingClientRect(), l = bt.querySelector('span:last-child');
          if (r.left < -0.5 || r.right > innerWidth + 0.5) out.push(bt.dataset.t + ' buiten beeld');
          if (l.scrollWidth > l.clientWidth + 1) out.push(bt.dataset.t + ' afgekapt ' + l.scrollWidth + '>' + l.clientWidth);
          if (r.height < 44 || r.width < 44) out.push(bt.dataset.t + ' te klein ' + Math.round(r.width) + 'x' + Math.round(r.height)); });
        if (document.documentElement.scrollWidth > innerWidth + 1) out.push('pagina breder dan het scherm');
        return out; });
      ok(!fit.length, width + ' px: alle tabs passen, groot genoeg om te tikken ' + fit.join('; '));
      ok((await pg.textContent('#tabs button[data-t=ai]')).trim() === 'Assistent', 'label Assistent');
      await pg.click('#tabs button[data-t=ai]'); await pg.waitForTimeout(250);
      ok(await pg.evaluate(() => current === 'ai' && document.body.classList.contains('tabs-on') && document.body.classList.contains('tab-root')), 'de tab opent de assistent, tabbalk blijft');
      ok(await pg.getAttribute('#tabs button[data-t=ai]', 'aria-current') === 'page' && !(await pg.getAttribute('#tabs button[data-t=home]', 'aria-current')), 'aria-current op Assistent');
      ok(!(await pg.isVisible('#s-ai .hdr .back')), 'als tab: geen terugknop (zoals Zoeken en Instellingen)');
      // Typen: de tabbalk gaat weg; versturen raakt niet mis (het vak springt niet weg onder de vinger)
      await pg.evaluate(() => { Android._ai.st.klein = { state: 'ready' }; enterAi(); }); await pg.waitForTimeout(100);
      // Het invoervak staat boven de tabbalk, niet eronder
      const pos = await pg.evaluate(() => { const c = $('#ai-comp').getBoundingClientRect(), t = $('#tabs').getBoundingClientRect(); return [Math.round(c.bottom), Math.round(t.top)]; });
      ok(pos[0] <= pos[1] + 1, 'invoervak boven de tabbalk (' + pos + ')');
      if (width === 320) await pg.screenshot({ path: __dirname + '/shots/ai-tab-320-' + scheme + '.png' });
      await pg.fill('#ai-in', 'Hallo');
      ok(!(await pg.isVisible('#tabs')), 'tijdens het typen: tabbalk weg');
      await pg.click('#ai-send'); await pg.waitForTimeout(150);
      ok(await pg.evaluate(() => !!Android._ai.last && Android._ai.last.messages.slice(-1)[0].content === 'Hallo'), 'versturen met de knop lukt');
      await pg.waitForTimeout(900);
      ok(await pg.isVisible('#tabs'), 'na het typen: tabbalk weer terug');
      // Terug: naar Start; de tegel op Start is er nog
      await pg.evaluate(() => goBack()); await pg.waitForTimeout(150);
      ok(await pg.evaluate(() => current === 'home'), 'terug: naar Start');
      ok(await pg.evaluate(() => !!document.querySelector('#tile-ai')), 'de tegel op Start is er nog');
      // Via een sprong (bijv. samenvatten): wel de terugknop, tabbalk zichtbaar
      await pg.evaluate(() => jumpTo('ai')); await pg.waitForTimeout(150);
      ok(await pg.evaluate(() => document.body.classList.contains('tabs-on') && !document.body.classList.contains('tab-root')), 'via een sprong: terugknop, tabbalk blijft');
      // Een scherm onder de assistent (instellingen) is geen tab
      await pg.evaluate(() => show('aiset')); await pg.waitForTimeout(100);
      ok(await pg.evaluate(() => !document.body.classList.contains('tabs-on')), 'Lokale AI (instellingen): geen tabbalk');
      await pg.close();
    }
  }
  // Losse app: geen tabbalk
  const pg = await b.newPage({ viewport: { width: 360, height: 720 } });
  pg.on('pageerror', e => errs.push(e.message));
  await pg.addInitScript(() => { window.__testSolo = 'ai'; });
  await pg.goto(url); await pg.waitForTimeout(400);
  ok(await pg.evaluate(() => current === 'ai') && !(await pg.isVisible('#tabs')), 'losse app Assistent: geen tabbalk');
  await b.close();
  console.log('errors', errs);
})();
