const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch();
  const errors = [];
  try {
    for (const colorScheme of ['light', 'dark']) {
      const page = await browser.newPage({ colorScheme, viewport: {width: 390, height: 844} });
      page.on('pageerror', e => errors.push(e.message));
      await page.goto('file://' + path.join(__dirname, 'ui', 'index.html'));
      const [vandaag, gisteren, ouder] = await page.evaluate(() => {
        const dag = rgToday();
        return [dag, rgDayAdd(dag, -1), rgDayAdd(dag, -35)];
      });
      await page.click('#tile-cpap');
      assert.match(await page.locator('#cp-leeg').innerText(), /Nog geen CPAP-metingen/);
      const opslaan = () => page.click('#cp-form [type=submit]');
      const toevoegen = async (day, mask, values) => {
        await page.click('#cp-nieuw');
        await page.fill('#cp-day', day);
        await page.fill('#cp-mask', mask);
        for (const [veld, waarde] of Object.entries(values)) await page.fill('#cp-' + veld, waarde);
        await opslaan();
      };

      await page.click('#cp-nieuw');
      await page.fill('#cp-mask', 'AirFit P10');
      await opslaan();
      assert.match(await page.locator('#cp-form-fout').innerText(), /minstens één meting/);
      await page.fill('#cp-seal', '21');
      await opslaan();
      assert.match(await page.locator('#cp-form-fout').innerText(), /tussen 0 en 20/);
      await page.fill('#cp-seal', '18');
      await page.fill('#cp-ahi', '0,5');
      await page.fill('#cp-usage', '7:30');
      await opslaan();
      await page.waitForSelector('#cp-nachten [data-dag]');
      assert.match(await page.locator('#cp-nachten').innerText(), /7 u 30 min/);

      await toevoegen(gisteren, 'airfit   P10', { seal: '12', leak: '0' });
      assert.equal(await page.locator('#cp-maskers article').count(), 1, 'gelijke maskernamen worden samengeteld');
      const vergelijking = await page.locator('#cp-maskers article').innerText();
      assert.match(vergelijking, /15\/20 punten · 2 nachten/);
      assert.match(vergelijking, /0 L\/min · Eén nacht/);
      await page.selectOption('#cp-meting', 'leak');
      assert.equal(await page.locator('#cp-verloop li').count(), 1);
      assert.match(await page.locator('#cp-verloop').innerText(), /0 L\/min/);

      await toevoegen(vandaag, 'AirFit P10', { seal: '19' });
      assert.match(await page.locator('#cp-form-fout').innerText(), /bestaat al/);
      await page.click('#s-cpapnacht .back');

      const model = '<b>AirFit F20</b>';
      await toevoegen(ouder, model, { leak: '23.5', off: '2', score: '90' });
      assert.equal(await page.locator('#cp-periode').inputValue(), 'all', 'een oude invoer blijft zichtbaar na bewaren');
      assert.equal(await page.locator('#cp-maskers article').count(), 2);
      assert.equal(await page.locator('#cp-maskers h3 b').count(), 0);
      assert.match(await page.locator('#cp-maskers').innerText(), /<b>AirFit F20<\/b>/);
      await page.selectOption('#cp-periode', '30');
      assert.equal(await page.locator('#cp-maskers article').count(), 1);
      await page.selectOption('#cp-periode', 'all');

      const oud = page.locator('#cp-nachten [data-dag="' + ouder + '"]');
      await oud.click();
      assert.equal(await page.locator('#cp-mask').inputValue(), model);
      assert.equal(await page.locator('#cp-leak').inputValue(), '23,5');
      await page.evaluate(dag => { Android._cpNachten.find(n => n.day === dag).off = 3; }, ouder);
      await page.fill('#cp-leak', '20,5');
      await opslaan();
      assert.match(await page.locator('#cp-form-fout').innerText(), /in een ander venster gewijzigd/);
      await page.click('#s-cpapnacht .back');
      await oud.click();
      assert.equal(await page.locator('#cp-off').inputValue(), '3');
      await page.fill('#cp-leak', '20,5');
      await opslaan();
      assert.match(await oud.innerText(), /20,5 L\/min/);
      await oud.click();
      await page.click('#cp-menu');
      await page.getByRole('button', {name: 'Nacht verwijderen', exact: true}).click();
      await page.click('#modal-cancel');
      assert.equal(await page.isVisible('#s-cpapnacht'), true);
      await page.click('#cp-menu');
      await page.getByRole('button', {name: 'Nacht verwijderen', exact: true}).click();
      await page.click('#modal-ok');
      assert.equal(await oud.count(), 0);

      await page.click('#s-cpap .back');
      assert.equal(await page.locator('#cp-nachten button').count(), 0, 'verlaten wist de getoonde waarden uit het scherm');
      await page.evaluate(() => { show('setregio'); });
      await page.selectOption('#rg-lang', 'en');
      await page.evaluate(() => { show('home'); });
      await page.click('#tile-cpap');
      await page.getByRole('heading', {name: 'CPAP analysis', exact: true}).waitFor();
      await page.getByRole('heading', {name: 'Compare by mask'}).waitFor();
      assert.match(await page.locator('#cp-maskers').innerText(), /15\/20 points · 2 nights/);
      await page.close();
    }
  } finally {
    await browser.close();
  }
  assert.deepEqual(errors, []);
  console.log('errors', errors);
})().catch(e => { console.log('errors', [String(e.stack || e)]); process.exit(1); });
