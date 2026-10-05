// Tegel "Telefoon-skin" op het startscherm van Rene's Tools opent de skin.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  const errs = []; pg.on('pageerror', e => errs.push(e.message));
  await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
  const groups = await pg.$$eval('#home-groups .hgroup', x => x.map(g => (g.querySelector('h3') || {}).textContent + ':' + [...g.querySelectorAll('.tile b')].map(e => e.textContent).join('/')));
  console.log('first group', groups[0]);
  console.log('visible', await pg.isVisible('#tile-skin'), 'sub', await pg.textContent('#tile-skin-sub'));
  await pg.click('#tile-skin'); console.log('opened skin', await pg.evaluate(() => Android._ho === true));
  await pg.evaluate(() => { Android._ho = false; openTool('skin'); }); console.log('shortcut opens skin', await pg.evaluate(() => Android._ho === true));
  // bestaande indeling zonder skin: komt er bovenaan bij
  await pg.evaluate(() => { localStorage.setItem('rt.home', JSON.stringify({ grouped: true, hidden: [], groups: [{ name: 'Mijn tools', tools: ['radio', 'notes'] }] })); renderHome(); });
  console.log('migrated', await pg.$$eval('#home-groups .hgroup h3', x => x.map(e => e.textContent).join(',')));
  await pg.screenshot({ path: __dirname + '/shots/skin-tile.png' });
  console.log('errors', errs);
  await b.close();
})();
