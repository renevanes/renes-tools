// Nieuws: het script dat in een videospeler onthoudt waar je was (web/nieuws-video.js, zie NieuwsVideo.java).
// Een video in een schaduw-DOM (zoals de speler van mave) en een gewone video; tijd en lengte zijn nagebootst.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const BRON = fs.readFileSync(path.join(__dirname, '..', '..', 'web', 'nieuws-video.js'), 'utf8')
  .replace('__TEKST__', JSON.stringify({ verder: 'Verder vanaf {tijd}', begin: 'Vanaf het begin' }));
(async () => {
  const b = await chromium.launch();
  const errs = [];
  const ok = (c, m) => { if (!c) errs.push('mislukt: ' + m); };
  const ctx = await b.newContext({ viewport: { width: 390, height: 300 } });
  // een vaste plek (zoals de speler van de videodienst), met het script vooraan
  await ctx.route('https://speler.example/**', r => r.fulfill({ contentType: 'text/html', body: '<!doctype html><html><head><script>' + BRON + '</script></head><body><div id="host"></div></body></html>' }));
  const pg = await ctx.newPage();
  pg.on('pageerror', e => errs.push(e.message));
  const laad = async () => { await pg.goto('https://speler.example/v1/player.html'); };
  // een video zoals een speler hem maakt: in een (gesloten) schaduw-DOM, met play() vanuit de speler
  const maak = () => pg.evaluate(() => {
    const host = document.getElementById('host'), sh = host.attachShadow({ mode: 'closed' }), v = document.createElement('video');
    let t = 0; window.__dur = 1800;
    Object.defineProperty(v, 'currentTime', { get: () => t, set: x => { t = +x; window.__gezet = (window.__gezet || []).concat(+x); } });
    Object.defineProperty(v, 'duration', { get: () => window.__dur });
    sh.appendChild(v); window.__v = v;
    v.play().catch(() => {}); // de speler start hem
    return true;
  });
  const vuur = (soort, tijd) => pg.evaluate(([s, x]) => { const v = window.__v; if (x != null) Object.getOwnPropertyDescriptor(v, 'currentTime').set(x); window.__gezet = []; v.dispatchEvent(new Event(s)); }, [soort, tijd]);
  const opgeslagen = () => pg.evaluate(() => JSON.parse(localStorage.getItem('tools.plek:https://speler.example/v1/player.html') || 'null'));

  await laad(); await maak();
  await vuur('playing'); await vuur('timeupdate', 3);
  ok(await opgeslagen() === null, 'eerste 10 seconden: niets onthouden');
  await vuur('timeupdate', 754);
  ok((await opgeslagen() || {}).t === 754, 'tijd onthouden (' + JSON.stringify(await opgeslagen()) + ')');
  await vuur('timeupdate', 760);
  ok((await opgeslagen() || {}).t === 754, 'hooguit elke 5 seconden opslaan');
  await vuur('pause', 812);
  ok((await opgeslagen() || {}).t === 812, 'bij pauze meteen');

  // Opnieuw openen: verder waar je was, met een regel en een knop
  await laad(); await maak(); await vuur('playing');
  ok(await pg.evaluate(() => window.__v.currentTime === 812), 'verder vanaf 13:32');
  const regel = await pg.evaluate(() => { const r = document.querySelector('[role=status]'); return r && r.textContent; });
  ok(regel === 'Verder vanaf 13:32Vanaf het begin', 'regel bovenaan (' + regel + ')');
  ok(await pg.evaluate(() => { const k = document.querySelector('[role=status] button'); return !!k && k.getBoundingClientRect().height >= 44; }), 'knop groot genoeg');
  await pg.click('[role=status] button');
  ok(await pg.evaluate(() => window.__v.currentTime === 0 && !document.querySelector('[role=status]')) && await opgeslagen() === null, 'Vanaf het begin: naar 0 en vergeten');

  // Bijna afgelopen of afgelopen: vergeten
  await vuur('timeupdate', 900); await vuur('pause', 1790);
  ok(await opgeslagen() === null, 'laatste 30 seconden: als gezien');
  await vuur('pause', 600); await vuur('ended');
  ok(await opgeslagen() === null, 'afgelopen: vergeten');

  // De speler begon zelf al ergens anders: niet overnemen
  await vuur('pause', 600); await laad(); await maak();
  await pg.evaluate(() => Object.getOwnPropertyDescriptor(window.__v, 'currentTime').set(300)); await vuur('playing');
  ok(await pg.evaluate(() => window.__v.currentTime === 300 && !document.querySelector('[role=status]')), 'eigen beginplek van de speler gaat voor');

  // Een gewone video zonder speler-script (knoppen van de video zelf): de play-gebeurtenis
  await pg.evaluate(() => localStorage.clear()); await laad();
  await pg.evaluate(() => { const v = document.createElement('video'); let t = 0; Object.defineProperty(v, 'currentTime', { get: () => t, set: x => { t = +x; } }); Object.defineProperty(v, 'duration', { get: () => 600 }); document.body.appendChild(v); window.__v = v; v.dispatchEvent(new Event('play')); });
  await vuur('pause', 120);
  ok((await opgeslagen() || {}).t === 120, 'ook zonder play() van een speler');

  // Een andere video op dezelfde plek (andere lengte): niet springen
  await pg.evaluate(() => localStorage.setItem('tools.plek:https://speler.example/v1/player.html', JSON.stringify({ t: 300, d: 1800, w: Date.now() })));
  await laad(); await maak(); await pg.evaluate(() => { window.__dur = 900; }); await vuur('playing');
  ok(await pg.evaluate(() => window.__v.currentTime === 0 && !document.querySelector('[role=status]')), 'andere lengte: niet verder');

  // Twee video's op een pagina: de tweede eerst afspelen neemt de plek van de eerste niet over
  await pg.evaluate(() => localStorage.clear()); await laad();
  await pg.evaluate(() => {
    const mk = (d) => { const v = document.createElement('video'); let t = 0; Object.defineProperty(v, 'currentTime', { get: () => t, set: x => { t = +x; } }); Object.defineProperty(v, 'duration', { get: () => d }); document.body.appendChild(v); return v; };
    window.__a = mk(1200); window.__b = mk(1200);
    localStorage.setItem('tools.plek:https://speler.example/v1/player.html', JSON.stringify({ t: 500, d: 1200, w: Date.now() })); // plek van de eerste
    window.__b.play().catch(() => {}); window.__b.dispatchEvent(new Event('playing'));
  });
  ok(await pg.evaluate(() => window.__b.currentTime === 0), 'tweede video: niet de plek van de eerste');
  await pg.evaluate(() => { window.__a.play().catch(() => {}); window.__a.dispatchEvent(new Event('playing')); });
  ok(await pg.evaluate(() => window.__a.currentTime === 500), 'eerste video: wel zijn eigen plek');

  // Oude plekken (meer dan 90 dagen) gaan weg; jonge blijven
  await pg.evaluate(() => { localStorage.clear(); localStorage.setItem('tools.plek:https://speler.example/oud', JSON.stringify({ t: 50, d: 600, w: Date.now() - 91 * 864e5 })); localStorage.setItem('tools.plek:https://speler.example/jong', JSON.stringify({ t: 50, d: 600, w: Date.now() })); localStorage.setItem('iets anders', 'x'); });
  await laad();
  ok(await pg.evaluate(() => !localStorage.getItem('tools.plek:https://speler.example/oud') && !!localStorage.getItem('tools.plek:https://speler.example/jong') && localStorage.getItem('iets anders') === 'x'), 'oude plekken opgeruimd, de rest blijft');

  // Twee keer het script (artikel na laden): geen dubbele regels
  await pg.evaluate(BRON);
  ok(await pg.evaluate(() => window.__toolsPlek === 1), 'tweede keer: doet niets');

  // Een tekst met tekens die HTML zouden zijn: als tekst
  await pg.evaluate(() => localStorage.setItem('tools.plek:https://speler.example/v1/player.html', JSON.stringify({ t: 61, d: 600 })));
  const ctx2 = await b.newContext();
  await ctx2.route('https://speler.example/**', r => r.fulfill({ contentType: 'text/html', body: '<!doctype html><html><head><script>' + BRON.replace('Verder vanaf {tijd}', '<img src=x onerror=window.__x=1> {tijd}') + '</script></head><body><div id="host"></div></body></html>' }));
  const p2 = await ctx2.newPage(); p2.on('pageerror', e => errs.push(e.message));
  await p2.goto('https://speler.example/v1/player.html');
  await p2.evaluate(() => { localStorage.setItem('tools.plek:https://speler.example/v1/player.html', JSON.stringify({ t: 61, d: 600, w: Date.now() })); const v = document.createElement('video'); let t = 0; Object.defineProperty(v, 'currentTime', { get: () => t, set: x => { t = +x; } }); Object.defineProperty(v, 'duration', { get: () => 600 }); document.body.appendChild(v); v.play().catch(() => {}); v.dispatchEvent(new Event('playing')); });
  await p2.waitForTimeout(100);
  ok(await p2.evaluate(() => !window.__x && !document.querySelector('[role=status] img') && document.querySelector('[role=status] span').textContent.endsWith('1:01')), 'tekst als tekst, tijd 1:01');
  await ctx2.close();

  console.log('errors', errs);
  await b.close();
  if (errs.length) process.exitCode = 1;
})().catch(e => { console.error(e); process.exit(1); });
