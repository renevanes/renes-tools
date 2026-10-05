// Automatiseringen: EasyPark-sjabloon, Bluetooth en plek kiezen, knoppen opnemen, opslaan, aan/uit, testen en verwijderen.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    const errs = []; pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
    console.log('tile', (await pg.textContent('#tile-auto')).replace(/\s+/g, ' ').trim());
    await pg.click('#tile-auto'); await pg.waitForTimeout(150);
    console.log('screen', await pg.evaluate(() => current), 'empty shown', await pg.isVisible('#au-empty'));
    console.log('perm rows', await pg.$$eval('#au-perms .auperm b', l => l.map(x => x.textContent).join(' | ')));
    await pg.screenshot({ path: __dirname + '/shots/auto-list-empty-' + scheme + '.png', fullPage: true });

    // Sjabloon: parkeren starten
    await pg.click('text=Parkeren starten als ik parkeer'); await pg.waitForTimeout(200);
    console.log('editor', await pg.evaluate(() => current), 'name', await pg.inputValue('#ae-name'), 'app', await pg.inputValue('#ae-app'));
    console.log('bt note', await pg.textContent('#ae-btnote'));
    await pg.click('#ae-btnote a'); await pg.waitForTimeout(150);
    console.log('bt chips', await pg.$$eval('#ae-bt button', l => l.map(x => x.textContent + (x.classList.contains('on') ? '*' : '')).join(' | ')));
    // opslaan zonder auto en plek geeft een melding
    await pg.click('#s-autoed button.big:has-text("Opslaan")'); await pg.waitForTimeout(100);
    console.log('warn', await pg.textContent('#ae-warn'));
    await pg.click('#ae-bt button:has-text("VW Golf")');
    console.log('place box visible', await pg.isVisible('#ae-placebox'));
    await pg.click('#ae-here'); await pg.waitForTimeout(250); // eerst toestemming
    await pg.click('#ae-here'); await pg.waitForTimeout(300);
    console.log('place', await pg.textContent('#ae-pinfo'));
    await pg.fill('#ae-pname', 'Kantoor');
    await pg.click('#ae-radius button[data-r="100"]');
    await pg.click('#ae-days button[data-d="0"]'); await pg.click('#ae-days button[data-d="4"]');
    await pg.fill('#ae-from', '07:00'); await pg.dispatchEvent('#ae-from', 'change');
    await pg.fill('#ae-to', '19:00'); await pg.dispatchEvent('#ae-to', 'change');
    // volledig automatisch zonder knoppen mag niet
    await pg.click('#ae-mode button[data-v="auto"]');
    await pg.click('#s-autoed button.big:has-text("Opslaan")'); await pg.waitForTimeout(100);
    console.log('warn auto', await pg.textContent('#ae-warn'));
    // opnemen: eerst toegankelijkheid
    await pg.click('text=● Opnemen in de app'); await pg.waitForTimeout(100);
    console.log('modal', await pg.textContent('#modal-title'));
    await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    await pg.evaluate(() => onResumeApp()); await pg.waitForTimeout(100);
    console.log('back in editor', await pg.evaluate(() => current), 'kept place', await pg.evaluate(() => aeRule.place.name));
    await pg.click('text=● Opnemen in de app'); await pg.waitForTimeout(100);
    console.log('modal2', await pg.textContent('#modal-title'));
    await pg.click('#modal-ok'); await pg.waitForTimeout(500);
    console.log('steps', await pg.$$eval('#ae-steps li .lbl', l => l.map(x => x.textContent).join(' | ')), 'toast', await pg.textContent('#toast'));
    await pg.click('text=＋ Wachten'); await pg.click('#ae-steps li:nth-child(3) button[aria-label="Omhoog"]');
    console.log('steps2', await pg.$$eval('#ae-steps li .lbl', l => l.map(x => x.textContent).join(' | ')));
    await pg.screenshot({ path: __dirname + '/shots/auto-edit-' + scheme + '.png', fullPage: true });
    await pg.click('#s-autoed button.big:has-text("Opslaan")'); await pg.waitForTimeout(150);
    console.log('saved, now on', await pg.evaluate(() => current));
    console.log('rule', (await pg.textContent('#au-list')).replace(/\s+/g, ' ').trim());
    console.log('perm rows', await pg.$$eval('#au-perms .auperm', l => l.map(x => x.textContent.replace(/\s+/g, ' ').trim()).join(' | ')));
    await pg.screenshot({ path: __dirname + '/shots/auto-list-' + scheme + '.png', fullPage: true });

    // tweede sjabloon: herinnering stoppen, alleen melding
    await pg.click('text=Herinnering: parkeeractie stoppen'); await pg.waitForTimeout(150);
    console.log('stop tpl', await pg.inputValue('#ae-name'), 'steps hidden', !(await pg.isVisible('#ae-stepsbox')), 'bt preselected', await pg.$$eval('#ae-bt button.on', l => l.length));
    await pg.click('#s-autoed button.big:has-text("Opslaan en nu testen")'); await pg.waitForTimeout(150);
    console.log('tested', await pg.evaluate(() => Android._au.tested === aeRule.id), 'toast', await pg.textContent('#toast'));
    await pg.evaluate(() => goBack()); await pg.waitForTimeout(100);
    console.log('list count', await pg.$$eval('#au-list .aurule', l => l.length), 'log', await pg.$$eval('#au-log li', l => l.length));
    // aan/uit
    await pg.click('#au-list .aurule:first-child .tg'); await pg.waitForTimeout(80);
    console.log('off', await pg.evaluate(() => Android._au.rules[0].on), await pg.$$eval('#au-list .aurule.off', l => l.length));
    // bewerken en verwijderen
    await pg.click('#au-list .aurule:nth-child(2) .aumain'); await pg.waitForTimeout(100);
    await pg.click('#ae-del'); await pg.click('#modal-ok'); await pg.waitForTimeout(100);
    console.log('after delete', await pg.evaluate(() => current), await pg.$$eval('#au-list .aurule', l => l.length));
    await pg.evaluate(() => show('home')); await pg.waitForTimeout(100);
    console.log('tile sub', await pg.textContent('#tile-auto-sub'));
    console.log('errors', errs);
  }
  await b.close();
})();
