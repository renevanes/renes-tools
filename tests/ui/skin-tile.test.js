// Tegel "Telefoon-skin" op het startscherm van Rene's Tools opent de skin. Sinds indeling 2 staat hij in de groep Slim
// (de losse groep Telefoon is weg); een eigen indeling zonder skin krijgt hem in de groep met de meeste Slim-tools.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  const errs = []; pg.on('pageerror', e => errs.push(e.message));
  const ok = (c, m) => { if (!c) errs.push('mislukt: ' + m); else console.log('✓ ' + m); };
  await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
  const groups = () => pg.$$eval('#home-groups .hgroup', x => x.map(g => (g.querySelector('h3') || {}).textContent + ':' + [...g.querySelectorAll('.tile')].map(e => e.id.replace('tile-', '')).join('/')));
  const g = await groups();
  ok(!g.some(x => x.startsWith('Telefoon:')), 'geen losse groep Telefoon meer');
  ok(g.some(x => x.startsWith('Slim:') && x.endsWith('/skin')), 'skin achteraan in Slim (' + g.find(x => x.startsWith('Slim:')) + ')');
  ok(await pg.isVisible('#tile-skin') && (await pg.textContent('#tile-skin-sub')).length > 0, 'tegel zichtbaar met uitleg');
  await pg.click('#tile-skin'); ok(await pg.evaluate(() => Android._ho === true), 'tegel opent de skin');
  await pg.evaluate(() => { Android._ho = false; openTool('skin'); }); ok(await pg.evaluate(() => Android._ho === true), 'snelkoppeling opent de skin');
  // Eigen indeling zonder skin: komt bij de groep met de meeste tools uit Slim, niet in een nieuwe groep bovenaan
  await pg.evaluate(() => { localStorage.setItem('rt.home', JSON.stringify({ grouped: true, hidden: [], groups: [{ name: 'Mijn tools', tools: ['radio', 'notes', 'ai'] }, { name: 'Rest', tools: ['pdf'] }] })); renderHome(); });
  const m = await groups();
  ok(m[0].startsWith('Mijn tools:') && m[0].includes('skin') && !m.some(x => x.startsWith('Telefoon')), 'eigen indeling: skin bij Mijn tools (' + m[0] + ')');
  await pg.screenshot({ path: __dirname + '/shots/skin-tile.png' });
  console.log('errors', errs);
  await b.close();
})();
