// Elke geregistreerde tool kan los starten, inclusief nieuwe tools, eigen instellingen en beveiligde tools.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');
const register = require('../../tools/tool-register');
const W = +process.env.UI_WACHT || 1;
(async () => {
  const source = fs.readFileSync(path.join(__dirname, '../../web/src/script/00-tools.js'), 'utf8');
  const future = register.readTools(source.replace('const TOOLS = {', "const TOOLS = {\n  nieuwtool: ['Nieuwe tool', '#123456', '★'],"));
  assert(register.soloTools(future).includes('nieuwtool'), 'nieuwe tools doen standaard mee');
  assert(register.javaSource(future).includes('"nieuwtool"'), 'nieuwe tool komt ook in het native register');
  const browser = await chromium.launch(), errors = [];
  const url = 'file://' + path.join(__dirname, 'ui/index.html');
  for (const scheme of ['light', 'dark']) {
    for (const id of register.soloTools(register.readTools())) {
      const pg = await browser.newPage({viewport:{width:390,height:844},colorScheme:scheme});
      pg.on('pageerror', err => errors.push(id + ': ' + err.message));
      await pg.addInitScript(tool => { window.__testSolo = tool; }, id);
      await pg.goto(url); await pg.waitForTimeout(220 * W);
      const root = id === 'player' ? 'radio' : id;
      const state = await pg.evaluate(() => ({current, tabs:getComputedStyle($('#tabs')).display, root:document.body.classList.contains('solo-root'), visible:!!document.querySelector('.screen.on')?.getClientRects().length, allowed:SOLO_TOOLS}));
      assert.equal(state.current,root,id+' opent zijn eigen scherm');
      assert.equal(state.tabs,'none',id+' heeft geen algemene tabbalk');
      assert(state.root && state.visible,id+' heeft een zichtbaar startscherm');
      assert.equal(state.allowed.length,register.soloTools(register.readTools()).length);
      const settings = await pg.evaluate(() => [...document.querySelectorAll('.screen.on [data-to]')].map(el => ({to:el.dataset.to,allowed:soloOk(el.dataset.to)})));
      assert(settings.every(item => item.allowed),id+' kan bij zijn eigen instellingen: '+JSON.stringify(settings));
      await pg.evaluate(() => { show('home'); show('settings'); openTool('contacts'); });
      assert.equal(await pg.evaluate(() => current),id==='contacts'?'contacts':root,id+' kan niet naar een andere tool');
      if (id === 'wc') {
        await pg.click('#wc-ophalen'); await pg.waitForTimeout(350 * W);
        await pg.click('#wc-set-btn'); await pg.waitForTimeout(100 * W);
        assert.equal(await pg.evaluate(() => current),'wcset');
        await pg.click('#s-wcset .strow:has-text("Bronnen")');
        assert.equal(await pg.evaluate(() => current),'wcbronnen');
      }
      if (id === 'bank') {
        assert.equal(await pg.isVisible('#bk-dicht'),true,'Bankzaken blijft op slot bij starten');
        await pg.evaluate(() => Android.bankUnlock()); await pg.waitForTimeout(160 * W);
        assert.equal(await pg.evaluate(() => bk.st.open),true,'Bankzaken kan met eigen ontgrendeling open');
        await pg.evaluate(() => Android.bankLock());
      }
      if (id === 'masterdata') {
        assert.equal(await pg.isVisible('#cm-unlock'),true,'Stamgegevens vraagt eigen ontgrendeling');
        await pg.click('#cm-unlock'); await pg.waitForFunction(() => cmOpen() && cm.recordsReady);
        await pg.evaluate(() => show('cmlog'));
        assert.equal(await pg.evaluate(() => current),'cmlog','eigen subscherm blijft beschikbaar');
      }
      if (id === 'backup') {
        assert.equal(await pg.evaluate(() => soloSrcOk('Android.waPickFolder()')),true,'de zelfstandige backup-tool mag zijn map kiezen');
      } else {
        assert.equal(await pg.evaluate(() => soloSrcOk('Android.waPickFolder()')),false,'andere tools kunnen de gedeelde backup-map niet wijzigen');
      }
      console.log('✓ '+id+' ('+scheme+')');
      await pg.close();
    }
  }
  await browser.close();
  console.log('errors', errors);
  assert.deepEqual(errors,[]);
})().catch(err => { console.log('errors', [String(err.stack || err)]); process.exit(1); });
