// De testbrug van de radio: een vertraagde startmelding (600 ms) mag stoppen of een nieuwe zender niet ongedaan maken (issue #12).
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const pg = await b.newPage({ viewport: { width: 390, height: 844 } });
  const errs = []; pg.on('pageerror', e => errs.push(e.message));
  await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(400);
  const zender = (naam, url) => JSON.stringify({ name: naam, url });

  // De tijd wordt in de pagina gemeten (niet met waitForTimeout): de herkansing rekt die wachttijden op.

  // Stoppen vóór de startmelding: blijft gestopt
  const na = await pg.evaluate(z => new Promise(klaar => {
    Android.radioPlay(z); Android.radioStop();
    setTimeout(() => klaar({ status: Android._rd.st.status, naam: (Android._rd.st.last || {}).name, title: Android._rd.st.title }), 900);
  }), zender('Radio A', 'https://a.example.org/s'));
  console.log('stoppen voor de melding', JSON.stringify(na));
  if (na.status !== 'stopped' || na.naam !== 'Radio A' || na.title) errs.push('na stoppen weer aan: ' + JSON.stringify(na));

  // Twee keer snel na elkaar: de tweede zender speelt, de oude melding (na 600 ms) schrijft niet in de nieuwe toestand
  const r = await pg.evaluate(([x, y]) => new Promise(klaar => {
    const st = () => ({ status: Android._rd.st.status, naam: (Android._rd.st.station || {}).name });
    let tussen;
    Android.radioPlay(x);
    setTimeout(() => Android.radioPlay(y), 200);   // B start op 200 ms, zijn melding komt op ±800 ms
    setTimeout(() => { tussen = st(); }, 700);     // na de melding van A, vóór die van B
    setTimeout(() => klaar({ tussen, eind: st() }), 1300);
  }), [zender('Radio A', 'https://a.example.org/s'), zender('Radio B', 'https://b.example.org/s')]);
  console.log('tussendoor', JSON.stringify(r.tussen), 'daarna', JSON.stringify(r.eind));
  if (r.tussen.status !== 'connecting' || r.tussen.naam !== 'Radio B') errs.push('oude startmelding raakte de nieuwe zender: ' + JSON.stringify(r.tussen));
  if (r.eind.status !== 'playing' || r.eind.naam !== 'Radio B') errs.push('tweede zender speelt niet: ' + JSON.stringify(r.eind));

  // Daarna gewoon stoppen werkt nog
  await pg.evaluate(() => Android.radioStop());
  const gestopt = await pg.evaluate(() => Android._rd.st.status);
  console.log('gestopt', gestopt);
  if (gestopt !== 'stopped') errs.push('stoppen na afspelen: ' + gestopt);
  console.log('errors', errs);
  await b.close();
})();
