// De testbrug van de radio: een vertraagde startmelding (600 ms) mag stoppen of een nieuwe zender niet ongedaan maken (issue #12).
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  const errs = []; pg.on('pageerror', e => errs.push(e.message));
  await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
  const zender = (naam, url) => JSON.stringify({ name: naam, url });

  // Stoppen vóór de startmelding: blijft gestopt
  await pg.evaluate(z => { Android.radioPlay(z); Android.radioStop(); }, zender('Radio A', 'https://a.example.org/s'));
  await pg.waitForTimeout(900);
  const na = await pg.evaluate(() => ({ status: Android._rd.st.status, last: (Android._rd.st.last || {}).name, title: Android._rd.st.title }));
  console.log('stoppen voor de melding', JSON.stringify(na));
  if (na.status !== 'stopped' || na.last !== 'Radio A' || na.title) errs.push('na stoppen weer aan: ' + JSON.stringify(na));

  // Twee keer snel na elkaar: de tweede zender speelt, de oude melding schrijft niet in de nieuwe toestand
  await pg.evaluate(([x, y]) => { Android.radioPlay(x); setTimeout(() => Android.radioPlay(y), 300); }, [zender('Radio A', 'https://a.example.org/s'), zender('Radio B', 'https://b.example.org/s')]);
  await pg.waitForTimeout(700); // de melding van A is geweest, die van B nog niet
  const tussen = await pg.evaluate(() => ({ status: Android._rd.st.status, naam: Android._rd.st.station.name }));
  console.log('tussendoor', JSON.stringify(tussen));
  if (tussen.status !== 'connecting' || tussen.naam !== 'Radio B') errs.push('oude startmelding raakte de nieuwe zender: ' + JSON.stringify(tussen));
  await pg.waitForTimeout(500);
  const eind = await pg.evaluate(() => ({ status: Android._rd.st.status, naam: Android._rd.st.station.name }));
  console.log('daarna', JSON.stringify(eind));
  if (eind.status !== 'playing' || eind.naam !== 'Radio B') errs.push('tweede zender speelt niet: ' + JSON.stringify(eind));

  // Gewoon afspelen en daarna stoppen werkt nog
  await pg.evaluate(() => Android.radioStop());
  console.log('gestopt', await pg.evaluate(() => Android._rd.st.status));
  console.log('errors', errs);
  await b.close();
})();
