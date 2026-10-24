// Wordt vóór elke interfacetest geladen (node -r). UI_WACHT > 1 maakt alle vaste wachttijden in de tests
// (page.waitForTimeout) zoveel keer langer: voor een trage machine, of bij de tweede poging van een test.
const f = parseFloat(process.env.UI_WACHT || '1');
if (f > 0 && f !== 1) {
  const pw = require('playwright');
  // Eén keer per pagina (browser.newPage loopt zelf via newContext + context.newPage)
  const patch = p => { if (p.__traag) return p; p.__traag = true; const w = p.waitForTimeout.bind(p); p.waitForTimeout = ms => w(Math.round(ms * f)); return p; };
  const wrapCtx = c => { if (c.__traag) return c; c.__traag = true; const np = c.newPage.bind(c); c.newPage = async (...a) => patch(await np(...a)); return c; };
  for (const name of ['chromium', 'firefox', 'webkit']) {
    const bt = pw[name]; if (!bt) continue;
    const launch = bt.launch.bind(bt);
    bt.launch = async (...a) => {
      const b = await launch(...a);
      const np = b.newPage.bind(b), nc = b.newContext.bind(b);
      b.newPage = async (...x) => patch(await np(...x));
      b.newContext = async (...x) => wrapCtx(await nc(...x));
      return b;
    };
  }
}
