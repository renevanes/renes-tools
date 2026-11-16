// Instellingen → Koppelingen: één overzicht van alle diensten buiten de telefoon, met de stand.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  const url = 'file://' + require('path').join(__dirname, 'ui', 'index.html');
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto(url); await pg.waitForTimeout(300);
    await pg.evaluate(() => show('settings')); await pg.waitForTimeout(250);
    ok(/koppeling|gekoppeld/i.test(await pg.evaluate(() => $('#st-sum-kopp').textContent)), scheme + ': Instellingen toont de stand: ' + await pg.evaluate(() => $('#st-sum-kopp').textContent));
    await pg.click('.strow:has-text("Koppelingen")'); await pg.waitForTimeout(250);
    const rijen = () => pg.$$eval('#kp-list .strow', x => x.map(e => ({ id: e.dataset.id, naar: e.dataset.naar, aan: e.dataset.aan, tekst: e.querySelector('small').textContent, h: e.getBoundingClientRect().height })));
    let r = await rijen();
    ok(await pg.evaluate(() => current === 'setkopp') && ['spotify', 'google', 'ec', 'smtp', 'audd', 'stat'].every(id => r.some(x => x.id === id)), scheme + ': alle koppelingen staan erin (' + r.map(x => x.id).join(', ') + ')');
    ok(r.every(x => x.tekst.trim() && x.h >= 44), scheme + ': elke rij heeft een stand en is groot genoeg');
    // de stand volgt de gegevens
    await pg.evaluate(() => { Android._sp.linked = false; Android._mu.token = ''; kpRender(); });
    r = await rijen();
    ok(r.find(x => x.id === 'spotify').aan === '0' && /Niet gekoppeld/.test(r.find(x => x.id === 'spotify').tekst) && /Nog geen sleutel/.test(r.find(x => x.id === 'audd').tekst), scheme + ': niet gekoppeld staat er zo');
    await pg.evaluate(() => { Android._sp.linked = true; Android._mu.token = 'x'; kpRender(); });
    r = await rijen();
    ok(r.find(x => x.id === 'spotify').aan === '1' && /^Gekoppeld/.test(r.find(x => x.id === 'spotify').tekst) && r.find(x => x.id === 'audd').aan === '1', scheme + ': gekoppeld ook');
    ok(await pg.evaluate(() => !/abc|xyz/.test($('#kp-list').textContent.replace(/Eigen Cloud/g, ''))), scheme + ': geen sleutels in beeld');
    // een rij gaat naar het scherm van die koppeling, en terug kom je weer hier
    await pg.click('#kp-list .strow[data-id="audd"]'); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current === 'setmusic'), scheme + ': rij → het scherm van die koppeling');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current === 'setkopp' && $('#kp-list .strow')), scheme + ': terug → weer Koppelingen');
    await pg.click('#kp-list .strow[data-id="smtp"]'); await pg.waitForTimeout(300);
    ok(await pg.evaluate(() => current === 'facsmtp'), scheme + ': mailserver → het scherm in Facturen');
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(250);
    ok(await pg.evaluate(() => current === 'setkopp'), scheme + ': en terug');
    // tool op slot: geen accountnaam of adres
    await pg.evaluate(() => { const echt = window.tsVerborgen; window.tsVerborgen = tool => tool === 'facturen'; try { kpRender(); } finally { window.tsVerborgen = echt; } });
    r = await rijen();
    ok(['google', 'ec', 'smtp'].every(id => { const x = r.find(y => y.id === id); return x.aan === '0' && /^Op slot/.test(x.tekst) && !/@|\.nl|\.com/.test(x.tekst); }), scheme + ': Facturen op slot: alleen "Op slot", geen account of adres (' + r.find(x => x.id === 'google').tekst + ')');
    await pg.close();
  }
  console.log('errors', errs);
  await b.close();
  if (errs.length) process.exit(1);
})();
