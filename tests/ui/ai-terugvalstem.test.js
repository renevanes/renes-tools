// Assistent zonder lokale stem: de stem van Android die zonder internet werkt (testbrug zoals SystemSpeech), een eenmalige
// tip om Ronnie te downloaden, nooit vanzelf een venster bij vanzelf voorlezen; met een lokale stem weer ReadAloud.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const errs = [];
  const ok = (c, m) => { console.log((c ? '✓ ' : '✗ ') + m); if (!c) errs.push(m); };
  for (const scheme of ['light', 'dark']) {
    const pg = await b.newPage({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
    pg.on('pageerror', e => errs.push(e.message));
    await pg.goto('file://' + require('path').join(__dirname, 'ui', 'index.html')); await pg.waitForTimeout(300);
    await pg.evaluate(() => { Android._ai.st.klein = { state: 'ready' }; Android._tts.ms = 1200; Android._speech.voice = ''; Android._speech.voices.forEach(v => v.state = 'none'); show('ai'); }); await pg.waitForTimeout(200);
    await pg.fill('#ai-in', 'Hallo'); await pg.click('#ai-send'); await pg.waitForTimeout(900);
    // Tik op Voorlezen: terugvalstem, met de tip en zonder pauzeknop (die kan Android niet)
    await pg.click('#ai-msgs .aispk'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._tts.system === 1 && Android._speech.said.length === 0 && aiSpk && aiSpk.system), 'zonder lokale stem: de stem van Android (niet ReadAloud)');
    ok(await pg.isVisible('#ai-spkhint') && (await pg.textContent('#ai-spkhint')).includes('Download een lokale stem (zoals Dii) voor een betere stem'), 'eenmalige tip om Ronnie te downloaden');
    ok(await pg.isHidden('#ai-speech-pause') && await pg.isVisible('#ai-spkbar'), 'balk zonder Pauzeren (Android kan niet pauzeren)');
    ok(await pg.evaluate(() => !sp.settings), 'geen venster vanzelf');
    await pg.screenshot({ path: __dirname + '/shots/ai-terugvalstem-' + scheme + '.png' });
    await pg.waitForTimeout(1400);
    ok(await pg.isHidden('#ai-spkbar') && await pg.evaluate(() => !aiSpk), 'klaar: balk weg');
    const box = await pg.$eval('#ai-spkhint .icon-btn', e => { const r = e.getBoundingClientRect(); return [r.width, r.height]; });
    ok(box[0] >= 48 && box[1] >= 48, 'sluitknop van de tip 48×48: ' + box);
    await pg.click('#ai-spkhint .icon-btn'); await pg.click('#ai-msgs .aispk'); await pg.waitForTimeout(150);
    ok(await pg.isHidden('#ai-spkhint'), 'de tip komt maar één keer');
    await pg.evaluate(() => aiSpeakStop()); await pg.waitForTimeout(50);
    ok(await pg.evaluate(() => !aiSpk) && await pg.isHidden('#ai-spkbar'), 'stoppen werkt ook bij de terugvalstem');
    // Geen enkele stem die zonder internet werkt
    await pg.evaluate(() => { Android._tts.noSystem = true; aiSpeakStart(aiChat.msgs[aiChat.msgs.length - 1], aiChat, true); }); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => !sp.settings) && await pg.isVisible('#ai-spkhint'), 'vanzelf voorlezen zonder stem: alleen de tip, geen venster');
    await pg.click('#ai-spkhint button.mini'); await pg.waitForTimeout(100);
    ok(await pg.evaluate(() => sp.settings) && await pg.isHidden('#ai-spkhint'), 'Stem kiezen opent het stemmenvenster');
    await pg.evaluate(() => spSettingsClose());
    await pg.click('#ai-msgs .aispk'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => sp.settings), 'zelf op Voorlezen tikken zonder stem: de stemkiezer');
    await pg.evaluate(() => spSettingsClose());
    // Met een lokale stem gebruikt de assistent die
    await pg.evaluate(() => { Android._tts.noSystem = false; Android._speech.voices[0].state = 'ready'; Android._speech.voice = 'tts-nl-ronnie'; Android._tts.system = 0; });
    await pg.click('#ai-msgs .aispk'); await pg.waitForTimeout(150);
    ok(await pg.evaluate(() => Android._tts.system === 0 && Android._speech.said.slice(-1)[0].source === 'ai' && !aiSpk.system) && await pg.isVisible('#ai-speech-pause'), 'lokale stem gedownload: ReadAloud, met Pauzeren');
    await pg.evaluate(() => aiSpeakStop());
    await pg.close();
  }
  console.log('errors', errs);
  await b.close();
  if (errs.length) process.exit(1);
})();
